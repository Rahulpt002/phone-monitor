#!/system/bin/sh
# hsm-keepalive late_start service (plan 6.1 "root keep-alive script in Magisk's service.d").
# Magisk / KernelSU run this once per boot, in the background, as root.
#
# 1. waits for the boot to finish, 2. grants the Agent its permissions and battery exemptions,
# 3. starts the Agent's foreground service, 4. every CHECK_INTERVAL seconds restarts it if the
# process (or its service) is gone. Log: /data/local/tmp/hsm-keepalive.log (rotated).
# Pause it with: su -c touch /data/adb/hsm-keepalive.pause  (remove the file to resume).
# It does NOT make the phone power on by itself: see docs/install.md and docs/phase0.md.

MODDIR=${0%/*}
. "$MODDIR/common.sh"
load_config

LOCK=/dev/.hsm-keepalive.pid

# One loop per boot even if the script is started again by hand. /dev is a tmpfs, so the lock
# never survives a reboot.
if [ -f "$LOCK" ]; then
	old=$(cat "$LOCK" 2>/dev/null)
	case "$old" in
	'' | *[!0-9]*) ;;
	*)
		if [ "$old" != "$$" ] && [ -r "/proc/$old/cmdline" ]; then
			case "$(tr '\000' ' ' <"/proc/$old/cmdline" 2>/dev/null)" in
			*hsm-keepalive*)
				exit 0
				;;
			esac
		fi
		;;
	esac
fi
echo "$$" >"$LOCK"

log_msg "start (module $(grep -m 1 '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2), interval ${CHECK_INTERVAL}s)"

waited=0
until [ "$(getprop sys.boot_completed)" = 1 ]; do
	sleep 5
	waited=$((waited + 5))
	if [ "$waited" -eq 600 ]; then log_msg "still waiting for sys.boot_completed"; fi
done
# Credential-encrypted storage and the package manager settle a little after boot_completed.
waited=0
until [ "$(getprop sys.user.0.ce_available)" = true ] || [ "$waited" -ge 120 ]; do
	sleep 5
	waited=$((waited + 5))
done
sleep 10
log_msg "boot completed (boot reason: $(getprop sys.boot.reason))"

prepared=0
prepare() {
	grant_permissions
	apply_exemptions
	prepared=1
}

if agent_installed; then
	prepare
	if agent_running && service_running; then
		log_msg "Agent already running"
	elif start_agent; then
		log_msg "Agent started"
	else
		log_msg "Agent did not start (will retry every ${CHECK_INTERVAL}s)"
	fi
else
	log_msg "Agent ($PKG) is not installed; waiting for it"
fi

loops=0
fails=0
paused_logged=0
missing_logged=0
while true; do
	sleep "$CHECK_INTERVAL"
	loops=$((loops + 1))
	if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
		log_msg "module disabled or being removed: keep-alive loop stops"
		rm -f "$LOCK"
		exit 0
	fi
	if [ -f "$PAUSE_FLAG" ]; then
		if [ "$paused_logged" = 0 ]; then log_msg "paused ($PAUSE_FLAG exists)"; fi
		paused_logged=1
		continue
	fi
	if [ "$paused_logged" = 1 ]; then log_msg "resumed"; fi
	paused_logged=0
	if ! agent_installed; then
		if [ "$missing_logged" = 0 ]; then log_msg "Agent is not installed; waiting for it"; fi
		missing_logged=1
		prepared=0
		continue
	fi
	if [ "$missing_logged" = 1 ]; then log_msg "Agent installed"; fi
	missing_logged=0
	if [ "$prepared" = 0 ]; then prepare; fi
	# Re-apply the exemptions hourly: an update, a reset or the OS can drop them.
	if [ $((loops % (3600 / CHECK_INTERVAL + 1))) -eq 0 ]; then apply_exemptions; fi

	reason=""
	if ! agent_running; then
		reason="Agent process not running"
	elif [ $((loops % 5)) -eq 0 ] && ! service_running; then
		reason="Agent process alive but AgentService not running"
	fi
	[ -n "$reason" ] || {
		fails=0
		continue
	}
	if start_agent; then
		log_msg "$reason: restarted"
		fails=0
	else
		fails=$((fails + 1))
		# Keep trying every interval, but log a persistent failure only now and then.
		if [ "$fails" -le 3 ] || [ $((fails % 30)) -eq 0 ]; then
			log_msg "$reason: restart failed ($fails in a row)"
		fi
	fi
done
