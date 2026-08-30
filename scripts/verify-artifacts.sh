#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
matrix_file="$project_dir/gradle/version-matrix.json"
dist_dir="$project_dir/dist"
project_version=$("$project_dir/gradlew" -p "$project_dir" -q printVersion)
loader=$(jq -r '.fabricLoader' "$matrix_file")

fail() {
    echo "Artifact verification failed: $*" >&2
    exit 1
}

class_major() {
    local artifact=$1
    local class_file=$2
    local high low
    read -r high low < <(unzip -p "$artifact" "$class_file" | od -An -tu1 -j6 -N2)
    [[ -n "${high:-}" && -n "${low:-}" ]] || fail "missing class $class_file in $(basename "$artifact")"
    echo $((high * 256 + low))
}

expected_fabric=$(jq '.versions | length' "$matrix_file")
expected_paper=$(jq '[.versions[].paper | select(.available == true)] | length' "$matrix_file")
actual_fabric=$(find "$dist_dir" -maxdepth 1 -type f -name 'vanillashape-fabric-*.jar' | wc -l)
actual_paper=$(find "$dist_dir" -maxdepth 1 -type f -name 'vanillashape-paper-*.jar' | wc -l)
[[ "$actual_fabric" -eq "$expected_fabric" ]] || fail "expected $expected_fabric Fabric JARs, found $actual_fabric"
[[ "$actual_paper" -eq "$expected_paper" ]] || fail "expected $expected_paper Paper JARs, found $actual_paper"

while IFS= read -r row; do
    minecraft=$(jq -r '.minecraft' <<<"$row")
    java=$(jq -r '.java' <<<"$row")
    fabric_project=$(jq -r '.fabricProject' <<<"$row")
    fabric_band=$(jq -r '.fabricBand' <<<"$row")
    expected_major=$((java == 21 ? 65 : 69))
    fabric_jar="$dist_dir/vanillashape-fabric-$minecraft-$project_version.jar"
    [[ -f "$fabric_jar" ]] || fail "missing $(basename "$fabric_jar")"

    metadata=$(unzip -p "$fabric_jar" fabric.mod.json)
    jq -e --arg version "$project_version" --arg minecraft "=$minecraft" \
        --arg loader ">=$loader" --arg java ">=$java" \
        '.version == $version and .depends.minecraft == $minecraft
            and .depends.fabricloader == $loader and .depends.java == $java' \
        <<<"$metadata" >/dev/null \
        || fail "wrong Fabric metadata in $(basename "$fabric_jar")"
    if [[ "$fabric_project" == fabric-legacy ]]; then
        entrypoint=dev/twme/vanillashape/fabric/LegacyVanillaShapeClient.class
    else
        entrypoint=dev/twme/vanillashape/fabric/VanillaShapeClient.class
    fi
    [[ "$(class_major "$fabric_jar" "$entrypoint")" -eq "$expected_major" ]] \
        || fail "wrong Java bytecode in $(basename "$fabric_jar")"
    fabric_listing=$(unzip -Z1 "$fabric_jar")
    grep -Eq '^META-INF/jars/common-.*\.jar$' <<<"$fabric_listing" \
        || fail "common protocol classes are not bundled in $(basename "$fabric_jar")"
    if [[ "$fabric_project" == fabric-legacy ]]; then
        renderer_mixin='dev/twme/vanillashape/fabric/mixin/LegacyLevelRendererMixin.class'
        if [[ "$fabric_band" == submit-node-* ]]; then
            grep -Fqx "$renderer_mixin" <<<"$fabric_listing" \
                || fail "submit-node renderer mixin is missing from $(basename "$fabric_jar")"
        elif grep -Fqx "$renderer_mixin" <<<"$fabric_listing"; then
            fail "submit-node renderer mixin leaked into $(basename "$fabric_jar")"
        fi
    fi

    if [[ "$(jq -r '.paper.available' <<<"$row")" == true ]]; then
        paper_jar="$dist_dir/vanillashape-paper-$minecraft-$project_version.jar"
        [[ -f "$paper_jar" ]] || fail "missing $(basename "$paper_jar")"
        plugin_yml=$(unzip -p "$paper_jar" plugin.yml)
        grep -Fqx "version: '$project_version'" <<<"$plugin_yml" \
            || fail "wrong plugin version in $(basename "$paper_jar")"
        grep -Fqx "api-version: '$minecraft'" <<<"$plugin_yml" \
            || fail "wrong Paper api-version in $(basename "$paper_jar")"
        [[ "$(class_major "$paper_jar" dev/twme/vanillashape/paper/VanillaShapePlugin.class)" -eq "$expected_major" ]] \
            || fail "wrong Java bytecode in $(basename "$paper_jar")"
        paper_listing=$(unzip -Z1 "$paper_jar")
        grep -Fqx 'dev/twme/vanillashape/libs/sqlite/JDBC.class' <<<"$paper_listing" \
            || fail "relocated SQLite driver is missing from $(basename "$paper_jar")"
    fi
done < <(jq -c '.versions[]' "$matrix_file")

[[ -f "$dist_dir/SHA256SUMS" ]] || fail 'SHA256SUMS is missing'
(cd "$dist_dir" && sha256sum --check --strict SHA256SUMS)
echo "Verified $actual_fabric Fabric and $actual_paper Paper artifacts."
