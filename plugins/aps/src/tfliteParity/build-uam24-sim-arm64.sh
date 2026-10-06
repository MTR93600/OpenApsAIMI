#!/bin/bash
# TensorFlow Lite C 2.4.0, tag v2.4.0, compiled for iossimulator-arm64.
# One thread, no delegate. Compares raw float32 bits to the Android 2.4.0
# arm64-v8a words in vectors.txt. Any mismatch fails the job.
#
# The published 2.4.0 framework has no arm64 simulator slice. This script
# builds the library from source. It is not that unpublished device slice.
#
# Pod flags kept (Bazel --config=ios, cpu ios_arm64, -c opt):
#   -DFARMHASH_NO_CXX_STRING -Wno-sign-compare -Wno-c++11-narrowing
#   -fno-exceptions -O3 -std=c++14 -w
#   -DTFLITE_WITH_RUY  (kernels/BUILD selects it for cpu_ios_arm64)
# Makefile default kept: -DTFLITE_WITHOUT_XNNPACK. The runner attaches no
# delegate. Dropped versus the pod makefile/bazel ios config, because they
# change the GEMM or because Xcode 26 rejects them:
#   -fembed-bitcode, -mno-thumb, -framework Accelerate,
#   -DTF_LITE_USE_CBLAS, -DGEMMLOWP_ALLOW_SLOW_SCALAR_FALLBACK,
#   -miphoneos-version-min=9.0 (this build uses the simulator SDK, min 12.0).
# There is no ios_sim_arm64 Bazel config in the v2.4.0 .bazelrc.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
CACHE_LIB="$ROOT/plugins/aps/build/tflite-24-sim-arm64/libtensorflow-lite.a"
SRC="$ROOT/plugins/aps/build/tflite-24-src"
WORK="$ROOT/plugins/aps/build/tflite-24-sim-arm64"
MODEL="$ROOT/plugins/aps/src/tfliteParity/modelUAM.tflite"
VECTORS="$ROOT/plugins/aps/src/tfliteParity/vectors.txt"
RUNNER_SRC="$ROOT/plugins/aps/src/tfliteParity/uam24_runner.c"
HEADER_DIR="$ROOT/plugins/aps/src/nativeInterop/cinterop"
TARBALL_URL="https://github.com/tensorflow/tensorflow/archive/refs/tags/v2.4.0.tar.gz"

mkdir -p "$WORK"

lib_is_simulator() {
  python3 - "$1" << 'PY'
import subprocess, sys
path = sys.argv[1]
text = subprocess.check_output(["otool", "-l", path], text=True, errors="replace")
platforms = []
for line in text.splitlines():
    line = line.strip()
    if line.startswith("platform "):
        platforms.append(line.split(None, 1)[1])

def kind(token):
    if token == "7" or token.startswith("7 ") or "IOSSIMULATOR" in token:
        return "sim"
    if token == "2" or token.startswith("2 ") or token == "IOS":
        return "device"
    return "other"

kinds = [kind(token) for token in platforms]
if kinds.count("sim") == 0 or "device" in kinds:
    print("platforms:", " ".join(platforms[:12]) or "(none)")
    raise SystemExit(1)
print("IOSSIMULATOR objects:", kinds.count("sim"))
PY
}

build_lib() {
  echo "Building TensorFlow Lite C 2.4.0 for iossimulator-arm64"
  rm -rf "$SRC"
  mkdir -p "$SRC"
  curl -fsSL -o "$SRC/v2.4.0.tar.gz" "$TARBALL_URL"
  tar -xzf "$SRC/v2.4.0.tar.gz" -C "$SRC"
  local tree="$SRC/tensorflow-2.4.0"
  if [[ ! -d "$tree" ]]; then
    echo "tag v2.4.0 did not extract to tensorflow-2.4.0"
    exit 1
  fi
  local makefile="$tree/tensorflow/lite/tools/make/Makefile"
  if ! grep -q 'cp -u tensorflow/lite/schema/schema_generated.h.OPENSOURCE' "$makefile"; then
    echo "schema_generated.h copy line not found; refusing to build"
    exit 1
  fi
  # BSD cp on the runner has no -u. The copy is unconditional.
  sed -i '' 's/cp -u tensorflow\/lite\/schema\/schema_generated.h.OPENSOURCE/cp -f tensorflow\/lite\/schema\/schema_generated.h.OPENSOURCE/' "$makefile"
  cat > "$tree/tensorflow/lite/tools/make/targets/ios_makefile.inc" << 'EOF'
# Replaces the v2.4.0 iOS makefile. That file forces TARGET_ARCH=x86_64,
# the device SDK for any other arch, -fembed-bitcode, CBLAS and Accelerate.
# This one targets the arm64 simulator and the pod's RUY float path.
ifeq ($(TARGET), ios)
  IPHONEOS_SYSROOT := $(shell xcrun --sdk iphonesimulator --show-sdk-path)
  MIN_SDK_VERSION := 12.0
  TARGET_ARCH := arm64
  CXXFLAGS += -mios-simulator-version-min=$(MIN_SDK_VERSION) \
    -DFARMHASH_NO_CXX_STRING \
    -Wno-sign-compare \
    -Wno-c++11-narrowing \
    -fno-exceptions \
    -w \
    --std=c++14 \
    -DTFLITE_WITH_RUY \
    -ffp-contract=on \
    -isysroot ${IPHONEOS_SYSROOT} \
    -arch $(TARGET_ARCH) \
    -O3 -DNDEBUG
  CFLAGS += -mios-simulator-version-min=$(MIN_SDK_VERSION) \
    -DFARMHASH_NO_CXX_STRING \
    -Wno-sign-compare \
    -w \
    -ffp-contract=on \
    -isysroot ${IPHONEOS_SYSROOT} \
    -arch $(TARGET_ARCH) \
    -O3 -DNDEBUG
  LDFLAGS := -mios-simulator-version-min=${MIN_SDK_VERSION} -arch $(TARGET_ARCH)
  LIBS := -lc++
endif
EOF
  echo "Downloading Eigen, gemmlowp, ruy, absl, farmhash, flatbuffers, fft2d, FP16"
  (cd "$tree" && bash tensorflow/lite/tools/make/download_dependencies.sh)
  if [[ ! -f "$tree/tensorflow/lite/c/c_api.cc" ]]; then
    echo "tensorflow/lite/c/c_api.cc missing from tag v2.4.0"
    exit 1
  fi
  # Attempt 1 (run 37402326168) compiled with Xcode 26 clang and stopped in
  # elementwise.cc: std::abs<float> is not a function pointer. The Android
  # AAR was built by NDK clang 7.0.2, which accepts that form. These five
  # wrappers call the same libm functions. modelUAM does not use ABS, SIN,
  # COS, LOG or SQRT. -O3 -ffp-contract=on matches the NDK clang default
  # and the Bazel opt config. RUY stays on, as in cpu_ios_arm64.
  python3 - "$tree/tensorflow/lite/kernels/elementwise.cc" << 'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
replacements = {
    "return EvalImpl<float>(context, node, std::abs<float>, type);":
        "return EvalImpl<float>(context, node, [](float v) { return std::abs(v); }, type);",
    "return EvalNumeric(context, node, std::sin);":
        "return EvalNumeric(context, node, [](float v) { return std::sin(v); });",
    "return EvalNumeric(context, node, std::cos);":
        "return EvalNumeric(context, node, [](float v) { return std::cos(v); });",
    "return EvalNumeric(context, node, std::log);":
        "return EvalNumeric(context, node, [](float v) { return std::log(v); });",
    "return EvalNumeric(context, node, std::sqrt);":
        "return EvalNumeric(context, node, [](float v) { return std::sqrt(v); });",
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"elementwise.cc patch missed: {old}")
    text = text.replace(old, new, 1)
path.write_text(text)
print("patched elementwise.cc for this clang")
PY
  local jobs
  jobs="$(sysctl -n hw.ncpu)"
  echo "simulator clang:"
  xcrun -sdk iphonesimulator clang --version | head -4
  make --version | head -2
  echo "make -j$jobs micro TARGET=ios TARGET_ARCH=arm64 BUILD_WITH_RUY=true -O3 -ffp-contract=on"
  make -C "$tree" -j"$jobs" -f tensorflow/lite/tools/make/Makefile micro \
    TARGET=ios TARGET_ARCH=arm64 BUILD_WITH_RUY=true \
    CC="xcrun -sdk iphonesimulator clang" \
    CXX="xcrun -sdk iphonesimulator clang++"
  local built="$tree/tensorflow/lite/tools/make/gen/ios_arm64/lib/libtensorflow-lite.a"
  if [[ ! -f "$built" ]]; then
    echo "libtensorflow-lite.a was not produced"
    find "$tree/tensorflow/lite/tools/make/gen" -name 'libtensorflow-lite.a' -print || true
    exit 1
  fi
  if ! lib_is_simulator "$built"; then
    echo "built library is not an iossimulator-arm64 binary"
    otool -l "$built" | awk '/LC_BUILD_VERSION/,/minos/' | head -40
    exit 1
  fi
  cp "$built" "$CACHE_LIB"
  echo "cached $CACHE_LIB"
}

if [[ -f "$CACHE_LIB" ]] && lib_is_simulator "$CACHE_LIB"; then
  echo "Using cached $CACHE_LIB"
else
  rm -f "$CACHE_LIB"
  build_lib
fi
file "$CACHE_LIB"

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
echo "SDK $SDK"
clang -target arm64-apple-ios15.0-simulator -isysroot "$SDK" -O2 \
  -I "$HEADER_DIR" \
  "$RUNNER_SRC" \
  -Wl,-force_load,"$CACHE_LIB" \
  -lc++ \
  -o "$WORK/uam24-sim-arm64"
codesign --force --sign - "$WORK/uam24-sim-arm64"
file "$WORK/uam24-sim-arm64"
if ! lib_is_simulator "$WORK/uam24-sim-arm64"; then
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
