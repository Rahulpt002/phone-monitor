/**
 * Named locks so a command and a watchdog step never fight over the same subsystem
 * (a conflicting command is rejected with `busy`, docs/protocol.md 3.5).
 */
export type LockName = 'radio' | 'system' | 'finder';

export class LockSet {
  private readonly held = new Map<LockName, string>();

  tryAcquire(name: LockName, owner: string): boolean {
    const holder = this.held.get(name);
    if (holder !== undefined) return holder === owner;
    this.held.set(name, owner);
    return true;
  }

  release(name: LockName, owner: string): void {
    if (this.held.get(name) === owner) this.held.delete(name);
  }

  isHeld(name: LockName): boolean {
    return this.held.has(name);
  }

  holder(name: LockName): string | undefined {
    return this.held.get(name);
  }

  clear(): void {
    this.held.clear();
  }
}
