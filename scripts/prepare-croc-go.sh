#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/go-core/croc-patched"
UPSTREAM="https://github.com/schollz/croc.git"
TAG="v10.0.12"
COMMIT="c016eedbbcf5d3ce29d437ba4effdd67068f4ce6"

rm -rf "$TARGET"
git clone --quiet --no-checkout "$UPSTREAM" "$TARGET"
git -C "$TARGET" checkout --quiet --detach "$COMMIT"
actual="$(git -C "$TARGET" rev-parse HEAD)"
if [[ "$actual" != "$COMMIT" ]]; then
  echo "Unexpected Croc source revision: $actual" >&2
  exit 1
fi

for patch in \
  "$ROOT/patches/croc-v10.0.12-sendfile-race.patch" \
  "$ROOT/patches/croc-v10.0.12-mobile-redact-secret.patch" \
  "$ROOT/patches/croc-v10.0.12-mobile-no-startup-dns.patch"; do
  git -C "$TARGET" apply --check "$patch"
  git -C "$TARGET" apply "$patch"
done

echo "Prepared Croc $TAG at $COMMIT with Crocaun Android patches."
