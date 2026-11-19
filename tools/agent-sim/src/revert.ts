import { REVERT_SETTLE_S, type CommandName, type RevertConfirm } from '@hsm/shared';

/** What the Agent persists before applying a link-cutting change (docs/protocol.md 3.5, step 1). */
export interface ArmedRevert {
  command_id: string;
  name: CommandName;
  /** The action that undoes the change, e.g. mobile_data {enabled: true}. */
  undo: { name: CommandName; args: Record<string, unknown> };
  confirm: RevertConfirm;
  /** Phone clock (ms). */
  applied_at: number;
  deadline: number;
}

export interface RevertHost {
  now(): number;
  /** Connected to the Server and past `hello`. */
  isConnected(): boolean;
  newProbeId(): string;
  sendProbe(id: string): void;
  /** Applies the undo action. */
  undo(r: ArmedRevert): void;
  /** Queues `cmd_revert` (and, for a real revert, the `revert` event). `quiet` skips the event. */
  report(r: ArmedRevert, state: 'kept' | 'reverted', detail: string, quiet: boolean): void;
  persist(list: ArmedRevert[]): void;
}

/** Which state each command changes; a newer command on the same target supersedes an armed revert. */
export const REVERT_TARGET: Partial<Record<CommandName, string>> = {
  mobile_data: 'data',
  data_cycle: 'data',
  airplane_mode: 'airplane',
  airplane_cycle: 'airplane',
  usb_tethering: 'tether',
  network_mode: 'network_mode',
};

interface Entry {
  r: ArmedRevert;
  deadlineTimer: NodeJS.Timeout | null;
  probeTimer: NodeJS.Timeout | null;
}

/**
 * Auto-revert state machine (plan 6.4 rule 1, docs/protocol.md 3.5):
 * - `agent_link`: from applied_at + REVERT_SETTLE_S the Agent sends a probe every 10 s while
 *   connected and once after every reconnect; the first probe_ack for a probe sent at or after
 *   the settle time keeps the change.
 * - `dashboard`: only cmd_keep keeps it.
 * - At the deadline without confirmation the undo runs and the revert is reported.
 */
export class RevertManager {
  private readonly entries = new Map<string, Entry>();
  private readonly probes = new Map<string, { commandId: string; sentAt: number }>();
  private suspended = false;

  constructor(
    private readonly host: RevertHost,
    private readonly settleS: number = REVERT_SETTLE_S,
    private readonly probeEveryS: number = 10,
  ) {}

  get size(): number {
    return this.entries.size;
  }

  list(): ArmedRevert[] {
    return [...this.entries.values()].map((e) => e.r);
  }

  get(commandId: string): ArmedRevert | undefined {
    return this.entries.get(commandId)?.r;
  }

  arm(r: ArmedRevert): ArmedRevert {
    this.clear(r.command_id);
    const entry: Entry = { r, deadlineTimer: null, probeTimer: null };
    this.entries.set(r.command_id, entry);
    if (!this.suspended) this.schedule(entry);
    this.persist();
    return r;
  }

  /** Agent start: persisted reverts past their deadline are reverted at once, the rest re-armed. */
  restore(list: readonly ArmedRevert[]): void {
    for (const r of list) {
      if (!r || typeof r.command_id !== 'string') continue;
      this.entries.set(r.command_id, { r, deadlineTimer: null, probeTimer: null });
    }
    this.resume();
  }

  /** Phone or Agent going down: timers stop, the armed list stays (it is persisted). */
  suspend(): void {
    this.suspended = true;
    for (const e of this.entries.values()) this.stopTimers(e);
    this.probes.clear();
  }

  /** Back up (after boot / Agent restart / at start). */
  resume(): void {
    this.suspended = false;
    const now = this.host.now();
    for (const e of [...this.entries.values()]) {
      if (now >= e.r.deadline) this.revertNow(e.r.command_id, 'deadline passed while the Agent was not running');
      else this.schedule(e);
    }
    this.persist();
  }

  /** Called after every `hello`: one probe per agent_link revert that is past its settle time. */
  onConnected(): void {
    if (this.suspended) return;
    const now = this.host.now();
    for (const e of this.entries.values()) {
      if (e.r.confirm === 'agent_link' && now >= this.settleAt(e.r)) this.probe(e.r.command_id);
    }
  }

  /** Returns true when the probe confirmed (kept) a change. */
  onProbeAck(probeId: string): boolean {
    const p = this.probes.get(probeId);
    if (!p) return false;
    this.probes.delete(probeId);
    const e = this.entries.get(p.commandId);
    if (!e || e.r.confirm !== 'agent_link' || p.sentAt < this.settleAt(e.r)) return false;
    const after = Math.round((p.sentAt - e.r.applied_at) / 1000);
    this.resolve(e, 'kept', `server round-trip ${after} s after the change`);
    return true;
  }

  /** cmd_keep from the Dashboard. Returns true when it matched an armed revert. */
  onKeep(commandId: string): boolean {
    const e = this.entries.get(commandId);
    if (!e) return false;
    this.resolve(e, 'kept', 'kept from the Dashboard');
    return true;
  }

  /**
   * A newer command changes the same state (like the Kotlin Agent's supersedeLocked): every earlier
   * armed revert on it is reported `kept` ("Superseded by a newer <label> command (<id>)"), with no
   * undo and no `revert` event. Returns the superseded entries, oldest first, so a newer command
   * that arms a revert itself can inherit the earliest undo: an unconfirmed chain still returns to
   * the last state that worked.
   */
  supersede(target: string | undefined, byCommandId: string, byLabel: string): ArmedRevert[] {
    if (!target) return [];
    const doomed = [...this.entries.values()]
      .filter((e) => REVERT_TARGET[e.r.name] === target && e.r.command_id !== byCommandId)
      .sort((a, b) => a.r.applied_at - b.r.applied_at);
    for (const e of doomed) this.resolve(e, 'kept', `Superseded by a newer ${byLabel} command (${byCommandId})`, true);
    return doomed.map((e) => e.r);
  }

  dispose(): void {
    for (const e of this.entries.values()) this.stopTimers(e);
    this.probes.clear();
  }

  private settleAt(r: ArmedRevert): number {
    return r.applied_at + this.settleS * 1000;
  }

  private schedule(e: Entry): void {
    this.stopTimers(e);
    const now = this.host.now();
    const id = e.r.command_id;
    e.deadlineTimer = setTimeout(() => this.revertNow(id, `no confirmation within ${Math.round((e.r.deadline - e.r.applied_at) / 1000)} s`), Math.max(0, e.r.deadline - now));
    if (e.r.confirm === 'agent_link') {
      e.probeTimer = setTimeout(() => {
        this.probe(id);
        const current = this.entries.get(id);
        if (current) current.probeTimer = setInterval(() => this.probe(id), this.probeEveryS * 1000);
      }, Math.max(0, this.settleAt(e.r) - now));
    }
  }

  private probe(commandId: string): void {
    if (this.suspended || !this.entries.has(commandId) || !this.host.isConnected()) return;
    const id = this.host.newProbeId();
    this.probes.set(id, { commandId, sentAt: this.host.now() });
    this.host.sendProbe(id);
  }

  private revertNow(commandId: string, detail: string): void {
    const e = this.entries.get(commandId);
    if (!e) return;
    this.clear(commandId);
    this.host.undo(e.r);
    this.host.report(e.r, 'reverted', detail, false);
    this.persist();
  }

  private resolve(e: Entry, state: 'kept' | 'reverted', detail: string, quiet = false): void {
    this.clear(e.r.command_id);
    this.host.report(e.r, state, detail, quiet);
    this.persist();
  }

  private clear(commandId: string): void {
    const e = this.entries.get(commandId);
    if (!e) return;
    this.stopTimers(e);
    this.entries.delete(commandId);
    for (const [pid, p] of this.probes) if (p.commandId === commandId) this.probes.delete(pid);
  }

  private stopTimers(e: Entry): void {
    if (e.deadlineTimer) clearTimeout(e.deadlineTimer);
    if (e.probeTimer) {
      clearTimeout(e.probeTimer);
      clearInterval(e.probeTimer);
    }
    e.deadlineTimer = null;
    e.probeTimer = null;
  }

  private persist(): void {
    this.host.persist(this.list());
  }
}
