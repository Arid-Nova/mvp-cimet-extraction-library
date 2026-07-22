#!/usr/bin/env bash
# Minimal Maven wrapper launcher — delegates to .mvn/wrapper/maven-wrapper.jar
DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$DIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: Maven wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi

exec java -jar "$WRAPPER_JAR" "$@"
