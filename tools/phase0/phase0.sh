#!/usr/bin/env bash
# Phase 0 feasibility driver for the outdoor hotspot phone (docs/plan.md section 12).
# Procedure and pass conditions: docs/phase0.md. Runs on macOS's bash 3.2 and on Linux bash.
#
# Everything that runs on the phone lives in device/hsm-phase0-device.sh (mksh/toybox only).
# Results go to tools/phase0/results/<timestamp>-<model>/ (git-ignored). Later runs for the
# same phone (matched by ro.serialno) append to its newest directory, so `profile` and `report`
# can combine results gathered over several days.

set -eu

PHASE0_VERSION=1.0.0
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
DEVICE_HELPER=$SCRIPT_DIR/device/hsm-phase0-device.sh
RESULTS_ROOT=${PHASE0_RESULTS_ROOT:-$SCRIPT_DIR/results}
ADB=${ADB:-adb}
FASTBOOT=${FASTBOOT:-fastboot}

DEV_DIR=/data/local/tmp/hsm-phase0
DEV_HELPER=$DEV_DIR/hsm-phase0-device.sh
AGENT_PKG=com.hotspotmonitor.agent

# The Phase 0 table of docs/plan.md section 12, in order. Must-have: yes / most.
PHASE0_ROWS="Bootloader:yes
Root:yes
Automatic power-on:yes
Tethering after reboot:yes
Power budget:yes
Charge control:yes
Control commands:most
Signal readings:yes
Heat:yes"

RESULTS_DIR=""
NEW_SESSION=0
HAVE_ROOT=""
HELPER_PUSHED=0
RESTORES=""
DEV_ID=""
DEV_MANUF=""
DEV_MODEL=""
DEV_SDK=""
DEV_TRANSPORT=usb

# --- output -------------------------------------------------------------------------------

say() { printf '%s\n' "$*"; }
note() { printf '  %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() {
	printf 'ERROR: %s\n' "$1" >&2
	exit "${2:-1}"
}
section() { printf '\n== %s ==\n' "$*"; }
field() { printf '  %-22s %s\n' "$1" "$2"; }

usage() {
	cat <<'EOF'
Usage: phase0.sh [-s SERIAL] [--results DIR] [--new] <command> [options]

Phase 0 feasibility tests for the hotspot phone (docs/plan.md section 12, docs/phase0.md).
Needs adb (Android platform-tools), USB debugging, and root (Magisk or KernelSU) for most tests.
Nothing changes on the phone unless an option says so, and those options ask first.

Commands:
  info                    Model, Android / One UI, build, bootloader and OEM-unlock state,
                          Knox warranty bit, carrier, IMEI (last 4 digits only)
  root [--reboot]         Check su and the root manager. --reboot reboots to prove root survives
  commands [--live]       Check every control from plan 6.3 (help text only). --live toggles
                          each control and restores it, asking before anything that can drop adb
  signal [--seconds N] [--slot N]
                          Sample RSRP/RSRQ/SINR/band/PCI about once per second (default 60 s)
  charge-discover [--test PATH [--on V] [--off V] [--seconds N]]
                          List power supplies and known charge-control files. --test writes the
                          "off" value, watches charging for N s (default 20) and restores it
  current-sign [--seconds N]
                          Find the unit and sign of current_now (you unplug and re-plug once)
  thermal                 List thermal zones and suggest the profile's thermal keywords
  usb [--live [--function rndis|ncm]] [--reboot-test]
                          USB functions, tether interface and IP. --live switches USB tethering,
                          --reboot-test reboots and checks tethering comes back by itself
  autoboot [--check] [--fastboot] [--test] [--confirm pass|fail]
                          Explain and check automatic power-on. --fastboot sets
                          off-mode-charge 0 (non-Samsung), --test powers off and waits for a boot
  power-log start [--interval S] [--hours H] [--fresh] | status | stop | pull [--min-hours H]
                          On-phone battery logger (default every 60 s, until stopped) and the
                          power-budget verdict
  heat-log start [--interval S] [--hours H] [--fresh] | status | stop | pull
                          On-phone temperature logger and the 45 C verdict
  profile                 Draft a device profile JSON from the results so far (no phone needed)
  report                  Print the Phase 0 pass/fail table from the results so far (no phone needed)
  all [--seconds N]       Run the safe subset (info, root, commands, signal, charge-discover,
                          thermal, usb, autoboot --check, profile) and print the table

Global options:
  -s SERIAL               Phone to use (default: $ANDROID_SERIAL or the only connected device)
  --results DIR           Results directory (default: newest results/<timestamp>-<model>/ of
                          this phone, created on first use)
  --new                   Start a new results directory for this phone
  -h, --help              Show this help
  --version               Show the script version

Environment: ADB and FASTBOOT (tool paths), ANDROID_SERIAL.
Exit codes: 0 ok, 1 command failed, 2 usage error, 3 adb or phone not available.
EOF
}

# --- small helpers ------------------------------------------------------------------------

TAB=$(printf '\t')

oneline() { printf '%s' "$1" | tr '\t\r\n|' '   /'; }

# Single-quote a string for the phone's shell.
shq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

sanitize() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'; }

is_uint() {
	case "${1:-}" in
	'' | *[!0-9]*) return 1 ;;
	esac
	return 0
}

need_value() {
	if [ -z "${2:-}" ]; then die "Option $1 needs a value (see --help)." 2; fi
}

# Ask on the terminal. Returns 1 when declined or when there is no terminal.
confirm() {
	local ans=""
	if ! { : >/dev/tty; } 2>/dev/null; then
		warn "No terminal to confirm: skipped ($1)"
		return 1
	fi
	printf '%s [y/N] ' "$1" >/dev/tty
	read -r ans </dev/tty || ans=""
	case "$ans" in
	y | Y | yes | YES | Yes) return 0 ;;
	esac
	return 1
}

pause_enter() {
	if { : >/dev/tty; } 2>/dev/null; then
		printf '%s ' "$1" >/dev/tty
		read -r _ </dev/tty || true
	fi
}

have_tty() { { : >/dev/tty; } 2>/dev/null; }

# key=value files inside the results directory
kv_set() {
	local f="$RESULTS_DIR/$1" tmp
	tmp="$f.tmp.$$"
	{
		if [ -f "$f" ]; then awk -v k="$2" 'index($0, k "=") != 1' "$f"; fi
		printf '%s=%s\n' "$2" "$(oneline "$3")"
	} >"$tmp"
	mv "$tmp" "$f"
}

kv_get() {
	local f="$RESULTS_DIR/$1"
	if [ -f "$f" ]; then
		awk -v k="$2" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2) } END { if (v != "") print v }' "$f"
	fi
}

# Phase 0 table row: name, status (PASS FAIL PARTIAL MANUAL INFO), detail
record() {
	local f="$RESULTS_DIR/results.tsv" tmp
	tmp="$f.tmp.$$"
	{
		if [ -f "$f" ]; then awk -F '\t' -v k="$1" '$1 != k' "$f"; fi
		printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$(date +%Y-%m-%dT%H:%M:%S)" "$(oneline "$3")"
	} >"$tmp"
	mv "$tmp" "$f"
	printf '  -> Phase 0 "%s": %s  %s\n' "$1" "$2" "$3"
}

# --- restore stack (undo actions run on exit, Ctrl-C included) ----------------------------

push_restore() {
	RESTORES="$1
$RESTORES"
}

pop_restore() {
	RESTORES=$(printf '%s\n' "$RESTORES" | sed '1d')
}

run_restores() {
	trap '' PIPE
	local line
	if [ -z "$RESTORES" ]; then return 0; fi
	printf '%s\n' "$RESTORES" | while IFS= read -r line; do
		if [ -n "$line" ]; then
			printf 'Restoring: %s\n' "$line" >/dev/tty 2>/dev/null || true
			rsh "$line" >/dev/null 2>&1 || true
		fi
	done
	RESTORES=""
}

# --- adb ----------------------------------------------------------------------------------

_adb_shell() {
	local out rc=0
	out=$("$ADB" shell "$1" </dev/null 2>&1) || rc=$?
	if [ -n "$out" ]; then printf '%s\n' "$out" | tr -d '\r'; fi
	return "$rc"
}

# Run a command string in the phone's shell; prints output (stderr merged). Never fails:
# callers parse the output. *_rc variants return the remote exit status instead.
ash() { _adb_shell "$1" || true; }
ash_rc() { _adb_shell "$1"; }
rsh() { ash "su -c $(shq "$1")"; }
rsh_rc() { ash_rc "su -c $(shq "$1")"; }

root_ok() {
	if [ -z "$HAVE_ROOT" ]; then
		if ash "su -c id" | grep -q 'uid=0'; then HAVE_ROOT=yes; else HAVE_ROOT=no; fi
	fi
	[ "$HAVE_ROOT" = yes ]
}

# As root when su works, else as the shell user.
psh() {
	if root_ok; then rsh "$1"; else ash "$1"; fi
}

need_root() {
	if ! root_ok; then
		die "'$1' needs root: 'su -c id' did not return uid=0. Run 'phase0.sh root' for details." 1
	fi
}

is_network_serial() {
	case "$1" in
	*:* | *._adb-tls-* | *._adb._tcp*) return 0 ;;
	esac
	return 1
}

push_helper() {
	if [ "$HELPER_PUSHED" = 1 ]; then return 0; fi
	[ -f "$DEVICE_HELPER" ] || die "Missing $DEVICE_HELPER (incomplete checkout?)"
	ash "mkdir -p $DEV_DIR" >/dev/null
	"$ADB" push "$DEVICE_HELPER" "$DEV_HELPER" </dev/null >/dev/null 2>&1 || die "adb push to $DEV_HELPER failed"
	ash "chmod 0755 $DEV_HELPER" >/dev/null
	HELPER_PUSHED=1
}

# Run the on-phone helper (as root when available).
helper() {
	local args="" a
	for a in "$@"; do args="$args $(shq "$a")"; done
	push_helper
	psh "sh $DEV_HELPER$args"
}

require_device() {
	command -v "$ADB" >/dev/null 2>&1 ||
		die "adb not found. Install Android platform-tools and put adb on PATH, or set ADB=/path/to/adb." 3
	local list state n props serial
	list=$("$ADB" devices 2>/dev/null </dev/null | tr -d '\r' | awk 'NR > 1 && NF >= 2 { print $1, $2 }') || list=""
	if [ -n "${ANDROID_SERIAL:-}" ]; then
		serial=$ANDROID_SERIAL
		state=$(printf '%s\n' "$list" | awk -v s="$serial" '$1 == s { print $2; exit }')
		if [ -z "$state" ] && is_network_serial "$serial"; then
			"$ADB" connect "$serial" </dev/null >/dev/null 2>&1 || true
			state=$("$ADB" -s "$serial" get-state 2>/dev/null </dev/null | tr -d '\r') || state=""
		fi
		case "$state" in
		device) ;;
		unauthorized) die "Phone $serial is unauthorized: unlock it and accept 'Allow USB debugging' (tick 'Always allow')." 3 ;;
		offline) die "Phone $serial is offline: re-plug the cable or run 'adb kill-server'." 3 ;;
		'') die "Phone $serial not found. Connected: $(if [ -n "$list" ]; then printf '%s' "$list" | tr '\n' ' '; else printf '(none)'; fi)" 3 ;;
		*) die "Phone $serial is in state '$state'; it must be booted into Android ('device')." 3 ;;
		esac
	else
		n=$(printf '%s\n' "$list" | awk '$2 == "device" { c++ } END { print c + 0 }')
		if [ "$n" -eq 0 ]; then
			if printf '%s\n' "$list" | grep -q 'unauthorized'; then
				die "Phone is unauthorized: unlock it and accept 'Allow USB debugging' (tick 'Always allow')." 3
			fi
			die "No Android phone found. Connect it by USB, enable Developer options > USB debugging, accept the prompt on the phone, and check with 'adb devices'." 3
		fi
		if [ "$n" -gt 1 ]; then
			die "More than one phone connected; choose one with -s SERIAL: $(printf '%s\n' "$list" | awk '$2 == "device" { printf "%s ", $1 }')" 3
		fi
		serial=$(printf '%s\n' "$list" | awk '$2 == "device" { print $1; exit }')
	fi
	ANDROID_SERIAL=$serial
	export ANDROID_SERIAL
	if is_network_serial "$serial"; then DEV_TRANSPORT=network; else DEV_TRANSPORT=usb; fi
	props=$(ash 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.sdk; getprop sys.boot_completed; getprop ro.serialno')
	DEV_MANUF=$(printf '%s\n' "$props" | sed -n 1p)
	DEV_MODEL=$(printf '%s\n' "$props" | sed -n 2p)
	DEV_SDK=$(printf '%s\n' "$props" | sed -n 3p)
	if [ "$(printf '%s\n' "$props" | sed -n 4p)" != 1 ]; then
		warn "Android has not finished booting (sys.boot_completed is not 1); results may be incomplete."
	fi
	DEV_ID=$(printf '%s\n' "$props" | sed -n 5p)
	[ -n "$DEV_ID" ] || DEV_ID=$serial
	[ -n "$DEV_MODEL" ] || DEV_MODEL=unknown
}

boot_id() { ash 'cat /proc/sys/kernel/random/boot_id' | tr -d ' \n'; }

device_state() { "$ADB" get-state 2>/dev/null </dev/null | tr -d '\r\n' || true; }

wait_device_gone() {
	local waited=0
	while [ "$waited" -lt "$1" ]; do
		if [ "$(device_state)" != device ]; then return 0; fi
		sleep 2
		waited=$((waited + 2))
	done
	return 1
}

# Wait until the phone is back with sys.boot_completed=1.
wait_boot() {
	local limit=$1 waited=0
	while [ "$waited" -lt "$limit" ]; do
		if [ "$DEV_TRANSPORT" = network ]; then "$ADB" connect "$ANDROID_SERIAL" </dev/null >/dev/null 2>&1 || true; fi
		if [ "$(device_state)" = device ] && [ "$(ash 'getprop sys.boot_completed' | tr -d ' \n')" = 1 ]; then
			HELPER_PUSHED=0
			return 0
		fi
		sleep 3
		waited=$((waited + 3))
		if [ $((waited % 30)) -eq 0 ]; then say "  ... still waiting ($waited s)"; fi
	done
	return 1
}

# --- results directory --------------------------------------------------------------------

ensure_results_dir() {
	local d latest=""
	if [ -n "$RESULTS_DIR" ]; then
		mkdir -p "$RESULTS_DIR"
		[ -f "$RESULTS_DIR/.serial" ] || printf '%s\n' "$DEV_ID" >"$RESULTS_DIR/.serial"
		return 0
	fi
	if [ "$NEW_SESSION" != 1 ] && [ -d "$RESULTS_ROOT" ]; then
		for d in "$RESULTS_ROOT"/*/; do
			[ -d "$d" ] || continue
			if [ -f "$d.serial" ] && [ "$(sed -n 1p "$d.serial")" = "$DEV_ID" ]; then latest=${d%/}; fi
		done
	fi
	if [ -z "$latest" ]; then
		latest="$RESULTS_ROOT/$(date +%Y%m%d-%H%M%S)-$(sanitize "$DEV_MODEL")"
		mkdir -p "$latest"
		printf '%s\n' "$DEV_ID" >"$latest/.serial"
	fi
	RESULTS_DIR=$latest
}

# For commands that work without a phone: --results, else the newest directory of any phone.
offline_results_dir() {
	local d latest=""
	if [ -n "$RESULTS_DIR" ]; then
		[ -d "$RESULTS_DIR" ] || die "Results directory $RESULTS_DIR does not exist." 2
		return 0
	fi
	if [ -d "$RESULTS_ROOT" ]; then
		for d in "$RESULTS_ROOT"/*/; do
			if [ -d "$d" ]; then latest=${d%/}; fi
		done
	fi
	[ -n "$latest" ] || die "No results yet in $RESULTS_ROOT. Run a device command (for example 'phase0.sh info') first." 1
	RESULTS_DIR=$latest
}

# --- awk libraries ------------------------------------------------------------------------

# Thermal zone helpers shared by `thermal` and `heat-log pull`.
AWK_THERMAL_LIB='
function tz_c(raw,   v) {
  if (raw !~ /^-?[0-9]+$/) return ""
  v = raw + 0
  if (v >= 1000 || v <= -1000) v = v / 1000
  else if (v > 150 || v < -40) v = v / 10
  if (v < -30 || v > 150) return ""
  return v
}
# Qualcomm BCL zones (soc, vbat, ibat, ...) report charge level, voltage or current, not heat.
function tz_cat(type,   t) {
  t = tolower(type)
  if (t == "soc" || t ~ /bcl|vbat|ibat|vph|socd|ocp|lvl[0-9]/) return "bcl"
  if (t ~ /batt|bms/) return "battery"
  if (t ~ /skin|xo_therm|xo-therm|quiet|msm_therm|msm-therm|ap_therm|ap-therm|mtktsap|neutral/) return "skin"
  if (t ~ /modem|mdm|pa_therm|pa-therm|mmw|btsmdpa/) return "modem"
  if (t ~ /cpu|tsens|big|mid|little|apc[0-9]|cluster/) return "cpu"
  if (t ~ /gpu|g3d/) return "gpu"
  return "other"
}
function tz_kw(type, cat,   t, i, n, k) {
  t = tolower(type)
  n = split(KW[cat], k, " ")
  for (i = 1; i <= n; i++) if (index(t, k[i]) > 0) return k[i]
  return ""
}
BEGIN {
  KW["cpu"] = "cpu tsens big mid little apc cluster"
  KW["modem"] = "modem mdm pa_therm pa-therm mmw btsmdpa"
  KW["skin"] = "skin xo_therm xo-therm quiet msm_therm msm-therm ap_therm ap-therm mtktsap neutral"
  KW["battery"] = "battery batt bms"
}
'

# Parse the raw telephony.registry samples of `signal` into CSV rows.
AWK_SIGNAL_PARSE='
function kv(s, key,   v) {
  s = " " s
  if (match(s, "[^A-Za-z0-9_]" key " *= *-?[0-9]+")) {
    v = substr(s, RSTART, RLENGTH); sub(/^[^=]*= */, "", v); return v
  }
  return ""
}
function band_of(s, key,   v) {
  s = " " s
  if (match(s, "[^A-Za-z0-9_]" key " *= *[[][0-9]+")) {
    v = substr(s, RSTART, RLENGTH); sub(/^.*[[]/, "", v); return v
  }
  return ""
}
# Returns the value unchanged (as text, so 36-bit cell IDs never print in exponent form), or ""
# for the Android "unavailable" markers and out-of-range readings.
function valid(v, lo, hi,   n) {
  if (v == "") return ""
  n = v + 0
  if (n == 2147483647 || n == -2147483648 || n < lo || n > hi) return ""
  return v
}
# LTE RSSNR is in dB on current Android; some builds still report tenths of a dB.
function sinr_fix(v) {
  if (v == "") return ""
  v = v + 0
  if (v == 2147483647 || v == -2147483648) return ""
  if (v > 50 || v < -30) { v = v / 10; TENTHS = 1 }
  if (v > 50 || v < -30) return ""
  return v
}
function lte_band(e,   i) {
  if (e == "") return ""
  for (i = 1; i <= NB; i++) if (e + 0 >= BLO[i] && e + 0 <= BHI[i]) return BN[i]
  return ""
}
function clamp01(v) { return v < 0 ? 0 : (v > 1 ? 1 : v) }
function score(rsrp, sinr, rsrq,   s) {
  if (rsrp == "") return ""
  s = 100 * (0.45 * (sinr == "" ? 0 : clamp01((sinr + 5) / 30)) + 0.35 * clamp01((rsrp + 120) / 40) + 0.20 * (rsrq == "" ? 0 : clamp01((rsrq + 20) / 12)))
  return int(s * 10 + 0.5) / 10
}
function parse_slot(s,   sig, cell, ss, rest, elem, typ, lte, nr, p, q, a, m, rat, nsa, got_lte, got_nr, other,
                    c_rsrp, c_rsrq, c_sinr, c_rssi, c_ta, s_rsrp, s_rsrq, s_sinr, s_rssi, s_ta,
                    n_rsrp, n_rsrq, n_sinr, sn_rsrp, sn_rsrq, sn_sinr, b) {
  O_TYPE = O_BAND = O_EARFCN = O_PCI = O_CI = O_TAC = O_RSRP = O_RSRQ = O_SINR = O_RSSI = O_TA = ""
  O_NBAND = O_NARFCN = O_NPCI = O_NRSRP = O_NRSRQ = O_NSINR = O_BANDSRC = ""
  L_PCI = L_CI = L_TAC = L_EARFCN = L_BAND = L_BANDSRC = ""
  N_PCI = N_ARFCN = N_BAND = N_NCI = N_TAC = ""
  sig = SIG[s]; cell = CELL[s]; ss = SS[s]
  got_lte = 0; got_nr = 0; other = ""
  c_rsrp = c_rsrq = c_sinr = c_rssi = c_ta = n_rsrp = n_rsrq = n_sinr = ""
  rest = cell
  while (match(rest, "CellInfo(Lte|Nr|Wcdma|Gsm|Tdscdma|Cdma):[{]")) {
    typ = substr(rest, RSTART + 8, RLENGTH - 10)
    rest = substr(rest, RSTART + RLENGTH)
    elem = rest
    if (match(elem, "CellInfo(Lte|Nr|Wcdma|Gsm|Tdscdma|Cdma):[{]")) elem = substr(elem, 1, RSTART - 1)
    if (elem !~ /mRegistered=YES/) continue
    if (typ == "Lte" && !got_lte) {
      got_lte = 1
      L_PCI = valid(kv(elem, "mPci"), 0, 503); L_CI = valid(kv(elem, "mCi"), 0, 268435455)
      L_TAC = valid(kv(elem, "mTac"), 0, 65535); L_EARFCN = valid(kv(elem, "mEarfcn"), 0, 262143)
      b = band_of(elem, "mBands")
      if (b != "") { L_BAND = b; L_BANDSRC = "reported" } else { L_BAND = lte_band(L_EARFCN); L_BANDSRC = (L_BAND == "" ? "" : "earfcn") }
      c_rsrp = valid(kv(elem, "rsrp"), -156, -31); c_rsrq = valid(kv(elem, "rsrq"), -43, 20)
      c_sinr = sinr_fix(kv(elem, "rssnr")); c_rssi = valid(kv(elem, "rssi"), -140, -20); c_ta = valid(kv(elem, "ta"), 0, 1282)
    } else if (typ == "Nr" && !got_nr) {
      got_nr = 1
      N_PCI = valid(kv(elem, "mPci"), 0, 1007); N_ARFCN = valid(kv(elem, "mNrArfcn"), 0, 3279165)
      N_BAND = band_of(elem, "mBands"); N_NCI = valid(kv(elem, "mNci"), 0, 68719476735)
      N_TAC = valid(kv(elem, "mTac"), 0, 16777215)
      n_rsrp = valid(kv(elem, "ssRsrp"), -156, -31); n_rsrq = valid(kv(elem, "ssRsrq"), -43, 20); n_sinr = valid(kv(elem, "ssSinr"), -23, 40)
    } else if (other == "" && (typ == "Wcdma" || typ == "Gsm" || typ == "Tdscdma" || typ == "Cdma")) {
      other = toupper(typ)
    }
  }
  s_rsrp = s_rsrq = s_sinr = s_rssi = s_ta = sn_rsrp = sn_rsrq = sn_sinr = ""
  p = index(sig, "mLte=")
  if (p > 0) {
    lte = substr(sig, p); q = index(lte, ",mNr="); if (q > 0) lte = substr(lte, 1, q - 1)
    s_rsrp = valid(kv(lte, "rsrp"), -156, -31); s_rsrq = valid(kv(lte, "rsrq"), -43, 20)
    s_sinr = sinr_fix(kv(lte, "rssnr")); s_rssi = valid(kv(lte, "rssi"), -140, -20); s_ta = valid(kv(lte, "ta"), 0, 1282)
    p = index(sig, "mNr=")
    if (p > 0) {
      nr = substr(sig, p); q = index(nr, ",primary="); if (q > 0) nr = substr(nr, 1, q - 1)
      sn_rsrp = valid(kv(nr, "ssRsrp"), -156, -31); sn_rsrq = valid(kv(nr, "ssRsrq"), -43, 20); sn_sinr = valid(kv(nr, "ssSinr"), -23, 40)
    }
  } else if (match(sig, "SignalStrength: *-?[0-9]")) {
    # Android 9 and older: positional fields, LTE RSRP/RSRQ/RSSNR are fields 9-11 (RSSNR in 0.1 dB).
    m = split(substr(sig, index(sig, "SignalStrength:") + 15), a, " ")
    if (m >= 11) {
      s_rsrp = valid(a[9], -156, -31); s_rsrq = valid(a[10], -43, 20)
      if (a[11] ~ /^-?[0-9]+$/ && a[11] + 0 != 2147483647) s_sinr = valid(a[11] / 10, -30, 50)
    }
  }
  rat = ""
  if (match(ss, "getRilDataRadioTechnology=[0-9]+[(][A-Za-z0-9_-]+[)]")) {
    rat = substr(ss, RSTART, RLENGTH); sub(/^[^(]*[(]/, "", rat); sub(/[)]$/, "", rat)
  }
  nsa = (ss ~ /[nN]rState *= *CONNECTED/)
  if (rat == "NR") O_TYPE = "NR_SA"
  else if (rat ~ /^LTE/) O_TYPE = (nsa || got_nr) ? "NR_NSA" : "LTE"
  else if (rat ~ /^(UMTS|HSDPA|HSUPA|HSPA|HSPAP)$/) O_TYPE = "WCDMA"
  else if (rat ~ /^(GPRS|EDGE|GSM)$/) O_TYPE = "GSM"
  else if (rat ~ /TD_SCDMA/) O_TYPE = "TDSCDMA"
  else if (got_nr && !got_lte) O_TYPE = "NR_SA"
  else if (got_lte) O_TYPE = (got_nr || nsa) ? "NR_NSA" : "LTE"
  else if (other != "") O_TYPE = other
  else O_TYPE = "UNKNOWN"
  O_NBAND = N_BAND; O_NARFCN = N_ARFCN; O_NPCI = N_PCI
  O_NRSRP = (sn_rsrp != "" ? sn_rsrp : n_rsrp); O_NRSRQ = (sn_rsrq != "" ? sn_rsrq : n_rsrq); O_NSINR = (sn_sinr != "" ? sn_sinr : n_sinr)
  if (O_TYPE == "NR_SA") {
    O_BAND = N_BAND; O_EARFCN = N_ARFCN; O_PCI = N_PCI; O_CI = N_NCI; O_TAC = N_TAC
    O_RSRP = O_NRSRP; O_RSRQ = O_NRSRQ; O_SINR = O_NSINR; O_BANDSRC = (N_BAND == "" ? "" : "reported")
  } else {
    O_BAND = L_BAND; O_EARFCN = L_EARFCN; O_PCI = L_PCI; O_CI = L_CI; O_TAC = L_TAC; O_BANDSRC = L_BANDSRC
    O_RSRP = (s_rsrp != "" ? s_rsrp : c_rsrp); O_RSRQ = (s_rsrq != "" ? s_rsrq : c_rsrq); O_SINR = (s_sinr != "" ? s_sinr : c_sinr)
    O_RSSI = (s_rssi != "" ? s_rssi : c_rssi); O_TA = (s_ta != "" ? s_ta : c_ta)
  }
  return (O_RSRP != "")
}
function flush(   s, chosen) {
  if (!have) return
  have = 0
  chosen = -1
  if (want >= 0) chosen = want
  else {
    for (s = 0; s < 8; s++) if ((s in SLOTS) && parse_slot(s)) { chosen = s; break }
    if (chosen < 0) chosen = 0
  }
  parse_slot(chosen)
  printf "%s,%.2f,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n", ts, up / 100, chosen, O_TYPE, O_BAND, O_EARFCN, O_PCI, O_CI, O_TAC, O_RSRP, O_RSRQ, O_SINR, O_RSSI, O_TA, O_NBAND, O_NARFCN, O_NPCI, O_NRSRP, O_NRSRQ, O_NSINR, score(O_RSRP, O_SINR, O_RSRQ), O_BANDSRC
  split("", SIG); split("", CELL); split("", SS); split("", SLOTS)
}
BEGIN {
  want = (slot == "" ? -1 : slot + 0)
  n = split("0:599:1 600:1199:2 1200:1949:3 1950:2399:4 2400:2649:5 2650:2749:6 2750:3449:7 3450:3799:8 3800:4149:9 4150:4749:10 4750:4949:11 5010:5179:12 5180:5279:13 5280:5379:14 5730:5849:17 5850:5999:18 6000:6149:19 6150:6449:20 6450:6599:21 6600:7399:22 7500:7699:23 7700:8039:24 8040:8689:25 8690:9039:26 9040:9209:27 9210:9659:28 9660:9769:29 9770:9869:30 9870:9919:31 9920:10359:32 36000:36199:33 36200:36349:34 36350:36949:35 36950:37549:36 37550:37749:37 37750:38249:38 38250:38649:39 38650:39649:40 39650:41589:41 41590:43589:42 43590:45589:43 45590:46589:44 46590:46789:45 46790:54539:46 54540:55239:47 55240:56739:48 56740:58239:49 58240:59089:50 59090:59139:51 59140:60139:52 60140:60254:53 65536:66435:65 66436:67335:66 67336:67535:67 67536:67835:68 67836:68335:69 68336:68585:70 68586:68935:71 68936:68985:72 68986:69035:73 69036:69465:74 69466:70315:75 70316:70365:76 70366:70545:85", T, " ")
  for (i = 1; i <= n; i++) { split(T[i], P, ":"); BLO[i] = P[1] + 0; BHI[i] = P[2] + 0; BN[i] = P[3] }
  NB = n
  cur = 0; have = 0
  print "epoch,uptime_s,slot,net_type,band,earfcn,pci,cell_id,tac,rsrp,rsrq,sinr,rssi,ta,nr_band,nr_arfcn,nr_pci,nr_ss_rsrp,nr_ss_rsrq,nr_ss_sinr,score,band_source"
}
/^##HSM ts=/ {
  flush()
  ts = $0; sub(/^##HSM ts=/, "", ts); sub(/ .*/, "", ts)
  up = $0; sub(/.* up=/, "", up); up = up + 0
  have = 1; cur = 0
  next
}
/^##HSM end/ { flush(); next }
/Phone Id=/ { x = $0; sub(/.*Phone Id=/, "", x); cur = x + 0; next }
/mSignalStrength=/ { SIG[cur] = $0; SLOTS[cur] = 1; next }
/mCellInfo=/ { CELL[cur] = $0; SLOTS[cur] = 1; next }
/mServiceState=/ { SS[cur] = $0; next }
END { flush(); if (TENTHS) print "#note: this phone reports LTE RSSNR in tenths of a dB; values were divided by 10" > "/dev/stderr" }
'

AWK_SIGNAL_SUMMARY='
BEGIN { FS = "," }
NR == 1 { next }
{
  n++
  up = $2 + 0; if (n == 1) first = up; last = up
  if ($10 != "") { c_rsrp++; sr += $10; if (mn_r == "" || $10 + 0 < mn_r) mn_r = $10 + 0; if (mx_r == "" || $10 + 0 > mx_r) mx_r = $10 + 0 }
  if ($11 != "") { c_rsrq++; sq += $11 }
  if ($12 != "") { c_sinr++; ss += $12; if (mn_s == "" || $12 + 0 < mn_s) mn_s = $12 + 0; if (mx_s == "" || $12 + 0 > mx_s) mx_s = $12 + 0 }
  if ($5 != "") { c_band++; if (!($5 in BANDS)) { BANDS[$5] = 1; bandlist = bandlist (bandlist == "" ? "" : " ") $5 } }
  if ($7 != "") { c_pci++; if (!($7 in PCIS)) { PCIS[$7] = 1; pcilist = pcilist (pcilist == "" ? "" : " ") $7 } }
  if ($21 != "") { c_sc++; sc += $21 }
  if ($4 != "" && !($4 in TYPES)) { TYPES[$4] = 1; typelist = typelist (typelist == "" ? "" : " ") $4 }
  if ($22 == "earfcn") derived = 1
  key = $10 "/" $11 "/" $12
  if (n > 1 && key != prev) changes++
  prev = key
  cellk = $7 "/" $8
  if (cellk != "/") { if (pcell != "" && cellk != pcell) switches++; pcell = cellk }
}
function pct(c) { return n ? int(100 * c / n + 0.5) : 0 }
END {
  if (n == 0) { print "No samples were parsed."; print "@@verdict\tFAIL\tno samples parsed (is telephony.registry readable?)"; exit }
  dur = last - first
  rate = (dur > 0 ? (n - 1) / dur : 0)
  upd = (dur > 0 ? changes / dur : 0)
  printf "  Samples              %d over %.1f s = %.2f samples/s\n", n, dur, rate
  printf "  Network types        %s\n", (typelist == "" ? "-" : typelist)
  printf "  RSRP                 %3d%% of samples", pct(c_rsrp); if (c_rsrp) printf ", avg %.1f dBm (min %d, max %d)", sr / c_rsrp, mn_r, mx_r; printf "\n"
  printf "  RSRQ                 %3d%% of samples", pct(c_rsrq); if (c_rsrq) printf ", avg %.1f dB", sq / c_rsrq; printf "\n"
  printf "  SINR                 %3d%% of samples", pct(c_sinr); if (c_sinr) printf ", avg %.1f dB (min %s, max %s)", ss / c_sinr, mn_s, mx_s; printf "\n"
  printf "  Band                 %3d%% of samples: %s%s\n", pct(c_band), (bandlist == "" ? "-" : bandlist), (derived ? " (derived from EARFCN)" : "")
  printf "  PCI                  %3d%% of samples: %s\n", pct(c_pci), (pcilist == "" ? "-" : pcilist)
  printf "  Score (plan 7.2)     %s\n", (c_sc ? sprintf("avg %.1f", sc / c_sc) : "-")
  printf "  Serving-cell changes %d\n", switches
  printf "  New readings         %.2f per second (how often RSRP/RSRQ/SINR changed)\n", upd
  miss = ""
  if (pct(c_rsrp) < 80) miss = miss " RSRP"
  if (pct(c_rsrq) < 80) miss = miss " RSRQ"
  if (pct(c_sinr) < 80) miss = miss " SINR"
  if (pct(c_band) < 80) miss = miss " band"
  if (pct(c_pci) < 80) miss = miss " PCI"
  detail = sprintf("%.2f samples/s, RSRP %d%%, RSRQ %d%%, SINR %d%%, band %d%%, PCI %d%%", rate, pct(c_rsrp), pct(c_rsrq), pct(c_sinr), pct(c_band), pct(c_pci))
  if (n < 10) printf "@@verdict\tPARTIAL\tonly %d samples; run longer. %s\n", n, detail
  else if (miss != "") printf "@@verdict\tFAIL\tmissing in >20%% of samples:%s. %s\n", miss, detail
  else if (rate < 0.8) printf "@@verdict\tPARTIAL\treadable but below 1 sample/s. %s\n", detail
  else printf "@@verdict\tPASS\t%s\n", detail
}
'

# --- info ---------------------------------------------------------------------------------

INFO_PROPS="ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.product.name
ro.build.version.release ro.build.version.sdk ro.build.id ro.build.display.id ro.build.version.incremental
ro.build.version.security_patch ro.build.fingerprint ro.build.type ro.bootloader ro.boot.flash.locked
ro.boot.verifiedbootstate ro.boot.vbmeta.device_state ro.boot.veritymode sys.oem_unlock_allowed
ro.oem_unlock_supported ro.boot.warranty_bit ro.warranty_bit ro.build.version.oneui ro.csc.sales_code
ro.hardware ro.soc.manufacturer ro.soc.model ro.product.cpu.abi gsm.sim.state gsm.sim.operator.alpha
gsm.sim.operator.numeric gsm.operator.alpha gsm.operator.numeric gsm.network.type gsm.operator.isroaming
sys.usb.config persist.sys.usb.config"

prop_in() { printf '%s\n' "$1" | awk -v k="$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }'; }

oneui_version() {
	local v=$1 major minor patch
	is_uint "$v" || return 0
	v=$((10#$v))
	major=$((v / 10000))
	minor=$(((v / 100) % 100))
	patch=$((v % 100))
	if [ "$patch" -gt 0 ]; then printf '%s.%s.%s' "$major" "$minor" "$patch"; else printf '%s.%s' "$major" "$minor"; fi
}

imei_tail() {
	local raw digits args
	root_ok || return 0
	for args in "1 s16 com.android.shell" "1"; do
		raw=$(rsh "service call iphonesubinfo $args")
		digits=$(printf '%s\n' "$raw" | awk -F "'" 'NF >= 3 { s = s $2 } END { gsub(/[^0-9]/, "", s); print s }')
		if [ "${#digits}" -eq 15 ]; then
			printf '%s' "${digits#"${digits%????}"}"
			return 0
		fi
	done
	return 0
}

cmd_info() {
	section "Device information"
	local script out manuf brand model device sdk rel oneui_raw oneui locked vbs vbstate allowed supported
	local bl_state wbit kg sim imei detail status major
	script="for p in $(printf '%s' "$INFO_PROPS" | tr '\n' ' '); do printf '%s=%s\\n' \"\$p\" \"\$(getprop \$p)\"; done"
	out=$(ash "$script")
	printf '%s\n' "$out" >"$RESULTS_DIR/info-props.txt"
	manuf=$(prop_in "$out" ro.product.manufacturer)
	brand=$(prop_in "$out" ro.product.brand)
	model=$(prop_in "$out" ro.product.model)
	device=$(prop_in "$out" ro.product.device)
	rel=$(prop_in "$out" ro.build.version.release)
	sdk=$(prop_in "$out" ro.build.version.sdk)
	oneui_raw=$(prop_in "$out" ro.build.version.oneui)
	oneui=$(oneui_version "$oneui_raw")
	locked=$(prop_in "$out" ro.boot.flash.locked)
	vbs=$(prop_in "$out" ro.boot.verifiedbootstate)
	vbstate=$(prop_in "$out" ro.boot.vbmeta.device_state)
	allowed=$(prop_in "$out" sys.oem_unlock_allowed)
	supported=$(prop_in "$out" ro.oem_unlock_supported)
	wbit=$(prop_in "$out" ro.boot.warranty_bit)
	[ -n "$wbit" ] || wbit=$(prop_in "$out" ro.warranty_bit)

	field "Manufacturer / model" "$manuf $model ($device, brand $brand)"
	field "Android" "$rel (SDK $sdk), build $(prop_in "$out" ro.build.display.id), patch $(prop_in "$out" ro.build.version.security_patch)"
	field "Build type" "$(prop_in "$out" ro.build.type)"
	field "SoC / hardware" "$(prop_in "$out" ro.soc.manufacturer) $(prop_in "$out" ro.soc.model) $(prop_in "$out" ro.hardware)"
	if [ -n "$oneui" ]; then field "One UI" "$oneui"; fi
	field "Bootloader version" "$(prop_in "$out" ro.bootloader)"

	if [ "$locked" = 0 ] || [ "$vbstate" = unlocked ] || [ "$vbs" = orange ]; then
		bl_state=unlocked
	elif [ "$locked" = 1 ] || [ "$vbstate" = locked ] || [ "$vbs" = green ] || [ "$vbs" = yellow ]; then
		bl_state=locked
	else
		bl_state=unknown
	fi
	field "Bootloader state" "$bl_state (flash.locked=${locked:--}, verifiedbootstate=${vbs:--}, vbmeta=${vbstate:--})"
	field "OEM unlocking" "switch on=${allowed:--} (sys.oem_unlock_allowed), supported=${supported:--}"
	if [ -n "$wbit" ]; then
		if [ "$wbit" = 1 ]; then field "Knox warranty bit" "1 (tripped; expected after unlocking)"; else field "Knox warranty bit" "$wbit"; fi
	fi

	status=MANUAL
	detail="state unknown: check Developer options > OEM unlocking"
	if [ "$bl_state" = unlocked ]; then
		status=PASS
		detail="bootloader unlocked (verifiedbootstate=${vbs:--})"
	else
		if [ "$bl_state" = locked ]; then
			if [ "$allowed" = 1 ]; then
				status=PARTIAL
				detail="OEM unlocking is switched on but the bootloader is still locked: unlock it next"
			else
				detail="locked and OEM unlocking is off or hidden: switch it on in Developer options (Samsung: connect to the internet, the switch may take up to 7 days to appear)"
			fi
		fi
		case "$(printf '%s' "$manuf" | tr 'A-Z' 'a-z')" in
		samsung)
			case "$model" in
			*U | *U1 | *W)
				status=FAIL
				detail="$model is a US/Canada model: its bootloader cannot be unlocked (plan Appendix A.1)"
				;;
			esac
			if [ -n "$oneui" ]; then
				major=${oneui%%.*}
				if [ "$major" -ge 8 ]; then
					status=FAIL
					detail="One UI $oneui removed bootloader unlocking (plan Appendix A.1)"
				fi
			fi
			;;
		esac
	fi
	if [ "$(printf '%s' "$manuf" | tr 'A-Z' 'a-z')" = samsung ]; then
		kg=$(ash "pm list packages com.samsung.android.kgclient" | grep -c 'kgclient' || true)
		if [ "$kg" -gt 0 ]; then
			field "Knox Guard client" "installed (normal on many models; a financed phone shows a lock or payment notice)"
		fi
		note "Samsung: also confirm the model does not end in U, U1 or W, and the software is One UI 7 or older."
	fi

	sim="$(prop_in "$out" gsm.sim.state), $(prop_in "$out" gsm.sim.operator.alpha) ($(prop_in "$out" gsm.sim.operator.numeric)), network $(prop_in "$out" gsm.network.type), roaming $(prop_in "$out" gsm.operator.isroaming)"
	field "SIM / carrier" "$sim"
	imei=$(imei_tail)
	if [ -n "$imei" ]; then field "IMEI" "...$imei (last 4 digits only)"; else field "IMEI" "not read (needs root; never stored in full)"; fi
	note "Check by hand: IMEI not blacklisted, no FRP / carrier / finance lock, no water damage (plan 4.6)."

	kv_set info.env manufacturer "$manuf"
	kv_set info.env brand "$brand"
	kv_set info.env model "$model"
	kv_set info.env device "$device"
	kv_set info.env android "$rel"
	kv_set info.env sdk "$sdk"
	kv_set info.env build_id "$(prop_in "$out" ro.build.id)"
	kv_set info.env oneui "$oneui"
	kv_set info.env bootloader_state "$bl_state"
	kv_set info.env imei_tail "$imei"
	kv_set info.env soc "$(prop_in "$out" ro.soc.manufacturer) $(prop_in "$out" ro.soc.model)"
	record "Bootloader" "$status" "$detail"
}

# --- root ---------------------------------------------------------------------------------

root_impl() {
	local v
	v=$(rsh 'command -v magisk >/dev/null 2>&1 && magisk -v' | head -n 1)
	if [ -n "$v" ] && ! printf '%s' "$v" | grep -qi 'not found'; then
		printf 'Magisk %s (%s)' "$v" "$(rsh 'magisk -V' | head -n 1)"
		return 0
	fi
	if [ -n "$(rsh '[ -e /data/adb/ksud ] && echo yes')" ]; then
		printf 'KernelSU %s' "$(rsh '/data/adb/ksud -V 2>/dev/null' | head -n 1)"
		return 0
	fi
	if [ -n "$(rsh '[ -e /data/adb/apd ] && echo yes')" ]; then
		printf 'APatch'
		return 0
	fi
	printf 'su %s' "$(rsh 'su -v' | head -n 1)"
}

cmd_root() {
	local do_reboot=0 id_out impl bid prev_bid first_api
	while [ $# -gt 0 ]; do
		case "$1" in
		--reboot) do_reboot=1 ;;
		*) die "root: unknown option $1" 2 ;;
		esac
		shift
	done
	section "Root"
	id_out=$(ash "su -c id")
	if ! printf '%s' "$id_out" | grep -q 'uid=0'; then
		HAVE_ROOT=no
		field "su -c id" "$(oneline "$id_out")"
		case "$id_out" in
		*"not found"*) note "No su binary: the phone is not rooted. Root it with Magisk or KernelSU (docs/install.md)." ;;
		*"denied"* | *"Permission"*) note "su refused: grant root to 'Shell' in Magisk / KernelSU (Superuser list), then re-run." ;;
		*) note "If Magisk showed a prompt for 'Shell', tap Grant (Forever) and re-run." ;;
		esac
		record "Root" FAIL "su did not return uid=0"
		return 1
	fi
	HAVE_ROOT=yes
	impl=$(root_impl)
	bid=$(boot_id)
	field "su -c id" "$(oneline "$id_out")"
	field "Root manager" "$impl"
	field "Boot id" "$bid"
	if [ -n "$(rsh "[ -d /data/adb/modules/hsm-keepalive ] && echo yes")" ]; then
		field "hsm-keepalive module" "installed"
	else
		field "hsm-keepalive module" "not installed (tools/phase0/build-magisk-module.sh)"
	fi
	kv_set root.env impl "$impl"
	first_api=$(ash 'getprop ro.product.first_api_level' | tr -d ' \n')
	if [ "$(printf '%s' "$DEV_MANUF" | tr 'A-Z' 'a-z')" = samsung ] && is_uint "$first_api" && [ "$first_api" -le 28 ]; then
		warn "This Samsung launched with Android 9 or older (first API $first_api), e.g. the Galaxy S10: its boot image has no ramdisk, so Magisk sits in the recovery partition and root is only active when the phone boots through recovery. A cold power-on after a power cut would start WITHOUT root. 'root --reboot' (a normal reboot) shows whether this phone is affected."
	fi
	prev_bid=$(kv_get root.env ok_boot_id)

	if [ "$do_reboot" = 1 ]; then
		if confirm "Reboot the phone now to prove root survives? adb disconnects for 1-2 minutes."; then
			kv_set root.env ok_boot_id "$bid"
			ash "reboot" >/dev/null
			say "Rebooting; waiting up to 5 minutes for Android to come back..."
			wait_device_gone 60 || true
			if ! wait_boot 300; then
				record "Root" PARTIAL "works; the phone did not come back within 5 minutes after the reboot"
				return 1
			fi
			HAVE_ROOT=""
			prev_bid=$bid
			bid=$(boot_id)
			if ! root_ok; then
				record "Root" FAIL "root lost after reboot ($impl)"
				return 1
			fi
		fi
	fi
	if [ -n "$prev_bid" ] && [ "$prev_bid" != "$bid" ]; then
		kv_set root.env survived yes
		record "Root" PASS "$impl; survives a reboot (worked in two boots)"
	elif [ "$(kv_get root.env survived)" = yes ]; then
		record "Root" PASS "$impl; survives a reboot (shown in an earlier boot)"
	else
		record "Root" PARTIAL "$impl works; reboot survival not shown yet (run: phase0.sh root --reboot)"
	fi
	kv_set root.env ok_boot_id "$bid"
}

# --- commands (plan 6.3) -------------------------------------------------------------------

cmd_row() {
	local f="$RESULTS_DIR/commands.tsv" tmp
	tmp="$f.tmp.$$"
	{
		if [ -f "$f" ]; then awk -F '\t' -v k="$1" '$1 != k' "$f"; fi
		printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$(oneline "$3")" "$4" "$(oneline "$5")"
	} >"$tmp"
	mv "$tmp" "$f"
	printf '  %-24s %-12s %s%s\n' "$1" "$4" "$3" "${5:+  ($5)}"
}

help_has() { printf '%s\n' "$(psh "$1 2>&1")" | grep -Eq -- "$2"; }

commands_safe() {
	local f="$RESULTS_DIR/commands.tsv"
	: >"$f"
	say "  Control                  Status       Method"
	if help_has "svc data" 'enable'; then
		cmd_row "Mobile data" mobile_data "svc data enable|disable" supported ""
	else
		cmd_row "Mobile data" mobile_data "svc data enable|disable" unsupported "svc data has no enable/disable"
	fi
	if help_has "cmd connectivity help" 'airplane-mode'; then
		cmd_row "Airplane mode" airplane_mode "cmd connectivity airplane-mode enable|disable" supported ""
	elif root_ok; then
		cmd_row "Airplane mode" airplane_mode "settings put global airplane_mode_on + AIRPLANE_MODE broadcast (root)" supported "fallback: cmd connectivity has no airplane-mode"
	else
		cmd_row "Airplane mode" airplane_mode "cmd connectivity airplane-mode" unsupported "needs Android 11+ or root"
	fi
	if help_has "svc usb" 'setFunctions'; then
		cmd_row "USB tethering" usb_tethering "svc usb setFunctions rndis|ncm" supported ""
	else
		cmd_row "USB tethering" usb_tethering "svc usb setFunctions" unsupported "svc usb has no setFunctions"
	fi
	if help_has "cmd wifi help" 'start-softap'; then
		cmd_row "Wi-Fi hotspot" hotspot "cmd wifi start-softap <ssid> wpa2 <pass> / stop-softap" supported ""
	else
		cmd_row "Wi-Fi hotspot" hotspot "cmd wifi start-softap" unsupported "cmd wifi has no start-softap"
	fi
	if help_has "svc wifi" 'enable'; then
		cmd_row "Wi-Fi" wifi "svc wifi enable|disable" supported ""
	elif help_has "cmd wifi help" 'set-wifi-enabled'; then
		cmd_row "Wi-Fi" wifi "cmd wifi set-wifi-enabled enabled|disabled" supported "svc wifi missing"
	else
		cmd_row "Wi-Fi" wifi "svc wifi" unsupported ""
	fi
	if help_has "svc bluetooth" 'enable'; then
		cmd_row "Bluetooth" bluetooth "svc bluetooth enable|disable" supported ""
	elif help_has "cmd bluetooth_manager help" 'enable'; then
		cmd_row "Bluetooth" bluetooth "cmd bluetooth_manager enable|disable" supported "svc bluetooth missing"
	else
		cmd_row "Bluetooth" bluetooth "svc bluetooth" unsupported ""
	fi
	psh "cmd phone help 2>&1" | grep -iE -A 6 'allowed-network-types' >"$RESULTS_DIR/commands-network-mode-help.txt" || true
	if [ -s "$RESULTS_DIR/commands-network-mode-help.txt" ] && grep -q 'set-allowed-network-types-for-users' "$RESULTS_DIR/commands-network-mode-help.txt"; then
		cmd_row "Network mode" network_mode "cmd phone set-allowed-network-types-for-users" supported "syntax: commands-network-mode-help.txt"
	else
		cmd_row "Network mode" network_mode "cmd phone set-allowed-network-types-for-users" unsupported "device-dependent; needs a profile override"
	fi
	if help_has "svc power" 'reboot'; then
		cmd_row "Reboot" reboot "svc power reboot" supported ""
	else
		cmd_row "Reboot" reboot "reboot (root)" supported "svc power has no reboot; plain 'reboot' works as root"
	fi
	if help_has "input" 'keyevent'; then
		cmd_row "Screen sleep / wake" screen "input keyevent 223 / 224" supported ""
	else
		cmd_row "Screen sleep / wake" screen "input keyevent" unsupported ""
	fi
	if [ -n "$(psh 'settings get system screen_brightness' | grep -E '^[0-9]+$' || true)" ]; then
		cmd_row "Brightness" brightness "settings put system screen_brightness <0-255>" supported ""
	else
		cmd_row "Brightness" brightness "settings put system screen_brightness" unsupported ""
	fi
	if [ -f "$RESULTS_DIR/charge.tsv" ] && awk -F '\t' '$4 == "yes" && $5 == "yes"' "$RESULTS_DIR/charge.tsv" | grep -q .; then
		cmd_row "Charging on / off" charging "$(awk -F '\t' '$4 == "yes" && $5 == "yes" { print $1; exit }' "$RESULTS_DIR/charge.tsv")" supported "verified by charge-discover --test"
	else
		cmd_row "Charging on / off" charging "charge-control file" unknown "run: phase0.sh charge-discover --test <path>"
	fi
	if help_has "am help" 'force-stop'; then
		cmd_row "Apps: force stop" app_force_stop "am force-stop <pkg>" supported ""
	else
		cmd_row "Apps: force stop" app_force_stop "am force-stop" unsupported ""
	fi
	if help_has "pm help" 'disable-user'; then
		cmd_row "Apps: disable / enable" app_disable "pm disable-user --user 0 <pkg> / pm enable <pkg>" supported ""
	else
		cmd_row "Apps: disable / enable" app_disable "pm disable-user" unsupported ""
	fi
	if help_has "top -b -n 1 | head -n 12" 'PID'; then
		cmd_row "Process list" process_list "top -b -n 1" supported ""
	else
		cmd_row "Process list" process_list "top -n 1" unsupported ""
	fi
	cmd_row "Send SMS / USSD" send_sms "Android telephony API (Agent)" api "tested from the Dashboard in Phase 3"
	if root_ok; then
		cmd_row "Root shell" shell "su -c" supported ""
	else
		cmd_row "Root shell" shell "su -c" unsupported "no root"
	fi
}

data_connected() {
	ash "dumpsys telephony.registry" | grep -q 'mDataConnectionState=2'
}

wait_data() {
	local waited=0
	while [ "$waited" -lt "$1" ]; do
		if data_connected; then return 0; fi
		sleep 3
		waited=$((waited + 3))
	done
	return 1
}

link_warning() {
	# $1 = what can drop the adb link; asks only when it can
	if [ "$DEV_TRANSPORT" = network ]; then
		if confirm "$1 can cut adb over the network (this session uses $ANDROID_SERIAL). Continue?"; then
			return 0
		fi
		return 1
	fi
	return 0
}

live_mobile_data() {
	say "-- Mobile data"
	if ! data_connected; then
		cmd_row "Mobile data" mobile_data "svc data enable|disable" untested "no data connection before the test (SIM? APN?)"
		return 0
	fi
	link_warning "Turning mobile data off" || return 0
	push_restore "svc data enable"
	rsh "svc data disable" >/dev/null
	sleep 6
	if data_connected; then
		rsh "svc data enable" >/dev/null
		pop_restore
		cmd_row "Mobile data" mobile_data "svc data disable" failed "data stayed connected"
		return 0
	fi
	rsh "svc data enable" >/dev/null
	pop_restore
	if wait_data 90; then
		cmd_row "Mobile data" mobile_data "svc data enable|disable" ok "off and back on"
	else
		cmd_row "Mobile data" mobile_data "svc data enable|disable" failed "data did not reconnect within 90 s"
	fi
}

live_airplane() {
	local before on method_on method_off
	say "-- Airplane mode"
	before=$(rsh "settings get global airplane_mode_on" | tr -d ' \n')
	if [ "$before" = 1 ]; then
		cmd_row "Airplane mode" airplane_mode "-" untested "airplane mode was already on"
		return 0
	fi
	link_warning "Airplane mode" || return 0
	if help_has "cmd connectivity help" 'airplane-mode'; then
		method_on="cmd connectivity airplane-mode enable"
		method_off="cmd connectivity airplane-mode disable"
	else
		method_on="settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
		method_off="settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
	fi
	push_restore "$method_off"
	rsh "$method_on" >/dev/null
	sleep 5
	on=$(rsh "settings get global airplane_mode_on" | tr -d ' \n')
	rsh "$method_off" >/dev/null
	pop_restore
	if [ "$on" != 1 ]; then
		cmd_row "Airplane mode" airplane_mode "$method_on" failed "airplane_mode_on stayed $on"
		return 0
	fi
	if wait_data 120; then
		cmd_row "Airplane mode" airplane_mode "$method_on" ok "on and off; data came back"
	else
		cmd_row "Airplane mode" airplane_mode "$method_on" failed "data did not come back within 120 s"
	fi
}

live_hotspot() {
	local pass out state
	say "-- Wi-Fi hotspot"
	if ! help_has "cmd wifi help" 'start-softap'; then
		cmd_row "Wi-Fi hotspot" hotspot "cmd wifi start-softap" unsupported ""
		return 0
	fi
	link_warning "Starting the hotspot (it can switch Wi-Fi client mode off)" || return 0
	pass=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 14 || true)
	[ "${#pass}" -ge 8 ] || pass="hsmPhase0test$$"
	push_restore "cmd wifi stop-softap"
	out=$(rsh "cmd wifi start-softap HSM-Phase0-Test wpa2 $pass")
	sleep 8
	state=$(rsh "dumpsys wifi" | grep -iE 'WIFI_AP_STATE_ENABLED|SoftApState.*ENABLED|mApState=13|softap.*state.*enabled' | head -n 3 || true)
	if [ -z "$state" ]; then
		state=$(rsh "ip -o link show" | grep -E ' (ap|swlan|softap|wlan1)[0-9]*[:@].*UP' | head -n 1 || true)
	fi
	rsh "cmd wifi stop-softap" >/dev/null
	pop_restore
	if printf '%s' "$out" | grep -qiE 'error|exception|unknown'; then
		cmd_row "Wi-Fi hotspot" hotspot "cmd wifi start-softap" failed "$(oneline "$out")"
	elif [ -n "$state" ]; then
		cmd_row "Wi-Fi hotspot" hotspot "cmd wifi start-softap <ssid> wpa2 <pass> / stop-softap" ok "started and stopped"
	else
		cmd_row "Wi-Fi hotspot" hotspot "cmd wifi start-softap <ssid> wpa2 <pass> / stop-softap" unknown "ran without error; state not visible: check with another device"
	fi
}

toggle_setting_test() {
	# control name setting enable_cmd disable_cmd
	local control=$1 name=$2 setting=$3 en=$4 dis=$5 before mid after
	before=$(rsh "settings get global $setting" | tr -d ' \n')
	case "$before" in
	1) push_restore "$en"; rsh "$dis" >/dev/null ;;
	0) push_restore "$dis"; rsh "$en" >/dev/null ;;
	*)
		cmd_row "$control" "$name" "$en" untested "settings global $setting = '$before'"
		return 0
		;;
	esac
	sleep 5
	mid=$(rsh "settings get global $setting" | tr -d ' \n')
	if [ "$before" = 1 ]; then rsh "$en" >/dev/null; else rsh "$dis" >/dev/null; fi
	pop_restore
	sleep 4
	after=$(rsh "settings get global $setting" | tr -d ' \n')
	if [ "$mid" != "$before" ] && [ "$after" = "$before" ]; then
		cmd_row "$control" "$name" "$en / $dis" ok "toggled and restored"
	else
		cmd_row "$control" "$name" "$en / $dis" failed "$setting: before=$before during=$mid after=$after"
	fi
}

live_wifi() {
	say "-- Wi-Fi"
	link_warning "Toggling Wi-Fi" || return 0
	if help_has "svc wifi" 'enable'; then
		toggle_setting_test "Wi-Fi" wifi wifi_on "svc wifi enable" "svc wifi disable"
	else
		toggle_setting_test "Wi-Fi" wifi wifi_on "cmd wifi set-wifi-enabled enabled" "cmd wifi set-wifi-enabled disabled"
	fi
}

live_bluetooth() {
	say "-- Bluetooth"
	if help_has "svc bluetooth" 'enable'; then
		toggle_setting_test "Bluetooth" bluetooth bluetooth_on "svc bluetooth enable" "svc bluetooth disable"
	else
		toggle_setting_test "Bluetooth" bluetooth bluetooth_on "cmd bluetooth_manager enable" "cmd bluetooth_manager disable"
	fi
}

live_network_mode() {
	local cur out after
	say "-- Network mode"
	cur=$(rsh "cmd phone get-allowed-network-types-for-users 2>&1")
	printf '%s\n' "$cur" >"$RESULTS_DIR/commands-network-mode-get.txt"
	cur=$(printf '%s\n' "$cur" | grep -oE '[01]{10,}' | head -n 1 || true)
	if [ -z "$cur" ]; then
		cmd_row "Network mode" network_mode "cmd phone get-allowed-network-types-for-users" unknown "no bitmask in the output: see commands-network-mode-get.txt"
		return 0
	fi
	if ! confirm "Set the allowed network types to the current value ($cur) to test the command? The radio may re-register for a few seconds."; then
		cmd_row "Network mode" network_mode "cmd phone get-allowed-network-types-for-users" untested "set skipped"
		return 0
	fi
	out=$(rsh "cmd phone set-allowed-network-types-for-users $cur 2>&1")
	after=$(rsh "cmd phone get-allowed-network-types-for-users 2>&1" | grep -oE '[01]{10,}' | head -n 1 || true)
	if [ "$after" = "$cur" ] && ! printf '%s' "$out" | grep -qiE 'error|exception|usage'; then
		cmd_row "Network mode" network_mode "cmd phone set-allowed-network-types-for-users <bitmask>" ok "set accepted (same value written back)"
	else
		cmd_row "Network mode" network_mode "cmd phone set-allowed-network-types-for-users" failed "$(oneline "$out")"
	fi
	wait_data 60 || warn "Data has not reconnected yet after the network-mode test."
}

wakefulness() { rsh "dumpsys power" | grep -oE 'mWakefulness=[A-Za-z]+' | head -n 1 | sed 's/.*=//'; }

live_screen() {
	local before mid after
	say "-- Screen sleep / wake"
	before=$(wakefulness)
	rsh "input keyevent 223" >/dev/null
	sleep 2
	mid=$(wakefulness)
	rsh "input keyevent 224" >/dev/null
	sleep 2
	after=$(wakefulness)
	if [ "$before" != Awake ]; then rsh "input keyevent 223" >/dev/null; fi
	if [ "$mid" != Awake ] && [ "$after" = Awake ]; then
		cmd_row "Screen sleep / wake" screen "input keyevent 223 / 224" ok "slept ($mid) and woke"
	else
		cmd_row "Screen sleep / wake" screen "input keyevent 223 / 224" failed "wakefulness: $before -> $mid -> $after"
	fi
}

live_brightness() {
	local b m got
	say "-- Brightness"
	b=$(rsh "settings get system screen_brightness" | tr -d ' \n')
	m=$(rsh "settings get system screen_brightness_mode" | tr -d ' \n')
	if ! is_uint "$b"; then
		cmd_row "Brightness" brightness "settings put system screen_brightness" untested "current value '$b'"
		return 0
	fi
	push_restore "settings put system screen_brightness $b; settings put system screen_brightness_mode ${m:-0}"
	rsh "settings put system screen_brightness_mode 0; settings put system screen_brightness 17" >/dev/null
	got=$(rsh "settings get system screen_brightness" | tr -d ' \n')
	rsh "settings put system screen_brightness $b; settings put system screen_brightness_mode ${m:-0}" >/dev/null
	pop_restore
	if [ "$got" = 17 ]; then
		cmd_row "Brightness" brightness "settings put system screen_brightness <0-255>" ok "set and restored"
	else
		cmd_row "Brightness" brightness "settings put system screen_brightness" failed "read back '$got'"
	fi
}

live_apps() {
	local pkg="" p out
	say "-- Apps"
	for p in com.google.android.calculator com.android.calculator2 com.sec.android.app.popupcalculator com.miui.calculator com.oneplus.calculator org.lineageos.recorder; do
		if ash "pm list packages $p" | grep -qx "package:$p"; then
			pkg=$p
			break
		fi
	done
	if [ -z "$pkg" ]; then
		cmd_row "Apps: disable / enable" app_disable "pm disable-user" untested "no calculator-like test app found"
		return 0
	fi
	out=$(rsh_rc "am force-stop $pkg" && echo OK || true)
	if printf '%s' "$out" | grep -q OK; then
		cmd_row "Apps: force stop" app_force_stop "am force-stop <pkg>" ok "tested on $pkg"
	else
		cmd_row "Apps: force stop" app_force_stop "am force-stop <pkg>" failed "$(oneline "$out")"
	fi
	if ash "pm list packages -d" | grep -qx "package:$pkg"; then
		cmd_row "Apps: disable / enable" app_disable "pm disable-user" untested "$pkg was already disabled"
		return 0
	fi
	push_restore "pm enable $pkg"
	rsh "pm disable-user --user 0 $pkg" >/dev/null
	if ash "pm list packages -d" | grep -qx "package:$pkg"; then
		rsh "pm enable $pkg" >/dev/null
		pop_restore
		if ash "pm list packages -e" | grep -qx "package:$pkg"; then
			cmd_row "Apps: disable / enable" app_disable "pm disable-user --user 0 <pkg> / pm enable <pkg>" ok "tested on $pkg"
		else
			cmd_row "Apps: disable / enable" app_disable "pm enable" failed "$pkg did not re-enable: run 'adb shell pm enable $pkg'"
		fi
	else
		rsh "pm enable $pkg" >/dev/null
		pop_restore
		cmd_row "Apps: disable / enable" app_disable "pm disable-user" failed "$pkg did not show as disabled"
	fi
}

live_reboot() {
	say "-- Reboot"
	if ! confirm "Reboot the phone with 'svc power reboot'? adb disconnects for 1-2 minutes."; then
		cmd_row "Reboot" reboot "svc power reboot" untested "skipped"
		return 0
	fi
	rsh "svc power reboot" >/dev/null
	wait_device_gone 60 || true
	if wait_boot 300; then
		cmd_row "Reboot" reboot "svc power reboot" ok "rebooted and came back"
	else
		cmd_row "Reboot" reboot "svc power reboot" failed "not back within 5 minutes"
	fi
}

commands_verdict() {
	local f="$RESULTS_DIR/commands.tsv" total ok bad must
	total=$(awk -F '\t' '$4 != "api" { c++ } END { print c + 0 }' "$f")
	ok=$(awk -F '\t' '$4 == "supported" || $4 == "ok" { c++ } END { print c + 0 }' "$f")
	bad=$(awk -F '\t' '$4 == "unsupported" || $4 == "failed" { printf "%s%s", (c++ ? ", " : ""), $1 }' "$f")
	must=$(awk -F '\t' '($2 == "mobile_data" || $2 == "airplane_mode" || $2 == "usb_tethering" || $2 == "reboot") && ($4 == "unsupported" || $4 == "failed") { printf "%s ", $1 }' "$f")
	if [ -n "$must" ]; then
		record "Control commands" FAIL "$ok of $total work; key controls missing: $must"
	elif [ "$ok" -ge $((total * 7 / 10)) ]; then
		record "Control commands" PASS "$ok of $total work$1${bad:+; not working: $bad}"
	else
		record "Control commands" PARTIAL "$ok of $total work$1${bad:+; not working: $bad}"
	fi
}

cmd_commands() {
	local live=0
	while [ $# -gt 0 ]; do
		case "$1" in
		--live) live=1 ;;
		*) die "commands: unknown option $1" 2 ;;
		esac
		shift
	done
	section "Control commands (plan 6.3), safe check"
	root_ok || warn "No root: checks run as the shell user. The Agent runs these commands as root."
	commands_safe
	if [ "$live" = 1 ]; then
		need_root "commands --live"
		section "Control commands, live test (each change is undone)"
		if [ "$DEV_TRANSPORT" = usb ]; then
			note "adb runs over USB: radio toggles do not cut it; USB tethering and reboot do and ask first."
		fi
		live_mobile_data
		live_airplane
		live_wifi
		live_bluetooth
		live_hotspot
		live_network_mode
		live_screen
		live_brightness
		live_apps
		say "-- USB tethering"
		usb_live_test rndis || true
		live_reboot
		commands_verdict " (live-tested)"
	else
		commands_verdict " (help text only; --live proves them)"
	fi
	say "Saved: $RESULTS_DIR/commands.tsv"
}

# --- signal -------------------------------------------------------------------------------

cmd_signal() {
	local secs=60 slot="" ts raw csv summary verdict
	while [ $# -gt 0 ]; do
		case "$1" in
		--seconds | -n)
			need_value "$1" "${2:-}"
			secs=$2
			shift
			;;
		--slot)
			need_value "$1" "${2:-}"
			slot=$2
			shift
			;;
		*) die "signal: unknown option $1" 2 ;;
		esac
		shift
	done
	is_uint "$secs" && [ "$secs" -ge 5 ] || die "signal: --seconds must be a whole number >= 5" 2
	if [ -n "$slot" ] && ! is_uint "$slot"; then die "signal: --slot must be 0, 1, ..." 2; fi
	section "Signal readings (plan 6.2, 7.1)"
	push_helper
	if [ "$(ash 'settings get secure location_mode' | tr -d ' \n')" = 0 ]; then
		warn "Location is OFF: Android hides cell details. Turn it on (docs/install.md)."
	fi
	note "Sampling telephony.registry for $secs s (keep the screen on for the most frequent updates)..."
	ts=$(date +%Y%m%d-%H%M%S)
	raw="$RESULTS_DIR/signal-$ts.raw.txt"
	csv="$RESULTS_DIR/signal-$ts.csv"
	"$ADB" shell "sh $DEV_HELPER signal $secs" </dev/null 2>&1 | tr -d '\r' >"$raw" || true
	awk -v slot="$slot" "$AWK_SIGNAL_PARSE" "$raw" >"$csv"
	summary=$(awk "$AWK_SIGNAL_SUMMARY" "$csv")
	printf '%s\n' "$summary" | grep -v '^@@'
	note "Values are what Android has cached; the Agent requests fresh cell info, so its Finder updates at least this often."
	verdict=$(printf '%s\n' "$summary" | grep '^@@verdict' | tail -n 1)
	record "Signal readings" "$(printf '%s' "$verdict" | cut -f 2)" "$(printf '%s' "$verdict" | cut -f 3)"
	say "Saved: $csv"
}

# --- charge control -----------------------------------------------------------------------

# name|on|off|kind|note. Values are those documented by the ACC project and the kernels named.
CHARGE_KNOWN="batt_slate_mode|0|1|switch|Samsung: 1 suspends USB input, so the phone runs from the battery while stopped
input_suspend|0|1|switch|Qualcomm: 1 suspends USB input, so the phone runs from the battery while stopped
charging_enabled|1|0|switch|older Qualcomm: 0 stops charging while USB keeps powering the phone
battery_charging_enabled|1|0|switch|older Qualcomm: same as charging_enabled
charge_enabled|1|0|switch|some kernels: 0 stops charging (verify)
charge_disable|0|1|switch|some kernels: 1 stops charging (verify)
op_disable_charge|0|1|switch|OnePlus 5-8 (OxygenOS msm kernels): 1 stops charging
mmi_charging_enable|1|0|switch|OPLUS kernels (OnePlus 9 and later, Oppo, Realme): 0 stops charging (verify)
store_mode|0|1|limit|Samsung retail mode: pauses charging only above about 70%, resumes near 60%
batt_full_capacity|100|5|limit|Samsung Protect battery cap in percent; a cap below the current level pauses charging (verify the accepted range)
charge_stop_level|100|5|switch|Google charger: charging stops at this level; a value below the current level stops it
charge_start_level|0|0|info|Google charger: level where charging resumes
charge_control_end_threshold|100|5|switch|Linux standard end threshold; a value below the current level stops charging (verify)
charge_control_start_threshold|0|0|info|Linux standard start threshold
charge_behaviour|auto|inhibit-charge|switch|Linux 5.17+ standard: inhibit-charge rests the battery on USB power (verify)
bypass_charger|0|1|switch|MediaTek: 1 bypass (idle) mode on some kernels (verify)
chg_suspend|0|1|switch|Pixel debugfs (usually absent on user builds)
current_cmd|0 0|0 1|switch|MediaTek /proc/mtk_battery_cmd: '0 1' stops charging (ACC writes it as 0::1)
disable_charger|0|1|switch|older MediaTek mt-battery (verify)
siop_level|0|0|info|Samsung thermal input-current level (0-100), not a switch
charge_control_limit|0|0|info|Qualcomm thermal charge-current step, not a switch
charge_control_limit_max|0|0|info|highest step for charge_control_limit"

charge_known() {
	# prints on|off|kind|note for a path, by basename
	printf '%s\n' "$CHARGE_KNOWN" | awk -F '|' -v n="${1##*/}" '$1 == n { print $2 "|" $3 "|" $4 "|" $5; exit }'
}

battery_now() { helper battery | grep -E '^[0-9]+,' | tail -n 1; }

status_name() {
	case "$1" in
	2) printf 'charging' ;;
	3) printf 'discharging' ;;
	4) printf 'not charging' ;;
	5) printf 'full' ;;
	*) printf 'unknown(%s)' "$1" ;;
	esac
}

cmd_charge_discover() {
	local test_path="" on="" off="" secs=20 out known
	while [ $# -gt 0 ]; do
		case "$1" in
		--test)
			need_value "$1" "${2:-}"
			test_path=$2
			shift
			;;
		--on)
			need_value "$1" "${2:-}"
			on=$2
			shift
			;;
		--off)
			need_value "$1" "${2:-}"
			off=$2
			shift
			;;
		--seconds)
			need_value "$1" "${2:-}"
			secs=$2
			shift
			;;
		*) die "charge-discover: unknown option $1" 2 ;;
		esac
		shift
	done
	is_uint "$secs" && [ "$secs" -ge 5 ] || die "charge-discover: --seconds must be >= 5" 2
	need_root charge-discover
	section "Charge control discovery (plan 4.2)"
	out=$(helper charge-scan)
	printf '%s\n' "$out" >"$RESULTS_DIR/charge-discover.txt"
	say "  Power supplies (name, type, online, status, capacity, current_now, voltage_now):"
	printf '%s\n' "$out" | awk -F '\t' '$1 == "PS" { printf "    %-18s %-10s online=%-3s %-13s cap=%-4s cur=%-9s volt=%s\n", $2, $3, $4, $5, $6, $7, $8 }'
	say "  Known charge-control files found (value, mode):"
	: >"$RESULTS_DIR/charge-candidates.tsv"
	printf '%s\n' "$out" | awk -F '\t' '$1 == "CTL" { print $2 "\t" $3 "\t" $4 }' | while IFS= read -r line; do
		p=$(printf '%s' "$line" | cut -f 1)
		v=$(printf '%s' "$line" | cut -f 2)
		m=$(printf '%s' "$line" | cut -f 3)
		known=$(charge_known "$p")
		printf '    %-62s = %-10s %s\n' "$p" "$v" "$m"
		if [ -n "$known" ]; then
			if [ "$(printf '%s' "$known" | cut -d '|' -f 3)" = info ]; then
				printf '      info: %s\n' "$(printf '%s' "$known" | cut -d '|' -f 4)"
			else
				printf '      %s: on=%s off=%s; %s\n' "$(printf '%s' "$known" | cut -d '|' -f 3)" \
					"$(printf '%s' "$known" | cut -d '|' -f 1)" "$(printf '%s' "$known" | cut -d '|' -f 2)" "$(printf '%s' "$known" | cut -d '|' -f 4)"
			fi
			printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$v" "$m" "$(printf '%s' "$known" | cut -d '|' -f 1)" \
				"$(printf '%s' "$known" | cut -d '|' -f 2)" "$(printf '%s' "$known" | cut -d '|' -f 3)" \
				"$(printf '%s' "$known" | cut -d '|' -f 4)" >>"$RESULTS_DIR/charge-candidates.tsv"
		fi
	done
	if ! grep -q . "$RESULTS_DIR/charge-candidates.tsv"; then
		note "None of the known files exist. Look through the power_supply folders in charge-discover.txt,"
		note "check the ACC project's list for this model, or rely on the built-in limit (Samsung: Protect battery)."
	else
		note "Test the most promising 'switch' with: phase0.sh charge-discover --test <path>"
	fi
	if [ -n "$test_path" ]; then
		charge_test "$test_path" "$on" "$off" "$secs"
	elif ! awk -F '\t' '$1 == "Charge control"' "$RESULTS_DIR/results.tsv" 2>/dev/null | grep -q .; then
		record "Charge control" MANUAL "candidates listed; prove one with: phase0.sh charge-discover --test <path>"
	fi
}

# Summarise watch rows: prints "<n> <charging> <discharging> <notcharging> <full> <unplugged> <median current>"
watch_stats() {
	awk -F ',' '/^[0-9]+,/ {
		n++; s = $5; if (s == 2) c++; else if (s == 3) d++; else if (s == 4) nc++; else if (s == 5) f++
		if ($6 == "none") u++
		if ($7 ~ /^-?[0-9]+$/) { cur[++k] = $7 + 0 }
	} END {
		for (i = 2; i <= k; i++) { v = cur[i]; j = i - 1; while (j > 0 && cur[j] > v) { cur[j + 1] = cur[j]; j-- } cur[j + 1] = v }
		med = (k ? cur[int((k + 1) / 2)] : "-")
		printf "%d %d %d %d %d %d %s\n", n, c + 0, d + 0, nc + 0, f + 0, u + 0, med
	}'
}

charge_test() {
	local path=$1 on=$2 off=$3 secs=$4 known orig row st plug before during after stamp wout tag
	local b_n b_c d_n d_c d_d d_nc d_u a_n a_c b_med d_med mode stopped restored
	known=$(charge_known "$path")
	[ -n "$on" ] || on=$(printf '%s' "$known" | cut -d '|' -f 1)
	[ -n "$off" ] || off=$(printf '%s' "$known" | cut -d '|' -f 2)
	if [ -z "$on" ] || [ -z "$off" ] || [ "$on" = "$off" ]; then
		die "No on/off values known for $path: pass --on VALUE --off VALUE." 2
	fi
	section "Charge control test: $path"
	if [ -z "$(rsh "[ -e $(shq "$path") ] && echo yes")" ]; then die "$path does not exist on the phone." 1; fi
	orig=$(rsh "cat $(shq "$path")" | head -n 1)
	row=$(battery_now)
	st=$(printf '%s' "$row" | cut -d ',' -f 5)
	plug=$(printf '%s' "$row" | cut -d ',' -f 6)
	field "Current value" "$orig"
	field "Battery now" "$(printf '%s' "$row" | cut -d ',' -f 4)%, $(status_name "$st"), plug=$plug"
	if [ "$plug" = none ]; then die "The phone is not plugged in. Connect the charger and re-run." 1; fi
	if [ "$st" != 2 ]; then
		warn "The battery is not charging now ($(status_name "$st")); the test can only prove a stop while it charges."
		confirm "Continue anyway?" || return 1
	fi
	confirm "Write '$off' to $path for $secs s, then restore '$orig'? (A reboot also restores sysfs values.)" || return 1
	stamp=$(date +%Y%m%d-%H%M%S)
	tag=$(sanitize "${path##*/}")
	wout="$RESULTS_DIR/charge-test-$tag-$stamp.csv"
	note "Baseline (5 s)..."
	before=$(helper watch 5)
	push_restore "echo $(shq "$orig") > $(shq "$path")"
	if ! rsh_rc "echo $(shq "$off") > $(shq "$path")" >/dev/null; then
		warn "Writing '$off' failed (the kernel rejected the value)."
	fi
	field "Value now" "$(rsh "cat $(shq "$path")" | head -n 1)"
	note "Watching for $secs s with charging stopped..."
	during=$(helper watch "$secs")
	rsh "echo $(shq "$orig") > $(shq "$path")" >/dev/null
	pop_restore
	field "Restored value" "$(rsh "cat $(shq "$path")" | head -n 1)"
	note "Watching 15 s after restoring..."
	after=$(helper watch 15)
	{
		printf '# phase\t%s\n' "baseline"
		printf '%s\n' "$before"
		printf '# phase\t%s (wrote %s)\n' "stopped" "$off"
		printf '%s\n' "$during"
		printf '# phase\t%s (restored %s)\n' "restored" "$orig"
		printf '%s\n' "$after"
	} >"$wout"
	set -- $(printf '%s\n' "$before" | watch_stats)
	b_n=$1 b_c=$2 b_med=$7
	# Ignore the first third of the stopped window: the charger needs a few seconds to react.
	set -- $(printf '%s\n' "$during" | grep -E '^[0-9]+,' | sed "1,$((secs / 3))d" | watch_stats)
	d_n=$1 d_c=$2 d_d=$3 d_nc=$4 d_u=$6 d_med=$7
	set -- $(printf '%s\n' "$after" | grep -E '^[0-9]+,' | sed '1,5d' | watch_stats)
	a_n=$1 a_c=$2
	field "Baseline" "$b_c of $b_n samples charging, median current_now $b_med"
	field "Stopped" "$d_c charging, $d_d discharging, $d_nc not charging of $d_n samples, median current_now $d_med"
	field "Restored" "$a_c of $a_n samples charging"
	stopped=no
	restored=no
	mode=-
	if [ "$d_n" -gt 0 ] && [ "$d_c" -eq 0 ]; then
		stopped=yes
		if [ "$d_nc" -ge "$d_d" ]; then mode="idle: battery rests, USB powers the phone"; else mode="input suspended: phone runs from the battery"; fi
		if [ "$d_u" -gt 0 ]; then mode="USB input cut (plug shows none)"; fi
	fi
	if [ "$a_n" -gt 0 ] && [ "$a_c" -gt 0 ]; then restored=yes; fi
	field "Result" "stopped=$stopped restored=$restored mode=$mode"
	printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$path" "$on" "$off" "$stopped" "$restored" "$mode" "$(date +%Y-%m-%d)" >>"$RESULTS_DIR/charge.tsv"
	if [ "$stopped" = yes ] && [ "$restored" = yes ]; then
		record "Charge control" PASS "$path off=$off on=$on ($mode)"
	elif [ "$stopped" = yes ]; then
		record "Charge control" PARTIAL "$path stopped charging but charging did not resume within 15 s: check by hand"
	else
		if ! awk -F '\t' '$1 == "Charge control" && $2 == "PASS"' "$RESULTS_DIR/results.tsv" 2>/dev/null | grep -q .; then
			record "Charge control" FAIL "$path did not stop charging; try another candidate"
		fi
	fi
	say "Saved: $wout"
}

# --- current-sign -------------------------------------------------------------------------

cmd_current_sign() {
	local secs=75 csv="$DEV_DIR/current-sign.csv" pidf="$DEV_DIR/current-sign.pid" local_csv t res unit sign
	while [ $# -gt 0 ]; do
		case "$1" in
		--seconds)
			need_value "$1" "${2:-}"
			secs=$2
			shift
			;;
		*) die "current-sign: unknown option $1" 2 ;;
		esac
		shift
	done
	is_uint "$secs" && [ "$secs" -ge 45 ] || die "current-sign: --seconds must be >= 45" 2
	section "current_now unit and sign"
	have_tty || die "current-sign is interactive: run it in a terminal." 2
	push_helper
	field "current_now path" "$(helper current-path | head -n 1)"
	psh "rm -f $csv $pidf $pidf.stop" >/dev/null
	psh "nohup sh $DEV_HELPER log power $csv 1 $secs $pidf </dev/null >/dev/null 2>&1 &" >/dev/null
	sleep 2
	note "The phone now logs once per second for $secs s, on its own (adb may drop when you unplug)."
	note "Follow the prompts: keep the cable in, unplug it, then plug it back in."
	t=0
	while [ "$t" -lt "$secs" ]; do
		if [ "$t" -eq 0 ]; then say ">>> Keep the cable PLUGGED IN (charging) ..."; fi
		if [ "$t" -eq $((secs / 4)) ]; then say ">>> UNPLUG the cable now and leave it out ..."; fi
		if [ "$t" -eq $((secs * 3 / 5)) ]; then say ">>> PLUG the cable back in now ..."; fi
		sleep 1
		t=$((t + 1))
	done
	say "Waiting for adb to come back..."
	wait_boot 120 || die "The phone did not reconnect within 2 minutes. Plug it in and run 'phase0.sh current-sign' again." 3
	sleep 2
	local_csv="$RESULTS_DIR/current-sign-$(date +%Y%m%d-%H%M%S).csv"
	psh "cat $csv" | grep -E '^(epoch|[0-9]+,)' >"$local_csv" || true
	res=$(awk -F ',' 'NR > 1 && $7 ~ /^-?[0-9]+$/ {
		v = $7 + 0; a = (v < 0 ? -v : v); if (a > mx) mx = a
		if ($6 == "none") { d += v; nd++ } else if ($5 == 2) { c += v; nc++ }
	} END {
		unit = (mx >= 10000 ? "uA" : "mA")
		sign = ""
		if (nd > 0 && d / nd != 0) sign = (d / nd < 0 ? 1 : -1)
		else if (nc > 0 && c / nc != 0) sign = (c / nc > 0 ? 1 : -1)
		printf "%s %s %d %d %s %s\n", unit, (sign == "" ? "?" : sign), nc, nd, (nc ? c / nc : "-"), (nd ? d / nd : "-")
	}' "$local_csv")
	set -- $res
	unit=$1 sign=$2
	field "Samples" "$3 plugged and charging, $4 unplugged"
	field "Average raw current" "charging $5, unplugged $6"
	if [ "$4" -eq 0 ] && [ "$3" -eq 0 ]; then
		die "No usable samples. Make sure the phone was charging before you unplugged it, then re-run." 1
	fi
	if [ "$4" -eq 0 ]; then warn "No unplugged samples: the sign comes from the charging samples only (less reliable)."; fi
	if [ "$sign" = "?" ]; then die "Could not decide the sign (current_now stayed 0). This phone may not report current." 1; fi
	field "Result" "unit=$unit sign=$sign  (profile: \"current_now\": { \"unit\": \"$unit\", \"sign\": $sign })"
	kv_set current.env unit "$unit"
	kv_set current.env sign "$sign"
	kv_set current.env path "$(helper current-path | head -n 1)"
	say "Saved: $local_csv"
}

# --- thermal ------------------------------------------------------------------------------

cmd_thermal() {
	local out batt res
	section "Thermal zones (plan 6.2 Heat)"
	out=$(helper thermal-scan)
	printf '%s\n' "$out" >"$RESULTS_DIR/thermal-zones.txt"
	batt=$(battery_now | cut -d ',' -f 9)
	if is_uint "$batt"; then field "Battery (dumpsys)" "$((batt / 10)).$((batt % 10)) C"; fi
	res=$(printf '%s\n' "$out" | awk -F '\t' "$AWK_THERMAL_LIB"'
	$1 == "TZ" {
		c = tz_c($4); cat = tz_cat($3)
		printf "    zone %-4s %-28s %8s  %s\n", $2, $3, (c == "" ? "n/a" : sprintf("%.1f C", c)), cat
		if (cat == "cpu" || cat == "modem" || cat == "skin" || cat == "battery") {
			k = tz_kw($3, cat)
			if (k != "" && !((cat SUBSEP k) in SEEN)) { SEEN[cat, k] = 1; LIST[cat] = LIST[cat] (LIST[cat] == "" ? "" : ",") k }
			if (c != "" && (MAX[cat] == "" || c > MAX[cat])) MAX[cat] = c
		}
	}
	END {
		printf "@@kw\tcpu=%s\tmodem=%s\tskin=%s\tbattery=%s\n", LIST["cpu"], LIST["modem"], LIST["skin"], LIST["battery"]
		printf "@@max\tcpu=%s\tmodem=%s\tskin=%s\tbattery=%s\n", MAX["cpu"], MAX["modem"], MAX["skin"], MAX["battery"]
	}')
	say "  Zones (index, type, temperature, category):"
	printf '%s\n' "$res" | grep -v '^@@'
	note "Category 'bcl' = Qualcomm battery-current-limit zones (charge level, voltage, current): never use them as temperatures."
	local kw
	kw=$(printf '%s\n' "$res" | grep '^@@kw' | cut -f 2-)
	field "Suggested keywords" "$(printf '%s' "$kw" | tr '\t' ' ')"
	field "Hottest now" "$(printf '%s\n' "$res" | grep '^@@max' | cut -f 2- | tr '\t' ' ')"
	kv_set thermal.env cpu "$(printf '%s' "$kw" | tr '\t' '\n' | sed -n 's/^cpu=//p')"
	kv_set thermal.env modem "$(printf '%s' "$kw" | tr '\t' '\n' | sed -n 's/^modem=//p')"
	kv_set thermal.env skin "$(printf '%s' "$kw" | tr '\t' '\n' | sed -n 's/^skin=//p')"
	kv_set thermal.env battery "$(printf '%s' "$kw" | tr '\t' '\n' | sed -n 's/^battery=//p')"
	say "Saved: $RESULTS_DIR/thermal-zones.txt"
}

# --- usb tethering ------------------------------------------------------------------------

tether_ifaces() {
	psh "ip -o -4 addr show" | awk '$2 ~ /^(rndis|ncm|usb)[0-9]/ { split($4, a, "/"); print $2, a[1] }'
}

usb_functions() { ash "getprop sys.usb.config" | head -n 1; }

wait_tether() {
	local waited=0 t
	while [ "$waited" -lt "$1" ]; do
		t=$(tether_ifaces | head -n 1)
		if [ -n "$t" ]; then
			printf '%s' "$t"
			return 0
		fi
		sleep 3
		waited=$((waited + 3))
	done
	return 1
}

usb_live_test() {
	local func=${1:-rndis} prev prev_funcs t
	prev=$(usb_functions)
	prev_funcs=$(printf '%s' "$prev" | tr ',' '\n' | grep -v '^adb$' | grep -v '^$' | tr '\n' ',' | sed 's/,$//' || true)
	t=$(tether_ifaces | head -n 1)
	if [ -n "$t" ]; then
		if ! confirm "USB tethering is on ($t). Turn it off and on again? The USB link re-connects twice."; then
			cmd_row "USB tethering" usb_tethering "svc usb setFunctions $func" untested "skipped"
			return 0
		fi
		ash "svc usb setFunctions" >/dev/null
		sleep 5
		wait_boot 60 || true
		if [ -n "$(tether_ifaces)" ]; then
			cmd_row "USB tethering" usb_tethering "svc usb setFunctions" failed "tether interface stayed up after turning it off"
			return 0
		fi
		note "Tethering is off; turning it back on..."
	else
		if ! confirm "Switch USB to '$func' (USB tethering)? The USB link re-connects, so adb drops for a few seconds."; then
			cmd_row "USB tethering" usb_tethering "svc usb setFunctions $func" untested "skipped"
			return 0
		fi
	fi
	ash "svc usb setFunctions $func" >/dev/null
	sleep 5
	wait_boot 60 || {
		cmd_row "USB tethering" usb_tethering "svc usb setFunctions $func" failed "adb did not come back after the switch"
		return 1
	}
	if t=$(wait_tether 45); then
		cmd_row "USB tethering" usb_tethering "svc usb setFunctions $func" ok "up: $t"
		kv_set usb.env function "$func"
		kv_set usb.env tether "$t"
	else
		cmd_row "USB tethering" usb_tethering "svc usb setFunctions $func" failed "no tether interface with an IPv4 address within 45 s (sys.usb.config=$(usb_functions))"
	fi
	if [ -z "$(printf '%s' "$prev" | tr ',' '\n' | grep -E '^(rndis|ncm)$' || true)" ]; then
		if confirm "Restore the previous USB functions (${prev_funcs:-none})?"; then
			ash "svc usb setFunctions $prev_funcs" >/dev/null
			sleep 5
			wait_boot 60 || warn "adb has not come back yet; re-plug the cable if needed."
		fi
	fi
	return 0
}

cmd_usb() {
	local live=0 reboot_test=0 func=rndis t cfg
	while [ $# -gt 0 ]; do
		case "$1" in
		--live) live=1 ;;
		--reboot-test) reboot_test=1 ;;
		--function)
			need_value "$1" "${2:-}"
			func=$2
			shift
			;;
		*) die "usb: unknown option $1" 2 ;;
		esac
		shift
	done
	case "$func" in rndis | ncm) ;; *) die "usb: --function must be rndis or ncm" 2 ;; esac
	section "USB functions and tethering (plan 6.3, 4.4)"
	cfg=$(usb_functions)
	field "sys.usb.config" "$cfg"
	field "sys.usb.state" "$(ash 'getprop sys.usb.state' | head -n 1)"
	field "persist.sys.usb.config" "$(ash 'getprop persist.sys.usb.config' | head -n 1)"
	field "svc usb getFunctions" "$(oneline "$(ash 'svc usb getFunctions 2>&1')")"
	field "USB controller" "$(ash 'getprop sys.usb.controller' | head -n 1)"
	t=$(tether_ifaces)
	if [ -n "$t" ]; then
		field "Tether interface" "$(oneline "$t")"
		kv_set usb.env tether "$(printf '%s\n' "$t" | head -n 1)"
		case "$t" in
		ncm*) kv_set usb.env function ncm ;;
		*) kv_set usb.env function rndis ;;
		esac
	else
		field "Tether interface" "none (USB tethering is off)"
	fi
	psh "dumpsys tethering 2>/dev/null || dumpsys connectivity tethering" >"$RESULTS_DIR/usb-tethering-dump.txt" 2>&1 || true
	note "Phase 0 needs: Developer options > Default USB configuration = USB tethering, so it comes up after a reboot."
	if [ "$live" = 1 ]; then usb_live_test "$func"; fi
	if [ "$reboot_test" = 1 ]; then
		if confirm "Reboot now and check that USB tethering comes back with no touch? adb drops for 1-2 minutes."; then
			ash "reboot" >/dev/null
			say "Rebooting..."
			wait_device_gone 60 || true
			if ! wait_boot 300; then
				record "Tethering after reboot" FAIL "the phone did not come back within 5 minutes"
				return 1
			fi
			if t=$(wait_tether 120); then
				record "Tethering after reboot" PASS "up by itself after reboot: $t"
				kv_set usb.env tether "$t"
			else
				record "Tethering after reboot" FAIL "no tether interface 2 minutes after boot: set Default USB configuration = USB tethering (or let the Agent's tether fix do it) and re-test"
			fi
		fi
	elif [ -n "$t" ] && ! awk -F '\t' '$1 == "Tethering after reboot" && $2 == "PASS"' "$RESULTS_DIR/results.tsv" 2>/dev/null | grep -q .; then
		record "Tethering after reboot" PARTIAL "tethering is up now ($(oneline "$t")); prove it after a reboot: phase0.sh usb --reboot-test"
	fi
}

# --- automatic power-on -------------------------------------------------------------------

cmd_autoboot() {
	local check=0 fb=0 test=0 conf="" manuf method mods bootmode bid newbid fbout
	while [ $# -gt 0 ]; do
		case "$1" in
		--check) check=1 ;;
		--fastboot) fb=1 ;;
		--test) test=1 ;;
		--confirm)
			need_value "$1" "${2:-}"
			conf=$2
			shift
			;;
		*) die "autoboot: unknown option $1" 2 ;;
		esac
		shift
	done
	case "$conf" in '' | pass | fail) ;; *) die "autoboot: --confirm takes pass or fail" 2 ;; esac
	section "Automatic power-on (plan 4.3, Appendix A.2)"
	manuf=$(printf '%s' "$DEV_MANUF" | tr 'A-Z' 'a-z')
	bootmode=$(ash 'getprop ro.bootmode; getprop ro.boot.mode; getprop sys.boot.reason; getprop ro.boot.bootreason' | tr '\n' ' ')
	field "Boot mode / reason" "$bootmode"
	if [ "$manuf" = samsung ]; then
		method=magisk_autoboot
		note "Samsung has no fastboot, so 'fastboot oem off-mode-charge' does not exist."
		note "Needed: a root change that makes the charging-only mode (LPM) reboot into Android, e.g. an"
		note "auto-boot Magisk module or boot-image patch, or on older models replacing the charging-animation"
		note "program (/system/bin/lpm). Samsung's own 'power on when plugged in' is a Knox enterprise policy."
		if root_ok; then
			mods=$(rsh 'for m in /data/adb/modules/*; do [ -f "$m/module.prop" ] && printf "%s: %s\n" "${m##*/}" "$(grep -m1 "^name=" "$m/module.prop" | cut -d= -f2-)"; done')
			field "Magisk modules" "$(oneline "${mods:-none}")"
			if printf '%s' "$mods" | grep -qiE 'autoboot|auto.boot|lpm|charg'; then
				note "A module that looks like an auto-boot module is installed. Prove it with --test."
			else
				note "No auto-boot module found."
			fi
			field "/system/bin/lpm" "$(rsh '[ -e /system/bin/lpm ] && echo present || echo absent')"
		fi
	else
		method=fastboot_oem_off_mode_charge
		note "Phones with fastboot: reboot to the bootloader and run"
		note "  adb reboot bootloader"
		note "  fastboot oem off-mode-charge 0"
		note "  fastboot reboot"
		note "If fastboot rejects the command, this bootloader lacks it: use a root/ramdisk method instead."
		if [ "$fb" = 1 ]; then
			command -v "$FASTBOOT" >/dev/null 2>&1 || die "fastboot not found (Android platform-tools)." 3
			if confirm "Reboot to the bootloader now and run 'fastboot oem off-mode-charge 0'? adb drops until Android is back."; then
				ash "reboot bootloader" >/dev/null
				local waited=0
				while [ "$waited" -lt 60 ] && [ -z "$("$FASTBOOT" devices 2>/dev/null | head -n 1)" ]; do
					sleep 2
					waited=$((waited + 2))
				done
				if [ -z "$("$FASTBOOT" devices 2>/dev/null | head -n 1)" ]; then
					die "No fastboot device after 60 s. Check the USB driver and run the commands by hand." 3
				fi
				fbout=$("$FASTBOOT" oem off-mode-charge 0 2>&1 || true)
				field "fastboot output" "$(oneline "$fbout")"
				"$FASTBOOT" reboot >/dev/null 2>&1 || true
				kv_set autoboot.env fastboot_output "$fbout"
				wait_boot 300 || warn "Android is not back yet."
				HAVE_ROOT=""
			fi
		fi
	fi
	kv_set autoboot.env method "$method"
	note "The proof (Phase 0 must-have): from fully off, the phone boots into Android by itself when"
	note "power is connected. --test powers it off while plugged in (that puts it in charging-only mode,"
	note "the state after a power cut) and waits for it to come back. Phase 5 repeats this with an empty battery."
	if [ "$test" = 1 ]; then
		if confirm "Power the phone OFF now (cable stays plugged in) and wait up to 4 minutes for it to boot by itself?"; then
			bid=$(boot_id)
			rsh "svc power shutdown" >/dev/null
			wait_device_gone 60 || true
			say "Phone is off. Waiting for it to boot by itself (do not touch it)..."
			if wait_boot 240; then
				newbid=$(boot_id)
				if [ "$newbid" != "$bid" ]; then
					kv_set autoboot.env verified yes
					record "Automatic power-on" PASS "$method: booted by itself after power-off with the charger connected"
				else
					record "Automatic power-on" FAIL "the phone never went off (same boot id)"
				fi
			else
				kv_set autoboot.env verified no
				record "Automatic power-on" FAIL "$method: no boot within 4 minutes (stays in charging mode?)"
				note "Press power to start it, fix the method, and re-test."
			fi
			return 0
		fi
	fi
	if [ -n "$conf" ]; then
		if [ "$conf" = pass ]; then
			kv_set autoboot.env verified yes
			record "Automatic power-on" PASS "$method: confirmed by hand"
		else
			kv_set autoboot.env verified no
			record "Automatic power-on" FAIL "$method: confirmed failing by hand"
		fi
		return 0
	fi
	if [ "$check" = 0 ] && have_tty; then
		say ""
		say "Manual test: unplug the cable, power the phone off, wait 30 s, plug in the real cable and port."
		if confirm "Did the phone boot into Android by itself? (answer only after doing the test)"; then
			kv_set autoboot.env verified yes
			record "Automatic power-on" PASS "$method: confirmed by hand"
			return 0
		fi
	fi
	if ! awk -F '\t' '$1 == "Automatic power-on" && ($2 == "PASS" || $2 == "FAIL")' "$RESULTS_DIR/results.tsv" 2>/dev/null | grep -q .; then
		record "Automatic power-on" MANUAL "$method: not proven yet (phase0.sh autoboot --test, or --confirm pass|fail)"
	fi
}

# --- loggers (power-log, heat-log) -----------------------------------------------------------

logger_pid() { psh "cat $DEV_DIR/$1-log.pid 2>/dev/null" | head -n 1 | tr -d ' '; }

logger_running() {
	local pid
	pid=$(logger_pid "$1")
	is_uint "$pid" || return 1
	# cmdline is NUL-separated and toybox grep stops at the first NUL, so convert it first.
	psh "xargs -0 echo </proc/$pid/cmdline 2>/dev/null | grep -q hsm-phase0-device && echo yes" | grep -q yes
}

logger_cmd() {
	local mode=$1 action=${2:-} interval=60 hours=0 fresh=0 minh=24 csv pid rows
	[ $# -gt 0 ] && shift
	[ $# -gt 0 ] && shift
	while [ $# -gt 0 ]; do
		case "$1" in
		--interval)
			need_value "$1" "${2:-}"
			interval=$2
			shift
			;;
		--hours)
			need_value "$1" "${2:-}"
			hours=$2
			shift
			;;
		--min-hours)
			need_value "$1" "${2:-}"
			minh=$2
			shift
			;;
		--fresh) fresh=1 ;;
		*) die "$mode-log: unknown option $1" 2 ;;
		esac
		shift
	done
	is_uint "$interval" && [ "$interval" -ge 1 ] || die "$mode-log: --interval must be >= 1" 2
	is_uint "$hours" || die "$mode-log: --hours must be a whole number" 2
	is_uint "$minh" || die "$mode-log: --min-hours must be a whole number" 2
	csv="$DEV_DIR/$mode-log.csv"
	pid="$DEV_DIR/$mode-log.pid"
	case "$action" in
	start)
		section "$mode-log start"
		if logger_running "$mode"; then
			say "  Already running (pid $(logger_pid "$mode")). Use '$mode-log status' or '$mode-log stop'."
			return 0
		fi
		push_helper
		root_ok || warn "No root: logging as the shell user; Android may stop it when adb disconnects."
		if [ "$fresh" = 1 ]; then psh "rm -f $csv" >/dev/null; fi
		psh "rm -f $pid.stop" >/dev/null
		psh "nohup sh $DEV_HELPER log $mode $csv $interval $((hours * 3600)) $pid </dev/null >/dev/null 2>&1 &" >/dev/null
		sleep 3
		if logger_running "$mode"; then
			field "Logger" "running (pid $(logger_pid "$mode")), every $interval s, $([ "$hours" -gt 0 ] && echo "for $hours h" || echo "until stopped")"
			field "On the phone" "$csv"
			if [ "$mode" = power ]; then
				note "Now run the real load: tether through the real cable and port, keep traffic flowing"
				note "(streaming or a download loop) and leave the screen off. Pull after 24 h or more:"
				note "  phase0.sh power-log pull"
			else
				note "Put the phone in the closed enclosure, in its real spot, through a hot afternoon. Then:"
				note "  phase0.sh heat-log pull"
			fi
		else
			die "The logger did not start. Check: adb shell sh $DEV_HELPER log $mode $csv $interval 0 $pid" 1
		fi
		;;
	status)
		section "$mode-log status"
		if logger_running "$mode"; then field "Logger" "running (pid $(logger_pid "$mode"))"; else field "Logger" "not running"; fi
		rows=$(psh "[ -f $csv ] && wc -l < $csv" | tr -d ' \n')
		field "Rows" "${rows:-0} (including the header)"
		field "Last row" "$(psh "[ -f $csv ] && tail -n 1 $csv" | head -n 1)"
		;;
	stop)
		section "$mode-log stop"
		if logger_running "$mode"; then
			psh "touch $pid.stop; kill $(logger_pid "$mode") 2>/dev/null; rm -f $pid" >/dev/null
			field "Logger" "stopped"
		else
			field "Logger" "was not running"
		fi
		;;
	pull)
		section "$mode-log pull"
		if [ -z "$(psh "[ -f $csv ] && echo yes")" ]; then die "No $csv on the phone: start it with '$mode-log start'." 1; fi
		psh "cat $csv" | grep -E '^(epoch|[0-9]+,)' >"$RESULTS_DIR/$mode-log.csv" || true
		field "Pulled" "$RESULTS_DIR/$mode-log.csv ($(awk 'END { print NR - 1 }' "$RESULTS_DIR/$mode-log.csv") rows)"
		if logger_running "$mode"; then note "The logger is still running; pull again later for a longer record."; fi
		if [ "$mode" = power ]; then power_summary "$minh"; else heat_summary; fi
		;;
	*) die "$mode-log: use start, status, stop or pull" 2 ;;
	esac
}

power_summary() {
	local minh=$1 unit sign out verdict
	unit=$(kv_get current.env unit)
	sign=$(kv_get current.env sign)
	out=$(awk -F ',' -v unit="$unit" -v sign="$sign" -v minh="$minh" '
	NR == 1 { next }
	$1 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ {
		n++; t = $1 + 0; l = $4 + 0
		if (n == 1) { t0 = t; l0 = l; mn = l; mx = l }
		h = (t - t0) / 3600
		sx += h; sy += l; sxx += h * h; sxy += h * l
		if (l < mn) mn = l; if (l > mx) mx = l
		lastl = l; lastt = t
		if ($6 != "none") plugged++; else unplugged++
		if ($6 != "none" && $5 == 3) plugdis++
		if ($6 == "none" && prevplug != "" && prevplug != "none") cuts++
		prevplug = $6
		if (n > 1 && t - pt > gapmax) gapmax = t - pt
		pt = t
		if ($7 ~ /^-?[0-9]+$/) { raw += $7; nraw++; a = ($7 < 0 ? -$7 : $7); if (a > amax) amax = a }
		if ($9 ~ /^[0-9]+$/ && $9 / 10 > tmax) tmax = $9 / 10
	}
	END {
		if (n < 2) { print "  Not enough rows yet."; print "@@verdict\tPARTIAL\tnot enough rows"; exit }
		hours = (lastt - t0) / 3600
		den = n * sxx - sx * sx
		slope = (den != 0 ? (n * sxy - sx * sy) / den : 0)
		u = unit; if (u == "") u = (amax >= 10000 ? "uA" : "mA")
		div = (u == "uA" ? 1000 : 1)
		if (unit == "mA" && amax >= 20000) print "  NOTE: current_now looks like microamps although current-sign found mA: re-run current-sign."
		if (unit == "uA" && amax > 0 && amax < 1000) print "  NOTE: current_now looks like milliamps although current-sign found uA: re-run current-sign."
		printf "  Duration             %.1f h, %d rows, longest gap %d min\n", hours, n, gapmax / 60
		printf "  Battery              %d%% -> %d%% (min %d%%, max %d%%), trend %+.2f %%/h\n", l0, lastl, mn, mx, slope
		printf "  Plugged in           %d%% of rows; plugged but discharging %d%%; power cuts seen %d\n", 100 * plugged / n, 100 * plugdis / n, cuts
		if (nraw) {
			avg = raw / nraw / div
			if (sign != "") printf "  Net battery current  %+.0f mA average (positive = charging)\n", avg * sign
			else printf "  current_now average  %+.0f mA raw (sign unknown: run phase0.sh current-sign)\n", avg
		}
		printf "  Max battery temp     %.1f C\n", tmax
		detail = sprintf("%.1f h, %d%% -> %d%%, trend %+.2f %%/h", hours, l0, lastl, slope)
		if (plugdis * 5 > n) detail = detail sprintf(", plugged but discharging %d%% of the time", 100 * plugdis / n)
		if (slope < -0.2 || lastl < l0 - 5) printf "@@verdict\tFAIL\tbattery trends down: %s\n", detail
		else if (hours < minh) printf "@@verdict\tPARTIAL\tonly %.1f of %d h logged so far: %s\n", hours, minh, detail
		else printf "@@verdict\tPASS\t%s\n", detail
	}' "$RESULTS_DIR/power-log.csv")
	printf '%s\n' "$out" | grep -v '^@@'
	verdict=$(printf '%s\n' "$out" | grep '^@@verdict' | tail -n 1)
	record "Power budget" "$(printf '%s' "$verdict" | cut -f 2)" "$(printf '%s' "$verdict" | cut -f 3)"
	note "If it fails: a USB 3.0 or charging-capable port, screen off, Wi-Fi/Bluetooth off, a shorter or"
	note "thicker cable, or a powered USB hub that passes data (plan 4.1)."
}

heat_summary() {
	local out verdict
	out=$(awk -F ',' "$AWK_THERMAL_LIB"'
	NR == 1 { next }
	$1 ~ /^[0-9]+$/ {
		n++; t = $1 + 0; if (n == 1) t0 = t; lastt = t
		hr = substr($2, 12, 2) + 0
		if (hr >= 12 && hr < 18) aft++
		if ($4 ~ /^-?[0-9]+$/) {
			b = $4 / 10
			if (nb == 0 || b > bmax) { bmax = b; bmax_at = $2 }
			nb++; bsum += b
			if (b >= 40) over40++
			if (b >= 45) over45++
		}
		m = split($5, z, "|")
		for (i = 1; i <= m; i++) {
			p = index(z[i], "="); if (p == 0) continue
			ty = substr(z[i], 1, p - 1); c = tz_c(substr(z[i], p + 1)); if (c == "") continue
			cat = tz_cat(ty)
			if (cat == "bcl") continue
			if (!(cat in CMAX) || c > CMAX[cat]) { CMAX[cat] = c; CTYPE[cat] = ty }
		}
	}
	END {
		if (n < 2) { print "  Not enough rows yet."; print "@@verdict\tPARTIAL\tnot enough rows"; exit }
		hours = (lastt - t0) / 3600
		per = (n > 1 ? hours / (n - 1) : 0)
		printf "  Duration             %.1f h, %d rows (%.1f h between 12:00 and 18:00 phone time)\n", hours, n, aft * per
		if (nb) printf "  Battery              max %.1f C at %s, average %.1f C; >= 40 C for %.1f h, >= 45 C for %.1f h\n", bmax, bmax_at, bsum / nb, over40 * per, over45 * per
		split("skin cpu modem gpu other battery", order, " ")
		for (i = 1; i <= 6; i++) if (order[i] in CMAX) printf "  Max %-16s %.1f C (%s)\n", order[i], CMAX[order[i]], CTYPE[order[i]]
		detail = sprintf("battery max %.1f C at %s over %.1f h", bmax, bmax_at, hours)
		if (nb == 0) printf "@@verdict\tPARTIAL\tno battery temperature in the log\n"
		else if (bmax >= 45) printf "@@verdict\tFAIL\t%s (limit 45 C)\n", detail
		else if (aft * per < 2 || hours < 4) printf "@@verdict\tPARTIAL\t%s; log a full hot afternoon in the closed enclosure\n", detail
		else printf "@@verdict\tPASS\t%s\n", detail
	}' "$RESULTS_DIR/heat-log.csv")
	printf '%s\n' "$out" | grep -v '^@@'
	verdict=$(printf '%s\n' "$out" | grep '^@@verdict' | tail -n 1)
	record "Heat" "$(printf '%s' "$verdict" | cut -f 2)" "$(printf '%s' "$verdict" | cut -f 3)"
	note "If it fails: more shade, a light-coloured box, vents with a drip edge, screen off, or a lower charge limit (plan 4.2, 4.5)."
}

# --- profile ------------------------------------------------------------------------------

json_str() { printf '"%s"' "$(printf '%s' "$1" | tr -d '\000-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }

json_list() {
	# comma-separated words -> JSON array
	local out="" w
	local IFS=,
	for w in $1; do
		[ -n "$w" ] || continue
		out="$out${out:+, }$(json_str "$w")"
	done
	printf '[%s]' "$out"
}

regex_escape() { printf '%s' "$1" | sed 's/[][\\.*^$+?(){}|/]/\\&/g'; }

cmd_profile() {
	local manuf model id name android build unit sign func method verified anote cc notes cpu modem skin batt
	local f_out unsupported line p on off mode pnote
	[ -f "$RESULTS_DIR/info.env" ] || die "No info.env in $RESULTS_DIR: run 'phase0.sh info' with the phone connected first." 1
	section "Draft device profile"
	manuf=$(kv_get info.env manufacturer)
	model=$(kv_get info.env model)
	android=$(kv_get info.env android)
	build=$(kv_get info.env build_id)
	id=$(printf '%s_%s' "$manuf" "$model" | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/_/g; s/^_+//; s/_+$//')
	name="$manuf $model"
	notes="$(json_str "Draft from tools/phase0/phase0.sh on $(date +%Y-%m-%d): $name, Android $android, build $build.")"

	cc=""
	if [ -f "$RESULTS_DIR/charge.tsv" ]; then
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			p=$(printf '%s' "$line" | cut -f 1)
			on=$(printf '%s' "$line" | cut -f 2)
			off=$(printf '%s' "$line" | cut -f 3)
			mode=$(printf '%s' "$line" | cut -f 6)
			if printf '%s' "$cc" | grep -qF "$(json_str "$p")"; then continue; fi
			cc="$cc${cc:+,
}    { \"path\": $(json_str "$p"), \"on\": $(json_str "$on"), \"off\": $(json_str "$off"), \"note\": $(json_str "verified with phase0.sh charge-discover --test on $(printf '%s' "$line" | cut -f 7): $mode") }"
		done <<EOF
$(awk -F '\t' '$4 == "yes" && $5 == "yes"' "$RESULTS_DIR/charge.tsv")
EOF
	fi
	if [ -f "$RESULTS_DIR/charge-candidates.tsv" ]; then
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			p=$(printf '%s' "$line" | cut -f 1)
			if printf '%s' "$cc" | grep -qF "$(json_str "$p")"; then continue; fi
			on=$(printf '%s' "$line" | cut -f 4)
			off=$(printf '%s' "$line" | cut -f 5)
			pnote=$(printf '%s' "$line" | cut -f 7)
			cc="$cc${cc:+,
}    { \"path\": $(json_str "$p"), \"on\": $(json_str "$on"), \"off\": $(json_str "$off"), \"note\": $(json_str "UNTESTED candidate: $pnote") }"
		done <<EOF
$(awk -F '\t' '$3 == "rw" && $6 == "switch"' "$RESULTS_DIR/charge-candidates.tsv")
EOF
	fi
	if [ -z "$cc" ]; then
		notes="$notes,
    $(json_str "No charge-control file found or verified: charge_control is empty, so the Agent cannot enforce the charge limit.")"
	fi

	unit=$(kv_get current.env unit)
	sign=$(kv_get current.env sign)
	if [ -z "$unit" ] || [ -z "$sign" ]; then
		unit=uA
		sign=1
		notes="$notes,
    $(json_str "current_now unit/sign not measured (defaults uA, +1): run phase0.sh current-sign.")"
	fi

	func=$(kv_get usb.env function)
	[ -n "$func" ] || func=rndis

	method=$(kv_get autoboot.env method)
	verified=$(kv_get autoboot.env verified)
	[ -n "$method" ] || method=unknown
	case "$verified" in
	yes) anote="verified in Phase 0 (phone booted by itself with power connected)" ;;
	no) anote="FAILED in Phase 0: fix before deployment" ;;
	*) anote="not verified yet: phase0.sh autoboot --test" ;;
	esac

	cpu=$(kv_get thermal.env cpu)
	modem=$(kv_get thermal.env modem)
	skin=$(kv_get thermal.env skin)
	batt=$(kv_get thermal.env battery)
	if [ -z "$cpu$modem$skin$batt" ]; then
		notes="$notes,
    $(json_str "thermal keywords are generic defaults: run phase0.sh thermal.")"
	fi
	[ -n "$cpu" ] || cpu="cpu,tsens"
	[ -n "$modem" ] || modem="modem,mdm,pa_therm,pa-therm"
	[ -n "$skin" ] || skin="skin,xo_therm,xo-therm,quiet"
	[ -n "$batt" ] || batt="battery"

	if [ -f "$RESULTS_DIR/commands.tsv" ]; then
		unsupported=$(awk -F '\t' '$4 == "unsupported" || $4 == "failed" { printf "%s%s (%s)", (c++ ? ", " : ""), $2, $3 }' "$RESULTS_DIR/commands.tsv")
		if [ -n "$unsupported" ]; then
			notes="$notes,
    $(json_str "Phase 0 commands not working with the default method: $unsupported. Add overrides under commands.")"
		fi
	fi

	if [ -n "$cc" ]; then
		cc="[
$cc
  ]"
	else
		cc="[]"
	fi
	f_out="$RESULTS_DIR/profile-$id.json"
	# Written straight to the file: bash 3.2 mis-parses here-documents inside $(...).
	cat >"$f_out" <<EOF
{
  "id": $(json_str "$id"),
  "name": $(json_str "$name"),
  "match": { "manufacturer": [$(json_str "$(printf '%s' "$manuf" | tr 'A-Z' 'a-z')")], "model_regex": $(json_str "^$(regex_escape "$model")\$") },
  "charge_control": $cc,
  "current_now": { "unit": $(json_str "$unit"), "sign": $sign },
  "usb_function": $(json_str "$func"),
  "autoboot": { "method": $(json_str "$method"), "note": $(json_str "$anote") },
  "thermal": {
    "cpu": $(json_list "$cpu"),
    "modem": $(json_list "$modem"),
    "skin": $(json_list "$skin"),
    "battery": $(json_list "$batt")
  },
  "notes": [
    $notes
  ]
}
EOF
	cat "$f_out"
	if command -v python3 >/dev/null 2>&1; then
		if python3 -m json.tool "$f_out" >/dev/null 2>&1; then note "JSON is valid."; else warn "The draft is not valid JSON; please report this."; fi
	elif command -v node >/dev/null 2>&1; then
		if node -e 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))' "$f_out" >/dev/null 2>&1; then note "JSON is valid."; else warn "The draft is not valid JSON; please report this."; fi
	fi
	say "Saved: $f_out"
	note "Review it (docs/device-profiles.md), keep only verified charge_control entries first, then copy it to"
	note "agent/app/src/main/assets/profiles/$id.json, or paste it into settings.profile_override from the Dashboard."
}

# --- report -------------------------------------------------------------------------------

cmd_report() {
	local f="$RESULTS_DIR/results.tsv" md="$RESULTS_DIR/phase0-table.md" row name must status detail at missing=0
	section "Phase 0 results ($RESULTS_DIR)"
	printf '  %-24s %-6s %-8s %s\n' "Test" "Must" "Status" "Detail"
	{
		printf '| Test | Must-have? | Result | Detail | Date |\n'
		printf '|------|------------|--------|--------|------|\n'
	} >"$md"
	while IFS= read -r row; do
		[ -n "$row" ] || continue
		name=${row%%:*}
		must=${row##*:}
		status="NOT RUN"
		detail=""
		at=""
		if [ -f "$f" ]; then
			status=$(awk -F '\t' -v k="$name" '$1 == k { print $2 }' "$f" | tail -n 1)
			detail=$(awk -F '\t' -v k="$name" '$1 == k { print $4 }' "$f" | tail -n 1)
			at=$(awk -F '\t' -v k="$name" '$1 == k { print $3 }' "$f" | tail -n 1)
			[ -n "$status" ] || status="NOT RUN"
		fi
		printf '  %-24s %-6s %-8s %s\n' "$name" "$must" "$status" "$detail"
		printf '| %s | %s | %s | %s | %s |\n' "$name" "$must" "$status" "$detail" "${at%%T*}" >>"$md"
		if [ "$must" = yes ] && [ "$status" != PASS ]; then missing=$((missing + 1)); fi
	done <<EOF
$PHASE0_ROWS
EOF
	say ""
	if [ "$missing" -eq 0 ]; then
		say "Exit condition met: every must-have row passes."
	else
		say "$missing must-have row(s) not passed yet. Exit (plan 12): all must-have rows pass, or choose a different phone."
	fi
	say "Markdown table for docs/phase0-results.md: $md"
}

# --- all ----------------------------------------------------------------------------------

cmd_all() {
	local secs=30 step
	while [ $# -gt 0 ]; do
		case "$1" in
		--seconds)
			need_value "$1" "${2:-}"
			secs=$2
			shift
			;;
		*) die "all: unknown option $1" 2 ;;
		esac
		shift
	done
	for step in info root commands signal charge-discover thermal usb autoboot profile; do
		case "$step" in
		info) (cmd_info) || warn "info did not complete" ;;
		root) (cmd_root) || warn "root did not pass" ;;
		commands) (cmd_commands) || warn "commands did not complete" ;;
		signal) (cmd_signal --seconds "$secs") || warn "signal did not complete" ;;
		charge-discover)
			if root_ok; then (cmd_charge_discover) || warn "charge-discover did not complete"; else warn "charge-discover skipped: no root"; fi
			;;
		thermal) (cmd_thermal) || warn "thermal did not complete" ;;
		usb) (cmd_usb) || warn "usb did not complete" ;;
		autoboot) (cmd_autoboot --check) || warn "autoboot did not complete" ;;
		profile) (cmd_profile >/dev/null) || warn "profile did not complete" ;;
		esac
	done
	say ""
	say "Not part of 'all' (they change things or take hours): root --reboot, commands --live,"
	say "charge-discover --test, current-sign, usb --live / --reboot-test, autoboot --test,"
	say "power-log, heat-log. See docs/phase0.md."
	cmd_report
}

# --- main ---------------------------------------------------------------------------------

run_sub() {
	trap 'run_restores' EXIT
	trap 'run_restores; exit 130' INT TERM
	"$@"
}

main() {
	local sub="" log rc fn
	ARGS=()
	while [ $# -gt 0 ]; do
		case "$1" in
		-h | --help | help)
			usage
			exit 0
			;;
		--version)
			say "phase0.sh $PHASE0_VERSION"
			exit 0
			;;
		-s | --serial)
			need_value "$1" "${2:-}"
			ANDROID_SERIAL=$2
			export ANDROID_SERIAL
			shift
			;;
		--results)
			need_value "$1" "${2:-}"
			RESULTS_DIR=$2
			shift
			;;
		--new) NEW_SESSION=1 ;;
		*)
			if [ -z "$sub" ]; then sub=$1; else ARGS+=("$1"); fi
			;;
		esac
		shift
	done
	if [ -z "$sub" ]; then
		usage >&2
		exit 2
	fi
	case "$sub" in
	info | root | commands | signal | charge-discover | current-sign | thermal | usb | autoboot | power-log | heat-log | all)
		require_device
		ensure_results_dir
		;;
	profile | report)
		offline_results_dir
		;;
	*)
		printf 'Unknown command: %s\n\n' "$sub" >&2
		usage >&2
		exit 2
		;;
	esac
	log="$RESULTS_DIR/log-$sub.txt"
	{
		printf '\n=== %s  phase0.sh %s %s  (phone %s %s, serial %s)\n' "$(date +%Y-%m-%dT%H:%M:%S)" "$sub" \
			"${ARGS[*]+${ARGS[*]}}" "${DEV_MANUF:-}" "${DEV_MODEL:-}" "${ANDROID_SERIAL:-offline}"
	} >>"$log"
	say "Results: $RESULTS_DIR"
	case "$sub" in
	charge-discover) fn=cmd_charge_discover ;;
	current-sign) fn=cmd_current_sign ;;
	power-log) fn="logger_cmd power" ;;
	heat-log) fn="logger_cmd heat" ;;
	*) fn="cmd_$sub" ;;
	esac
	# $fn is unquoted on purpose: "logger_cmd power" is a function plus its first argument.
	# shellcheck disable=SC2086
	run_sub $fn ${ARGS[@]+"${ARGS[@]}"} 2>&1 | tee -i -a "$log"
	rc=${PIPESTATUS[0]}
	exit "$rc"
}

main "$@"
