#!/usr/bin/env bash
# Builds BitChord's release APKs from a source checkout, the same way BitChord's
# own release workflow does (assembleProdRelease). Without their keystore and API
# keys the APKs are unsigned and the keys are blank.
#
#   tools/build_bitchord.sh <bitchord-checkout> <output-dir> <name>
#
# Needs JDK 17 and ANDROID_HOME. Writes <output-dir>/BitChord-<name>_<abi>.apk.
# A failed build is tried once more, since JitPack builds some dependencies on
# first request and often times out the first time.
set -euo pipefail
source_dir=$1
output_dir=$(mkdir -p "$2" && cd "$2" && pwd)
name=$3

cd "$source_dir"
printf 'sdk.dir=%s\nLASTFM_API_KEY=\nLASTFM_SECRET=\nLISTEN_TOGETHER_SERVER=\n' "$ANDROID_HOME" > local.properties
chmod +x gradlew
build() { ./gradlew --no-daemon --console=plain assembleProdRelease; }
if ! build; then
  echo "::warning::BitChord's build failed; trying once more."
  build
fi
apks=app/build/outputs/apk/prod/release
for abi in arm64-v8a armeabi-v7a x86_64; do
  found=("$apks"/*-"$abi"-*.apk)
  if [ ! -f "${found[0]}" ] || [ "${#found[@]}" -ne 1 ]; then
    echo "Expected one $abi APK in $apks, found: $(ls "$apks" 2>/dev/null | tr '\n' ' ')" >&2
    exit 1
  fi
  cp "${found[0]}" "$output_dir/BitChord-${name}_$abi.apk"
done
