#!/usr/bin/env bash
set -euo pipefail

ORT_TAG="v1.20.0"
if ! command -v python3 >/dev/null 2>&1; then
  printf 'ONNX Runtime iOS build: required tool python3 was not found in PATH; install it before building.\n' >&2
  exit 1
fi
SCRIPT_DIR="$(python3 -c 'import pathlib, sys; print(pathlib.Path(sys.argv[1]).resolve().parent)' "$0")"
ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
BUILD_ROOT="$ROOT/plugins/aps/build/onnxRuntime"
SOURCE_DIR="$BUILD_ROOT/onnxruntime-source"
UPSTREAM_BUILDER="$SOURCE_DIR/tools/ci_build/github/apple/build_apple_framework.py"
DEFAULT_BUILD_SETTINGS="$SOURCE_DIR/tools/ci_build/github/apple/default_full_apple_framework_build_settings.json"
BUILD_SETTINGS="$BUILD_ROOT/ios_arm64_build_settings.json"
ARTIFACT_DIR="$BUILD_ROOT/onnxruntime-apple-framework"
EIGEN_SOURCE_DIR="$BUILD_ROOT/eigen-source"
EIGEN_COMMIT="e7248b26a1ed53fa030c5c459f7ea095dfd276ac"
CMAKE_BIN="${CMAKE_BIN:-cmake}"

fail() {
  printf 'ONNX Runtime iOS build: %s\n' "$1" >&2
  exit 1
}

if [[ "$(uname -s)" != "Darwin" ]]; then
  fail "this source build requires macOS and Xcode."
fi

for tool in git xcodebuild xcrun; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    fail "required tool '$tool' was not found in PATH; install it before building."
  fi
done

# The upstream build calls `cmake` by name, so put a custom binary first on PATH.
if [[ "$CMAKE_BIN" == */* ]]; then
  if [[ "${CMAKE_BIN##*/}" != "cmake" ]]; then
    fail "CMAKE_BIN must name a 'cmake' executable because the upstream build invokes cmake by name."
  fi
  if [[ "$CMAKE_BIN" != /* ]]; then
    CMAKE_BIN="$ROOT/$CMAKE_BIN"
  fi
  if [[ ! -x "$CMAKE_BIN" ]]; then
    fail "CMake executable '$CMAKE_BIN' is missing or not executable."
  fi
  PATH="$(dirname "$CMAKE_BIN"):$PATH"
  export PATH
elif [[ "$CMAKE_BIN" != "cmake" ]] || ! command -v "$CMAKE_BIN" >/dev/null 2>&1; then
  fail "CMake executable '$CMAKE_BIN' was not found; set CMAKE_BIN to a cmake binary or add CMake to PATH."
fi

xcodebuild -version >/dev/null 2>&1 || fail "Xcode is not selected or is not available."
"$CMAKE_BIN" --version >/dev/null 2>&1 || fail "CMake executable '$CMAKE_BIN' could not be run."

mkdir -p "$BUILD_ROOT"

if [[ ! -d "$SOURCE_DIR/.git" ]]; then
  if [[ -e "$SOURCE_DIR" ]]; then
    fail "$SOURCE_DIR exists but is not an ONNX Runtime Git checkout; move it aside and retry."
  fi
  git clone --depth 1 --branch "$ORT_TAG" --recurse-submodules --shallow-submodules \
    https://github.com/microsoft/onnxruntime.git "$SOURCE_DIR"
fi

CHECKED_OUT_TAG="$(git -C "$SOURCE_DIR" describe --tags --exact-match HEAD 2>/dev/null || true)"
if [[ "$CHECKED_OUT_TAG" != "$ORT_TAG" ]]; then
  fail "source checkout must be exactly $ORT_TAG (found '${CHECKED_OUT_TAG:-no exact tag}')."
fi

if [[ ! -f "$UPSTREAM_BUILDER" || ! -f "$DEFAULT_BUILD_SETTINGS" ]]; then
  fail "the $ORT_TAG checkout is missing the upstream Apple builder or build settings."
fi

# ORT 1.20's Eigen archive hash no longer matches GitLab's generated zip, so use its pinned Git commit.
if [[ ! -d "$EIGEN_SOURCE_DIR/.git" ]]; then
  if [[ -e "$EIGEN_SOURCE_DIR" ]]; then
    fail "$EIGEN_SOURCE_DIR exists but is not an Eigen Git checkout; move it aside and retry."
  fi
  git init "$EIGEN_SOURCE_DIR"
  git -C "$EIGEN_SOURCE_DIR" remote add origin https://gitlab.com/libeigen/eigen.git
  git -C "$EIGEN_SOURCE_DIR" fetch --depth 1 origin "$EIGEN_COMMIT"
  git -C "$EIGEN_SOURCE_DIR" checkout --detach FETCH_HEAD
fi

CHECKED_OUT_EIGEN_COMMIT="$(git -C "$EIGEN_SOURCE_DIR" rev-parse HEAD 2>/dev/null || true)"
if [[ "$CHECKED_OUT_EIGEN_COMMIT" != "$EIGEN_COMMIT" ]]; then
  fail "Eigen source must be exactly $EIGEN_COMMIT (found '${CHECKED_OUT_EIGEN_COMMIT:-no commit}')."
fi

python3 - "$DEFAULT_BUILD_SETTINGS" "$BUILD_SETTINGS" "$EIGEN_SOURCE_DIR" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as settings_file:
    settings = json.load(settings_file)
architectures = settings.get("build_osx_archs", {})
required = {"iphoneos": {"arm64"}, "iphonesimulator": {"arm64"}}
for platform, expected in required.items():
    actual = set(architectures.get(platform, []))
    if not expected <= actual:
        raise SystemExit(
            f"ONNX Runtime Apple build settings lack {platform} architecture(s): "
            f"{', '.join(sorted(expected - actual))}"
        )
settings["build_osx_archs"] = {
    "iphoneos": ["arm64"],
    "iphonesimulator": ["arm64"],
}
# The optional XNNPACK provider pulls a KleidiAI archive whose checksum fails on this host.
settings["build_params"]["base"] = [
    arg for arg in settings["build_params"]["base"] if arg != "--use_xnnpack"
]
settings["build_params"]["base"].extend(
    ["--use_preinstalled_eigen", f"--eigen_path={sys.argv[3]}"]
)
with open(sys.argv[2], "w", encoding="utf-8") as settings_file:
    json.dump(settings, settings_file, indent=2)
    settings_file.write("\n")
PY

python3 "$UPSTREAM_BUILDER" \
  --build_dir "$ARTIFACT_DIR" \
  --config Release \
  "$BUILD_SETTINGS"

bash "$SCRIPT_DIR/verify_onnxruntime_ios.sh" "$ARTIFACT_DIR/framework_out/onnxruntime.xcframework"
