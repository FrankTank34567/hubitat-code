#!/usr/bin/env python3
"""Compare the Frame Bridge app and Frame Notifier driver here with the copies
in FrankTank34567/epaper-dashboard (hubitat/). Exit 1 if they differ.

Read-only; uses `gh api` (needs `gh auth login`). Line endings and the trailing
newline are ignored. Run: python -I check_epaper_drift.py
"""
import base64, json, os, subprocess, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = "FrankTank34567/epaper-dashboard"
PAIRS = (
    ("apps/Frame Bridge.groovy", "hubitat/FrameBridge.groovy"),
    ("drivers/Frame Notifier.groovy", "hubitat/FrameNotifierDriver.groovy"),
)


def norm(b):
    return b.replace(b"\r\n", b"\n").rstrip(b"\n")


def remote(path):
    out = subprocess.run(["gh", "api", "repos/%s/contents/%s" % (REPO, path)],
                         capture_output=True, text=True)
    if out.returncode:
        sys.exit("gh api failed for %s: %s" % (path, out.stderr.strip()))
    return base64.b64decode(json.loads(out.stdout)["content"])


bad = 0
for here, there in PAIRS:
    with open(os.path.join(ROOT, here), "rb") as f:
        same = norm(f.read()) == norm(remote(there))
    print(("OK     " if same else "DRIFT  ") + here + "  <->  " + there)
    bad += not same
sys.exit(1 if bad else 0)
