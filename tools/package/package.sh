#!/usr/bin/env bash
# Builds a self-contained NEXUS application image (and a zip of it) with jpackage:
#   tools/package/package.sh            -> dist/NEXUS/ and dist/NEXUS-<os>.zip
#
# The runtime is a jlink image of the JDK modules NEXUS needs; the application's modules (and
# PDFBox, an automatic module jlink cannot link) stay on the module path inside the image, next to
# the Tessera native library. Run "./mvnw package -DskipTests" and build native/tessera first.
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
VERSION=0.1.0
OUT=dist
rm -rf "$OUT" target/package
mkdir -p "$OUT" target/package/mods

cp nexus-app/target/lib/*.jar target/package/mods/
cp nexus-app/target/nexus-app-$VERSION.jar target/package/mods/
for lib in native/tessera/build/Release/tessera.dll native/tessera/build/libtessera.so native/tessera/build/libtessera.dylib; do
  [ -f "$lib" ] && cp "$lib" target/package/mods/
done

"$JAVA_HOME/bin/jlink" --output target/package/runtime --strip-debug --no-header-files --no-man-pages \
  --add-modules java.base,java.desktop,java.logging,java.net.http,java.naming,java.sql,java.xml,java.scripting,jdk.management,jdk.unsupported,jdk.incubator.vector,jdk.crypto.ec,jdk.zipfs,jdk.jfr

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows; SEP=';' ;;
  Darwin) OS=macos; SEP=':' ;;
  *) OS=linux; SEP=':' ;;
esac

"$JAVA_HOME/bin/jpackage" --type app-image --name NEXUS --app-version "$VERSION" --dest "$OUT" \
  --vendor "YUStulinu" --description "A visual AI studio in Java" \
  --runtime-image target/package/runtime \
  --input target/package/mods --main-jar "nexus-app-$VERSION.jar" --main-class nexus.app.Launcher \
  --java-options "--add-modules jdk.incubator.vector" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-XX:+UseZGC" \
  --java-options "-Dnexus.tessera.dir=\$APPDIR"

if command -v zip >/dev/null; then
  (cd "$OUT" && zip -qr "NEXUS-$OS.zip" NEXUS)
else
  # Windows without zip: the JDK's jar tool writes zip files too.
  (cd "$OUT" && "$JAVA_HOME/bin/jar" --create --no-manifest --file "NEXUS-$OS.zip" NEXUS)
fi
echo "built $OUT/NEXUS and $OUT/NEXUS-$OS.zip"
