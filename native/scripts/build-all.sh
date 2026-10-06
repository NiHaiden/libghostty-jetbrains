#!/usr/bin/env bash
# Cross-compiles libghostty-jb for every platform the plugin ships and lays
# the results out as <out>/<os>-<arch>/<libname>, which is the layout the
# plugin's NativeLibrary loader expects.
#
# Usage: build-all.sh [out-dir] [target...]
#   default out-dir: native/dist, default targets: all of TARGETS below.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mkdir -p "${1:-$here/dist}" && cd "${1:-$here/dist}" && pwd)"
shift || true

# zig target triple -> plugin platform directory. Linux targets pin an old
# glibc so the library loads on older distros.
declare -A TARGETS=(
  [x86_64-linux-gnu.2.28]=linux-x64
  [aarch64-linux-gnu.2.28]=linux-aarch64
  [x86_64-macos.11.0]=darwin-x64
  [aarch64-macos.11.0]=darwin-aarch64
  [x86_64-windows-gnu]=windows-x64
  [aarch64-windows-gnu]=windows-aarch64
)

selected=("$@")
if [[ ${#selected[@]} -eq 0 ]]; then selected=("${!TARGETS[@]}"); fi

for triple in "${selected[@]}"; do
  platform="${TARGETS[$triple]:?unknown target $triple}"
  echo "==> $triple ($platform)"
  prefix="$(mktemp -d)"
  (cd "$here" && zig build -Doptimize=ReleaseFast -Dtarget="$triple" --prefix "$prefix")
  mkdir -p "$out/$platform"
  case "$platform" in
    windows-*) cp "$prefix/bin/ghostty-jb.dll" "$out/$platform/" ;;
    darwin-*) cp "$prefix/lib/libghostty-jb.dylib" "$out/$platform/" ;;
    *) cp "$prefix/lib/libghostty-jb.so" "$out/$platform/" ;;
  esac
  rm -rf "$prefix"
done
ls -lR "$out"
