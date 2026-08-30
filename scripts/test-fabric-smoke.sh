#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
matrix_file="$project_dir/gradle/version-matrix.json"
loader=$(jq -r '.fabricLoader' "$matrix_file")
log_root=$(mktemp -d /tmp/vanillashape-fabric-smoke.XXXXXX)

cleanup() {
    if [[ "${VANILLASHAPE_KEEP_CLIENT_LOGS:-0}" == 1 ]]; then
        echo "Client logs retained at $log_root"
    else
        rm -rf -- "$log_root"
    fi
}
trap cleanup EXIT

command -v jq >/dev/null 2>&1 || { echo 'jq is required.' >&2; exit 2; }
command -v timeout >/dev/null 2>&1 || { echo 'timeout is required.' >&2; exit 2; }
command -v xvfb-run >/dev/null 2>&1 || {
    echo 'xvfb-run is required to smoke-test Minecraft clients without a display.' >&2
    exit 2
}

if [[ $# -gt 0 ]]; then
    versions=("$@")
else
    # Every distinct rendering/model/light compatibility boundary.
    versions=(1.21 1.21.2 1.21.5 1.21.6 1.21.9 1.21.11 26.1 26.2)
fi

for minecraft in "${versions[@]}"; do
    row=$(jq -ce --arg minecraft "$minecraft" \
        '.versions[] | select(.minecraft == $minecraft)' "$matrix_file") || {
        echo "Unknown Minecraft version: $minecraft" >&2
        exit 2
    }
    fabric_api=$(jq -r '.fabricApi' <<<"$row")
    fabric_project=$(jq -r '.fabricProject' <<<"$row")
    log_file="$log_root/$minecraft.log"
    echo "Launching Fabric $minecraft smoke test"
    if [[ "$fabric_project" == fabric-legacy ]]; then
        command=("$project_dir/gradlew" -p "$project_dir" :fabric-legacy:runClient
            -Plegacy_minecraft_version="$minecraft"
            -Plegacy_fabric_api_version="$fabric_api"
            -Plegacy_loader_version="$loader" --console=plain)
    else
        command=("$project_dir/gradlew" -p "$project_dir" :fabric:runClient
            -Pminecraft_version="$minecraft"
            -Pfabric_api_version="$fabric_api"
            -Ploader_version="$loader" --console=plain)
    fi

    ALSOFT_DRIVERS=null timeout --kill-after=10 180 xvfb-run -a "${command[@]}" >"$log_file" 2>&1 &
    client_job=$!
    launch_deadline=$((SECONDS + 170))
    while kill -0 "$client_job" 2>/dev/null \
            && ! grep -Fq 'minecraft:textures/atlas/blocks.png-atlas' "$log_file" \
            && [[ "$SECONDS" -lt "$launch_deadline" ]]; do
        sleep 1
    done
    reached_menu=false
    if grep -Fq 'minecraft:textures/atlas/blocks.png-atlas' "$log_file"; then
        reached_menu=true
        kill "$client_job" 2>/dev/null || true
    fi
    set +e
    wait "$client_job"
    exit_code=$?
    set -e
    if [[ "$reached_menu" != true ]] \
        || ! grep -Fq "Loading Minecraft $minecraft with Fabric Loader $loader" "$log_file" \
        || ! grep -Eq "VanillaShape( legacy)? client renderer initialized" "$log_file" \
        || ! grep -Fq 'minecraft:textures/atlas/blocks.png-atlas' "$log_file" \
        || grep -Eq 'MixinApplyError|InjectionError|InvalidInjectionException|Could not execute entrypoint|The game has crashed|ExceptionInInitializerError|NoSuchMethodError|NoClassDefFoundError' "$log_file"; then
        echo "Fabric $minecraft smoke test failed (exit $exit_code)." >&2
        tail -n 180 "$log_file" >&2
        exit 1
    fi
    grep -Eq "[[:space:]]- java $(jq -r '.java' <<<"$row")$" "$log_file" || {
        echo "Fabric $minecraft did not launch on the expected Java version." >&2
        tail -n 100 "$log_file" >&2
        exit 1
    }
    echo "Fabric $minecraft: OK"
done

echo "Verified ${#versions[@]} Fabric rendering compatibility boundaries."
