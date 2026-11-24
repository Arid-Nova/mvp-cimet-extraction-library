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

# NOTE: JAVA_TOOL_OPTIONS is intentionally not set here.
# It's provided via the devcontainer environment (containerEnv) so it applies
# uniformly to the whole container and all Java processes.

# Execute the wrapper main
exec java -Dmaven.multiModuleProjectDirectory="$PWD" -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
