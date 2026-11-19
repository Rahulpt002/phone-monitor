/** One line per notable event: `HH:MM:SS tag    message`. */

const ANSI = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m',
} as const;
type Color = keyof typeof ANSI;

export type LogTag =
  | 'sim'
  | 'pair'
  | 'conn'
  | 'hello'
  | 'tx'
  | 'rx'
  | 'cmd'
  | 'event'
  | 'revert'
  | 'wdog'
  | 'scen'
  | 'finder'
  | 'warn'
  | 'error';

const TAG_COLOR: Record<LogTag, Color> = {
  sim: 'cyan',
  pair: 'magenta',
  conn: 'blue',
  hello: 'blue',
  tx: 'gray',
  rx: 'gray',
  cmd: 'green',
  event: 'cyan',
  revert: 'magenta',
  wdog: 'yellow',
  scen: 'magenta',
  finder: 'green',
  warn: 'yellow',
  error: 'red',
};

export interface LoggerOptions {
  color: boolean;
  verbose: boolean;
  /** Where lines go; defaults to stdout. Tests pass a collector or a no-op. */
  write?: (line: string) => void;
}

export class Logger {
  readonly verbose: boolean;
  private readonly color: boolean;
  private readonly write: (line: string) => void;

  constructor(opts: LoggerOptions) {
    this.color = opts.color;
    this.verbose = opts.verbose;
    this.write = opts.write ?? ((line) => process.stdout.write(line + '\n'));
  }

  static silent(): Logger {
    return new Logger({ color: false, verbose: false, write: () => undefined });
  }

  info(tag: LogTag, message: string): void {
    this.emit(tag, message, null);
  }

  /** Only with --verbose. */
  debug(tag: LogTag, message: string): void {
    if (this.verbose) this.emit(tag, message, 'dim');
  }

  warn(message: string): void {
    this.emit('warn', message, 'yellow');
  }

  error(message: string): void {
    this.emit('error', message, 'red');
  }

  private emit(tag: LogTag, message: string, bodyColor: Color | null): void {
    const d = new Date();
    const time = [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':');
    const label = tag.padEnd(6);
    if (!this.color) {
      this.write(`${time} ${label} ${message}`);
      return;
    }
    const body = bodyColor ? `${ANSI[bodyColor]}${message}${ANSI.reset}` : message;
    this.write(`${ANSI.gray}${time}${ANSI.reset} ${ANSI[TAG_COLOR[tag]]}${label}${ANSI.reset} ${body}`);
  }
}
