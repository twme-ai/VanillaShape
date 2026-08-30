#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
matrix_file="$project_dir/gradle/version-matrix.json"
dist_dir="$project_dir/dist"
component=${1:-all}
requested_version=${2:-}

if [[ "$component" != all && "$component" != fabric && "$component" != paper ]]; then
    echo "Usage: $0 [all|fabric|paper] [minecraft-version]" >&2
    exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to read gradle/version-matrix.json" >&2
    exit 2
fi

project_version=$("$project_dir/gradlew" -p "$project_dir" -q printVersion)
loader=$(jq -r '.fabricLoader' "$matrix_file")
selector='.versions[]'
if [[ -n "$requested_version" ]]; then
    selector+=" | select(.minecraft == \"$requested_version\")"
fi
mapfile -t rows < <(jq -c "$selector" "$matrix_file")
if [[ ${#rows[@]} -eq 0 ]]; then
    echo "Unknown Minecraft version: $requested_version" >&2
    exit 2
fi
if [[ "$component" == paper && -n "$requested_version" \
        && "$(jq -r '.paper.available' <<<"${rows[0]}")" != true ]]; then
    jq -r '.paper.reason' <<<"${rows[0]}" >&2
    exit 2
fi

mkdir -p "$dist_dir"
case "$component" in
    all) find "$dist_dir" -maxdepth 1 -type f \( -name 'vanillashape-*.jar' -o -name 'SHA256SUMS' \) -delete ;;
    fabric) find "$dist_dir" -maxdepth 1 -type f -name 'vanillashape-fabric-*.jar' -delete ;;
    paper) find "$dist_dir" -maxdepth 1 -type f -name 'vanillashape-paper-*.jar' -delete ;;
esac

"$project_dir/gradlew" -p "$project_dir" :common:test --console=plain

for row in "${rows[@]}"; do
    minecraft=$(jq -r '.minecraft' <<<"$row")
    fabric_api=$(jq -r '.fabricApi' <<<"$row")
    fabric_project=$(jq -r '.fabricProject' <<<"$row")

    if [[ "$component" == all || "$component" == fabric ]]; then
        echo "Building Fabric $minecraft"
        if [[ "$fabric_project" == fabric-legacy ]]; then
            "$project_dir/gradlew" -p "$project_dir" :fabric-legacy:clean :fabric-legacy:build \
                -Plegacy_minecraft_version="$minecraft" \
                -Plegacy_fabric_api_version="$fabric_api" \
                -Plegacy_loader_version="$loader" --console=plain
            artifact="$project_dir/fabric-legacy/build/libs/vanillashape-fabric-$minecraft-$project_version.jar"
        else
            "$project_dir/gradlew" -p "$project_dir" :fabric:clean :fabric:build \
                -Pminecraft_version="$minecraft" \
                -Pfabric_api_version="$fabric_api" \
                -Ploader_version="$loader" --console=plain
            artifact="$project_dir/fabric/build/libs/vanillashape-fabric-$minecraft-$project_version.jar"
        fi
        test -f "$artifact"
        cp "$artifact" "$dist_dir/"
    fi

    paper_available=$(jq -r '.paper.available' <<<"$row")
    if [[ ( "$component" == all || "$component" == paper ) && "$paper_available" == true ]]; then
        paper_api=$(jq -r '.paper.api' <<<"$row")
        worldedit=$(jq -r '.paper.worldEdit' <<<"$row")
        echo "Building Paper $minecraft"
        "$project_dir/gradlew" -p "$project_dir" :paper:clean :paper:build \
            -Ppaper_minecraft_version="$minecraft" \
            -Ppaper_api_version="$paper_api" \
            -Pworldedit_version="$worldedit" --console=plain
        artifact="$project_dir/paper/build/libs/vanillashape-paper-$minecraft-$project_version.jar"
        test -f "$artifact"
        cp "$artifact" "$dist_dir/"
    fi
done

if compgen -G "$dist_dir/vanillashape-*.jar" >/dev/null; then
    (cd "$dist_dir" && sha256sum vanillashape-*.jar > SHA256SUMS)
fi
echo "Artifacts are in $dist_dir"
