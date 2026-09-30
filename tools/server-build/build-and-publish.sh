#!/usr/bin/env bash
# Pull the latest PSBill code, build signed release APKs in Docker and publish
# them to CVPAP, which then serves them at $CVPAP_URL/dl/<app>.apk.
#
#   ./build-and-publish.sh            build only if there are new commits
#   FORCE=1 ./build-and-publish.sh    build even if nothing changed
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-$HERE/release.env}"
[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE (copy release.env.example)"; exit 1; }
set -a  # export everything (docker run -e VAR passes these through)
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${REPO_DIR:?}" "${CVPAP_URL:?}" "${APP_RELEASE_TOKEN:?}" "${KEYSTORE_FILE:?}" "${ANDROID_KEY_ALIAS:?}" "${ANDROID_KEYSTORE_PASSWORD:?}"
BRANCH="${BRANCH:-main}"; APPS="${APPS:-ajiriwa}"; VERSION_OFFSET="${VERSION_OFFSET:-100}"; VERSION_PREFIX="${VERSION_PREFIX:-1}"
STATE_DIR="${STATE_DIR:-$HERE/state}"; IMAGE="${IMAGE:-psbill-android-builder}"
mkdir -p "$STATE_DIR"
log() { echo "[$(date '+%F %T')] $*"; }

# one build at a time
exec 9>"$STATE_DIR/build.lock"
flock -n 9 || { log "another build is running"; exit 0; }

[ -f "$KEYSTORE_FILE" ] || { log "keystore $KEYSTORE_FILE not found (run create-keystore.sh)"; exit 1; }

cd "$REPO_DIR"
git fetch --quiet origin "$BRANCH"
git checkout --quiet "$BRANCH"
git reset --quiet --hard "origin/$BRANCH"
git clean -qfd -e local.properties
COMMIT="$(git rev-parse HEAD)"
if [ -z "${FORCE:-}" ] && [ "$(cat "$STATE_DIR/last-commit" 2>/dev/null || true)" = "$COMMIT" ]; then
  log "no new commits ($COMMIT) — nothing to do"; exit 0
fi
CODE=$(( VERSION_OFFSET + $(git rev-list --count HEAD) ))
NAME="${VERSION_PREFIX}.${CODE}"
NOTES="$(git log -1 --pretty=%s | cut -c1-300)"
log "building $APPS  v$NAME ($CODE)  commit ${COMMIT:0:7}"

docker build -q -t "$IMAGE" "$HERE" > /dev/null

TASKS=""; for a in $APPS; do TASKS="$TASKS :$a:assembleRelease"; done
EXTRA=""; [ -n "${AJIRIWA_MODULES:-}" ] && EXTRA="-Pajiriwa.modules=$AJIRIWA_MODULES"

docker run --rm \
  -v "$REPO_DIR":/src \
  -v psbill-gradle-cache:/root/.gradle \
  -v "$KEYSTORE_FILE":/keys/release.jks:ro \
  -e ANDROID_KEYSTORE_PATH=/keys/release.jks \
  -e ANDROID_KEYSTORE_PASSWORD -e ANDROID_KEY_ALIAS -e ANDROID_KEY_PASSWORD \
  "$IMAGE" bash -c "
    set -e
    printf 'sdk.dir=/opt/android-sdk\n' > local.properties
    # gradle.properties has a macOS-only truststore flag: override the JVM args here
    ./gradlew --no-daemon --console=plain \
      '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' \
      -PappVersionCode=$CODE -PappVersionName=$NAME $EXTRA $TASKS
    BT=\$(ls -d /opt/android-sdk/build-tools/* | sort -V | tail -1)
    for a in $APPS; do \$BT/apksigner verify \$a/build/outputs/apk/release/\$a-release.apk; done
  "

for a in $APPS; do
  APK="$REPO_DIR/$a/build/outputs/apk/release/$a-release.apk"
  [ -f "$APK" ] || { log "$a: signed APK not found (is signing configured?)"; exit 1; }
  log "publishing $a ($(du -h "$APK" | cut -f1))"
  curl -fsS --retry 3 -X POST "$CVPAP_URL/api/v1/app-releases" \
    -H "X-Release-Token: $APP_RELEASE_TOKEN" \
    -F "app=$a" -F "version_code=$CODE" -F "version_name=$NAME" -F "git_commit=$COMMIT" \
    -F "notes=$NOTES" -F "apk=@$APK;type=application/vnd.android.package-archive" \
    -o "$STATE_DIR/publish-$a.json" -w "  HTTP %{http_code}\n"
done

echo "$COMMIT" > "$STATE_DIR/last-commit"
for a in $APPS; do log "latest $a: $CVPAP_URL/dl/$a.apk"; done
