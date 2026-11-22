#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
  echo "Usage: sign-release.sh aligned-unsigned.apk output.apk" >&2
  exit 2
fi
: "${TW_SIGN_STORE:?Set TW_SIGN_STORE to the original release.p12}"
: "${TW_SIGN_PASSWORD_FILE:?Set TW_SIGN_PASSWORD_FILE to its password file}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to the Android SDK build-tools directory}"
project_dir=$(cd "$(dirname "$0")" && pwd)
expected=$(cat "$project_dir/release-certificate.sha256")
cert_hash=$(keytool -exportcert -keystore "$TW_SIGN_STORE" -storepass:file "$TW_SIGN_PASSWORD_FILE" -alias twcardreader | sha256sum | cut -d' ' -f1)
if [[ "$cert_hash" != "$expected" ]]; then
  echo 'Release signing certificate mismatch. Use the original signing backup.' >&2
  exit 1
fi
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$TW_SIGN_STORE" --ks-key-alias twcardreader \
  --ks-pass "file:$TW_SIGN_PASSWORD_FILE" --v4-signing-enabled false --out "$2" "$1"
report=$("$ANDROID_BUILD_TOOLS/apksigner" verify --verbose --print-certs "$2")
actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$report")
[[ "$actual" == "$expected" ]]
echo "$report"
