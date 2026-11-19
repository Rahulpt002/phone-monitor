#!/system/bin/sh
# Phase 0 helper that runs ON THE PHONE. tools/phase0/phase0.sh pushes it to
# /data/local/tmp/hsm-phase0/ and calls it through `adb shell` (as root when su works).
#
# It must run under Android's mksh, toybox sh and busybox ash, using toybox tools only:
# no awk (missing on older Android), no bash features, no `local`. Parsing of the raw
# output happens on the host.
#
# Usage:
#   hsm-phase0-device.sh battery-header            CSV header for battery rows
#   hsm-phase0-device.sh battery                   one battery CSV row
#   hsm-phase0-device.sh current-path              sysfs current_now path that is logged
#   hsm-phase0-device.sh watch SECONDS             one battery row per second
#   hsm-phase0-device.sh charge-scan               power supplies + known charge-control files
#   hsm-phase0-device.sh thermal-scan              thermal zones: TZ<TAB>index<TAB>type<TAB>raw
#   hsm-phase0-device.sh signal SECONDS            telephony.registry samples, about 1 per second
#   hsm-phase0-device.sh log power|heat CSV INTERVAL_S DURATION_S PIDFILE
#                                                  logger loop; DURATION_S 0 = until stopped

PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
export PATH

die() {
	printf 'hsm-phase0-device: %s\n' "$1" >&2
	exit 2
}

is_uint() {
	case "$1" in
	'' | *[!0-9]*) return 1 ;;
	esac
	return 0
}

# --- battery ------------------------------------------------------------------------------

CUR_PATH=""

find_current_path() {
	for p in /sys/class/power_supply/battery/current_now \
		/sys/class/power_supply/bms/current_now \
		/sys/class/power_supply/main/current_now; do
		if [ -r "$p" ]; then
			CUR_PATH=$p
			return 0
		fi
	done
	for d in /sys/class/power_supply/*; do
		[ -r "$d/type" ] || continue
		read -r ps_type 2>/dev/null <"$d/type" || continue
		if [ "$ps_type" = "Battery" ] && [ -r "$d/current_now" ]; then
			CUR_PATH=$d/current_now
			return 0
		fi
	done
	CUR_PATH=""
	return 1
}

battery_header() {
	printf 'epoch,local_time,uptime_s,level,status,plug,current_raw,voltage_mv,temp_dc\n'
}

# One CSV row. status is the numeric BatteryManager code (2 charging, 3 discharging,
# 4 not charging, 5 full), plug is ac|usb|wireless|dock|none, current_raw is the unscaled
# sysfs value (unit and sign differ per phone: see `phase0.sh current-sign`).
battery_row() {
	now=$(date +%s)
	local_time=$(date +%Y-%m-%dT%H:%M:%S)
	up=""
	read -r up _rest 2>/dev/null </proc/uptime || up=""
	cur=""
	if [ -n "$CUR_PATH" ]; then
		read -r cur 2>/dev/null <"$CUR_PATH" || cur=""
	fi
	dumpsys battery 2>/dev/null | {
		lvl="" st="" ac="" usb="" wl="" dock="" mv="" dc=""
		while IFS= read -r line; do
			line=${line#"${line%%[! ]*}"}
			case "$line" in
			"level: "*) [ -n "$lvl" ] || lvl=${line#"level: "} ;;
			"status: "*) [ -n "$st" ] || st=${line#"status: "} ;;
			"AC powered: "*) ac=${line#"AC powered: "} ;;
			"USB powered: "*) usb=${line#"USB powered: "} ;;
			"Wireless powered: "*) wl=${line#"Wireless powered: "} ;;
			"Dock powered: "*) dock=${line#"Dock powered: "} ;;
			"voltage: "*) [ -n "$mv" ] || mv=${line#"voltage: "} ;;
			"temperature: "*) [ -n "$dc" ] || dc=${line#"temperature: "} ;;
			esac
		done
		plug=none
		[ "$dock" = "true" ] && plug=dock
		[ "$wl" = "true" ] && plug=wireless
		[ "$usb" = "true" ] && plug=usb
		[ "$ac" = "true" ] && plug=ac
		printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$now" "$local_time" "$up" "$lvl" "$st" "$plug" "$cur" "$mv" "$dc"
	}
}

do_watch() {
	secs=$1
	is_uint "$secs" || die "watch: SECONDS must be a whole number"
	find_current_path || true
	battery_header
	n=0
	while [ "$n" -lt "$secs" ]; do
		battery_row
		n=$((n + 1))
		sleep 1
	done
}

# --- charge-control discovery -------------------------------------------------------------

# Names documented by the ACC project and common kernels. Listing them is read-only.
CHARGE_NAMES="input_suspend charging_enabled battery_charging_enabled charge_enabled charge_disable
batt_slate_mode store_mode batt_full_capacity siop_level charge_control_limit charge_control_limit_max
charge_stop_level charge_start_level charge_control_end_threshold charge_control_start_threshold
charge_behaviour op_disable_charge mmi_charging_enable bypass_charger"

CHARGE_EXTRA_PATHS="/sys/devices/platform/google,charger/charge_stop_level
/sys/devices/platform/google,charger/charge_start_level
/sys/devices/platform/soc/soc:google,charger/charge_stop_level
/sys/devices/platform/soc/soc:google,charger/charge_start_level
/sys/kernel/debug/google_charger/chg_suspend
/sys/kernel/debug/google_charger/input_suspend
/sys/class/oplus_chg/battery/mmi_charging_enable
/sys/devices/platform/charger/bypass_charger
/sys/devices/platform/mt-battery/disable_charger
/proc/mtk_battery_cmd/current_cmd"

file_mode() {
	# Prints rw or ro from the owner write bit. `[ -w ]` is useless here: root bypasses DAC.
	fm=$(ls -l "$1" 2>/dev/null) || {
		printf 'ro'
		return
	}
	fm=${fm%% *}
	case "$fm" in
	??w*) printf 'rw' ;;
	*) printf 'ro' ;;
	esac
}

print_ctl() {
	v=""
	read -r v 2>/dev/null <"$1" || v="(unreadable)"
	printf 'CTL\t%s\t%s\t%s\n' "$1" "$v" "$(file_mode "$1")"
}

read_or_dash() {
	rv="-"
	if [ -r "$1" ]; then
		read -r rv 2>/dev/null <"$1" || rv="-"
	fi
	printf '%s' "$rv"
}

do_charge_scan() {
	for d in /sys/class/power_supply/*; do
		[ -d "$d" ] || continue
		printf 'PS\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${d##*/}" "$(read_or_dash "$d/type")" \
			"$(read_or_dash "$d/online")" "$(read_or_dash "$d/status")" "$(read_or_dash "$d/capacity")" \
			"$(read_or_dash "$d/current_now")" "$(read_or_dash "$d/voltage_now")"
	done
	for d in /sys/class/power_supply/* /sys/class/qcom-battery /sys/class/oplus_chg/*; do
		[ -d "$d" ] || continue
		for n in $CHARGE_NAMES; do
			[ -e "$d/$n" ] && print_ctl "$d/$n"
		done
	done
	for p in $CHARGE_EXTRA_PATHS; do
		[ -e "$p" ] && print_ctl "$p"
	done
	return 0
}

# --- thermal ------------------------------------------------------------------------------

do_thermal_scan() {
	for z in /sys/class/thermal/thermal_zone*; do
		[ -d "$z" ] || continue
		ty="" raw=""
		read -r ty 2>/dev/null <"$z/type" || ty="?"
		read -r raw 2>/dev/null <"$z/temp" || raw=""
		printf 'TZ\t%s\t%s\t%s\n' "${z##*thermal_zone}" "$ty" "$raw"
	done
	return 0
}

thermal_compact() {
	# type=raw|type=raw... (no commas, so it fits in one CSV field)
	tc=""
	for z in /sys/class/thermal/thermal_zone*; do
		[ -d "$z" ] || continue
		ty="" raw=""
		read -r ty 2>/dev/null <"$z/type" || continue
		read -r raw 2>/dev/null <"$z/temp" || continue
		case "$ty" in *[,\|=\ ]* | '') ty="zone${z##*thermal_zone}" ;; esac
		case "$raw" in '' | *[!0-9-]*) continue ;; esac
		tc="$tc${tc:+|}$ty=$raw"
	done
	printf '%s' "$tc"
}

heat_header() {
	printf 'epoch,local_time,uptime_s,battery_dc,zones\n'
}

heat_row() {
	now=$(date +%s)
	local_time=$(date +%Y-%m-%dT%H:%M:%S)
	up=""
	read -r up _rest 2>/dev/null </proc/uptime || up=""
	dc=$(dumpsys battery 2>/dev/null | sed -n 's/^ *temperature: *//p' | head -n 1)
	printf '%s,%s,%s,%s,%s\n' "$now" "$local_time" "$up" "$dc" "$(thermal_compact)"
}

# --- signal -------------------------------------------------------------------------------

# /proc/uptime in centiseconds ("12345.67" -> 1234567).
uptime_cs() {
	u=""
	read -r u _rest </proc/uptime
	printf '%s%s' "${u%.*}" "${u#*.}"
}

do_signal() {
	secs=$1
	is_uint "$secs" || die "signal: SECONDS must be a whole number"
	frac=0
	if sleep 0.01 2>/dev/null; then frac=1; fi
	start=$(uptime_cs)
	end=$((start + secs * 100))
	i=0
	while :; do
		t0=$(uptime_cs)
		[ "$t0" -ge "$end" ] && break
		printf '##HSM ts=%s up=%s\n' "$(date +%s)" "$t0"
		dumpsys telephony.registry 2>/dev/null |
			grep -E 'Phone Id=|mSignalStrength=|mCellInfo=|mServiceState=|mDataConnectionState=' || true
		i=$((i + 1))
		t1=$(uptime_cs)
		rem=$((100 - (t1 - t0)))
		if [ "$rem" -gt 0 ]; then
			if [ "$frac" = 1 ]; then
				if [ "$rem" -lt 10 ]; then sleep "0.0$rem"; else sleep "0.$rem"; fi
			else
				sleep 1
			fi
		fi
	done
	printf '##HSM end samples=%s\n' "$i"
}

# --- logger -------------------------------------------------------------------------------

do_log() {
	mode=$1 csv=$2 interval=$3 duration=$4 pidfile=$5
	case "$mode" in power | heat) ;; *) die "log: mode must be power or heat" ;; esac
	is_uint "$interval" || die "log: INTERVAL_S must be a whole number"
	is_uint "$duration" || die "log: DURATION_S must be a whole number"
	[ "$interval" -ge 1 ] || interval=1
	# Survive the adb session that started us (nohup covers the rest).
	trap '' HUP
	echo "$$" >"$pidfile"
	chmod 0644 "$pidfile" 2>/dev/null
	if [ ! -s "$csv" ]; then
		if [ "$mode" = power ]; then battery_header >"$csv"; else heat_header >"$csv"; fi
	fi
	chmod 0644 "$csv" 2>/dev/null
	[ "$mode" = power ] && { find_current_path || true; }
	started=$(date +%s)
	while :; do
		now=$(date +%s)
		if [ "$duration" -gt 0 ] && [ $((now - started)) -ge "$duration" ]; then break; fi
		[ -f "$pidfile.stop" ] && break
		if [ "$mode" = power ]; then battery_row >>"$csv"; else heat_row >>"$csv"; fi
		sleep "$interval"
	done
	rm -f "$pidfile" "$pidfile.stop"
}

# --- main ---------------------------------------------------------------------------------

cmd=${1:-}
[ $# -gt 0 ] && shift
case "$cmd" in
battery-header) battery_header ;;
battery)
	find_current_path || true
	battery_row
	;;
current-path)
	if find_current_path; then printf '%s\n' "$CUR_PATH"; else exit 1; fi
	;;
watch) do_watch "${1:-}" ;;
charge-scan) do_charge_scan ;;
thermal-scan) do_thermal_scan ;;
signal) do_signal "${1:-60}" ;;
log)
	[ $# -ge 5 ] || die "log: usage: log power|heat CSV INTERVAL_S DURATION_S PIDFILE"
	do_log "$1" "$2" "$3" "$4" "$5"
	;;
*) die "unknown command '$cmd' (see the header of this file)" ;;
esac
