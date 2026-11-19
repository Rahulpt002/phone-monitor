#!/usr/bin/env bash
# Builds the hsm-keepalive Magisk / KernelSU module from agent/magisk/ into
# tools/phase0/dist/hsm-keepalive.zip. Works with macOS's bash 3.2 and Linux bash.
# Needs `zip`, or python3 as a fallback.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
SRC=$REPO_ROOT/agent/magisk
OUT_DIR=$SCRIPT_DIR/dist
OUT=$OUT_DIR/hsm-keepalive.zip

die() {
	printf 'ERROR: %s\n' "$1" >&2
	exit 1
}

for f in module.prop customize.sh common.sh service.sh action.sh uninstall.sh \
	META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script; do
	[ -f "$SRC/$f" ] || die "missing $SRC/$f"
done

id=$(sed -n 's/^id=//p' "$SRC/module.prop")
version=$(sed -n 's/^version=//p' "$SRC/module.prop")
[ "$id" = hsm-keepalive ] || die "module.prop id is '$id', expected hsm-keepalive"

# Android's shells choke on CRLF line endings, and a syntax error would only show up on the phone.
for f in "$SRC"/*.sh "$SRC/META-INF/com/google/android/update-binary" "$SRC/module.prop"; do
	if grep -q "$(printf '\r')" "$f"; then die "$f has CRLF line endings"; fi
done
for f in "$SRC"/*.sh "$SRC/META-INF/com/google/android/update-binary"; do
	sh -n "$f" || die "syntax error in $f"
done

mkdir -p "$OUT_DIR"
STAGE="$OUT_DIR/.stage-$$"
cleanup() {
	if [ -d "$STAGE" ]; then
		chmod -R u+rwx "$STAGE" 2>/dev/null || true
		rm -rf "$STAGE"
	fi
}
trap cleanup EXIT
rm -rf "$STAGE"
mkdir -p "$STAGE/META-INF/com/google/android"

# The zip holds the module only: README.md and extras/ stay in the repository.
cp "$SRC/module.prop" "$SRC/customize.sh" "$SRC/common.sh" "$SRC/service.sh" "$SRC/action.sh" \
	"$SRC/uninstall.sh" "$SRC/hsm-keepalive.conf.example" "$STAGE/"
cp "$SRC/META-INF/com/google/android/update-binary" "$SRC/META-INF/com/google/android/updater-script" \
	"$STAGE/META-INF/com/google/android/"
find "$STAGE" -type d -exec chmod 0755 {} +
find "$STAGE" -type f -exec chmod 0644 {} +
chmod 0755 "$STAGE"/*.sh "$STAGE/META-INF/com/google/android/update-binary"

rm -f "$OUT"
if command -v zip >/dev/null 2>&1; then
	(cd "$STAGE" && zip -q -r -X "$OUT" module.prop customize.sh common.sh service.sh action.sh \
		uninstall.sh hsm-keepalive.conf.example META-INF)
elif command -v python3 >/dev/null 2>&1; then
	python3 - "$STAGE" "$OUT" <<'PY'
import os
import sys
import zipfile

src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(root, name)
            info = zipfile.ZipInfo(os.path.relpath(path, src), date_time=(2026, 1, 1, 0, 0, 0))
            mode = 0o755 if name.endswith(".sh") or name == "update-binary" else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as fh:
                z.writestr(info, fh.read())
PY
else
	die "neither zip nor python3 is available"
fi

[ -s "$OUT" ] || die "the zip was not created"
printf 'Built %s (module %s %s, %s bytes)\n' "$OUT" "$id" "$version" "$(wc -c <"$OUT" | tr -d ' ')"
cat <<EOF
Install:
  adb push "$OUT" /sdcard/Download/
  adb shell su -c 'magisk --install-module /sdcard/Download/hsm-keepalive.zip'          # Magisk
  adb shell su -c '/data/adb/ksud module install /sdcard/Download/hsm-keepalive.zip'    # KernelSU
  adb reboot
or install the zip from the Magisk / KernelSU app (Modules, Install from storage).
EOF
