#!/usr/bin/env bash
# DAK: publishes the built APKs as the in-app update (branch «updates»: latest.json + APKs).
# The branch keeps only the newest version (one commit, force-pushed), so the repository doesn't grow.
#   buildScript/publish-update.sh [notes-file]    — after ./gradlew app:assembleOssRelease
set -euo pipefail
cd "$(dirname "$0")/.."
ver=$(grep '^VERSION_NAME=' nb4a.properties | cut -d= -f2)
notes=${1:-}
remote=${DAK_REMOTE:-kombain}
url=$(git remote get-url "$remote")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 - "$ver" "$work" "$notes" <<'PY'
import hashlib, json, os, shutil, sys
ver, work, notes = sys.argv[1], sys.argv[2], sys.argv[3]
apks = {}
for abi in ["arm64-v8a", "armeabi-v7a", "x86_64"]:
    src = f"app/build/outputs/apk/oss/release/NekoBox-{ver}-{abi}.apk"
    if not os.path.exists(src):
        continue
    name = f"DAK-{ver}-{abi}.apk"
    shutil.copy(src, os.path.join(work, name))
    data = open(src, "rb").read()
    apks[abi] = {"file": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
if "arm64-v8a" not in apks:
    sys.exit(f"нет APK версии {ver} — сначала соберите app:assembleOssRelease")
text = open(notes).read().strip() if notes else ""
json.dump({"version": ver, "notes": text, "apks": apks}, open(os.path.join(work, "latest.json"), "w"), ensure_ascii=False, indent=1)
open(os.path.join(work, "README.md"), "w").write(f"# DAK {ver}\n\nОбновления приложения DAK. {text}\n")
PY
git -C "$work" init -q -b updates
git -C "$work" add -A
git -C "$work" -c user.name="DAK" -c user.email="dak@localhost" commit -qm "DAK $ver"
git -C "$work" push -q -f "$url" updates:updates
echo "Опубликовано обновление DAK $ver"
