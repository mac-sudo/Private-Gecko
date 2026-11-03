#!/bin/sh
set -eu
umask 077
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
signing_dir=${MAX_SIGNING_DIR:-$(dirname "$project_dir")/private-signing}
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
signer="$sdk_dir/build-tools/36.0.0/apksigner"
input="$project_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
output="$project_dir/app/build/outputs/apk/release/app-release.apk"
test -f "$input"
test -f "$signing_dir/max-private-release.p12"
test -f "$signing_dir/release.pass"
test -f "$signing_dir/debug-to-release.lineage"
"$signer" sign --ks "$signing_dir/max-private-release.p12" \
  --ks-key-alias max-private-release --ks-pass "file:$signing_dir/release.pass" \
  --lineage "$signing_dir/debug-to-release.lineage" --rotation-min-sdk-version 28 \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --v4-signing-enabled false --out "$output" "$input"
"$signer" verify --min-sdk-version 28 --verbose --print-certs "$output"
