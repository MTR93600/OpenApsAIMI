#!/bin/bash
# Proves a linked Kotlin/Native test binary contains the source-built
# libtensorflow-lite.a and no published TensorFlowLiteC framework.
# Usage: check-uam24-linked.sh device|simulator
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
KIND="${1:-}"
case "$KIND" in
  device)
    LIBDIR="$ROOT/plugins/aps/build/tflite-24-device-arm64"
    TARGET="iosArm64"
    ;;
  simulator)
    LIBDIR="$ROOT/plugins/aps/build/tflite-24-sim-arm64"
    TARGET="iosSimulatorArm64"
    ;;
  *)
    echo "usage: $0 device|simulator"
    exit 1
    ;;
esac

LIB="$LIBDIR/libtensorflow-lite.a"
SIDE="$LIBDIR/archive.sha256"
MAP="$LIBDIR/debugTest-link.map"

if [[ ! -f "$LIB" || ! -f "$SIDE" ]]; then
  echo "missing $LIB or $SIDE"
  exit 1
fi

ACTUAL="$(shasum -a 256 "$LIB" | awk '{print $1}')"
CACHED="$(awk 'NR==1 { print $1 }' "$SIDE")"
echo "archive sha256 $ACTUAL"
echo "cached sha256 $CACHED"
if [[ -z "$ACTUAL" || "$ACTUAL" != "$CACHED" ]]; then
  echo "archive hash does not match the cache sidecar"
  exit 1
fi

KEXE=""
COUNT=0
while IFS= read -r path; do
  KEXE="$path"
  COUNT=$((COUNT + 1))
done < <(find "$ROOT/plugins/aps/build/bin/$TARGET" -type f -name '*.kexe' ! -path '*.dSYM/*' | sort)
if [[ "$COUNT" -ne 1 || -z "$KEXE" ]]; then
  echo "expected one $TARGET kexe, found $COUNT"
  find "$ROOT/plugins/aps/build/bin/$TARGET" -name '*.kexe' -print || true
  exit 1
fi
echo "binary $KEXE"
file "$KEXE"

echo "otool -L"
otool -L "$KEXE"
if otool -L "$KEXE" | grep -F "TensorFlowLite"; then
  echo "published TensorFlowLite framework is linked"
  exit 1
fi

if [[ ! -f "$MAP" ]]; then
  echo "missing link map $MAP"
  ls -la "$LIBDIR" || true
  exit 1
fi
if ! grep -F "$LIB" "$MAP" >/dev/null; then
  echo "link map does not name $LIB"
  grep -F -m 5 "libtensorflow-lite.a" "$MAP" || true
  exit 1
fi
if grep -F "TensorFlowLiteC" "$MAP"; then
  echo "link map names TensorFlowLiteC"
  exit 1
fi
echo "link map names the source archive"
# -m stops after five hits. A pipe into head dies with SIGPIPE under pipefail
# because the map names the archive once per object file.
grep -F -m 5 "$LIB" "$MAP"

nm -g -U "$KEXE" > "$LIBDIR/nm-defined.txt"
nm -g -u "$KEXE" > "$LIBDIR/nm-undefined.txt" || true

python3 - "$LIBDIR/nm-defined.txt" "$LIBDIR/nm-undefined.txt" << 'PY'
import collections
import pathlib
import sys

defined = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
undefined = pathlib.Path(sys.argv[2]).read_text(errors="replace").splitlines()
counts = collections.Counter()
for line in defined:
    parts = line.split()
    if len(parts) < 3:
        continue
    typ, name = parts[-2], parts[-1]
    if typ not in {"T", "D", "S", "B", "C", "A"}:
        continue
    if "TfLite" not in name:
        continue
    counts[name] += 1
if not counts:
    raise SystemExit("no defined TfLite symbol")
dups = sorted(name for name, count in counts.items() if count != 1)
if dups:
    print("TfLite symbols defined more than once")
    for name in dups:
        print(f"  {counts[name]} {name}")
    raise SystemExit(1)
missing_undefined = []
for line in undefined:
    parts = line.split()
    if not parts:
        continue
    name = parts[-1]
    if "TfLite" in name:
        missing_undefined.append(name)
if missing_undefined:
    print("undefined TfLite symbols")
    for name in missing_undefined:
        print(f"  {name}")
    raise SystemExit(1)
required = (
    "TfLiteVersion",
    "TfLiteModelCreate",
    "TfLiteInterpreterCreate",
    "TfLiteInterpreterOptionsSetNumThreads",
    "TfLiteInterpreterAllocateTensors",
    "TfLiteInterpreterInvoke",
)
names = set(counts)
for stem in required:
    if not any(stem in name for name in names):
        raise SystemExit(f"missing defined symbol {stem}")
print(f"TfLite symbols defined once: {len(counts)}")
for stem in required:
    match = sorted(name for name in names if stem in name)
    print(f"  {match[0]}")
PY
echo "linked runtime is the source archive only"
