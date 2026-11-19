# Sourced by the Magisk / KernelSU / APatch module installer (busybox ash), which provides
# ui_print, set_perm, set_perm_recursive and $MODPATH.

ui_print "- HSM keep-alive for the Hotspot Monitor Agent (com.hotspotmonitor.agent)"
if pm path com.hotspotmonitor.agent 2>/dev/null | grep -q '^package:'; then
	ui_print "- Agent found: it will be started after every boot and restarted if it stops"
else
	ui_print "! Agent not installed yet: install the APK (adb install -r -g <apk>)"
	ui_print "  The module starts it as soon as it is present."
fi
ui_print "- Log:      /data/local/tmp/hsm-keepalive.log"
ui_print "- Settings: /data/adb/hsm-keepalive.conf (optional, see hsm-keepalive.conf.example)"
ui_print "- Pause:    su -c touch /data/adb/hsm-keepalive.pause"
ui_print "! This module does NOT make the phone power on by itself after a power cut."
ui_print "  Set that up separately (docs/install.md, plan 4.3 and Appendix A)."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
