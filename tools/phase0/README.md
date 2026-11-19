# Phase 0 tools

Host-side scripts for Phase 0 of the plan (feasibility on the real phone, `docs/plan.md` section 12).
The full procedure, pass conditions and what to do on failure are in [`docs/phase0.md`](../../docs/phase0.md).

| File | What it does |
|------|--------------|
| `phase0.sh` | Runs the Phase 0 tests over `adb` + `su` and writes results to `results/<timestamp>-<model>/` |
| `device/hsm-phase0-device.sh` | Helper that `phase0.sh` pushes to the phone (`/data/local/tmp/hsm-phase0/`); mksh/toybox only |
| `build-magisk-module.sh` | Zips `agent/magisk/` into `dist/hsm-keepalive.zip` (the keep-alive module) |

Needs: Android platform-tools (`adb`, and `fastboot` for `autoboot --fastboot`), bash (macOS's 3.2 is
fine), awk, sed. USB debugging on, and root (Magisk or KernelSU) for most tests.

```sh
tools/phase0/phase0.sh --help
tools/phase0/phase0.sh all                     # safe checks, then the Phase 0 table
tools/phase0/phase0.sh root --cold-boot        # root must survive a start from fully off (S10!)
tools/phase0/phase0.sh charge-discover --test /sys/class/power_supply/battery/batt_slate_mode
tools/phase0/phase0.sh power-log start         # 24 h+ power budget test (then: power-log pull)
tools/phase0/phase0.sh report                  # table so far; also writes phase0-table.md
tools/phase0/phase0.sh profile                 # draft device profile JSON
tools/phase0/build-magisk-module.sh
```

Nothing is changed on the phone unless an option says so (`--live`, `--test`, `--reboot`,
`--cold-boot`, `--reboot-test`, `--fastboot`, `power-log start`), and those ask first. Values written by a test
are restored on exit, Ctrl-C included.

`results/` and `dist/` are git-ignored: results contain serial numbers, cell IDs and logs. Copy the
summary into `docs/phase0-results.md` instead.
