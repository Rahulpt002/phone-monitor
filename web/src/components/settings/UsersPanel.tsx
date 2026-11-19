/**
 * Settings > Users (admin): add users, change roles, reset a password or a lost authenticator,
 * delete users. The Server enforces the same rules (last admin, yourself).
 */
import type { Role, UserInfo } from '@hsm/shared';
import { useState } from 'react';
import { api, errorMessage, isApiError } from '../../lib/api';
import { formatDate } from '../../lib/format';
import { useAsync } from '../../lib/hooks';
import { useCanControl } from '../../store/selectors';
import { useSession } from '../../store/session';
import { toast } from '../../store/toasts';
import { Dialog } from '../ui/Dialog';
import { Field, Select, TextInput } from '../ui/forms';
import { Badge, Button, Card, Empty, Loading, Notice, TableWrap, cx, td, th } from '../ui/primitives';

export const MIN_PASSWORD = 10;

function CreateUser({ onCreated, disabled }: { onCreated: (u: UserInfo) => void; disabled: boolean }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('viewer');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const userErr = !/^[a-zA-Z0-9._-]{3,32}$/.test(username) ? '3 to 32 letters, digits, dot, dash or underscore.' : null;
  const pwErr = password.length < MIN_PASSWORD ? `At least ${MIN_PASSWORD} characters.` : null;
  const [touched, setTouched] = useState(false);
  return (
    <form
      className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_1fr_10rem_auto] sm:items-end"
      onSubmit={async (e) => {
        e.preventDefault();
        setTouched(true);
        if (userErr || pwErr) return;
        setBusy(true);
        setError(null);
        try {
          const u = await api.createUser({ username, password, role });
          onCreated(u);
          setUsername('');
          setPassword('');
          setTouched(false);
          toast.success(`User ${u.username} created.`);
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <Field label="Username" error={touched ? userErr : null}>
        {({ id, describedBy, invalid }) => (
          <TextInput id={id} autoComplete="off" autoCapitalize="none" value={username} onChange={(e) => setUsername(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} />
        )}
      </Field>
      <Field label="Password" error={touched ? pwErr : null}>
        {({ id, describedBy, invalid }) => (
          <TextInput id={id} type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} />
        )}
      </Field>
      <Field label="Role">
        {({ id }) => (
          <Select id={id} value={role} onChange={(e) => setRole(e.target.value as Role)} disabled={disabled}>
            <option value="viewer">Viewer</option>
            <option value="admin">Admin</option>
          </Select>
        )}
      </Field>
      <Button type="submit" variant="primary" busy={busy} disabled={disabled}>
        Add user
      </Button>
      {error ? (
        <div className="sm:col-span-4">
          <Notice tone="danger">{error}</Notice>
        </div>
      ) : null}
    </form>
  );
}

/** What resetting `u`'s two-factor authentication does, worded for the confirm dialog. */
export function resetTotpExplanation(u: UserInfo, self: boolean): string {
  if (self) {
    return 'This clears your authenticator and signs you out on every other device; this browser stays signed in. You must set up two-factor authentication again before you can make changes.';
  }
  const admin = u.role === 'admin' ? ' As an admin they must set it up again before they can make changes.' : '';
  return `This clears ${u.username}’s authenticator and signs them out on every device. They sign in again with their password only, then can set up two-factor authentication again.${admin}`;
}

export function UsersPanel() {
  const me = useSession((s) => s.user);
  const canControl = useCanControl();
  const { data, loading, error, setData, reload } = useAsync(() => api.users(), []);
  const [resetFor, setResetFor] = useState<UserInfo | null>(null);
  const [totpFor, setTotpFor] = useState<UserInfo | null>(null);
  const [deleteFor, setDeleteFor] = useState<UserInfo | null>(null);
  const [newPw, setNewPw] = useState('');
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const users = data ?? [];
  const admins = users.filter((u) => u.role === 'admin').length;

  const changeRole = async (u: UserInfo, role: Role) => {
    try {
      const updated = await api.updateUser(u.id, { role });
      setData(users.map((x) => (x.id === u.id ? updated : x)));
      toast.success(`${u.username} is now ${role === 'admin' ? 'an admin' : 'a viewer'}.`);
    } catch (e) {
      toast.error(errorMessage(e));
      reload();
    }
  };

  const resetTotp = async () => {
    if (!totpFor) return;
    const target = totpFor;
    setBusy(true);
    setDialogError(null);
    try {
      const updated = await api.updateUser(target.id, { reset_totp: true });
      setData(users.map((x) => (x.id === target.id ? updated : x)));
      setTotpFor(null);
      toast.success(`Two-factor authentication for ${target.username} was reset.`);
      // Resetting yourself makes you an admin without 2FA: pick up the enrollment requirement.
      if (target.id === me?.id) await useSession.getState().refreshMe();
    } catch (err) {
      if (isApiError(err, 'totp_enrollment_required')) useSession.getState().setTotpEnrollmentRequired(true);
      setDialogError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <Card title="Add a user">
        <p className="mb-3 text-sm text-muted">Admins monitor and control. Viewers only monitor: no controls, root shell or messages (plan 9).</p>
        <CreateUser onCreated={(u) => setData([...users, u])} disabled={!canControl} />
      </Card>
      <Card title="Users">
        {loading && !users.length ? <Loading /> : null}
        {error ? <Notice tone="danger">{errorMessage(error)}</Notice> : null}
        {users.length ? (
          <TableWrap label="Users">
            <table className="w-full min-w-[40rem]">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>User</th>
                  <th className={th}>Role</th>
                  <th className={th}>2FA</th>
                  <th className={th}>Created</th>
                  <th className={th}>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => {
                  const self = u.id === me?.id;
                  const lastAdmin = u.role === 'admin' && admins <= 1;
                  return (
                    <tr key={u.id} className="border-b border-border/60 last:border-0">
                      <td className={cx(td, 'font-medium')}>
                        {u.username} {self ? <Badge>you</Badge> : null}
                      </td>
                      <td className={td}>
                        <label className="sr-only" htmlFor={`role-${u.id}`}>
                          Role of {u.username}
                        </label>
                        <Select
                          id={`role-${u.id}`}
                          value={u.role}
                          onChange={(e) => changeRole(u, e.target.value as Role)}
                          disabled={!canControl || self || lastAdmin}
                          className="py-1.5"
                        >
                          <option value="admin">Admin</option>
                          <option value="viewer">Viewer</option>
                        </Select>
                      </td>
                      <td className={td}>{u.totp_enabled ? <Badge tone="ok">On</Badge> : <Badge tone={u.role === 'admin' ? 'warn' : 'neutral'}>Off</Badge>}</td>
                      <td className={cx(td, 'text-muted')}>{formatDate(u.created_at)}</td>
                      <td className={cx(td, 'whitespace-nowrap text-right')}>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={!canControl}
                          onClick={() => {
                            setResetFor(u);
                            setNewPw('');
                            setDialogError(null);
                          }}
                        >
                          Reset password
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={!canControl || !u.totp_enabled}
                          title={u.totp_enabled ? `Clear ${u.username}’s authenticator (lost phone)` : `${u.username} has no two-factor authentication to reset`}
                          aria-label={`Reset two-factor authentication for ${u.username}`}
                          onClick={() => {
                            setTotpFor(u);
                            setDialogError(null);
                          }}
                        >
                          Reset 2FA
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-danger"
                          disabled={!canControl || self || lastAdmin}
                          title={self ? 'You cannot delete yourself' : lastAdmin ? 'Cannot delete the last admin' : undefined}
                          onClick={() => {
                            setDeleteFor(u);
                            setDialogError(null);
                          }}
                        >
                          Delete
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableWrap>
        ) : !loading && !error ? (
          <Empty>No users.</Empty>
        ) : null}
      </Card>

      <Dialog
        open={Boolean(resetFor)}
        onClose={() => setResetFor(null)}
        title={`Reset password for ${resetFor?.username ?? ''}`}
        footer={
          <>
            <Button onClick={() => setResetFor(null)}>Cancel</Button>
            <Button variant="primary" type="submit" form="reset-pw" busy={busy}>
              Set password
            </Button>
          </>
        }
      >
        <form
          id="reset-pw"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!resetFor) return;
            if (newPw.length < MIN_PASSWORD) {
              setDialogError(`At least ${MIN_PASSWORD} characters.`);
              return;
            }
            setBusy(true);
            try {
              await api.updateUser(resetFor.id, { password: newPw });
              toast.success(`Password for ${resetFor.username} changed.`);
              setResetFor(null);
            } catch (err) {
              setDialogError(errorMessage(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          <Field label="New password" error={dialogError}>
            {({ id, describedBy, invalid }) => (
              <TextInput id={id} type="password" autoComplete="new-password" value={newPw} onChange={(e) => setNewPw(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} data-autofocus />
            )}
          </Field>
        </form>
      </Dialog>

      <Dialog
        open={Boolean(totpFor)}
        onClose={() => setTotpFor(null)}
        title={`Reset two-factor authentication for ${totpFor?.username ?? ''}?`}
        footer={
          <>
            <Button onClick={() => setTotpFor(null)} data-autofocus>
              Cancel
            </Button>
            <Button variant="danger" busy={busy} onClick={resetTotp}>
              Reset 2FA
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <p className="text-sm text-fg">{totpFor ? resetTotpExplanation(totpFor, totpFor.id === me?.id) : null}</p>
          <p className="text-sm text-muted">Use this when their phone with the authenticator app is lost or reset.</p>
          {dialogError ? <Notice tone="danger">{dialogError}</Notice> : null}
        </div>
      </Dialog>

      <Dialog
        open={Boolean(deleteFor)}
        onClose={() => setDeleteFor(null)}
        title={`Delete ${deleteFor?.username ?? ''}?`}
        description="They are signed out and cannot sign in again."
        footer={
          <>
            <Button onClick={() => setDeleteFor(null)} data-autofocus>
              Cancel
            </Button>
            <Button
              variant="danger"
              busy={busy}
              onClick={async () => {
                if (!deleteFor) return;
                setBusy(true);
                try {
                  await api.deleteUser(deleteFor.id);
                  setData(users.filter((x) => x.id !== deleteFor.id));
                  toast.success(`${deleteFor.username} deleted.`);
                  setDeleteFor(null);
                } catch (err) {
                  setDialogError(errorMessage(err));
                } finally {
                  setBusy(false);
                }
              }}
            >
              Delete user
            </Button>
          </>
        }
      >
        {dialogError ? <Notice tone="danger">{dialogError}</Notice> : <p className="text-sm text-muted">This cannot be undone.</p>}
      </Dialog>
    </div>
  );
}
