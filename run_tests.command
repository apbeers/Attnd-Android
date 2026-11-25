#!/bin/bash
cd "$(dirname "$0")"
LOG="$(dirname "$0")/test_results_android.txt"
echo "Running Android unit tests..." | tee "$LOG"
echo "======================================" | tee -a "$LOG"
./gradlew test --no-daemon 2>&1 | tee -a "$LOG"
# Live API tests: only when ATTND_IT_SERVER and ATTND_IT_KEY are set in the environment.
if [ -n "$ATTND_IT_SERVER" ] && [ -n "$ATTND_IT_KEY" ]; then
  echo "" | tee -a "$LOG"
  echo "Running live API tests against $ATTND_IT_SERVER..." | tee -a "$LOG"
  ./gradlew testDebugUnitTest -PintegrationTests --no-daemon 2>&1 | tee -a "$LOG"
fi
echo "" | tee -a "$LOG"
echo "Done. Results saved to test_results_android.txt" | tee -a "$LOG"
echo "Press any key to close."
read -n 1
