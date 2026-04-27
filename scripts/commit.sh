#!/bin/bash
# Stage all changes and commit locally. Does NOT push.
# Usage: bash scripts/commit.sh "your commit message"
# Can be called by the agent from the bash sandbox (commit works; push needs Mac).
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

if [ -z "$1" ]; then
  echo "Usage: bash scripts/commit.sh \"commit message\""
  exit 1
fi

rm -f .git/index.lock .git/HEAD.lock 2>/dev/null || true
# If locks still exist (FUSE sandbox can't delete them), fall back to push.command on Mac
if [ -f .git/index.lock ] || [ -f .git/HEAD.lock ]; then
  echo "⚠️  Git lock files exist and can't be removed from this environment."
  echo "   Run scripts/push.command on your Mac instead — it handles this automatically."
  exit 1
fi
git config user.email "apbeers@icloud.com"
git config user.name "Andrew"
git --no-optional-locks add -A
git --no-optional-locks commit -m "$1"
echo ""
echo "Committed: $(git --no-optional-locks rev-parse --short HEAD) — $1"
echo "Run scripts/push.command to push to GitHub."
