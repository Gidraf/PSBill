# Ajiriwa Windows agent — build tooling

The agent **source** is canonical in CVPAP at `app/services/winagent_payload/`.
Compiling it into `ajiriwa-agent.exe` is **automated from the web** — you don't
run anything here by hand:

1. Admin dashboard → **Windows machines → Compile**.
2. That queues a `winagent` build job. The build server agent
   (`tools/server-build/builder_agent.py`) picks it up, builds the Docker image
   `tools/server-build/Dockerfile.winebuild` (PyInstaller + Windows Python under
   Wine) the first time, fetches the agent source from CVPAP
   (`/api/v1/win-agent/worker/payload`), compiles `ajiriwa-agent.exe`, and uploads it.
3. From then on, every per-business installer the dashboard builds bundles the exe,
   so the café PCs need no Python. Until it's built, the installer ships the Python
   source and `install.ps1` falls back to system Python.

No Windows host and no manual Wine setup are needed — the builder builds the image
on demand, exactly like the Android build image.

## Signing (optional)
The exe is currently **unsigned**, so Windows SmartScreen shows a warning on first
run. To sign it, add `osslsigncode` to `Dockerfile.winebuild` and a signing step
after PyInstaller in `builder_agent.build_winagent`, using your code-signing cert.

## What the agent is / isn't
Consent-based management for a business's OWN machines: reports status / network /
connected-peripheral count + insert-remove / selected Windows events, and runs only
an allow-list of actions (lock, sign out, message, restart, shut down, start/stop
session). No remote shell, no keylogging, no screen capture. See the payload's
`README.txt` and the header of `agent.py`.
