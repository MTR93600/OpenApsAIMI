#!/bin/bash
# Shared source build of TensorFlow Lite tag v2.4.0 for arm64.
# Sourced by the simulator and device scripts. The parity flags are the ones
# that produced 67/67 against the Android arm64-v8a words:
#   -O3 -DNDEBUG -ffp-contract=on -DTFLITE_WITH_RUY -DTFLITE_WITHOUT_XNNPACK
# Apple clang, one thread at runtime, no delegate. The only intended
# difference between the two libraries is the SDK (iphonesimulator vs iphoneos)
# and the matching minimum-version flag name. Both use version 12.0.
# shellcheck shell=bash

uam24_tarball_url() {
  echo "https://github.com/tensorflow/tensorflow/archive/refs/tags/v2.4.0.tar.gz"
}

uam24_parity_flags() {
  printf '%s\n' \
    -DNDEBUG \
    -DTFLITE_WITHOUT_XNNPACK \
    -DTFLITE_WITH_RUY \
    -O3 \
    -ffp-contract=on
}

# sdk is iphonesimulator or iphoneos. Writes the makefile include to stdout.
uam24_makefile_inc() {
  local sdk="$1"
  local minflag
  case "$sdk" in
    iphonesimulator) minflag="-mios-simulator-version-min" ;;
    iphoneos) minflag="-miphoneos-version-min" ;;
    *)
      echo "unknown sdk $sdk" >&2
      return 1
      ;;
  esac
  cat << EOF
# Replaces the v2.4.0 iOS makefile. That file forces TARGET_ARCH=x86_64,
# -fembed-bitcode, CBLAS and Accelerate. This one keeps the flags of the
# iossimulator-arm64 build that matched Android arm64-v8a 67/67.
ifeq (\$(TARGET), ios)
  IPHONEOS_SYSROOT := \$(shell xcrun --sdk ${sdk} --show-sdk-path)
  MIN_SDK_VERSION := 12.0
  TARGET_ARCH := arm64
  CXXFLAGS += ${minflag}=\$(MIN_SDK_VERSION) \\
    -DFARMHASH_NO_CXX_STRING \\
    -Wno-sign-compare \\
    -Wno-c++11-narrowing \\
    -fno-exceptions \\
    -w \\
    --std=c++14 \\
    -DTFLITE_WITH_RUY \\
    -DTFLITE_WITHOUT_XNNPACK \\
    -ffp-contract=on \\
    -isysroot \${IPHONEOS_SYSROOT} \\
    -arch \$(TARGET_ARCH) \\
    -O3 -DNDEBUG
  CFLAGS += ${minflag}=\$(MIN_SDK_VERSION) \\
    -DFARMHASH_NO_CXX_STRING \\
    -Wno-sign-compare \\
    -w \\
    -DTFLITE_WITH_RUY \\
    -DTFLITE_WITHOUT_XNNPACK \\
    -ffp-contract=on \\
    -isysroot \${IPHONEOS_SYSROOT} \\
    -arch \$(TARGET_ARCH) \\
    -O3 -DNDEBUG
  LDFLAGS := ${minflag}=\${MIN_SDK_VERSION} -arch \$(TARGET_ARCH)
  LIBS := -lc++
endif
EOF
}

# Prints the SHA-256 of a file, hex only. The sidecar is this line and nothing else.
uam24_archive_sha256() {
  shasum -a 256 "$1" | awk '{print $1}'
}

# Prints "sim" or "device" after reading LC_BUILD_VERSION from a static archive.
uam24_platform_kind() {
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
sims = kinds.count("sim")
devices = kinds.count("device")
if sims and not devices:
    print("sim")
elif devices and not sims:
    print("device")
else:
    print("platforms:", " ".join(platforms[:12]) or "(none)", file=sys.stderr)
    raise SystemExit(1)
PY
}

uam24_prepare_tree() {
  local tree="$1"
  local sdk="$2"
  local tarball_dir="$3"
  local makefile="$tree/tensorflow/lite/tools/make/Makefile"
  mkdir -p "$tarball_dir"
  if [[ ! -f "$tarball_dir/v2.4.0.tar.gz" ]]; then
    curl -fsSL -o "$tarball_dir/v2.4.0.tar.gz" "$(uam24_tarball_url)"
  fi
  rm -rf "$tree"
  mkdir -p "$(dirname "$tree")"
  tar -xzf "$tarball_dir/v2.4.0.tar.gz" -C "$(dirname "$tree")"
  if [[ ! -d "$tree" ]]; then
    echo "tag v2.4.0 did not extract to $tree"
    return 1
  fi
  if ! grep -q 'cp -u tensorflow/lite/schema/schema_generated.h.OPENSOURCE' "$makefile"; then
    echo "schema_generated.h copy line not found; refusing to build"
    return 1
  fi
  # BSD cp on the runner has no -u. The copy is unconditional.
  sed -i '' 's/cp -u tensorflow\/lite\/schema\/schema_generated.h.OPENSOURCE/cp -f tensorflow\/lite\/schema\/schema_generated.h.OPENSOURCE/' "$makefile"
  uam24_makefile_inc "$sdk" > "$tree/tensorflow/lite/tools/make/targets/ios_makefile.inc"
  echo "Downloading Eigen, gemmlowp, ruy, absl, farmhash, flatbuffers, fft2d, FP16"
  (cd "$tree" && bash tensorflow/lite/tools/make/download_dependencies.sh)
  if [[ ! -f "$tree/tensorflow/lite/c/c_api.cc" ]]; then
    echo "tensorflow/lite/c/c_api.cc missing from tag v2.4.0"
    return 1
  fi
  # Xcode 26 rejects std::abs<float> as a function pointer. NDK clang 7.0.2
  # accepts it. These wrappers call the same libm functions. modelUAM does
  # not use ABS, SIN, COS, LOG or SQRT.
  # test_delegate_providers.cc is not named *test.cc, so the makefile
  # compiles it. -force_load then requires the test flag parser.
  rm -f "$tree/tensorflow/lite/kernels/test_delegate_providers.cc"
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
}

# Builds libtensorflow-lite.a when the cache is missing or the wrong platform.
# Writes flags.txt, compile-line.txt, clang-version.txt and archive.sha256
# next to the library. A cache hit checks the sidecar and does not rewrite it.
# platform is "simulator" or "device".
uam24_ensure_lib() {
  local platform="$1"
  local work="$2"
  local cache_lib="$work/libtensorflow-lite.a"
  local sdk tree expected_kind
  case "$platform" in
    simulator)
      sdk="iphonesimulator"
      expected_kind="sim"
      tree="$ROOT/plugins/aps/build/tflite-24-src-sim/tensorflow-2.4.0"
      ;;
    device)
      sdk="iphoneos"
      expected_kind="device"
      tree="$ROOT/plugins/aps/build/tflite-24-src-device/tensorflow-2.4.0"
      ;;
    *)
      echo "unknown platform $platform"
      return 1
      ;;
  esac
  mkdir -p "$work"
  if [[ -f "$cache_lib" && -f "$work/flags.txt" && -f "$work/compile-line.txt" && -f "$work/clang-version.txt" && -f "$work/archive.sha256" ]]; then
    local kind recorded actual
    kind="$(uam24_platform_kind "$cache_lib")"
    if [[ "$kind" == "$expected_kind" ]]; then
      recorded="$(awk 'NR==1 { print $1 }' "$work/archive.sha256")"
      actual="$(uam24_archive_sha256 "$cache_lib")"
      echo "archive sha256 $actual"
      echo "cached sha256 $recorded"
      if [[ -z "$recorded" || "$actual" != "$recorded" ]]; then
        echo "cached archive hash does not match libtensorflow-lite.a"
        return 1
      fi
      echo "Using cached $cache_lib ($kind)"
      return 0
    fi
    echo "cached library platform is $kind, expected $expected_kind"
  fi
  rm -f "$cache_lib" "$work/flags.txt" "$work/compile-line.txt" "$work/clang-version.txt" "$work/archive.sha256"
  echo "Building TensorFlow Lite C 2.4.0 for $platform ($sdk)"
  uam24_prepare_tree "$tree" "$sdk" "$ROOT/plugins/aps/build/tflite-24-tarball"
  xcrun -sdk "$sdk" clang --version > "$work/clang-version.full"
  head -1 "$work/clang-version.full" > "$work/clang-version.txt"
  cat "$work/clang-version.txt"
  local jobs
  jobs="$(sysctl -n hw.ncpu)"
  echo "make -j$jobs micro TARGET=ios TARGET_ARCH=arm64 BUILD_WITH_RUY=true -O3 -DNDEBUG -ffp-contract=on -DTFLITE_WITH_RUY -DTFLITE_WITHOUT_XNNPACK"
  make -C "$tree" -j"$jobs" -f tensorflow/lite/tools/make/Makefile micro \
    TARGET=ios TARGET_ARCH=arm64 BUILD_WITH_RUY=true \
    CC="xcrun -sdk $sdk clang" \
    CXX="xcrun -sdk $sdk clang++" \
    | tee "$work/make.log"
  local built="$tree/tensorflow/lite/tools/make/gen/ios_arm64/lib/libtensorflow-lite.a"
  if [[ ! -f "$built" ]]; then
    echo "libtensorflow-lite.a was not produced"
    find "$tree/tensorflow/lite/tools/make/gen" -name 'libtensorflow-lite.a' -print || true
    return 1
  fi
  local kind
  kind="$(uam24_platform_kind "$built")"
  if [[ "$kind" != "$expected_kind" ]]; then
    echo "built library platform is $kind, expected $expected_kind"
    otool -l "$built" | awk '/LC_BUILD_VERSION/,/minos/' | head -40
    return 1
  fi
  python3 - "$work/make.log" "$work/compile-line.txt" "$work/flags.txt" "$sdk" << 'PY'
import pathlib, sys
log = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
compile_out = pathlib.Path(sys.argv[2])
flags_out = pathlib.Path(sys.argv[3])
sdk = sys.argv[4]
required = [
    "-DNDEBUG",
    "-DTFLITE_WITHOUT_XNNPACK",
    "-DTFLITE_WITH_RUY",
    "-O3",
    "-ffp-contract=on",
]
line = next((item for item in log if "clang++" in item and "-DTFLITE_WITH_RUY" in item), "")
if not line:
    raise SystemExit("no clang++ line carried -DTFLITE_WITH_RUY")
if f"-sdk {sdk}" not in line and f"sdk {sdk}" not in line and sdk not in line:
    raise SystemExit(f"compile line is not for {sdk}")
missing = [flag for flag in required if flag not in line.split()]
if missing:
    raise SystemExit("compile line missing " + " ".join(missing))
compile_out.write_text(line.strip() + "\n")
flags_out.write_text("\n".join(required) + "\n")
print("recorded parity flags")
PY
  cp "$built" "$cache_lib"
  uam24_archive_sha256 "$cache_lib" > "$work/archive.sha256"
  echo "archive sha256 $(cat "$work/archive.sha256")"
  echo "cached sha256 $(cat "$work/archive.sha256")"
  echo "cached $cache_lib"
}
