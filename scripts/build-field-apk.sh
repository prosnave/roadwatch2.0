#!/usr/bin/env bash
# Builds the APK to hand to the field tester and checks it will UPDATE the app already on their phone.
#
#   ./scripts/build-field-apk.sh            # public flavor (what the tester should install)
#   ./scripts/build-field-apk.sh admin      # admin flavor (hazard editing tools)
#
# Output: dist/RoadWatch-<flavor>-<versionName>-vc<versionCode>.apk  (+ .sha256)
# Share it via Google Drive / WhatsApp / a file link. Gmail blocks .apk attachments.
set -euo pipefail

FLAVOR="${1:-public}"
cd "$(dirname "$0")/.."

# Monotonic versionCode = minutes since 2025-01-01, so every build can update the previous one.
VERSION_CODE=$(( ($(date +%s) - 1735689600) / 60 ))

# Certificate the tester's installed APKs were signed with (debug key of the original dev Mac).
EXPECTED_SHA1="9aa9214edfc7e9462f988d141bca86782e0d050c"

if [[ "$FLAVOR" != "public" && "$FLAVOR" != "admin" ]]; then
  echo "Flavor must be 'public' or 'admin'" >&2; exit 2
fi
CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"

echo "==> Unit tests"
./gradlew --console=plain -q ":app:test${CAP}DebugUnitTest"

echo "==> Building ${FLAVOR} (versionCode ${VERSION_CODE})"
./gradlew --console=plain -q ":app:assemble${CAP}Debug" -PrwVersionCode="${VERSION_CODE}"

SRC="app/build/outputs/apk/${FLAVOR}/debug/RoadWatch-${FLAVOR}-debug.apk"
[[ -f "$SRC" ]] || { echo "APK not found: $SRC" >&2; exit 1; }

BT="$(ls -d "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)"
VERSION_NAME="$("$BT/aapt2" dump badging "$SRC" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
SHA1="$("$BT/apksigner" verify --print-certs "$SRC" | sed -n 's/.*certificate SHA-1 digest: //p' | head -1)"

mkdir -p dist
OUT="dist/RoadWatch-${FLAVOR}-${VERSION_NAME}-vc${VERSION_CODE}.apk"
cp "$SRC" "$OUT"
shasum -a 256 "$OUT" | tee "${OUT}.sha256"

echo
echo "APK:        $OUT ($(du -h "$OUT" | cut -f1))"
echo "Signed by:  $SHA1"
if [[ "$SHA1" == "$EXPECTED_SHA1" ]]; then
  echo "Signature:  OK - installs over the previous build and KEEPS the tester's hazard data."
else
  echo "WARNING:    signature differs from the build on the tester's phone." >&2
  echo "            Android will refuse to update it; they must uninstall first and LOSE local data" >&2
  echo "            unless they export it (Settings > Export hazards) before uninstalling." >&2
fi
echo
echo "Next: upload to Google Drive, share the link. On the phone: open the link > Download > Open > Install"
echo "      (allow 'Install unknown apps' for Drive/Chrome/Files the first time)."
