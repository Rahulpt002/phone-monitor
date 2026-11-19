import { usePendingReverts } from '../../store/selectors';
import { RevertCountdown } from './CommandStatus';

/** All armed auto-reverts for the current phone; renders nothing when there are none. */
export function PendingReverts() {
  const pending = usePendingReverts();
  if (!pending.length) return null;
  return (
    <section aria-label="Pending auto-reverts" className="space-y-2">
      {pending.map((c) => (
        <RevertCountdown key={c.id} cmd={c} />
      ))}
    </section>
  );
}
