import type { PowerInfo } from '@hsm/shared';
import { formatCurrent, formatDuration } from '../lib/format';
import { trailingDuration } from '../lib/series';
import { useDeviceSettings } from '../store/selectors';
import { useHistory } from './charts/useHistory';
import { Notice } from './ui/primitives';

/**
 * Plugged in, battery current negative, and charging is supposed to be happening. A pause the
 * Agent made on purpose (charge limit, heat, manual) is not a weak supply, even when the switch
 * suspends input and the battery reports "discharging" (docs/phase0.md, input suspend); the Agent's
 * WeakPowerDetector ignores that state the same way.
 */
export function isPluggedButDraining(p: PowerInfo | undefined | null): boolean {
  if (!p || !p.plug || p.plug === 'none' || p.current_ma == null || p.current_ma >= 0) return false;
  if (p.charging_enabled === false || p.stop_reason != null) return false;
  return p.status !== 'full';
}

function DrainingNotice({ power }: { power: PowerInfo }) {
  const { settings } = useDeviceSettings();
  const h = useHistory(['power.current_ma'], '6h');
  const forMs = trailingDuration(h.series['power.current_ma'] ?? [], (v) => v < 0);
  const weak = forMs / 60_000 >= settings.watchdog.weak_power_min;
  return (
    <Notice tone={weak ? 'danger' : 'warn'} title={weak ? 'Power supply too weak' : 'Plugged in but discharging'}>
      The battery is draining at {formatCurrent(power.current_ma)} while on {power.plug?.toUpperCase()} power
      {forMs > 60_000 ? ` for ${formatDuration(forMs / 1000)}` : ''}. Use a stronger USB port, a shorter or thicker cable, or a powered hub
      (plan 4.1).
    </Notice>
  );
}

/** Plan 4.1: "The Dashboard always shows net battery current and alerts on plugged in but discharging". */
export function PowerWarning({ power }: { power: PowerInfo | undefined | null }) {
  if (!power || !isPluggedButDraining(power)) return null;
  return <DrainingNotice power={power} />;
}
