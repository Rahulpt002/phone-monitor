#!/system/bin/sh
# Optional (plan 5 and 10): ADB over the network for scrcpy, reachable ONLY through Tailscale.
# Not part of the module zip. Install it as a boot script:
#   adb push agent/magisk/extras/hsm-adb-vpn.sh /data/local/tmp/
#   adb shell su -c 'cp /data/local/tmp/hsm-adb-vpn.sh /data/adb/service.d/ && chmod 0755 /data/adb/service.d/hsm-adb-vpn.sh'
# then reboot. Remove the file from /data/adb/service.d/ and reboot to undo.
#
# Fails closed: adbd listens on TCP only after the firewall is verified for IPv4 AND IPv6. If a
# rule cannot be applied (iptables or ip6tables missing, a command failing), TCP adb stays off (or
# is switched off again) and the script retries every minute. The rules are re-checked every 10
# minutes because netd may rebuild iptables. ADB's own key authorisation still applies on top.

PORT=5555
TS_V4=100.64.0.0/10
TS_V6=fd7a:115c:a1e0::/48
CHAIN=HSM_ADB
TAG=hsm-adb-vpn

say() {
	log -t "$TAG" "$1" 2>/dev/null
	echo "$TAG: $1"
}

# The three rules of the chain are all present (order: lo, tailnet on tun+, DROP).
chain_ok() {
	"$1" -w -C "$CHAIN" -i lo -j RETURN 2>/dev/null &&
		"$1" -w -C "$CHAIN" -i tun+ -s "$2" -j RETURN 2>/dev/null &&
		"$1" -w -C "$CHAIN" -j DROP 2>/dev/null
}

jump_ok() {
	"$1" -w -C INPUT -p tcp --dport "$PORT" -j "$CHAIN" 2>/dev/null
}

# One address family. Returns 0 only when the port is verified to be guarded.
apply_family() {
	ipt=$1
	src=$2
	command -v "$ipt" >/dev/null 2>&1 || { say "$ipt not found: cannot guard port $PORT"; return 1; }
	chain_ok "$ipt" "$src" && jump_ok "$ipt" && return 0
	# Something is missing (first run, or netd rebuilt the tables). Block the port outright while the
	# chain is rebuilt, so it is never open; the block stays if any step below fails.
	"$ipt" -w -C INPUT -p tcp --dport "$PORT" -j DROP 2>/dev/null ||
		"$ipt" -w -I INPUT 1 -p tcp --dport "$PORT" -j DROP ||
		{ say "$ipt: could not block port $PORT"; return 1; }
	if ! chain_ok "$ipt" "$src"; then
		# Tailscale's VPN interface is a tun device; accept only tailnet source addresses on it.
		"$ipt" -w -N "$CHAIN" 2>/dev/null
		"$ipt" -w -F "$CHAIN" &&
			"$ipt" -w -A "$CHAIN" -i lo -j RETURN &&
			"$ipt" -w -A "$CHAIN" -i tun+ -s "$src" -j RETURN &&
			"$ipt" -w -A "$CHAIN" -j DROP ||
			{ say "$ipt: could not build chain $CHAIN"; return 1; }
	fi
	jump_ok "$ipt" || "$ipt" -w -I INPUT 1 -p tcp --dport "$PORT" -j "$CHAIN" ||
		{ say "$ipt: could not attach chain $CHAIN to INPUT"; return 1; }
	chain_ok "$ipt" "$src" && jump_ok "$ipt" || { say "$ipt: rules did not verify"; return 1; }
	# Verified: lift the temporary block (the chain now decides).
	while "$ipt" -w -D INPUT -p tcp --dport "$PORT" -j DROP 2>/dev/null; do :; done
	return 0
}

firewall_ok() {
	ok=0
	apply_family iptables "$TS_V4" || ok=1
	apply_family ip6tables "$TS_V6" || ok=1
	return $ok
}

adb_tcp_on() {
	[ "$(getprop service.adb.tcp.port)" = "$PORT" ]
}

open_adb() {
	setprop service.adb.tcp.port "$PORT"
	stop adbd
	start adbd
	say "adb listening on tcp:$PORT, reachable only from Tailscale (tun+, $TS_V4 / $TS_V6)"
}

close_adb() {
	setprop service.adb.tcp.port 0
	stop adbd
	start adbd
	say "firewall not verified: adb over TCP switched off (USB adb still works)"
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done

pport=$(getprop persist.adb.tcp.port)
if [ -n "$pport" ] && [ "$pport" != 0 ]; then
	say "warning: persist.adb.tcp.port=$pport opens adb on TCP regardless of this script; clear it with: setprop persist.adb.tcp.port ''"
fi

while true; do
	if firewall_ok; then
		adb_tcp_on || open_adb
		sleep 600
	else
		adb_tcp_on && close_adb
		say "firewall rules failed; adb over TCP stays off, retrying in 60 s"
		sleep 60
	fi
done
