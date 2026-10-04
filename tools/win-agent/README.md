# Ajiriwa Windows agent (build tooling)

The agent **source** is canonical in CVPAP at `app/services/winagent_payload/`
(so the server can zip + stamp it per business on download). This folder holds
the tooling to turn that source into a signed `ajiriwa-agent.exe`, so partners
don't need Python on their PCs.

## What the agent is
A consent-based management agent for a business's OWN Windows machines. It reports
status / network / connected-peripheral count + insert-remove / selected Windows
events, and carries out ONLY an allow-list of actions (lock, sign out, message,
restart, shut down, start/stop session). No remote shell, no keylogging, no screen
capture. See the header of `agent.py` and `README.txt` in the payload.

## Build the exe (Windows build host, or Linux + Wine)
    CVPAP=../../..                      # path to the CVPAP checkout on this host
    cp -r "$CVPAP/CVPAP/app/services/winagent_payload" ./payload
    cd payload
    pip install pyinstaller
    python build.py                     # -> dist/ajiriwa-agent.exe
    # sign it with your code-signing cert (signtool / osslsigncode), then:
    cp dist/ajiriwa-agent.exe "$CVPAP/CVPAP/app/services/winagent_payload/ajiriwa-agent.exe"

Once the exe sits next to the source in the payload dir, every per-business
installer the dashboard builds includes it, and `install.ps1` uses it instead of
system Python. `agent_config.json` (the business token) is still added per
download, so one exe serves every business and no token is baked into the binary.

## Per-business install (what the partner does)
Admin -> Windows machines -> pick business -> Create installer -> Download.
Unzip on each PC, run `install.ps1` as Administrator, accept the on-screen notice.
