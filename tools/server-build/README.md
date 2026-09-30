# Android app builder (server)

The super-admin builds the app **for a partner** with only the modules they need
(admin dashboard → **App builds**). The build is signed, stored, and **assigned** to that
partner: they get it under SMS Engine → Phones & sync (download, QR code, send by SMS),
and their phones are offered the update automatically.

```
admin: partner + app + modules ──▶ CVPAP job (QUEUED)
builder agent on the server ──claim──▶ git pull ─▶ Docker/Gradle build ─▶ sign ─▶ upload
CVPAP ──▶ DONE, assigned to the partner ──▶ /dl/build/<job>.apk (+ QR, SMS, update hint)
```

If the same modules were already built from the latest code, the admin gets the file
instantly. When `main` gets new commits the agent also builds the full apps by itself
(`/dl/ajiriwa.apk`, `/dl/psbill.apk`).

## Secrets — generated, encrypted, backed up, never typed

* The **signing key** (RSA-4096, PKCS12) and its password, and the **builder token**,
  are generated inside CVPAP with a secure RNG the first time they are needed.
* They are stored **encrypted** in the CVPAP database table `build_secrets`, keyed from
  the api `.env` (`SECRET_KEY`, or `BUILD_SECRETS_KEY` if you set one).
  → Backing up **the database + the api .env** is enough to restore them.
* The build server keeps no key on disk: the agent fetches it for each build into a
  private temp folder that is deleted afterwards.
* Extra safety: admin → App builds → **Recovery file** downloads the key + token as
  JSON. Keep it offline. Restore on a new server with
  `docker compose exec web python manage.py app_builder_import /path/ajiriwa-build-recovery.json`.
* The key is **never replaced automatically** — phones only accept updates signed with
  the same key. If the env key changed, CVPAP refuses and tells you to restore it.

## Setup — one command on the CVPAP server (as root)

1. Deploy the latest CVPAP (it creates the tables):
   `cd /path/to/CVPAP && docker compose up -d --build web celery celery-beat`
2. Run:

```bash
curl -fsSL https://raw.githubusercontent.com/Gidraf/PSBill/main/tools/server-build/setup.sh | sudo bash
```

It finds the CVPAP compose project (or pass `CVPAP_DIR=/path/to/CVPAP`), clones PSBill
into `/opt/psbill-build`, asks CVPAP to create the key + token, builds the Android build
image and starts the `psbill-builder` service. Needs Docker, git, python3 and ~8 GB disk.

Logs: `journalctl -u psbill-builder -f`. The admin page shows **Builder online**.

Options (env for setup.sh): `CVPAP_URL` (default `https://api.ajiriwa.gidraf.dev`),
`REPO_URL`, `BRANCH`. Edit `/opt/psbill-build/builder.env` later for `AUTO_APPS`
(empty = no automatic full builds) or `VERSION_OFFSET`.

## Versions

`versionCode = VERSION_OFFSET + commit count` and `versionName = 1.<code>`, so every new
build installs over the previous one and keeps the phone's sign-in and data.

## Installing on a phone

Open the link (or scan the QR code), download, tap the file, allow **Install unknown
apps** for the browser when Android asks, then **Install**.
