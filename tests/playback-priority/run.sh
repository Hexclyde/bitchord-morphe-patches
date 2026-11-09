#!/bin/sh
# Builds and runs the playback-priority and analysis-memory checks on their own.
# Usage: tests/playback-priority/run.sh [scratch directory on disk]
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
base=${1:-$root/build}
mkdir -p "$base"
out=$(mktemp -d "$base/playback-priority.XXXXXX")
trap 'rm -rf "$out"' EXIT HUP INT TERM
ext=$root/extensions/telegram/src/main/java
tools=${MORPHE_TOOLS:-$root/.deps/tools}
jdk=$(PYTHONPATH="$root${PYTHONPATH:+:$PYTHONPATH}" python3 -c 'from buildenv import jdk; print(jdk())')
mkdir -p "$out/classes" "$out/stub" "$out/src/app/bitchord/telegram"
# These checks use no translations.
for file in "$ext"/app/bitchord/telegram/*.java; do
  [ "${file##*/}" = Translations.java ] || ln -s "$file" "$out/src/app/bitchord/telegram/"
done
cp "$here/stub/Translations.java.txt" "$out/src/app/bitchord/telegram/Translations.java"
cp="$tools/android.jar:$tools/annotation.jar:$tools/json.jar:$root/build/extension-classes:$root/build/compile-api.jar:$tools/kotlinc/lib/kotlin-stdlib.jar:$root/ui-tests/aar-libs/media3-common.jar:$root/ui-tests/aar-libs/media3-datasource.jar:$root/ui-tests/aar-libs/media3-exoplayer.jar"
"$jdk/bin/javac" -J-Xshare:off --release 11 -proc:none -Xprefer:source -cp "$cp" -sourcepath "$out/src" \
  -d "$out/classes" "$root/build/PatchVersion.java" "$here/PlaybackPriorityTest.java" "$here/SourceAudioPriorityTest.java"
"$jdk/bin/javac" -J-Xshare:off --release 11 -proc:none -cp "$cp" -d "$out/stub" \
  "$here/stub/com/music/bitchord/playback/PlaybackService.java" \
  "$here/stub/android/media/MediaDataSource.java" "$here/stub/android/net/Uri.java"
for test in PlaybackPriorityTest SourceAudioPriorityTest; do
  "$jdk/bin/java" -Xmx192m -cp "$out/stub:$out/classes:$cp" app.bitchord.telegram.$test
done
