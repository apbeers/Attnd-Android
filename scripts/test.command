#!/bin/bash
# Run Android unit + integration tests (JVM tests, no emulator needed).
# Double-click to run on your Mac.
# Output saved to scripts/last_test_run.txt
cd "$(dirname "$0")/.."
LOG="scripts/last_test_run.txt"
API_KEY="$(grep ATTND_API_KEY local.properties | cut -d= -f2)"

echo "Running Android tests at $(date)" | tee "$LOG"
echo "======================================" | tee -a "$LOG"

./gradlew test -PATTND_API_KEY="$API_KEY" --no-daemon 2>&1 | tee -a "$LOG"

STATUS=$?
echo "" | tee -a "$LOG"
[ $STATUS -eq 0 ] && echo "✅ All tests passed." | tee -a "$LOG" \
                  || echo "❌ Tests failed. See log above." | tee -a "$LOG"
echo "Full report: app/build/reports/tests/testDebugUnitTest/index.html"
echo "Press any key to close."
read -n 1
