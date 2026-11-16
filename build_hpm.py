"""Generate Hubitat Package Manager files from hpm_config.json.

Usage:
  python -I build_hpm.py          write hpm/<key>/packageManifest.json, repository.json, hpm/versions.lock.json
  python -I build_hpm.py --check  exit 1 if generated files are stale or a file changed without a version bump

Rules enforced:
  - every packaged file exists and has a definition() name and namespace
  - a package whose files changed since the last build must have a new version,
    otherwise HPM would never offer the update to other hubs
  - package ids and app/driver ids are stable (uuid5 of the repo URL and key/file)
"""
import hashlib
import json
import re
import sys
import uuid
from datetime import date
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent
CONFIG = ROOT / "hpm_config.json"
HPM_DIR = ROOT / "hpm"
LOCK = HPM_DIR / "versions.lock.json"
REPO_FILE = ROOT / "repository.json"


def fail(msg):
    print(f"ERROR: {msg}")
    sys.exit(1)


def read_definition(path):
    text = path.read_text(encoding="utf-8")
    consts = dict(re.findall(r'@Field\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"', text))
    m = re.search(r"definition\s*\((.*?)\)\s*\{?", text, re.S)
    if not m:
        fail(f"{path.name}: no definition() block")
    block = m.group(1)

    def field(name):
        fm = re.search(rf'\b{name}\s*:\s*(?:"([^"]*)"|\'([^\']*)\'|(\w+))', block)
        if not fm:
            fail(f"{path.name}: definition() has no {name}")
        literal = fm.group(1) if fm.group(1) is not None else fm.group(2)
        if literal is not None:
            return literal
        if fm.group(3) in consts:
            return consts[fm.group(3)]
        fail(f"{path.name}: {name} refers to unknown constant {fm.group(3)}")

    return field("name"), field("namespace")


def stable_id(base, *parts):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "/".join((base,) + parts)))


def file_hash(path):
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def build():
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    base, raw = cfg["gitHubUrl"], cfg["rawBase"]
    lock = json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else {}
    new_lock, outputs, repo_pkgs = {}, {}, []

    for pkg in cfg["packages"]:
        key, version = pkg["key"], pkg["version"]
        if not re.fullmatch(r"\d+\.\d+\.\d+", version):
            fail(f"{key}: version '{version}' must be plain MAJOR.MINOR.PATCH (HPM treats pre-release tags as betas)")
        hashes, apps, drivers = {}, [], []
        for kind, out in (("apps", apps), ("drivers", drivers)):
            for entry in pkg.get(kind, []):
                path = ROOT / entry["file"]
                if not path.exists():
                    fail(f"{key}: missing file {entry['file']}")
                name, ns = read_definition(path)
                hashes[entry["file"]] = file_hash(path)
                item = {
                    "id": stable_id(base, key, entry["file"]),
                    "name": name,
                    "namespace": ns,
                    "location": raw + quote(entry["file"]),
                    "required": entry.get("required", True),
                }
                if kind == "apps":
                    item["oauth"] = entry.get("oauth", False)
                    item["primary"] = entry.get("primary", False)
                out.append(item)

        prev = lock.get(key)
        released = date.today().isoformat()
        if prev:
            if prev["hashes"] != hashes and prev["version"] == version:
                changed = sorted(f for f in hashes if prev["hashes"].get(f) != hashes[f])
                fail(f"{key}: {', '.join(changed)} changed but version is still {version}. Bump it in hpm_config.json.")
            if prev["version"] == version:
                released = prev["dateReleased"]
        new_lock[key] = {"version": version, "dateReleased": released, "hashes": hashes}

        manifest = {
            "packageName": pkg["name"],
            "author": cfg["author"],
            "version": version,
            "minimumHEVersion": pkg.get("minimumHEVersion", cfg["minimumHEVersion"]),
            "dateReleased": released,
            "documentationLink": cfg["gitHubUrl"],
        }
        if apps:
            manifest["apps"] = apps
        if drivers:
            manifest["drivers"] = drivers
        rel = f"hpm/{key}/packageManifest.json"
        outputs[rel] = manifest
        repo_pkgs.append({
            "id": stable_id(base, key),
            "name": pkg["name"],
            "category": pkg["category"],
            "location": raw + quote(rel),
            "description": pkg["description"],
        })

    outputs["repository.json"] = {"author": cfg["author"], "gitHubUrl": base, "packages": repo_pkgs}
    outputs["hpm/versions.lock.json"] = new_lock
    return outputs


def render(obj):
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def main():
    outputs = build()
    if "--check" in sys.argv:
        stale = [rel for rel, obj in outputs.items()
                 if not (ROOT / rel).exists() or (ROOT / rel).read_text(encoding="utf-8") != render(obj)]
        if stale:
            fail("generated files are stale, run build_hpm.py: " + ", ".join(stale))
        print("OK: HPM files up to date")
        return
    for rel, obj in outputs.items():
        out = ROOT / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render(obj), encoding="utf-8", newline="\n")
    print(f"wrote {len(outputs)} files")


if __name__ == "__main__":
    main()
