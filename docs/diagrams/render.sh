#!/usr/bin/env bash
set -euo pipefail

readonly REQUIRED_PLANTUML_VERSION="1.2026.6"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/plantuml-${REQUIRED_PLANTUML_VERSION}.jar" >&2
  exit 2
fi

readonly PLANTUML_JAR="$1"

if [[ ! -f "$PLANTUML_JAR" ]]; then
  echo "PlantUML JAR not found: $PLANTUML_JAR" >&2
  exit 2
fi

actual_version="$({
  java -Djava.awt.headless=true -jar "$PLANTUML_JAR" -version
} | sed -n 's/^PlantUML version \([^ ]*\).*/\1/p')"

if [[ "$actual_version" != "$REQUIRED_PLANTUML_VERSION" ]]; then
  echo "Expected PlantUML ${REQUIRED_PLANTUML_VERSION}, found ${actual_version:-unknown}" >&2
  exit 2
fi

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

java -Djava.awt.headless=true \
  -jar "$PLANTUML_JAR" \
  -charset UTF-8 \
  -nometadata \
  -tsvg \
  "$SCRIPT_DIR"/*.puml
