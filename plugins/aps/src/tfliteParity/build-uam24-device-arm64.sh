#!/bin/bash
# TensorFlow Lite C 2.4.0, tag v2.4.0, compiled for iphoneos-arm64.
# Same flags as the iossimulator-arm64 library (uam24-common.sh). The
# library is linked into the device test binary. It is not executed here:
# this runner has no iPhone. A mismatch of platform fails the job.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
# shellcheck source=uam24-common.sh
source "$ROOT/plugins/aps/src/tfliteParity/uam24-common.sh"
WORK="$ROOT/plugins/aps/build/tflite-24-device-arm64"

uam24_ensure_lib device "$WORK"
if [[ "$(uam24_platform_kind "$WORK/libtensorflow-lite.a")" != "device" ]]; then
  echo "device library is not an iphoneos-arm64 binary"
  exit 1
fi
file "$WORK/libtensorflow-lite.a"
echo "iphoneos-arm64 library is built and not executed"
echo "clang $(cat "$WORK/clang-version.txt")"
echo "flags:"
cat "$WORK/flags.txt"
