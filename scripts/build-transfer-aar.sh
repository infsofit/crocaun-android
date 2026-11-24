#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOMOBILE_VERSION="$(tr -d '\r\n' < "$ROOT/go-core/gomobile-version.txt")"

"$ROOT/scripts/prepare-croc-go.sh"
cd "$ROOT/go-core"
go test -mod=readonly ./...

go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}"
export PATH="$(go env GOPATH)/bin:$PATH"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
NDK_VERSION="${ANDROID_NDK_VERSION:-27.2.12479018}"
export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${NDK_VERSION}"
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "Android NDK not found at $ANDROID_NDK_HOME" >&2
  exit 1
fi

BINDING_DIR="$(mktemp -d)"
trap 'rm -rf "$BINDING_DIR"' EXIT
gobind -lang=java -javapkg=com.infsofit.crocaun -outdir="$BINDING_DIR" \
  github.com/infsofit/crocaun-android/go-core/transfer
JAVA_API="$BINDING_DIR/java/com/infsofit/crocaun/transfer/Transfer.java"
SESSION_API="$BINDING_DIR/java/com/infsofit/crocaun/transfer/TransferSession.java"
grep -Fq 'newTransferSession(String mode, String password, String pathsJSON, String outputDir, String relay, String relayPassword) throws Exception;' "$JAVA_API"
grep -Fq 'public native void run() throws Exception;' "$SESSION_API"
grep -Fq 'public native String status();' "$SESSION_API"

gomobile init
mkdir -p "$ROOT/app/libs"
gomobile bind \
  -target=android \
  -androidapi=30 \
  -javapkg=com.infsofit.crocaun \
  -o "$ROOT/app/libs/crocaun-transfer.aar" \
  github.com/infsofit/crocaun-android/go-core/transfer
