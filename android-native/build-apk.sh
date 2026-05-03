#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
  echo "Java runtime not found. Install Android Studio or a JDK, then rerun this script." >&2
  exit 1
fi

if [ -x ./gradlew ]; then
  ./gradlew assembleDebug
elif command -v gradle >/dev/null 2>&1; then
  gradle assembleDebug
else
  echo "Gradle not found. Open this folder in Android Studio, or install Gradle, then rerun this script." >&2
  exit 1
fi
