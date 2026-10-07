#!/bin/sh
# Linker shim for cross-linking macOS dylibs with cargo-zigbuild (used by
# build-all.sh). cargo-zigbuild 0.23.4 turns every "-Wl,<existing file>" into
# a positional input, which splits rustc's
#   -Wl,-exported_symbols_list -Wl,<file>
# pair and makes zig fail. Merge the pair into the one-argument form, then
# hand off to cargo-zigbuild's zig wrapper (named by $GHOSTTY_JB_CC_VAR).
set -eu
eval "real=\${$GHOSTTY_JB_CC_VAR:?cargo-zigbuild did not set $GHOSTTY_JB_CC_VAR}"
merge=""
for arg in "$@"; do
  shift
  if [ -n "$merge" ]; then
    set -- "$@" "-Wl,-exported_symbols_list,${arg#-Wl,}"
    merge=""
  elif [ "$arg" = "-Wl,-exported_symbols_list" ]; then
    merge=1
  else
    set -- "$@" "$arg"
  fi
done
exec "$real" "$@"
