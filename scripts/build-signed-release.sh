#!/usr/bin/env bash
# Builds LubeLink's signed release, an APK to install and an Android App Bundle (OD-64), from the four release
# secrets, and checks both are signed with that key. Used by CI's release job on a tag; it runs the same way on a PC.
#
#   LUBELINK_RELEASE_KEYSTORE_B64    the key file, base64
#   LUBELINK_RELEASE_STORE_PASSWORD  LUBELINK_RELEASE_KEY_ALIAS  LUBELINK_RELEASE_KEY_PASSWORD
#
# Usage: build-signed-release.sh <out-dir> <commit>. <commit> is the commit being released (in CI the tag's own
# commit). The checked-out source must be exactly that commit with no local changes, or nothing is built (exit 1), so
# a release is only ever signed from the source the tag names and CI tested. Then the secrets, in this order:
# without LUBELINK_RELEASE_KEYSTORE_B64 it builds nothing and says so (exit 0), whatever the other three hold; with it,
# each of the other three is required, and the first one missing is named (exit 1). The key is written to a private
# temporary folder, used for this build only and deleted, whatever the outcome; it is never printed. The output
# names the key's SHA-256 certificate fingerprint, to compare with the one written down when the key was made
# (docs/RELEASE-SIGNING-STEPS.md).
set -euo pipefail
out=${1:?usage: build-signed-release.sh <out-dir> <commit>}
commit=${2:?usage: build-signed-release.sh <out-dir> <commit>}
cd "$(dirname "$0")/.."

head=$(git rev-parse HEAD)
want=$(git rev-parse --verify --quiet "$commit^{commit}" || true)
if [ "$want" != "$head" ]; then
  echo "::error::The checked-out source is $head, not the commit to release ($commit): nothing is signed."
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "::error::The checked-out source has local changes: nothing is signed."
  exit 1
fi

if [ -z "${LUBELINK_RELEASE_KEYSTORE_B64:-}" ]; then
  echo "::notice::No release signing key secret: no signed release for this revision."
  exit 0
fi
for name in LUBELINK_RELEASE_STORE_PASSWORD LUBELINK_RELEASE_KEY_ALIAS LUBELINK_RELEASE_KEY_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "::error::$name is missing: all four release signing secrets are needed."
    exit 1
  fi
done

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -f local.properties ]; then sdk=$(sed -n 's/^sdk\.dir=//p' local.properties); fi
[ -n "$sdk" ] || { echo "::error::No Android SDK: set ANDROID_HOME."; exit 1; }
tools="$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)"

keydir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/lubelink-key.XXXXXX")
chmod 700 "$keydir"
trap 'rm -rf "$keydir"' EXIT
printf '%s' "$LUBELINK_RELEASE_KEYSTORE_B64" | base64 -d > "$keydir/release.jks"
export LUBELINK_RELEASE_KEYSTORE="$keydir/release.jks"
expected=$(keytool -list -v -keystore "$LUBELINK_RELEASE_KEYSTORE" -alias "$LUBELINK_RELEASE_KEY_ALIAS" \
  -storepass "$LUBELINK_RELEASE_STORE_PASSWORD" 2>/dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f' || true)
[ -n "$expected" ] || { echo "::error::The key file, alias or password could not be read."; exit 1; }

rm -rf app/build/outputs/apk/release build/distributions
./gradlew --no-daemon :app:assembleRelease :app:exportReleaseBundle
rm -rf "$keydir"

version=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)
apk=app/build/outputs/apk/release/app-release.apk
aab=build/distributions/LubeLink-v$version-release.aab
[ -f "$apk" ] && [ -f "$aab" ] || { echo "::error::The release APK or bundle was not built."; exit 1; }

"$tools/apksigner" verify "$apk"
apk_cert=$("$tools/apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
# jarsigner exits 0 even for an unsigned file, so its "jar verified." line is required, and the certificate below is
# what proves the key. (-strict refuses the ordering AGP writes into every bundle, so it is not used.)
jarsigner -verify "$aab" 2>&1 | grep -q '^jar verified' || { echo "::error::The bundle's signature does not verify."; exit 1; }
aab_cert=$(keytool -printcert -jarfile "$aab" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
if [ "$apk_cert" != "$expected" ] || [ "$aab_cert" != "$expected" ]; then
  echo "::error::A release file is not signed with the release key (key $expected, APK ${apk_cert:-none}, bundle ${aab_cert:-none})."
  exit 1
fi

mkdir -p "$out"
cp "$apk" "$out/LubeLink-v$version-release.apk"
cp "$aab" "$out/LubeLink-v$version-release.aab"
echo "::notice::Signed release LubeLink-v$version: APK and bundle; certificate SHA-256 $expected"
