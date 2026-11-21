# Device profiles

Everything that differs between phone models lives in one small JSON file, the device profile
(plan 6.1). Supporting a new phone means adding a profile, not changing the Agent. Profiles ship
inside the APK as `agent/app/src/main/assets/profiles/<id>.json`; the Server can adjust the one in
use through `settings.profile_override`.

| File | Phones |
|------|--------|
| [`generic.json`](../agent/app/src/main/assets/profiles/generic.json) | Fallback for any phone without its own profile |
| [`samsung_galaxy_s.json`](../agent/app/src/main/assets/profiles/samsung_galaxy_s.json) | Galaxy S10, S20, S21 families (`SM-G97x`, `SM-G98x`, `SM-G99x`) |
| [`samsung_galaxy_m52.json`](../agent/app/src/main/assets/profiles/samsung_galaxy_m52.json) | Galaxy M52 5G (`SM-M526B`), from Phase 0 on the hotspot phone |
| [`google_pixel.json`](../agent/app/src/main/assets/profiles/google_pixel.json) | Google Pixel |
| [`oneplus.json`](../agent/app/src/main/assets/profiles/oneplus.json) | OnePlus (manufacturer-only match) |

There is no Motorola or Xiaomi profile yet: their charge-control files vary too much by model to
ship one untested. They use `generic.json`, which already tries the common Qualcomm and MediaTek
files. Write a model profile from Phase 0 results before deploying one.

## 1. How the Agent picks the profile

At start the Agent (`ProfileRepo`) reads every `.json` file in `assets/profiles/` (a file that
fails to parse is skipped and logged) and compares each profile's `match` with
`Build.MANUFACTURER` and `Build.MODEL`:

1. A profile matches when **all** of its criteria fit: `manufacturer` contains this phone's
   manufacturer (case-insensitive), and `model_regex` is **found** in the model (a search, not a
   full match: anchor it with `^` / `$` when needed). A missing criterion matches anything.
2. Among matching profiles the **most specific** wins: manufacturer + model_regex beats
   manufacturer only. Ties go to the first file name in alphabetical order.
3. No match: the profile with an empty `match` and id `generic` (`generic.json`), else any profile
   with an empty match, else the Agent's built-in generic profile.
4. `settings.profile_override` from the Server is then deep-merged over the chosen profile's
   JSON (section 4). An override that does not parse is ignored and logged.

The profile in use is reported as `device.profile_id` in the `static` telemetry, and the
charge-control path in use as `power.charge_control`.

## 2. Schema

```json
{
  "id": "samsung_galaxy_s",
  "name": "Samsung Galaxy S10/S20/S21",
  "match": { "manufacturer": ["samsung"], "model_regex": "^SM-G9[789]" },
  "charge_control": [
    { "path": "/sys/class/power_supply/battery/batt_slate_mode", "on": "0", "off": "1", "note": "..." }
  ],
  "current_now": { "unit": "mA", "sign": 1 },
  "usb_function": "rndis",
  "autoboot": { "method": "magisk_autoboot", "note": "..." },
  "thermal": {
    "cpu": ["big", "mid", "little", "cpu", "tsens"],
    "modem": ["modem", "mdm", "pa_therm"],
    "skin": ["skin", "xo_therm", "quiet"],
    "battery": ["battery"]
  },
  "commands": { "brightness": ["settings put system screen_brightness {level}"] },
  "network_mode": { "lte_only": "<value for the device's mechanism>" },
  "notes": ["free text"]
}
```

| Field | Type | Default when missing | Meaning |
|-------|------|---------------------|---------|
| `id` | string, required | | Unique id; use it as the file name (`<id>.json`). Lowercase, `_` separated. |
| `name` | string | `id` | Human name shown in the Agent and Dashboard. |
| `match.manufacturer` | string[] | any | `Build.MANUFACTURER` values, case-insensitive (`samsung`, `google`, `oneplus`). |
| `match.model_regex` | string (regex) | any | Searched in `Build.MODEL` (`SM-G973F`, `Pixel 7`). `generic.json` has `"match": {}`. |
| `charge_control` | object[] | `[]` | Charge switches, tried in order: the **first path that exists, has its owner write bit set and accepts its current value written back** wins (root ignores file permissions, so the Agent reads the mode instead of trusting `[ -w ]`). If the first real stop while plugged in does not stop charging, the Agent restores `on`, skips that path and uses the next one (until restart or a profile change). Each has `path`, `on` (value that allows charging), `off` (value that stops it) and an optional `note`. Values are written as text, exactly as given (`"0 1"` includes the space). A switch that cuts USB input (`input_suspend`, `batt_slate_mode`) makes Android report the charger as unplugged while charging is off; the Agent does not report that as `power_lost` (a disconnect within 30 s of its own `off` write), but still reports a real loss: the USB host going away during the pause, or no power within 30 s after it switches charging back on. |
| `current_now.unit` | `"uA"` \| `"mA"` | `"uA"` | Unit of the raw `current_now` (sysfs, or `BatteryManager` which passes the same value through). |
| `current_now.sign` | `1` \| `-1` | `1` | Multiplier that makes charging positive. |
| `usb_function` | `"rndis"` \| `"ncm"` | `"rndis"` | USB function for tethering (`svc usb setFunctions <fn>`). Precedence everywhere: a command's `function` argument, then this value (with `profile_override`), then `settings.watchdog.usb_function`. The watchdog's tether fix passes the function last seen with tethering up, so it never switches a working NCM setup back to RNDIS. |
| `autoboot.method` | see below | `"unknown"` | How automatic power-on is done on this phone (plan 4.3). Informational: the Agent cannot set it up. |
| `autoboot.note` | string | | How it was set up and whether Phase 0 proved it. |
| `thermal.cpu` / `modem` / `skin` / `battery` | string[] | built-in lists | Substrings matched against `/sys/class/thermal/thermal_zone*/type` to fill `heat.cpu_c`, `modem_c`, `skin_c`, `battery_c`. **Always give all four**: a missing key falls back to the Agent's built-in list. |
| `commands` | object | `{}` | Per-command shell templates that replace the built-in implementation (section 3). |
| `network_mode` | object | `{}` | `NetworkMode` value (`auto`, `nr_lte`, `lte_only`, `lte_wcdma`, `wcdma_only`) to the value the Agent passes to this phone's network-mode mechanism. |
| `notes` | string[] | `[]` | Free text: caveats, Phase 0 date, model quirks. |

`autoboot.method` values: `fastboot_oem_off_mode_charge` (`fastboot oem off-mode-charge 0`),
`magisk_autoboot` (a root change that makes charging-only mode boot Android, typical on Samsung),
`lpm_replace` (the charging-animation program replaced, older Samsung), `none` (proven
impossible: do not deploy this phone), `unknown`.

### Charge-control entries

- Order matters. Put the path proven by `phase0.sh charge-discover --test` first. A file that
  exists and is writable but does nothing hides a working one further down until the Agent's
  effect check (about 30 s after the first stop while charging) drops it, and that check only
  runs while the phone is plugged in and charging.
- Prefer an *idle* switch (status "not charging" while plugged in: the battery rests and USB
  powers the phone) over an *input-suspend* switch (the phone runs from the battery until
  charging resumes). Both work with the Agent's limit and 10% resume hysteresis (plan 6.5).
- Level-type files (`charge_stop_level`, `batt_full_capacity`, `charge_control_end_threshold`)
  work as switches with `on` = `100` and `off` = a value below any real charge (`5`).
- Mark anything not proven on this exact model with "Uncertain: verify" in its note.

### Thermal keywords

- Matching is by substring of the zone type, ignoring case, so `cpu` matches `cpu-0-0-usr` and
  `cpuss-1`, `pa_therm` matches `pa_therm0`, and `big` matches the Exynos / Tensor zone `BIG`.
  Write keywords in lowercase.
- The Agent's built-in lists (used for a key the profile leaves out, and by the built-in fallback
  profile) are cpu `cpu`, `tsens`, `big`, `mid`, `little`; modem `modem`, `mdm`, `pa_therm`; skin
  `skin`, `xo_therm`, `quiet_therm`; battery `battery`.
- Never use Qualcomm battery-current-limit zones (`soc`, `vbat`, `ibat`, `*-lvl*`): they report
  charge level, voltage or current, not temperature. `phase0.sh thermal` marks them `bcl`.
- `phase0.sh thermal` prints the zones of the connected phone and suggests keywords.

## 3. Command templates

`commands` maps a command name from [`shared/src/commands.ts`](../shared/src/commands.ts) to a
list of shell templates. The Agent runs them in order as root, **before** its built-in commands:
the first that exits 0 (and, for the switches that the Agent can read back, actually changes the
phone's state) wins; when every template fails, the built-in commands are tried. A missing or
empty list means built-in only. Templates are for commands whose built-in form fails on this phone
(see `commands.tsv` from `phase0.sh commands --live`).

- `{name}` placeholders are filled by the Agent with **shell-quoted** values (`'true'`,
  `'rndis'`); do not add quotes around them. A template that uses a placeholder the command does
  not supply is skipped (and logged).
- **One list serves both directions.** For the on/off commands the direction reaches the template
  only through `{enabled}`, `{state}` or `{action}`, so a template without one of them runs for
  *both* on and off. Write `svc data {action}`, not `svc data enable`.
- Never put secrets in a template; the root shell log records each command (hotspot templates are
  logged as the template, not the rendered command, because they carry the password).

Placeholders per command (the source is `ControlsImpl` in the Agent; `agent/ARCHITECTURE.md`
section 8 lists the same):

| Command | Placeholders |
|---------|--------------|
| `mobile_data`, `airplane_mode`, `wifi`, `bluetooth` | `{enabled}` `true`/`false`, `{state}` `1`/`0`, `{action}` `enable`/`disable` |
| `usb_tethering` | the three above, plus `{function}`: `rndis` or `ncm` when switching on (the command's `function` argument, else the profile's `usb_function`), `none` when switching off |
| `hotspot` | the three above, plus `{ssid}` and `{password}` (switching on only; switching off supplies neither, so an "on" template that uses them is skipped for "off") |
| `network_mode` | `{mode}` (`auto`, `nr_lte`, `lte_only`, `lte_wcdma`, `wcdma_only`), `{value}` (this profile's `network_mode` entry for the mode, else `{bitmask_bin}`), `{bitmask}` (decimal), `{bitmask_bin}` (binary string), `{slot}` (SIM slot index of the default data SIM), `{sub_id}` (its subscription id) |
| `screen` | `{on}` `true`/`false`, `{keycode}` `224` (wake) / `223` (sleep) |
| `brightness` | `{level}` 0-255 |
| `app_force_stop`, `app_enable`, `app_disable` | `{package}` |
| `reboot`, `power_off`, `process_list` | none |

- `data_cycle` and the watchdog's data cycle run the `mobile_data` templates (off, then on);
  `airplane_cycle` and the heat rule's radio rest run the `airplane_mode` templates.
- `charging` and `charge_limit` have no templates: they use `charge_control` (section 2).
- SMS, USSD, `app_list`, `shell`, the Finder commands and `refresh` / `capability_probe` use
  Android APIs or fixed logic and ignore `commands`.
- A command with a template counts as supported in the capability map, even where the built-in
  command does not exist on this Android version (except `bluetooth` on a phone without
  Bluetooth, and `power_off` while `settings.commands.allow_power_off` is false).

```json
"commands": {
  "mobile_data": ["svc data {action}"],
  "usb_tethering": ["svc usb setFunctions {function}"],
  "brightness": ["settings put system screen_brightness {level}"],
  "app_force_stop": ["am force-stop {package}", "killall {package}"]
}
```

To branch on the direction inside one template, compare the quoted value:
`[ {enabled} = true ] && cmd ... on || cmd ... off`.

### Network mode values

Without a `network_mode` template the Agent runs
`cmd phone set-allowed-network-types-for-users -s <slot> <value>` (Android 12+), where `<value>`
is the profile's `network_mode` entry for the requested mode, or the Agent's own bitmask when the
profile has none. AOSP parses this value as a **binary string** (radix 2), so a profile entry
must be written in binary, not decimal. An entry that is not a plain token
(`[A-Za-z0-9_|,.:+-]`, at most 64 characters) is only available to templates as `{value}`. When
the profile supplies the value the Agent cannot check the result, so prove it in Phase 0.

Bits (`TelephonyManager` network-type bitmask: bit = 1 << (network type - 1)):

| Network types | Decimal bits |
|---------------|--------------|
| NR (5G) | 524288 |
| LTE, LTE_CA | 4096, 262144 |
| UMTS, HSDPA, HSUPA, HSPA, HSPAP, TD_SCDMA (WCDMA family) | 4, 128, 256, 512, 16384, 65536 |
| GPRS, EDGE, GSM | 1, 2, 32768 |
| CDMA, EVDO 0/A/B, 1xRTT, eHRPD | 8, 16, 32, 64, 2048, 8192 |
| IWLAN | 131072 |

The Agent's built-in values per mode:

| Mode | Decimal | Binary (what the command takes) |
|------|---------|---------------------------------|
| `auto` | 1047551 | `11111111101111111111` |
| `nr_lte` | 790528 | `11000001000000000000` |
| `lte_only` | 266240 | `1000001000000000000` |
| `lte_wcdma` | 349060 | `1010101001110000100` |
| `wcdma_only` | 82820 | `10100001110000100` |

A profile entry is only needed when this phone's build wants something else, for example
`"network_mode": { "lte_only": "1000001000000000000" }`, or a different mechanism through a
`network_mode` template such as `["<command> {slot} {bitmask}"]`. The help text this phone prints
is saved by `phase0.sh commands` in `commands-network-mode-help.txt`, and
`phase0.sh commands --live` records what `get-allowed-network-types-for-users` prints. Network-mode
changes always arm the auto-revert (plan 6.4).

## 4. Server override (`settings.profile_override`)

An admin can change the profile in use without a new APK: Dashboard settings (or
`PUT /api/settings`) with a `profile_override` object. It is deep-merged over the matched
profile's JSON: **objects merge key by key; arrays and plain values replace**. So overriding
`charge_control` replaces the whole list, while `{"current_now": {"sign": -1}}` changes only the
sign. Set it back to `null` to remove the override.

```json
{
  "settings": {
    "profile_override": {
      "charge_control": [
        { "path": "/sys/class/power_supply/battery/input_suspend", "on": "0", "off": "1", "note": "verified 2026-11-20" }
      ],
      "current_now": { "sign": -1 }
    }
  }
}
```

Use the override to test a change on the deployed phone, then make it permanent in the profile
file with the next APK.

## 5. Writing a profile for a new phone

1. Run Phase 0 ([`phase0.md`](phase0.md)), at least `info`, `charge-discover --test`,
   `current-sign`, `usb --live`, `autoboot`, `thermal` and `commands --live`.
2. `tools/phase0/phase0.sh profile` writes `results/<...>/profile-<id>.json`: `match` from the
   model, verified charge paths first (then untested writable candidates marked "UNTESTED"),
   `current_now` from `current-sign`, `usb_function` from the tether interface, `autoboot` from
   `autoboot`, `thermal` from `thermal`, and notes listing commands that did not work.
3. Review it:
   - widen `match.model_regex` to the model family if you are confident (`^SM-G97[035]` rather
     than `^SM-G973F$`), and rename `id` / `name`;
   - keep only charge paths you trust, verified first;
   - add `commands` overrides for controls that failed but have a working alternative;
   - write down the auto-boot method actually installed in `autoboot.note`.
4. Validate: `python3 -m json.tool <id>.json` (or `node -e "JSON.parse(require('fs').readFileSync('<id>.json','utf8'))"`).
5. Copy it to `agent/app/src/main/assets/profiles/<id>.json`, rebuild the APK, install it, and
   check the Agent's status screen (or the Dashboard's device info) shows the new profile id, and
   that `power.charge_control` names your path.
