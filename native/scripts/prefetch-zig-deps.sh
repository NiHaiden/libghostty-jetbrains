#!/usr/bin/env bash
# Pre-seeds a Zig project's package cache (zig-pkg/) for environments where
# Zig's built-in HTTP client can't reach dependency hosts (e.g. behind an
# intercepting proxy). It repeatedly runs `zig build`, collects the
# URLs Zig failed to download, fetches them with curl (or git for GitHub
# archive URLs) and hands them to `zig fetch`, until the graph resolves.
#
# Usage: prefetch-zig-deps.sh <zig-project-dir> [zig build args...]
set -euo pipefail

proj="$(cd "${1:-.}" && pwd)"
shift || true
ZIG_BUILD_ARGS=("$@")
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch_one() {
  local url="$1" n="$2"
  local dir="$work/$n"
  mkdir -p "$dir"
  if [[ "$url" =~ ^https://github.com/([^/]+)/([^/]+)/archive/(refs/tags/)?([^/]+)\.tar\.gz$ ]]; then
    local owner="${BASH_REMATCH[1]}" repo="${BASH_REMATCH[2]}" rev="${BASH_REMATCH[4]}"
    if curl -fsSL -o "$dir/pkg.tar.gz" "$url" 2>/dev/null; then
      (cd "$proj" && zig fetch "$dir/pkg.tar.gz")
      return
    fi
    git clone -q "https://github.com/$owner/$repo.git" "$dir/src"
    git -C "$dir/src" checkout -q "$rev"
    rm -rf "$dir/src/.git"
    (cd "$proj" && zig fetch "$dir/src")
  else
    # zig fetch infers the archive format from the file extension.
    local file="$dir/${url##*/}"
    curl -fsSL -o "$file" "$url"
    (cd "$proj" && zig fetch "$file")
  fi
}

for round in $(seq 1 30); do
  out="$(cd "$proj" && zig build "${ZIG_BUILD_ARGS[@]}" 2>&1 || true)"
  mapfile -t urls < <(grep -oE '\.url = "[^"]+"' <<<"$out" | sed -E 's/\.url = "([^"]+)"/\1/' | sort -u)
  if [[ ${#urls[@]} -eq 0 ]]; then
    if grep -q 'error' <<<"$out"; then echo "$out" >&2; exit 1; fi
    echo "all dependencies resolved"
    exit 0
  fi
  i=0
  for u in "${urls[@]}"; do
    i=$((i + 1))
    echo "[round $round] fetching $u"
    fetch_one "$u" "r${round}_$i"
  done
done
echo "gave up after 30 rounds" >&2
exit 1
