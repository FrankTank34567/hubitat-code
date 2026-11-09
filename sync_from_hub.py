#!/usr/bin/env python3
"""Pull selected apps/drivers/libraries from the Hubitat hub into this repo.

Read-only: only HTTP GET requests are made to the hub. No credentials are
read, stored, or written (the hub must have Hub Login Security off).
Commits only if something changed. Run: python -I sync_from_hub.py [--no-commit]
"""
import json, re, subprocess, sys, urllib.request, datetime, os

ROOT = os.path.dirname(os.path.abspath(__file__))
KINDS = (
    ("apps", "app", "/app/ajax/code?id=", "apps"),
    ("drivers", "driver", "/driver/ajax/code?id=", "drivers"),
    ("libraries", "library", "/library/ajax/code?id=", "libraries"),
)


def get(url):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def field(src, key):
    """Value of key in the definition()/library() header; resolves a bare constant."""
    m = re.search(r"\b(?:definition|library)\s*\(.*?\n\s*\)", src, re.S)
    block = m.group(0) if m else src
    m = re.search(r'\b%s\s*:\s*"([^"]*)"' % key, block)
    if m:
        return m.group(1)
    m = re.search(r"\b%s\s*:\s*([A-Za-z_]\w*)\s*," % key, block)
    if m:
        c = re.search(r'\b%s\s*=\s*"([^"]*)"' % m.group(1), src)
        return c.group(1) if c else ""
    return ""


def header_version(src):
    head = "\n".join(src.splitlines()[:80])
    m = re.search(r"^[ \t/*#]*Version:?\s*v?([0-9][\w.\-]*)", head, re.M | re.I)
    return m.group(1) if m else field(src, "version")


def safe(name):
    return re.sub(r'[\/:*?"<>|]+', "-", name).strip()


def old_dates():
    dates = {}
    try:
        for line in open(os.path.join(ROOT, "MANIFEST.md"), encoding="utf-8"):
            c = [x.strip() for x in line.strip().strip("|").split("|")]
            if len(c) == 6 and c[0].endswith(".groovy"):
                dates[c[0].strip("`")] = c[5]
    except OSError:
        pass
    return dates


def main():
    cfg = json.load(open(os.path.join(ROOT, "sync_config.json"), encoding="utf-8"))
    today = datetime.date.today().isoformat()
    prev = old_dates()
    rows = []
    for key, kind, ep, folder in KINDS:
        os.makedirs(os.path.join(ROOT, folder), exist_ok=True)
        for hub_id in cfg.get(key, []):
            d = json.loads(get(cfg["hub"] + ep + str(hub_id)))
            src = d["source"]
            name = field(src, "name") or d.get("name") or str(hub_id)
            rel = "%s/%s.groovy" % (folder, safe(name))
            path = os.path.join(ROOT, rel)
            try:
                with open(path, encoding="utf-8", newline="") as f:
                    unchanged = f.read() == src
            except OSError:
                unchanged = False
            if not unchanged:
                with open(path, "w", encoding="utf-8", newline="") as f:
                    f.write(src)
            date = prev.get(rel, today) if unchanged else today
            rows.append((rel, kind, field(src, "namespace"), header_version(src), hub_id, date))
    lines = ["# Manifest", "",
             "| File | Type | Namespace | Header version | Hub code id | Date pulled |",
             "|---|---|---|---|---|---|"]
    for r in sorted(rows):
        lines.append("| %s | %s | %s | %s | %s | %s |" % r)
    with open(os.path.join(ROOT, "MANIFEST.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    if "--no-commit" in sys.argv:
        return
    git = lambda *a: subprocess.run(["git", "-C", ROOT, *a], capture_output=True, text=True)
    git("add", "-A")
    if git("status", "--porcelain").stdout.strip():
        out = git("commit", "-m", "Sync from hub " + today)
        print(out.stdout or out.stderr)
        print("Committed. Run 'git push' to publish.")
    else:
        print("No changes.")


if __name__ == "__main__":
    main()
