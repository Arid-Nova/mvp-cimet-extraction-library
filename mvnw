#!/usr/bin/env bash
# Maven wrapper launcher that invokes the wrapper main class directly.
# Sets sensible JVM and Maven properties to avoid warnings under modern JDKs.

DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$DIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: Maven wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi

# Ensure Maven picks up correct multi-module project directory (fixes the "-Dmaven.multiModuleProjectDirectory system property is not set" message)
if [ -z "${MAVEN_OPTS+x}" ]; then
  MAVEN_OPTS="-Dmaven.multiModuleProjectDirectory=$PWD"
else
  MAVEN_OPTS="$MAVEN_OPTS -Dmaven.multiModuleProjectDirectory=$PWD"
fi
export MAVEN_OPTS

# For recent JDKs, warn about native access; enable it to silence warnings from older libraries
if [ -z "${JAVA_TOOL_OPTIONS+x}" ]; then
  JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"
else
  JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS --enable-native-access=ALL-UNNAMED"
fi
export JAVA_TOOL_OPTIONS

# Execute the wrapper main
exec java -Dmaven.multiModuleProjectDirectory="$PWD" -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
