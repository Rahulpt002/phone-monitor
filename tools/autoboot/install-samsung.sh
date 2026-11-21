#!/usr/bin/env bash
# Installs (or removes) the Samsung automatic power-on rule: tools/autoboot/hsm-autoboot.rc goes
# to /system/etc/init/ on the phone connected over adb.
#
# Why /system: Samsung starts its charging-only screen (service 'lpm') when power returns to a
# phone that is off. Magisk deliberately does not run in that mode ("Charger mode ... abort"), so
# neither Magisk modules nor Magisk overlay.d rc files can react to it. Android's own init reads
# /system/etc/init/*.rc in every mode, so the rule has to live there.
#
# Needs: root (su for the adb shell), vbmeta verification disabled (done when rooting), and an
# ext4 system partition without shared_blocks (checked below). A firmware flash removes the rule;
# run this again afterwards.
#
#   tools/autoboot/install-samsung.sh            # install
#   tools/autoboot/install-samsung.sh --remove   # remove
# Then prove it: tools/phase0/phase0.sh autoboot --test

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RC=$SCRIPT_DIR/hsm-autoboot.rc
DEST=/system/etc/init/hsm-autoboot.rc
ADB=${ADB:-adb}

die() {
	printf 'ERROR: %s\n' "$1" >&2
	exit 1
}

remove=0
case "${1:-}" in
'') ;;
--remove) remove=1 ;;
*) die "usage: $0 [--remove]" ;;
esac

[ -f "$RC" ] || die "missing $RC"
"$ADB" get-state >/dev/null 2>&1 || die "no phone on adb"
[ "$("$ADB" shell su -c id </dev/null 2>/dev/null | grep -c 'uid=0')" = 1 ] || die "su does not work: grant root to Shell in Magisk"
manuf=$("$ADB" shell getprop ro.product.manufacturer </dev/null | tr -d '\r' | tr 'A-Z' 'a-z')
[ "$manuf" = samsung ] || die "this method is for Samsung (found '$manuf'); use fastboot oem off-mode-charge 0 instead"

# The system partition must be ext4 without shared_blocks (EXT4_FEATURE_RO_COMPAT_SHARED_BLOCKS
# 0x4000), otherwise it cannot be mounted read-write.
check=$("$ADB" shell su <<'EOF'
dev=$(awk '$2 == "/" { print $1; exit }' /proc/mounts)
fs=$(awk '$2 == "/" { print $3; exit }' /proc/mounts)
ro=$(dd if="$dev" bs=1 skip=1124 count=4 2>/dev/null | od -An -tx4 | tr -d ' \n')
echo "$dev $fs $ro"
EOF
)
check=$(printf '%s' "$check" | tr -d '\r')
set -- $check
dev=${1:-} fs=${2:-} rocompat=${3:-0}
[ -n "$dev" ] || die "could not find the system block device"
[ "$fs" = ext4 ] || die "system is $fs, not ext4: it cannot be changed in place"
[ $((0x$rocompat & 0x4000)) = 0 ] || die "system ext4 has shared_blocks: it cannot be mounted read-write"

if [ "$remove" = 1 ]; then
	action="rm -f $DEST"
else
	"$ADB" push "$RC" /data/local/tmp/hsm-autoboot.rc >/dev/null
	action="cp /data/local/tmp/hsm-autoboot.rc $DEST && chown root:root $DEST && chmod 0644 $DEST && chcon u:object_r:system_file:s0 $DEST"
fi

"$ADB" shell su <<EOF
set -e
blockdev --setrw $dev
mount -o remount,rw /
$action
sync
mount -o remount,ro /
blockdev --setro $dev
ls -lZ $DEST 2>/dev/null || echo "$DEST removed"
EOF

if [ "$remove" = 1 ]; then
	echo "Removed. The phone stays in charging-only mode again when power returns."
else
	echo "Installed. Reboot once, then prove it: tools/phase0/phase0.sh autoboot --test"
fi
