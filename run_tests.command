#!/bin/bash
cd "$(dirname "$0")"
LOG="$(dirname "$0")/test_results_android.txt"
echo "Running Android integration tests..." | tee "$LOG"
echo "======================================" | tee -a "$LOG"
./gradlew test -PATTND_API_KEY="$(grep ATTND_API_KEY local.properties | cut -d= -f2)" --no-daemon 2>&1 | tee -a "$LOG"
echo "" | tee -a "$LOG"
echo "Done. Results saved to test_results_android.txt" | tee -a "$LOG"
echo "Press any key to close."
read -n 1
