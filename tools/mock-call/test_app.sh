#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
for suite in recording routes store lifecycle stats controller; do
  bash "tools/mock-call/test_${suite}.sh"
done
python3 tools/mock-call/test_android_glue.py
python3 tools/mock-call/test_scoped_ui.py
python3 tools/mock-call/test_app_entry.py
python3 tools/mock-call/test_screen_contract.py
python3 tools/amoled/test_theme.py
