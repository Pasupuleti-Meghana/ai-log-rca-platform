#!/usr/bin/env sh
# Generates a large multi-service log file for performance demos (default: 1,000,000 lines).
# Usage: ./tools/generate-large-log.sh [output] [lines]
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
java "$ROOT/src/test/java/com/logsense/tools/LogGenerator.java" "${1:-target/large.log}" "${2:-1000000}"