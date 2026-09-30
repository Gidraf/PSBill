#!/usr/bin/env bash
# One command, on the CVPAP server (as root):
#
#   curl -fsSL https://raw.githubusercontent.com/Gidraf/PSBill/main/tools/server-build/setup.sh | sudo bash
#   (or: sudo ./setup.sh   — optional env: CVPAP_DIR, CVPAP_URL, REPO_URL, BRANCH)
#
# It clones PSBill for building, asks CVPAP to create (once) the signing key and the
# builder token — both generated with a secure RNG and stored ENCRYPTED in the CVPAP
# database, so your normal DB + .env backup restores them — builds the Docker image
# and starts the builder agent. Nothing to type, no passwords on this machine.
set -euo pipefail

BASE=/opt/psbill-build
REPO_URL=${REPO_URL:-https://github.com/Gidraf/PSBill.git}
BRANCH=${BRANCH:-main}
CVPAP_URL=${CVPAP_URL:-https://api.ajiriwa.gidraf.dev}
REPO_DIR=$BASE/PSBill

say() { echo -e "\033[1;32m==>\033[0m $*"; }
die() { echo -e "\033[1;31mxx\033[0m $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || die "run as root (sudo)"
for c in docker git python3 curl; do command -v $c >/dev/null || die "$c is not installed"; done

# ── find the CVPAP docker compose project ──
if [ -z "${CVPAP_DIR:-}" ]; then
  for d in /opt/CVPAP /opt/cvpap /srv/CVPAP /srv/cvpap /root/CVPAP /home/*/CVPAP /home/*/Projects/CVPAP; do
    if [ -f "$d/docker-compose.yml" ] && grep -q "kiosk-ws" "$d/docker-compose.yml"; then CVPAP_DIR=$d; break; fi
  done
fi
[ -n "${CVPAP_DIR:-}" ] || die "CVPAP not found — run again with CVPAP_DIR=/path/to/CVPAP"
say "CVPAP: $CVPAP_DIR"

# ── build clone ──
mkdir -p "$BASE/state" && chmod 700 "$BASE"
if [ ! -d "$REPO_DIR/.git" ]; then
  say "cloning $REPO_URL"
  git clone --quiet --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
else
  git -C "$REPO_DIR" fetch --quiet origin "$BRANCH" && git -C "$REPO_DIR" reset --quiet --hard "origin/$BRANCH"
fi

# ── secrets live in CVPAP (encrypted); fetch only the builder token ──
say "creating / reading the signing key and builder token inside CVPAP"
TOKEN=$(cd "$CVPAP_DIR" && docker compose exec -T web python manage.py app_builder_setup --print-token 2>/dev/null | grep -E '^bld_' | tail -1 || true)
[ -n "$TOKEN" ] || die "could not get the builder token — deploy the latest CVPAP first: (cd $CVPAP_DIR && docker compose up -d --build web)"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Builder-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"setup-check"}' "$CVPAP_URL/api/v1/app-builds/worker/heartbeat")
[ "$code" = 200 ] || die "CVPAP at $CVPAP_URL answered $code — is the new version deployed and CVPAP_URL right?"

umask 077
cat > "$BASE/builder.env" <<ENV
CVPAP_URL=$CVPAP_URL
BUILDER_TOKEN=$TOKEN
REPO_DIR=$REPO_DIR
BRANCH=$BRANCH
BUILDER_NAME=$(hostname)
IMAGE=psbill-android-builder
VERSION_OFFSET=100
VERSION_PREFIX=1
AUTO_APPS=ajiriwa psbill
STATE_DIR=$BASE/state
ENV
umask 022

say "building the Android build image (first time takes a few minutes)"
docker build -q -t psbill-android-builder "$REPO_DIR/tools/server-build" >/dev/null

say "installing the builder service"
cat > /etc/systemd/system/psbill-builder.service <<UNIT
[Unit]
Description=Ajiriwa / PSBill Android app builder
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
EnvironmentFile=$BASE/builder.env
ExecStart=/usr/bin/python3 $REPO_DIR/tools/server-build/builder_agent.py
Restart=always
RestartSec=10
Nice=10

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now psbill-builder.service
systemctl restart psbill-builder.service

say "done. Admin dashboard → App builds shows the builder online; logs: journalctl -u psbill-builder -f"
say "Recovery copy of the signing key: admin → App builds → Download recovery file (keep it offline)."
