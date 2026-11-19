#!/system/bin/sh
# Optional (plan 5 and 10): ADB over the network for scrcpy, reachable ONLY through Tailscale.
# Not part of the module zip. Install it as a boot script:
#   adb push agent/magisk/extras/hsm-adb-vpn.sh /data/local/tmp/
#   adb shell su -c 'cp /data/local/tmp/hsm-adb-vpn.sh /data/adb/service.d/ && chmod 0755 /data/adb/service.d/hsm-adb-vpn.sh'
# then reboot. Remove the file from /data/adb/service.d/ and reboot to undo.
#
# The firewall goes up before adbd listens on TCP, and is re-applied every 10 minutes because
# netd may rebuild iptables. ADB's own key authorisation still applies on top.

PORT=5555
TS_V4=100.64.0.0/10
TS_V6=fd7a:115c:a1e0::/48
CHAIN=HSM_ADB

apply_firewall() {
	for ipt in iptables ip6tables; do
		command -v "$ipt" >/dev/null 2>&1 || continue
		if [ "$ipt" = iptables ]; then src=$TS_V4; else src=$TS_V6; fi
		# Filled only when the chain is new: never flushed, so the port is never briefly open.
		if "$ipt" -w -N "$CHAIN" 2>/dev/null; then
			"$ipt" -w -A "$CHAIN" -i lo -j RETURN
			# Tailscale's VPN interface is a tun device; accept only tailnet source addresses on it.
			"$ipt" -w -A "$CHAIN" -i tun+ -s "$src" -j RETURN
			"$ipt" -w -A "$CHAIN" -j DROP
		fi
		"$ipt" -w -C INPUT -p tcp --dport "$PORT" -j "$CHAIN" 2>/dev/null ||
			"$ipt" -w -I INPUT 1 -p tcp --dport "$PORT" -j "$CHAIN"
	done
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done

apply_firewall
setprop service.adb.tcp.port "$PORT"
stop adbd
start adbd
log -t hsm-adb-vpn "adb listening on tcp:$PORT, reachable only from Tailscale (tun+, $TS_V4 / $TS_V6)"

while true; do
	sleep 600
	apply_firewall
done
