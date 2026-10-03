#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
for suite in recording routes store lifecycle stats controller; do
  bash "tools/mock-call/test_${suite}.sh"
done
tmp_benchmark=$(mktemp -d)
trap 'rm -rf "$tmp_benchmark"' EXIT
kotlinc \
  app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/CallDenoiseSettings.kt \
  app/src/main/java/org/thoughtcrime/securesms/webrtc/audio/mock/LabBenchmark.kt \
  tools/mock-call/tests/BenchmarkContracts.kt \
  -include-runtime -d "$tmp_benchmark/benchmark.jar"
java -jar "$tmp_benchmark/benchmark.jar"
python3 tools/mock-call/test_android_glue.py
python3 tools/mock-call/test_scoped_ui.py
python3 tools/mock-call/test_app_entry.py
python3 tools/mock-call/test_screen_contract.py
python3 tools/amoled/test_theme.py
