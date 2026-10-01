#!/usr/bin/env bash
set -euo pipefail

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl unzip ca-certificates openjdk-21-jdk-headless python3 git

rm -rf /workspace /opt/android-sdk /opt/gradle-8.10.2 /tmp/src.zip /tmp/cmdline.zip /tmp/gradle.zip
mkdir -p /workspace /opt/android-sdk/cmdline-tools

curl -fL --retry 5 https://codeload.github.com/jessevermeulen14-byte/Daghoroscoop-aanpassen/zip/refs/heads/apk-build-406 -o /tmp/src.zip
unzip -q /tmp/src.zip -d /workspace
SRC="$(find /workspace -maxdepth 1 -type d -name 'Daghoroscoop-aanpassen-*' | head -1)"
test -n "$SRC"

curl -fL --retry 5 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip
unzip -q /tmp/cmdline.zip -d /tmp/cmdline
mkdir -p /opt/android-sdk/cmdline-tools/latest
mv /tmp/cmdline/cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

curl -fL --retry 5 https://services.gradle.org/distributions/gradle-8.10.2-bin.zip -o /tmp/gradle.zip
unzip -q /tmp/gradle.zip -d /opt

printf '%s' "$SLIM_KEY_B64" | base64 -d > /tmp/slim-v2.jks
export CM_KEYSTORE_PATH=/tmp/slim-v2.jks
export CM_KEYSTORE_PASSWORD="$SLIM_KEYSTORE_PASSWORD"
export CM_KEY_ALIAS="$SLIM_KEY_ALIAS"
export CM_KEY_PASSWORD="$SLIM_KEY_PASSWORD"

cd "$SRC/slimme-apk-build-406/android"
sed -i 's/androidx.core:core:1.15.0/androidx.core:core:1.0.2/g' app/build.gradle
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
export GRADLE_USER_HOME=/tmp/gradle-home-411
rm -rf "$GRADLE_USER_HOME"
mkdir -p "$GRADLE_USER_HOME"
printf 'org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8\norg.gradle.workers.max=1\norg.gradle.parallel=false\nandroid.useAndroidX=true\n' > gradle.properties
cp gradle.properties "$GRADLE_USER_HOME/gradle.properties"
export JAVA_TOOL_OPTIONS='-Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8'

/opt/gradle-8.10.2/bin/gradle --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx640m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8' -PslimBuildNumber=411 :app:assembleDebug

mkdir -p /srv
APK=/srv/Slimme-Modelkiezer-V2-build-411.apk
cp app/build/outputs/apk/debug/app-debug.apk "$APK"
CERT="$(/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr -d ':' | tr '[:lower:]' '[:upper:]')"
echo "BUILD411_CERT=$CERT"
test "$CERT" = 'A94493DD75717D029440396FE018933F6D808623EE371AD9630D76A2E07F9078'
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
echo "BUILD411_SHA256=$SHA"
CATBOX="$(curl -fsS --retry 5 -F 'reqtype=fileupload' -F "fileToUpload=@$APK" https://catbox.moe/user/api.php || true)"
echo "BUILD411_CATBOX=$CATBOX"

exec python3 -m http.server "${PORT:-8080}" --bind 0.0.0.0 --directory /srv
