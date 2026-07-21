#!/usr/bin/env bash
# Maven Wrapper launcher (minimal)
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_CMD=${JAVA_HOME:+"$JAVA_HOME/bin/java"}
if [ -z "$JAVA_CMD" ]; then
  JAVA_CMD=java
fi
JAR="$ROOT_DIR/.mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "maven wrapper jar not found: $JAR" >&2
  exit 1
fi
exec "$JAVA_CMD" -Dmaven.multiModuleProjectDirectory="$ROOT_DIR" -cp "$JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
