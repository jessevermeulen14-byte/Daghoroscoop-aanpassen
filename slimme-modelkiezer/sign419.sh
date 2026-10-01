#!/usr/bin/env bash
set -euo pipefail
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl unzip ca-certificates openjdk-21-jdk-headless python3
rm -rf /opt/android-sdk /tmp/cmdline.zip /tmp/artifact.zip /tmp/apk /tmp/slim-v2.jks
mkdir -p /opt/android-sdk/cmdline-tools /tmp/apk /srv
curl -fL --retry 5 "$ARTIFACT_URL" -o /tmp/artifact.zip
unzip -q /tmp/artifact.zip -d /tmp/apk
APK_IN="$(find /tmp/apk -type f -name '*.apk' | head -1)"
test -s "$APK_IN"
curl -fL --retry 5 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip
unzip -q /tmp/cmdline.zip -d /tmp/cmdline
mkdir -p /opt/android-sdk/cmdline-tools/latest
mv /tmp/cmdline/cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "build-tools;35.0.0" "platform-tools"
printf '%s' "$SLIM_KEY_B64" | base64 -d > /tmp/slim-v2.jks
APKSIGNER=/opt/android-sdk/build-tools/35.0.0/apksigner
OUT=/srv/Slimme-Modelkiezer-V2-build-419-signed.apk
"$APKSIGNER" sign --ks /tmp/slim-v2.jks --ks-pass "pass:$SLIM_KEYSTORE_PASSWORD" --ks-key-alias "$SLIM_KEY_ALIAS" --key-pass "pass:$SLIM_KEY_PASSWORD" --out "$OUT" "$APK_IN"
"$APKSIGNER" verify --verbose --print-certs "$OUT"
CERT="$("$APKSIGNER" verify --print-certs "$OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr -d ':' | tr '[:lower:]' '[:upper:]')"
echo "BUILD419_CERT=$CERT"
test "$CERT" = 'A94493DD75717D029440396FE018933F6D808623EE371AD9630D76A2E07F9078'
SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
echo "BUILD419_SHA256=$SHA"
CATBOX="$(curl -fsS --retry 5 -F 'reqtype=fileupload' -F "fileToUpload=@$OUT" https://catbox.moe/user/api.php || true)"
echo "BUILD419_CATBOX=$CATBOX"
exec python3 -m http.server "${PORT:-8080}" --bind 0.0.0.0 --directory /srv
