# Build & publish the Android apps on your server

The server clones this repo, builds **signed** release APKs in Docker and uploads
them to CVPAP. CVPAP keeps every version and always serves the newest one at:

    https://api.ajiriwa.gidraf.dev/dl/ajiriwa.apk
    https://api.ajiriwa.gidraf.dev/dl/psbill.apk

The SMS Engine → **Phones & sync** page shows the latest version, a QR code and a
"Send link by SMS" box; phones running an older build see **Update available** in
Sync & tracking.

## One-time setup (on the server, as root)

```bash
# 1. a dedicated clone just for building
mkdir -p /opt/psbill-build && cd /opt/psbill-build
git clone https://github.com/Gidraf/PSBill.git
cd PSBill/tools/server-build

# 2. the signing key — BACK UP release.jks and its password (phones only accept
#    updates signed with the same key)
./create-keystore.sh /opt/psbill-build/keys/release.jks ajiriwa

# 3. settings
cp release.env.example release.env && chmod 600 release.env
nano release.env            # passwords, APP_RELEASE_TOKEN, CVPAP_URL

# 4. CVPAP must know the same token: add to the api env file, then restart
#    APP_RELEASE_TOKEN=<same value>   (optional: APP_RELEASES_BUCKET=app-releases)
#    docker compose up -d web

# 5. first build (10–20 min the first time, ~3 min after that)
FORCE=1 ./build-and-publish.sh

# 6. build automatically whenever main changes (checks every 15 min)
cp psbill-apk-build.service psbill-apk-build.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now psbill-apk-build.timer
```

Needs Docker and ~8 GB free disk (SDK + Gradle cache). Logs:
`journalctl -u psbill-apk-build -f`.

## Everyday

| What | How |
|---|---|
| Publish now | `systemctl start psbill-apk-build` (or `FORCE=1 ./build-and-publish.sh`) |
| Withdraw a bad build | `curl -X PATCH -H "X-Release-Token: $TOKEN" -H 'Content-Type: application/json' -d '{"is_active":false}' $CVPAP_URL/api/v1/app-releases/ajiriwa/<version_code>` — the previous one is served again |
| Fewer Ajiriwa modules | `AJIRIWA_MODULES=dashboard,orders,sms` in `release.env` |
| Versions | `versionCode = VERSION_OFFSET + commit count`, so every build is newer than the last |

## Installing on a phone

Open the link (or scan the QR code), download, tap the file, allow **Install unknown
apps** for the browser when Android asks, then **Install**. Updates install over the
old app and keep the sign-in and data — as long as they are signed with the same key.
