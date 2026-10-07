#!/usr/bin/env sh
# Starts NEXUS from the build output (run "./mvnw package -DskipTests" first). Needs a JDK 25+.
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${NEXUS_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"
exec "$JAVA" --add-modules jdk.incubator.vector --enable-native-access=javafx.graphics,ALL-UNNAMED -XX:+UseZGC \
  -p "$HERE/nexus-app/target/lib:$HERE/nexus-app/target/nexus-app-0.1.0.jar" \
  -m nexus.app/nexus.app.NexusApp "$@"
