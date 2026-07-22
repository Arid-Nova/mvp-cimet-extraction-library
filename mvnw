#!/usr/bin/env bash
# Maven wrapper launcher that invokes the wrapper main class directly.
DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$DIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: Maven wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi

# Call the wrapper main class directly to avoid relying on a Main-Class in the JAR manifest
exec java -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
