#!/system/bin/sh
# Magisk 28+ / KernelSU "Action" button, or by hand:
#   adb shell su -c sh /data/adb/modules/hsm-keepalive/action.sh
# Re-grants the Agent's permissions and exemptions and starts it now. The boot loop in
# service.sh keeps running independently.

MODDIR=${0%/*}
. "$MODDIR/common.sh"
load_config

if ! agent_installed; then
	echo "The Agent ($PKG) is not installed."
	exit 1
fi
echo "Granting permissions and exemptions..."
grant_permissions
apply_exemptions
if agent_running && service_running; then
	echo "The Agent is running."
	log_msg "action: Agent already running"
	exit 0
fi
echo "Starting the Agent..."
if start_agent manual; then
	echo "The Agent is running."
	log_msg "action: Agent started"
else
	echo "The Agent did not start: see $LOG and 'adb logcat -s hsm-keepalive'."
	log_msg "action: Agent did not start"
	exit 1
fi
