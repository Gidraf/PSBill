#!/usr/bin/env bash
# Create the release signing key ONCE. Keep a backup somewhere safe: if it is
# lost, installed phones can't update any more (they must uninstall first).
set -euo pipefail
OUT=${1:-/opt/psbill-build/keys/release.jks}
ALIAS=${2:-ajiriwa}
if [ -e "$OUT" ]; then echo "Refusing to overwrite $OUT"; exit 1; fi
mkdir -p "$(dirname "$OUT")"
chmod 700 "$(dirname "$OUT")"
DN=${KEY_DN:-"CN=Ajiriwa, OU=Mobile, O=Ajiriwa, L=Nairobi, C=KE"}
if command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair -v -storetype PKCS12 -keystore "$OUT" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "$DN"
else
  # no local JDK: use the build image's keytool
  docker run --rm -it -v "$(dirname "$OUT")":/keys eclipse-temurin:17-jdk-jammy \
    keytool -genkeypair -v -storetype PKCS12 -keystore "/keys/$(basename "$OUT")" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "$DN"
fi
chmod 600 "$OUT"
echo
echo "Created $OUT (alias $ALIAS). Put the passwords in release.env and BACK UP this file."
