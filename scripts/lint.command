#!/bin/bash
# Run Android lint checks.
cd "$(dirname "$0")/.."
echo "Running lint..."
./gradlew lint --no-daemon 2>&1
echo ""
echo "Report: app/build/reports/lint-results-debug.html"
echo "Press any key to close."
read -n 1
