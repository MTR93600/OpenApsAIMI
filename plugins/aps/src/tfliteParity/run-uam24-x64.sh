#!/bin/bash
# TensorFlow Lite C 2.4.0, x86_64 simulator slice, on the macOS runner.
# One thread, no delegate. Compares raw float32 bits to the Android 2.4.0 words.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
WORK="$ROOT/plugins/aps/build/tflite-24"
MODEL="$ROOT/plugins/aps/src/tfliteParity/modelUAM.tflite"
VECTORS="$ROOT/plugins/aps/src/tfliteParity/vectors.txt"
RUNNER_SRC="$ROOT/plugins/aps/src/tfliteParity/uam24_runner.c"
HEADER_DIR="$ROOT/plugins/aps/src/nativeInterop/cinterop"
URL="https://dl.google.com/dl/cpdc/e8a95c1d411b795e/TensorFlowLiteC-2.4.0.tar.gz"

mkdir -p "$WORK"
if [[ ! -f "$WORK/TensorFlowLiteC" ]]; then
  echo "Downloading TensorFlow Lite C 2.4.0"
  curl -fsSL -o "$WORK/TensorFlowLiteC-2.4.0.tar.gz" "$URL"
  tar -xzf "$WORK/TensorFlowLiteC-2.4.0.tar.gz" -C "$WORK" \
    TensorFlowLiteC-2.4.0/Frameworks/TensorFlowLiteC.framework/TensorFlowLiteC
  mv "$WORK/TensorFlowLiteC-2.4.0/Frameworks/TensorFlowLiteC.framework/TensorFlowLiteC" \
    "$WORK/TensorFlowLiteC"
fi

echo "Fat binary:"
lipo -info "$WORK/TensorFlowLiteC"
lipo -thin x86_64 "$WORK/TensorFlowLiteC" -output "$WORK/TensorFlowLiteC-x86_64.o"
lipo -info "$WORK/TensorFlowLiteC-x86_64.o"

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
echo "SDK $SDK"
clang -target x86_64-apple-ios15.0-simulator -isysroot "$SDK" -O2 \
  -I "$HEADER_DIR" \
  "$RUNNER_SRC" "$WORK/TensorFlowLiteC-x86_64.o" \
  -lc++ \
  -o "$WORK/uam24"
codesign --force --sign - "$WORK/uam24"
file "$WORK/uam24"

echo "Runtimes:"
xcrun simctl list runtimes
echo "Device types:"
xcrun simctl list devicetypes | awk '/iPhone/ {print; exit}'

RUNTIME="$(python3 - << 'PY'
import json, subprocess
data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "runtimes", "-j"]))
ios = [r for r in data["runtimes"] if r.get("isAvailable") and "iOS" in r.get("name", "")]
if not ios:
    raise SystemExit("no available iOS runtime")
print(ios[-1]["identifier"])
PY
)"
DEVTYPE="$(xcrun simctl list devicetypes -j | python3 - << 'PY'
import json, sys
data = json.load(sys.stdin)
for item in data["devicetypes"]:
    if item["name"].startswith("iPhone"):
        print(item["identifier"])
        break
else:
    raise SystemExit("no iPhone device type")
PY
)"
echo "Creating simulator runtime=$RUNTIME device=$DEVTYPE"
UDID="$(xcrun simctl create uam24 "$DEVTYPE" "$RUNTIME")"
echo "UDID $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b

set +e
xcrun simctl spawn "$UDID" "$WORK/uam24" "$MODEL" "$VECTORS" > "$WORK/out.txt" 2> "$WORK/err.txt"
STATUS=$?
set -e
echo "spawn status $STATUS"
echo "stderr:"
cat "$WORK/err.txt" || true
if [[ "$STATUS" -ne 0 ]]; then
  echo "x86_64 spawn failed; retrying with arch -x86_64"
  set +e
  xcrun simctl spawn "$UDID" arch -x86_64 "$WORK/uam24" "$MODEL" "$VECTORS" > "$WORK/out.txt" 2> "$WORK/err.txt"
  STATUS=$?
  set -e
  echo "arch spawn status $STATUS"
  cat "$WORK/err.txt" || true
fi
xcrun simctl shutdown "$UDID" || true
xcrun simctl delete "$UDID" || true
if [[ "$STATUS" -ne 0 ]]; then
  echo "TensorFlow Lite C 2.4.0 x86_64 simulator did not run"
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
mismatches = []
for index, android, ios in rows:
    if android != ios:
        a = int(android, 16)
        b = int(ios, 16)
        def ordered(bits):
            return (0x80000000 - bits) if bits >= 0x80000000 else bits
        mismatches.append((index, android, ios, abs(ordered(a) - ordered(b))))
print(f"identical {67 - len(mismatches)} / 67")
if mismatches:
    print(f"max ULP {max(m[3] for m in mismatches)}")
    for index, android, ios, ulp in mismatches:
        print(f"{index} android={android} ios={ios} ulp={ulp}")
    raise SystemExit(1)
print("67 / 67 identical")
PY
