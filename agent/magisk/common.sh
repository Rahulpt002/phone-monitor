# Shared functions for service.sh and action.sh (sourced, not executed).
# Runs as root under Magisk's / KernelSU's busybox ash or Android's mksh: POSIX sh only.

PKG=com.hotspotmonitor.agent
RECEIVER=$PKG/.service.ControlReceiver
SERVICE=$PKG/.service.AgentService
ACTION_START=com.hotspotmonitor.agent.action.START

LOG=/data/local/tmp/hsm-keepalive.log
# Only root can write /data/adb, so sourcing the config there is safe.
CONF=/data/adb/hsm-keepalive.conf
PAUSE_FLAG=/data/adb/hsm-keepalive.pause

PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

# Defaults; /data/adb/hsm-keepalive.conf may override them (see hsm-keepalive.conf.example).
CHECK_INTERVAL=60
STAY_AWAKE_OFF=1
GRANT_SMS=1
EXTRA_PERMISSIONS=""
EXTRA_APPOPS=""
LOG_MAX_KB=256

load_config() {
	if [ -f "$CONF" ] && [ ! -L "$CONF" ]; then
		# shellcheck disable=SC1090
		. "$CONF"
	fi
	case "$CHECK_INTERVAL" in '' | *[!0-9]*) CHECK_INTERVAL=60 ;; esac
	[ "$CHECK_INTERVAL" -ge 15 ] || CHECK_INTERVAL=15
	case "$LOG_MAX_KB" in '' | *[!0-9]*) LOG_MAX_KB=256 ;; esac
	[ "$LOG_MAX_KB" -ge 16 ] || LOG_MAX_KB=16
	case "$STAY_AWAKE_OFF" in 1) ;; *) STAY_AWAKE_OFF=0 ;; esac
	case "$GRANT_SMS" in 0) ;; *) GRANT_SMS=1 ;; esac
}

log_msg() {
	# /data/local/tmp is writable by the adb shell user: never follow a planted symlink as root.
	if [ -L "$LOG" ]; then rm -f "$LOG"; fi
	if [ -f "$LOG" ]; then
		size=$(wc -c <"$LOG" 2>/dev/null)
		case "$size" in '' | *[!0-9\ ]*) size=0 ;; esac
		if [ $((size + 0)) -gt $((LOG_MAX_KB * 1024)) ]; then
			if [ -L "$LOG.1" ]; then rm -f "$LOG.1"; fi
			mv -f "$LOG" "$LOG.1"
		fi
	fi
	printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >>"$LOG"
	chmod 0644 "$LOG" 2>/dev/null
	log -t hsm-keepalive "$*" >/dev/null 2>&1
	return 0
}

agent_installed() {
	pm path "$PKG" 2>/dev/null | grep -q '^package:'
}

agent_running() {
	if [ -x /system/bin/pidof ]; then
		/system/bin/pidof "$PKG" >/dev/null 2>&1
	else
		pidof "$PKG" >/dev/null 2>&1
	fi
}

service_running() {
	dumpsys activity services "$SERVICE" 2>/dev/null | grep -q 'ServiceRecord'
}

# Runtime permissions the Agent declares (agent/app/src/main/AndroidManifest.xml).
# Background location needs fine/coarse first, so the order matters.
grant_permissions() {
	perms="android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION
android.permission.ACCESS_BACKGROUND_LOCATION android.permission.READ_PHONE_STATE
android.permission.CALL_PHONE android.permission.POST_NOTIFICATIONS android.permission.BLUETOOTH_CONNECT"
	if [ "$GRANT_SMS" = 1 ]; then
		perms="$perms android.permission.SEND_SMS android.permission.RECEIVE_SMS android.permission.READ_SMS"
	fi
	perms="$perms $EXTRA_PERMISSIONS"
	granted=""
	failed=""
	for p in $perms; do
		if pm grant "$PKG" "$p" >/dev/null 2>&1; then
			granted="$granted ${p##*.}"
		else
			failed="$failed ${p##*.}"
		fi
	done
	log_msg "permissions granted:${granted:- none}"
	if [ -n "$failed" ]; then
		# Not declared by this Agent build, not a runtime permission on this Android version, or
		# (SMS) a hard-restricted permission on an APK not installed with adb install.
		log_msg "permissions not granted:$failed"
	fi
}

apply_exemptions() {
	for op in GET_USAGE_STATS RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND $EXTRA_APPOPS; do
		appops set "$PKG" "$op" allow >/dev/null 2>&1 || log_msg "appops $op allow failed"
	done
	# Battery optimisation off. This also lets the Agent start its foreground service from the
	# background on Android 12+.
	dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 || log_msg "deviceidle whitelist failed"
	# Keep the app out of the restricted standby buckets (Android 9+).
	am set-standby-bucket "$PKG" active >/dev/null 2>&1
	if [ "$STAY_AWAKE_OFF" = 1 ]; then
		# Developer option "Stay awake" keeps the screen on while charging: wasted power and heat.
		settings put global stay_on_while_plugged_in 0 >/dev/null 2>&1
	fi
	return 0
}

# Start the Agent's foreground service. Returns 0 when the process is up afterwards.
# $1 = reason reported in the Agent's agent_start event: boot | restart (default) | manual | update.
start_agent() {
	start_reason=${1:-restart}
	case "$start_reason" in boot | restart | manual | update) ;; *) start_reason=restart ;; esac
	# 0x10000020 = FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES: reaches the app even
	# before its first launch or after a force-stop.
	am broadcast --user 0 -a "$ACTION_START" -n "$RECEIVER" -f 0x10000020 --es reason "$start_reason" >/dev/null 2>&1
	i=0
	while [ "$i" -lt 10 ]; do
		sleep 1
		if agent_running && service_running; then return 0; fi
		i=$((i + 1))
	done
	am start-foreground-service --user 0 -n "$SERVICE" --es reason "$start_reason" >/dev/null 2>&1
	i=0
	while [ "$i" -lt 10 ]; do
		sleep 1
		if agent_running; then return 0; fi
		i=$((i + 1))
	done
	return 1
}
