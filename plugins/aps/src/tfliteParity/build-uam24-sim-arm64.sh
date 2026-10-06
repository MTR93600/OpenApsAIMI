#!/bin/bash
# TensorFlow Lite C 2.4.0, tag v2.4.0, compiled for iossimulator-arm64.
# One thread, no delegate. Compares raw float32 bits to the Android 2.4.0
# arm64-v8a words in vectors.txt. Any mismatch fails the job.
#
# The published 2.4.0 framework has no arm64 simulator slice. This script
# builds the library from source, or reuses the cached static library.
# Flags live in uam24-common.sh and are shared with the device build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
# shellcheck source=uam24-common.sh
source "$ROOT/plugins/aps/src/tfliteParity/uam24-common.sh"
WORK="$ROOT/plugins/aps/build/tflite-24-sim-arm64"
MODEL="$ROOT/plugins/aps/src/tfliteParity/modelUAM.tflite"
VECTORS="$ROOT/plugins/aps/src/tfliteParity/vectors.txt"
RUNNER_SRC="$ROOT/plugins/aps/src/tfliteParity/uam24_runner.c"
HEADER_DIR="$ROOT/plugins/aps/src/nativeInterop/cinterop"

uam24_ensure_lib simulator "$WORK"
if [[ "$(uam24_platform_kind "$WORK/libtensorflow-lite.a")" != "sim" ]]; then
  echo "cached library is not an iossimulator-arm64 binary"
  exit 1
fi
file "$WORK/libtensorflow-lite.a"

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
echo "SDK $SDK"
clang -target arm64-apple-ios15.0-simulator -isysroot "$SDK" -O2 \
  -I "$HEADER_DIR" \
  "$RUNNER_SRC" \
  -Wl,-force_load,"$WORK/libtensorflow-lite.a" \
  -lc++ \
  -framework CoreFoundation \
  -o "$WORK/uam24-sim-arm64"
codesign --force --sign - "$WORK/uam24-sim-arm64"
file "$WORK/uam24-sim-arm64"
if [[ "$(uam24_platform_kind "$WORK/uam24-sim-arm64")" != "sim" ]]; then
  echo "runner is not an iossimulator-arm64 executable"
  exit 1
fi

echo "Runtimes:"
xcrun simctl list runtimes
SELECTION="$(python3 - << 'PY'
import json, subprocess
runtimes = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "runtimes", "-j"]))
ios = [r for r in runtimes["runtimes"] if r.get("isAvailable") and "iOS" in r.get("name", "")]
if not ios:
    raise SystemExit("no available iOS runtime")
devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devicetypes", "-j"]))
iphone = next(item for item in devices["devicetypes"] if item["name"].startswith("iPhone"))
print(ios[-1]["identifier"])
print(iphone["identifier"])
PY
)"
RUNTIME="$(printf '%s\n' "$SELECTION" | sed -n '1p')"
DEVTYPE="$(printf '%s\n' "$SELECTION" | sed -n '2p')"
echo "Creating simulator runtime=$RUNTIME device=$DEVTYPE"
UDID="$(xcrun simctl create uam24-arm64 "$DEVTYPE" "$RUNTIME")"
echo "UDID $UDID"
cleanup() {
  if [[ -n "${UDID:-}" ]]; then
    xcrun simctl shutdown "$UDID" || true
    xcrun simctl delete "$UDID" || true
  fi
}
trap cleanup EXIT
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b

set +e
xcrun simctl spawn "$UDID" "$WORK/uam24-sim-arm64" "$MODEL" "$VECTORS" > "$WORK/out.txt" 2> "$WORK/err.txt"
STATUS=$?
set -e
echo "spawn status $STATUS"
echo "stderr:"
cat "$WORK/err.txt" || true
if [[ "$STATUS" -ne 0 ]]; then
  echo "TensorFlow Lite C 2.4.0 iossimulator-arm64 did not run"
  exit "$STATUS"
fi

echo "stdout:"
cat "$WORK/out.txt"
python3 - << PY
from pathlib import Path
text = Path("$WORK/out.txt").read_text().strip().splitlines()
version = text[0]
if not version.startswith("version 2.4"):
    raise SystemExit(f"expected TFLite 2.4, got {version!r}")
rows = []
for line in text[1:]:
    index, android, ios = line.split()
    rows.append((int(index), android, ios))
if len(rows) != 67:
    raise SystemExit(f"expected 67 rows, got {len(rows)}")

def ordered(bits):
    return (0x80000000 - bits) if bits >= 0x80000000 else bits

mismatches = []
for index, android, ios in rows:
    if android != ios:
        mismatches.append((index, android, ios, abs(ordered(int(android, 16)) - ordered(int(ios, 16)))))
print("TensorFlow Lite C 2.4.0 iossimulator-arm64 against the Android arm64-v8a reference.")
print(f"identical {67 - len(mismatches)} / 67")
if mismatches:
    print(f"max ULP {max(item[3] for item in mismatches)}")
    for index, android, ios, ulp in mismatches:
        print(f"{index} android={android} ios={ios} ulp={ulp}")
    raise SystemExit("iossimulator-arm64 words differ from the Android arm64-v8a reference")
print("67 / 67 identical")
PY
