#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version (e.g. 0.2.0); fails if missing.
set -euo pipefail
version="$1"
notes="$(awk -v v="$version" '
  /^## / { if (found) exit; found = ($2 == v); next }
  found { print }
' CHANGELOG.md)"
[[ -n "${notes//[[:space:]]/}" ]] || { echo "no CHANGELOG.md section for $version" >&2; exit 1; }
printf '%s\n' "$notes"
