/**
 * Text the simulated phone prints: `top -n 1`, a small fake root shell and USSD replies.
 * The fake shell never changes the simulated state; the dedicated commands do that.
 */
import type { Phone } from './phone/phone.js';
import { formatDuration } from './util.js';

export function topText(phone: Phone): string {
  const sys = phone.system;
  const procs = sys.top(phone.utilisation());
  const memTotalK = sys.ramTotal * 1024;
  const memFreeK = sys.ramAvailMb * 1024;
  const cpuSum = Math.round(procs.reduce((a, p) => a + (p.cpu_pct ?? 0), 0));
  const header = [
    `Tasks: 412 total,   1 running, 411 sleeping,   0 stopped,   0 zombie`,
    `  Mem: ${String(memTotalK).padStart(9)}K total, ${String(memTotalK - memFreeK).padStart(9)}K used, ${String(memFreeK).padStart(9)}K free,    12288K buffers`,
    ` Swap:   4194300K total,    102400K used,   4091900K free,   2088960K cached`,
    `800%cpu ${String(Math.round(cpuSum * 0.6)).padStart(3)}%user   0%nice ${String(Math.round(cpuSum * 0.4)).padStart(3)}%sys ${String(800 - cpuSum).padStart(3)}%idle   0%iow   0%irq   0%sirq   0%host`,
    `  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS`,
  ];
  const rows = procs.map((p) => {
    const mem = ((p.mem_mb ?? 0) / sys.ramTotal) * 100;
    const res = p.mem_mb ? `${p.mem_mb}M` : '0';
    return [
      String(p.pid).padStart(5),
      p.user.padEnd(12),
      ' 20   0',
      ' 14G',
      res.padStart(4),
      ' 52M',
      'S',
      (p.cpu_pct ?? 0).toFixed(1).padStart(5),
      mem.toFixed(1).padStart(5),
      '  4:12.33',
      p.name,
    ].join(' ');
  });
  return [...header, ...rows].join('\n');
}

export interface ShellResult {
  code: number;
  output: string;
}

/** Answers a few common root-shell commands like a rooted Android 13 phone would. */
export function fakeShell(cmdline: string, phone: Phone, nowMs: number): ShellResult {
  const outputs: string[] = [];
  let code = 0;
  // `a && b` stops at the first failure, `a; b` always continues.
  const parts = cmdline.split(/(&&|;)/).map((s) => s.trim());
  let skip = false;
  for (const part of parts) {
    if (part === '&&') {
      skip = code !== 0;
      continue;
    }
    if (part === ';') {
      skip = false;
      continue;
    }
    if (!part || skip) continue;
    const res = runOne(part, phone, nowMs);
    if (res.output) outputs.push(res.output);
    code = res.code;
  }
  return { code, output: outputs.join('\n') };
}

function runOne(line: string, phone: Phone, nowMs: number): ShellResult {
  const [cmd = '', ...args] = line.split(/\s+/);
  const ok = (output: string): ShellResult => ({ code: 0, output });
  const sys = phone.system;
  const pw = phone.power;
  switch (cmd) {
    case 'id':
      return ok('uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0');
    case 'whoami':
      return ok('root');
    case 'true':
      return ok('');
    case 'false':
      return { code: 1, output: '' };
    case 'exit':
      return { code: Number.parseInt(args[0] ?? '0', 10) || 0, output: '' };
    case 'echo':
      return ok(args.join(' ').replace(/^["']|["']$/g, ''));
    case 'date':
      return ok(new Date(nowMs).toString());
    case 'uname':
      return ok(
        args.includes('-a')
          ? 'Linux localhost 5.4.254-qgki-27894318-abG991BXXSBGXJ1 #1 SMP PREEMPT Mon Oct 7 10:21:53 KST 2024 aarch64 Toybox'
          : 'Linux',
      );
    case 'uptime': {
      const up = Math.floor((nowMs - sys.bootAt) / 1000);
      const [l1, l5, l15] = sys.cpuLoad;
      const t = new Date(nowMs).toTimeString().slice(0, 8);
      return ok(` ${t} up ${formatDuration(up)},  0 users,  load average: ${l1.toFixed(2)}, ${l5.toFixed(2)}, ${l15.toFixed(2)}`);
    }
    case 'getprop':
      return getprop(args[0], phone);
    case 'top':
      return ok(topText(phone));
    case 'ps':
      return ok(
        ['USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME']
          .concat(sys.top(phone.utilisation()).map((p) => `${p.user.padEnd(12)} ${String(p.pid).padStart(5)}     1 1234567 ${String((p.mem_mb ?? 0) * 1024).padStart(6)} 0                   0 S ${p.name}`))
          .join('\n'),
      );
    case 'cat':
      return catFile(args[0] ?? '', phone);
    case 'df':
      return ok(
        [
          'Filesystem       Size  Used Avail Use% Mounted on',
          `/dev/block/dm-8  ${(sys.storageTotal / 1024).toFixed(0)}G   ${((sys.storageTotal - sys.storageFreeMb) / 1024).toFixed(0)}G   ${(sys.storageFreeMb / 1024).toFixed(0)}G  ${Math.round((1 - sys.storageFreeMb / sys.storageTotal) * 100)}% /data`,
        ].join('\n'),
      );
    case 'free':
      return ok(
        [
          '      total        used        free      shared     buffers',
          `Mem:  ${sys.ramTotal}M  ${sys.ramTotal - sys.ramAvailMb}M  ${sys.ramAvailMb}M  24M  12M`,
        ].join('\n'),
      );
    case 'ip':
      return ok(ipAddr(phone));
    case 'ping': {
      if (!phone.internetOk) return { code: 2, output: `ping: unknown host ${args[args.length - 1] ?? ''}` };
      const host = args[args.length - 1] ?? '1.1.1.1';
      const n = Math.min(10, Number.parseInt(args[args.indexOf('-c') + 1] ?? '4', 10) || 4);
      const lines = [`PING ${host} (${host}) 56(84) bytes of data.`];
      for (let i = 1; i <= n; i++) lines.push(`64 bytes from ${host}: icmp_seq=${i} ttl=57 time=${(38 + Math.random() * 12).toFixed(1)} ms`);
      lines.push('', `--- ${host} ping statistics ---`, `${n} packets transmitted, ${n} received, 0% packet loss`);
      return ok(lines.join('\n'));
    }
    case 'dumpsys':
      if (args[0] === 'battery') {
        return ok(
          [
            'Current Battery Service state:',
            `  AC powered: false`,
            `  USB powered: ${pw.plugged}`,
            `  status: ${pw.status() === 'charging' ? 2 : pw.status() === 'not_charging' ? 4 : 3}`,
            `  health: 2`,
            `  present: true`,
            `  level: ${Math.round(pw.percent)}`,
            `  scale: 100`,
            `  voltage: ${pw.voltageMv()}`,
            `  temperature: ${Math.round(phone.heat.battery * 10)}`,
            `  technology: Li-ion`,
          ].join('\n'),
        );
      }
      return ok('');
    case 'settings':
      return settingsCmd(args, phone);
    case 'pm':
      if (args[0] === 'list' && args[1] === 'packages') {
        const disabled = args.includes('-d');
        return ok(phone.apps.filter((a) => !disabled || !a.enabled).map((a) => `package:${a.pkg}`).join('\n'));
      }
      return ok('');
    case 'ls':
      return ok(['acct', 'apex', 'bin', 'cache', 'config', 'data', 'dev', 'etc', 'proc', 'product', 'sdcard', 'storage', 'sys', 'system', 'vendor'].join('\n'));
    case 'svc':
    case 'cmd':
    case 'am':
    case 'input':
    case 'sleep':
      // Accepted silently, like the real tools. The simulator's dedicated commands change state.
      return ok('');
    default:
      return { code: 127, output: `/system/bin/sh: ${cmd}: inaccessible or not found` };
  }
}

function getprop(key: string | undefined, phone: Phone): ShellResult {
  const props: Record<string, string> = {
    'ro.product.manufacturer': 'samsung',
    'ro.product.model': 'SM-G991B',
    'ro.product.device': 'o1s',
    'ro.build.version.release': '13',
    'ro.build.version.sdk': '33',
    'ro.build.id': 'TP1A.220624.014',
    'sys.boot.reason': phone.system.bootReason,
    'ro.boot.bootreason': phone.system.bootReason,
    'gsm.network.type': phone.netType(),
    'gsm.sim.state': phone.flags.airplane ? 'NOT_READY' : 'READY',
    'gsm.operator.alpha': phone.radioOn ? 'SimTel 4G' : '',
    'sys.usb.config': phone.tetherOn ? `${phone.flags.usbFunction},adb` : 'mtp,adb',
    'persist.sys.timezone': Intl.DateTimeFormat().resolvedOptions().timeZone,
  };
  if (!key) return { code: 0, output: Object.entries(props).map(([k, v]) => `[${k}]: [${v}]`).join('\n') };
  return { code: 0, output: props[key] ?? '' };
}

function catFile(path: string, phone: Phone): ShellResult {
  const pw = phone.power;
  const files: Record<string, string> = {
    '/proc/loadavg': `${phone.system.cpuLoad.map((n) => n.toFixed(2)).join(' ')} 1/1203 31337`,
    '/sys/class/power_supply/battery/capacity': String(Math.round(pw.percent)),
    '/sys/class/power_supply/battery/current_now': String(pw.currentMa * 1000),
    '/sys/class/power_supply/battery/voltage_now': String(pw.voltageMv() * 1000),
    '/sys/class/power_supply/battery/temp': String(Math.round(phone.heat.battery * 10)),
    '/sys/class/power_supply/battery/status': pw.status() === 'not_charging' ? 'Not charging' : pw.status() === 'charging' ? 'Charging' : 'Discharging',
    '/sys/class/power_supply/battery/batt_slate_mode': pw.chargingEnabled ? '0' : '1',
  };
  const content = files[path];
  if (content === undefined) return { code: 1, output: `cat: ${path}: No such file or directory` };
  return { code: 0, output: content };
}

function ipAddr(phone: Phone): string {
  const lines = [
    '1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN',
    '    inet 127.0.0.1/8 scope host lo',
  ];
  if (phone.radioOn && phone.flags.dataEnabled) {
    lines.push('14: rmnet_data2: <UP,LOWER_UP> mtu 1500 qdisc mq state UNKNOWN', '    inet 100.72.18.44/30 scope global rmnet_data2');
  }
  const tetherIf = phone.tetherIf;
  if (tetherIf) {
    lines.push(`31: ${tetherIf}: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP`, `    inet 192.168.42.129/24 brd 192.168.42.255 scope global ${tetherIf}`);
  }
  return lines.join('\n');
}

function settingsCmd(args: string[], phone: Phone): ShellResult {
  if (args[0] !== 'get') return { code: 0, output: '' };
  const key = `${args[1] ?? ''} ${args[2] ?? ''}`;
  const values: Record<string, string> = {
    'global airplane_mode_on': phone.flags.airplane ? '1' : '0',
    'global mobile_data': phone.flags.dataEnabled ? '1' : '0',
    'system screen_brightness': String(phone.system.brightness),
    'global boot_count': String(phone.system.bootCount),
  };
  return { code: 0, output: values[key] ?? 'null' };
}

/** Reply to a USSD code, like a carrier balance / plan menu. */
export function ussdReply(code: string, phone: Phone): string {
  const usedGb = phone.net.traffic(null).today_rx ?? 0;
  const leftGb = Math.max(0, 2 - usedGb / 1e9).toFixed(2);
  switch (code) {
    case '*121#':
    case '*123#':
      return `Main Bal: Rs 249.50. Daily data left: ${leftGb} GB of 2 GB. Pack valid till ${validTill()}.`;
    case '*333#':
      return `Your plan: 2 GB/day, unlimited calls. Valid till ${validTill()}. Reply 1 to renew.`;
    case '*1#':
    case '*282#':
      return 'Your mobile number is 7000012345.';
    case '*#06#':
      return `IMEI: 35*********${phone.imeiTail}`;
    default:
      return 'Sorry, this service is not available. Please try again later.';
  }
}

function validTill(): string {
  const d = new Date(Date.now() + 23 * 86_400_000);
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }).replace(/ /g, '-');
}

/** A carrier answering an SMS sent to a short code. */
export function shortCodeReply(to: string, body: string): string {
  if (/bal/i.test(body)) return 'Your main balance is Rs 249.50. Data left today: 1.42 GB.';
  if (/plan|pack/i.test(body)) return 'Your current pack: 2 GB/day, valid 28 days. Recharge at the nearest outlet.';
  return `Thank you for contacting ${to}. Send BAL for balance or PLAN for your current pack.`;
}
