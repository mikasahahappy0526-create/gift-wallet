#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APKSIGNER="${ANDROID_HOME:-/workspace/android-sdk}/build-tools/35.0.0/apksigner"
ZIPALIGN="${ANDROID_HOME:-/workspace/android-sdk}/build-tools/35.0.0/zipalign"
KS="$ROOT/android-app/keystore/gift-wallet-release.jks"
SRC="${1:-$ROOT/android-app/app/build/outputs/apk/release/app-release-unsigned.apk}"
OUT="${2:-$ROOT/dist/gift-wallet.apk}"
tmp="$(mktemp -d)"
"$ZIPALIGN" -f -p 4 "$SRC" "$tmp/aligned.apk"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias giftwallet \
  --ks-pass pass:giftwallet-release --key-pass pass:giftwallet-release \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$tmp/aligned.apk"
cp -f "$OUT" "$ROOT/dist/gift-wallet-1.3.8.apk"
"$APKSIGNER" verify --verbose "$OUT"
echo "Signed -> $OUT"
