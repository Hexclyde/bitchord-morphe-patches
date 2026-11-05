#!/bin/sh
# Builds and runs the model memory checks on their own, under the device's 192 MiB heap.
# Usage: tests/karaoke-memory/run.sh <scratch directory on disk>
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
base=${1:?scratch directory on disk}
mkdir -p "$base"
out=$(mktemp -d "$base/karaoke-memory.XXXXXX")
ext=$root/extensions/telegram/src/main/java/app/bitchord/telegram
tools=${MORPHE_TOOLS:-$root/.deps/tools}
jdk=$(ls -d "$tools"/jdk-21*)
mkdir -p "$out/stub/app/bitchord/telegram" "$out/classes"
cp "$here/stub/SourceAudio.java.txt" "$out/stub/app/bitchord/telegram/SourceAudio.java"
"$jdk/bin/javac" -J-Xshare:off --release 11 -proc:none \
  -cp "$tools/android.jar:$tools/annotation.jar:$tools/json.jar:$root/build/extension-classes:$root/build/compile-api.jar" \
  -d "$out/classes" "$out/stub/app/bitchord/telegram/SourceAudio.java" \
  "$ext/SeparatedSing.java" "$ext/SeparationModel.java" "$ext/VocalCut.java" "$ext/Fingerprint.java" \
  "$ext/BitPerfect.java" "$ext/SoundExtras.java" "$here/KaraokeMemoryTest.java"
"$jdk/bin/java" -Xmx192m -Djava.io.tmpdir="$out" -cp "$out/classes:$tools/android.jar:$tools/json.jar:$root/build/extension-classes:$root/build/compile-api.jar" \
  app.bitchord.telegram.KaraokeMemoryTest
