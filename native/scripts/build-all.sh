#!/usr/bin/env bash
# Cross-compiles the ghostty-jb native library for every platform the plugin
# ships and lays the results out as <out>/<os>-<arch>/<libname>, the layout
# the plugin's NativeLibrary loader expects.
#
# Needs: rustup with the targets below, cargo-zigbuild, zig 0.16.
#
# Usage: build-all.sh [out-dir] [rust-target...]
#   default out-dir: native/dist, default targets: all of TARGETS below.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mkdir -p "${1:-$here/dist}" && cd "${1:-$here/dist}" && pwd)"
shift || true

# Rust target -> plugin platform directory.
declare -A TARGETS=(
  [x86_64-unknown-linux-gnu]=linux-x64
  [aarch64-unknown-linux-gnu]=linux-aarch64
  [x86_64-apple-darwin]=darwin-x64
  [aarch64-apple-darwin]=darwin-aarch64
  [x86_64-pc-windows-gnu]=windows-x64
  [aarch64-pc-windows-gnullvm]=windows-aarch64
)

selected=("$@")
if [[ ${#selected[@]} -eq 0 ]]; then selected=("${!TARGETS[@]}"); fi

for target in "${selected[@]}"; do
  platform="${TARGETS[$target]:?unknown target $target}"
  echo "==> $target ($platform)"
  zig_target=""
  build_target="$target"
  env_target="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
  unset GHOSTTY_JB_CC_VAR "CARGO_TARGET_${env_target}_LINKER"
  case "$target" in
    *-apple-darwin)
      # Works around a cargo-zigbuild bug, see darwin-link.sh.
      export GHOSTTY_JB_CC_VAR="CC_${target//-/_}"
      export "CARGO_TARGET_${env_target}_LINKER=$here/scripts/darwin-link.sh"
      ;;
    *-linux-gnu)
      # Pin an old glibc so the library loads on older distros; the nested
      # libghostty Zig build has to agree with the final link.
      build_target="$target.2.28"
      zig_target="${target%%-*}-linux-gnu.2.28"
      ;;
  esac
  (cd "$here" && GHOSTTY_JB_ZIG_TARGET="$zig_target" cargo zigbuild --release --locked --target "$build_target")
  dir="$here/target/$target/release"
  mkdir -p "$out/$platform"
  case "$platform" in
    windows-*) cp "$dir/ghostty_jb.dll" "$out/$platform/" ;;
    darwin-*) cp "$dir/libghostty_jb.dylib" "$out/$platform/" ;;
    *) cp "$dir/libghostty_jb.so" "$out/$platform/" ;;
  esac
done
ls -lR "$out"
