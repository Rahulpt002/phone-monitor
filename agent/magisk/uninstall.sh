#!/system/bin/sh
# Run by Magisk / KernelSU while removing the module, early in boot (before Android services run),
# so it only removes files. Permissions and battery exemptions given to the Agent stay as they are;
# /data/adb/hsm-keepalive.conf is kept for a later reinstall (delete it by hand if unwanted).

rm -f /data/local/tmp/hsm-keepalive.log /data/local/tmp/hsm-keepalive.log.1
rm -f /data/adb/hsm-keepalive.pause /dev/.hsm-keepalive.pid
