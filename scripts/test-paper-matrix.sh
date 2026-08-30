#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
matrix_file="$project_dir/gradle/version-matrix.json"
dist_dir="$project_dir/dist"
project_version=$("$project_dir/gradlew" -p "$project_dir" -q printVersion)
requested_version=${1:-}
paper_cache=${VANILLASHAPE_PAPER_CACHE:-/tmp/vanillashape-paper-cache}
test_root=$(mktemp -d /tmp/vanillashape-paper-matrix.XXXXXX)

cleanup() {
    if [[ "${VANILLASHAPE_KEEP_TEST_SERVERS:-0}" == 1 ]]; then
        echo "Test servers retained at $test_root"
    else
        rm -rf -- "$test_root"
    fi
}
trap cleanup EXIT

resolve_java() {
    local version=$1
    local override
    if [[ "$version" == 21 ]]; then
        override=${VANILLASHAPE_JAVA21:-}
    else
        override=${VANILLASHAPE_JAVA25:-}
    fi
    if [[ -n "$override" && -x "$override" ]]; then
        echo "$override"
        return
    fi
    local conventional="/usr/lib/jvm/java-$version-openjdk-amd64/bin/java"
    if [[ -x "$conventional" ]]; then
        echo "$conventional"
        return
    fi
    if [[ "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')" == "$version" ]]; then
        command -v java
        return
    fi
    echo "Java $version was not found; set VANILLASHAPE_JAVA$version to its java executable." >&2
    return 1
}

mkdir -p "$paper_cache"
selector='.versions[] | select(.paper.available == true)'
if [[ -n "$requested_version" ]]; then
    selector+=" | select(.minecraft == \"$requested_version\")"
fi
mapfile -t rows < <(jq -c "$selector" "$matrix_file")
if [[ ${#rows[@]} -eq 0 ]]; then
    echo "No official Paper build is defined for Minecraft $requested_version." >&2
    exit 2
fi

for row in "${rows[@]}"; do
    minecraft=$(jq -r '.minecraft' <<<"$row")
    build=$(jq -r '.paper.build' <<<"$row")
    java_version=$(jq -r '.java' <<<"$row")
    java_bin=$(resolve_java "$java_version")
    plugin_jar="$dist_dir/vanillashape-paper-$minecraft-$project_version.jar"
    [[ -f "$plugin_jar" ]] || {
        echo "Missing $(basename "$plugin_jar"); run ./scripts/build-matrix.sh all first." >&2
        exit 1
    }

    echo "Testing Paper $minecraft build $build on Java $java_version"
    metadata=$(curl -fsSL --retry 3 \
        -H 'User-Agent: VanillaShape matrix test (https://github.com/twme-ai/VanillaShape)' \
        "https://fill.papermc.io/v3/projects/paper/versions/$minecraft/builds/$build")
    download_url=$(jq -er '.downloads["server:default"].url' <<<"$metadata")
    expected_sha=$(jq -er '.downloads["server:default"].checksums.sha256' <<<"$metadata")
    paper_jar="$paper_cache/paper-$minecraft-$build.jar"
    if [[ ! -f "$paper_jar" || "$(sha256sum "$paper_jar" | cut -d' ' -f1)" != "$expected_sha" ]]; then
        curl -fL --retry 3 -o "$paper_jar.part" "$download_url"
        mv "$paper_jar.part" "$paper_jar"
    fi
    [[ "$(sha256sum "$paper_jar" | cut -d' ' -f1)" == "$expected_sha" ]] || {
        echo "Checksum mismatch for Paper $minecraft build $build." >&2
        exit 1
    }

    server_dir="$test_root/$minecraft"
    mkdir -p "$server_dir/plugins"
    cp "$plugin_jar" "$server_dir/plugins/VanillaShape.jar"
    cp "$project_dir/scripts/paper-matrix-server.properties" "$server_dir/server.properties"
    console_log="$server_dir/console.log"
    console_fifo="$server_dir/console.pipe"
    mkfifo "$console_fifo"
    exec {console_fd}<>"$console_fifo"
    (
        cd "$server_dir"
        timeout 180 "$java_bin" -Dcom.mojang.eula.agree=true -Xms512M -Xmx1G \
            -jar "$paper_jar" --nogui --nojline <"$console_fifo"
    ) >"$console_log" 2>&1 &
    server_job=$!

    startup_deadline=$((SECONDS + 150))
    while kill -0 "$server_job" 2>/dev/null \
            && ! grep -Eq 'Done \([^)]*s\)! For help' "$console_log" \
            && [[ "$SECONDS" -lt "$startup_deadline" ]]; do
        sleep 1
    done
    if grep -Eq 'Done \([^)]*s\)! For help' "$console_log"; then
        printf 'version VanillaShape\n' >&"$console_fd"
        version_deadline=$((SECONDS + 15))
        while kill -0 "$server_job" 2>/dev/null \
                && ! grep -Eq "VanillaShape.*version.*${project_version//./\\.}" "$console_log" \
                && [[ "$SECONDS" -lt "$version_deadline" ]]; do
            sleep 1
        done
        printf 'stop\n' >&"$console_fd"
    fi
    exec {console_fd}>&-

    set +e
    wait "$server_job"
    exit_code=$?
    set -e
    if [[ "$exit_code" -ne 0 ]] \
        || ! grep -Fq "Enabling VanillaShape v$project_version" "$console_log" \
        || ! grep -Fq 'Loaded 0 overworld special blocks' "$console_log" \
        || ! grep -Eq "VanillaShape.*version.*${project_version//./\\.}" "$console_log" \
        || ! grep -Fq "Disabling VanillaShape v$project_version" "$console_log" \
        || [[ ! -f "$server_dir/plugins/VanillaShape/blocks.db" ]] \
        || grep -Eq 'Could not load.*VanillaShape|Error occurred while (loading|enabling) VanillaShape|NoClassDefFoundError|UnsupportedClassVersionError' "$console_log"; then
        echo "Paper $minecraft runtime verification failed (exit $exit_code)." >&2
        tail -n 160 "$console_log" >&2
        exit 1
    fi
    echo "Paper $minecraft: OK"
done

echo "Verified VanillaShape on ${#rows[@]} official Paper server builds."
