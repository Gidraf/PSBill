#!/usr/bin/env python3
"""
Ajiriwa app builder agent (runs on the CVPAP server as a systemd service).

  • every few seconds: tells CVPAP it is online and claims the next build job
    (jobs are created by the super-admin: partner + app + modules);
  • when main gets new commits: queues the full builds itself (reason AUTO);
  • a job: fetch code → get the signing key from CVPAP (kept encrypted there,
    written to a private temp dir only for the build) → build in Docker with the
    job's modules → verify the signature → upload the APK → CVPAP marks it DONE
    and assigns it to the partner.

Only the Python standard library is used. Settings come from the environment
(EnvironmentFile written by setup.sh): CVPAP_URL, BUILDER_TOKEN, REPO_DIR, BRANCH,
BUILDER_NAME, IMAGE, VERSION_OFFSET, VERSION_PREFIX, AUTO_APPS, STATE_DIR.
"""
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import base64
import hashlib

CVPAP_URL = os.environ.get("CVPAP_URL", "https://api.ajiriwa.gidraf.dev").rstrip("/")
TOKEN = os.environ.get("BUILDER_TOKEN", "")
REPO_DIR = os.environ.get("REPO_DIR", "/opt/psbill-build/PSBill")
BRANCH = os.environ.get("BRANCH", "main")
NAME = os.environ.get("BUILDER_NAME") or os.uname().nodename
IMAGE = os.environ.get("IMAGE", "psbill-android-builder")
VERSION_OFFSET = int(os.environ.get("VERSION_OFFSET", "100"))
VERSION_PREFIX = os.environ.get("VERSION_PREFIX", "1")
AUTO_APPS = os.environ.get("AUTO_APPS", "ajiriwa psbill").split()
STATE_DIR = os.environ.get("STATE_DIR", "/opt/psbill-build/state")
POLL = 5
FETCH_EVERY = 120

state = {"busy": False, "head": None}


def log(*a):
    print(time.strftime("[%F %T]"), *a, flush=True)


# ── CVPAP API ────────────────────────────────────────────────────────────────

def api(path, body=None, method=None, raw=None, headers=None, timeout=60):
    h = {"X-Builder-Token": TOKEN, **(headers or {})}
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(CVPAP_URL + path, data=data, method=method or ("POST" if data is not None else "GET"), headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read().decode() or "{}"
            return r.status, (json.loads(txt) if txt.strip() else {})
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def upload(job_id, apk_path, fields):
    """multipart/form-data POST of the APK + fields."""
    boundary = "----ajiriwa" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    with open(apk_path, "rb") as f:
        apk = f.read()
    parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="apk"; filename="app-release.apk"\r\n'
                  f"Content-Type: application/vnd.android.package-archive\r\n\r\n").encode() + apk + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return api(f"/api/v1/app-builds/worker/jobs/{job_id}/finish", raw=b"".join(parts), method="POST",
               headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, timeout=600)


# ── git ──────────────────────────────────────────────────────────────────────

def git(*args):
    return subprocess.run(["git", "-C", REPO_DIR, *args], check=True, capture_output=True, text=True).stdout.strip()


def fetch_head():
    git("fetch", "--quiet", "origin", BRANCH)
    return git("rev-parse", f"origin/{BRANCH}")


def checkout_latest():
    git("checkout", "--quiet", BRANCH)
    git("reset", "--quiet", "--hard", f"origin/{BRANCH}")
    git("clean", "-qfd", "-e", "local.properties")
    commit = git("rev-parse", "HEAD")
    count = int(git("rev-list", "--count", "HEAD"))
    return commit, VERSION_OFFSET + count


# ── one build ────────────────────────────────────────────────────────────────

STAGES = [
    (re.compile(r"Downloading|Download https?://"), "Downloading build tools"),
    (re.compile(r"compiling modules"), "Selecting modules"),
    (re.compile(r":compile\w*Kotlin"), "Compiling"),
    (re.compile(r":(merge|process)\w*Resources"), "Preparing resources"),
    (re.compile(r":(dex|mergeDex|minify)\w*"), "Converting code"),
    (re.compile(r":package\w*"), "Packaging"),
    (re.compile(r"apksigner|Verifies"), "Checking the signature"),
    (re.compile(r"BUILD SUCCESSFUL"), "Built"),
]


def progress(job_id, stage=None, lines=None, commit=None):
    body = {}
    if stage:
        body["stage"] = stage
    if lines is not None:
        body["log"] = "\n".join(lines)
    if commit:
        body["git_commit"] = commit
    try:
        api(f"/api/v1/app-builds/worker/jobs/{job_id}/progress", body)
    except Exception as e:
        log("progress failed:", e)


def ensure_image(jid, tail):
    """Build the Android image when it is missing (never built, or removed by a prune)
    or when tools/server-build/Dockerfile changed. Docker would otherwise try to pull
    IMAGE from Docker Hub, where it doesn't exist."""
    ctx = os.path.join(REPO_DIR, "tools", "server-build")
    with open(os.path.join(ctx, "Dockerfile"), "rb") as f:
        want = hashlib.sha256(f.read()).hexdigest()[:16]
    have = subprocess.run(["docker", "image", "inspect", "-f", '{{index .Config.Labels "psbill.dockerfile"}}', IMAGE],
                          capture_output=True, text=True)
    if have.returncode == 0 and have.stdout.strip() == want:
        return
    why = "missing" if have.returncode != 0 else "out of date"
    log(f"build image {IMAGE} {why}: building it")
    progress(jid, "Preparing the build image (first time takes about 10 minutes)")
    p = subprocess.Popen(["docker", "build", "--label", f"psbill.dockerfile={want}", "-t", IMAGE, ctx],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    last = 0.0
    for line in p.stdout:
        tail.append(line.rstrip())
        if time.time() - last > 5:
            progress(jid, "Preparing the build image (first time takes about 10 minutes)", list(tail)[-60:])
            last = time.time()
    if p.wait() != 0:
        raise RuntimeError(f"could not build the Docker image {IMAGE} (see log); check disk space and internet on the build server")


def run_job(job):
    jid, app = job["id"], job["app"]
    modules = ",".join(job.get("modules") or [])
    tail = collections.deque(maxlen=120)
    tmp = tempfile.mkdtemp(prefix="apk-sign-")
    os.chmod(tmp, 0o700)
    try:
        progress(jid, "Getting the latest code")
        fetch_head()
        commit, code = checkout_latest()
        ensure_image(jid, tail)
        name = f"{VERSION_PREFIX}.{code}"
        progress(jid, f"Building v{name} ({modules or 'all modules'})", commit=commit)

        status, sig = api("/api/v1/app-builds/worker/signing")
        if status != 200:
            raise RuntimeError(f"signing key unavailable: {sig.get('error') or status}")
        ks_path = os.path.join(tmp, "release.p12")
        with open(ks_path, "wb") as f:
            f.write(base64.b64decode(sig["keystore_b64"]))
        os.chmod(ks_path, 0o600)

        mod_flag = f"-P{app}.modules={modules}" if modules else ""
        inner = (
            "set -e\n"
            "printf 'sdk.dir=/opt/android-sdk\\n' > local.properties\n"
            # gradle.properties carries a macOS-only truststore flag: override the JVM args
            f"./gradlew --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' "
            f"-PappVersionCode={code} -PappVersionName={name} {mod_flag} :{app}:assembleRelease\n"
            "BT=$(ls -d /opt/android-sdk/build-tools/* | sort -V | tail -1)\n"
            f"$BT/apksigner verify --print-certs {app}/build/outputs/apk/release/{app}-release.apk\n"
        )
        env = dict(os.environ, ANDROID_KEYSTORE_PASSWORD=sig["password"], ANDROID_KEY_PASSWORD=sig["password"],
                   ANDROID_KEY_ALIAS=sig["alias"])
        cmd = ["docker", "run", "--rm", "-v", f"{REPO_DIR}:/src", "-v", "psbill-gradle-cache:/root/.gradle",
               "-v", f"{tmp}:/keys:ro", "-e", "ANDROID_KEYSTORE_PATH=/keys/release.p12",
               "-e", "ANDROID_KEYSTORE_PASSWORD", "-e", "ANDROID_KEY_PASSWORD", "-e", "ANDROID_KEY_ALIAS",
               IMAGE, "bash", "-c", inner]
        p = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        last_sent, stage = 0.0, None
        for line in p.stdout:
            line = line.rstrip()
            if sig["password"] in line:
                line = line.replace(sig["password"], "***")
            tail.append(line)
            for rx, label in STAGES:
                if rx.search(line):
                    stage = label
            if time.time() - last_sent > 3:
                progress(jid, stage, list(tail)[-60:])
                last_sent = time.time()
        rc = p.wait()
        if rc != 0:
            raise RuntimeError(f"build failed (exit {rc})")
        apk = os.path.join(REPO_DIR, app, "build", "outputs", "apk", "release", f"{app}-release.apk")
        if not os.path.exists(apk):
            raise RuntimeError("signed APK not found")
        progress(jid, "Uploading", list(tail)[-60:])
        subject = git("log", "-1", "--pretty=%s")[:300]
        status, res = upload(jid, apk, {"version_code": code, "version_name": name, "git_commit": commit,
                                        "notes": subject, "log": "\n".join(list(tail)[-60:])})
        if status >= 300:
            raise RuntimeError(f"upload refused: {res.get('error') or status}")
        log(f"job {jid}: {app} v{name} ready ({modules or 'all'})")
    except Exception as e:
        log(f"job {jid} failed: {e}")
        try:
            api(f"/api/v1/app-builds/worker/jobs/{jid}/finish", {"error": str(e)[:1500], "log": "\n".join(list(tail)[-80:])})
        except Exception:
            pass
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ── main loop ────────────────────────────────────────────────────────────────

def heartbeat_loop():
    while True:
        try:
            api("/api/v1/app-builds/worker/heartbeat", {"name": NAME, "head_commit": state["head"], "busy": state["busy"]})
        except Exception as e:
            log("heartbeat failed:", e)
        time.sleep(10)


def auto_builds(head):
    """New commits on main → full builds (CVPAP answers DONE if already built)."""
    marker = os.path.join(STATE_DIR, "auto-head")
    if open(marker).read().strip() == head if os.path.exists(marker) else False:
        return
    for app in AUTO_APPS:
        status, res = api("/api/v1/app-builds/worker/jobs", {"app": app, "modules": "all", "reason": "AUTO"})
        log(f"auto build {app}: {res.get('status', status)}")
    with open(marker, "w") as f:
        f.write(head)


def main():
    if not TOKEN:
        sys.exit("BUILDER_TOKEN is not set (run setup.sh)")
    os.makedirs(STATE_DIR, exist_ok=True)
    threading.Thread(target=heartbeat_loop, daemon=True).start()
    log(f"builder {NAME} watching {CVPAP_URL} ({REPO_DIR} @ {BRANCH})")
    last_fetch = 0.0
    while True:
        try:
            if time.time() - last_fetch > FETCH_EVERY:
                state["head"] = fetch_head()
                last_fetch = time.time()
                if AUTO_APPS:
                    auto_builds(state["head"])
            status, job = api("/api/v1/app-builds/worker/claim", {"name": NAME})
            if status == 200 and job.get("id"):
                state["busy"] = True
                log(f"job {job['id']}: {job['app']} {job.get('modules')}")
                run_job(job)
                state["busy"] = False
                continue
        except Exception as e:
            log("loop error:", e)
            state["busy"] = False
        time.sleep(POLL)


if __name__ == "__main__":
    main()
