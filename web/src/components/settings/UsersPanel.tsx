/**
 * Settings > Users (admin): add users, change roles, reset a password or a lost authenticator,
 * delete users. Every change asks for your own password again (reauth_password, plan 10), and your
 * own password and authenticator are changed under My account, which needs the current password.
 * The Server enforces the same rules (last admin, yourself).
 */
import type { Role, UserInfo } from '@hsm/shared';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '../../lib/api';
import { formatDate } from '../../lib/format';
import { useAsync } from '../../lib/hooks';
import { useCanControl } from '../../store/selectors';
import { useSession } from '../../store/session';
import { toast } from '../../store/toasts';
import { Field, Select, TextInput } from '../ui/forms';
import { Badge, Button, Card, Empty, Loading, Notice, TableWrap, cx, td, th } from '../ui/primitives';
import { ReauthDialog } from './ReauthDialog';

export const MIN_PASSWORD = 10;

const roleName = (role: Role) => (role === 'admin' ? 'an admin' : 'a viewer');

function CreateUser({ onCreated, disabled }: { onCreated: (u: UserInfo) => void; disabled: boolean }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('viewer');
  const [confirming, setConfirming] = useState(false);
  const userErr = !/^[a-zA-Z0-9._-]{3,32}$/.test(username) ? '3 to 32 letters, digits, dot, dash or underscore.' : null;
  const pwErr = password.length < MIN_PASSWORD ? `At least ${MIN_PASSWORD} characters.` : null;
  const [touched, setTouched] = useState(false);
  return (
    <>
      <form
        className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_1fr_10rem_auto] sm:items-end"
        onSubmit={(e) => {
          e.preventDefault();
          setTouched(true);
          if (!userErr && !pwErr) setConfirming(true);
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
        <Button type="submit" variant="primary" disabled={disabled}>
          Add user
        </Button>
      </form>
      {/* Outside the form above: React events from the dialog's own form bubble through the portal. */}
      <ReauthDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title={`Add ${username} as ${roleName(role)}?`}
        description={
          role === 'admin'
            ? 'Admins can control the phone with root access, read its messages and manage users.'
            : 'Viewers can monitor the phone but have no controls, root shell or messages.'
        }
        confirmLabel="Add user"
        onConfirm={async (reauth_password) => {
          const u = await api.createUser({ username, password, role, reauth_password });
          onCreated(u);
          setUsername('');
          setPassword('');
          setTouched(false);
          setConfirming(false);
          toast.success(`User ${u.username} created. They set up two-factor authentication when they first sign in.`);
        }}
      />
    </>
  );
}

/** What resetting `u`'s two-factor authentication does, worded for the confirm dialog. */
export function resetTotpExplanation(u: UserInfo): string {
  return `This clears ${u.username}’s authenticator and signs them out on every device; their push notifications stop too. They sign in with their password and must set up two-factor authentication again before they can use the Dashboard.`;
}

type Pending =
  | { kind: 'role'; user: UserInfo; role: Role }
  | { kind: 'password'; user: UserInfo }
  | { kind: 'totp'; user: UserInfo }
  | { kind: 'delete'; user: UserInfo };

export function UsersPanel() {
  const me = useSession((s) => s.user);
  const canControl = useCanControl();
  const { data, loading, error, setData } = useAsync(() => api.users(), []);
  const [pending, setPending] = useState<Pending | null>(null);
  const [newPw, setNewPw] = useState('');
  const users = data ?? [];
  const admins = users.filter((u) => u.role === 'admin').length;
  const replace = (updated: UserInfo) => setData(users.map((x) => (x.id === updated.id ? updated : x)));
  const open = (p: Pending) => {
    setNewPw('');
    setPending(p);
  };

  const confirm = async (reauth_password: string) => {
    if (!pending) return;
    const { user } = pending;
    switch (pending.kind) {
      case 'role': {
        replace(await api.updateUser(user.id, { role: pending.role, reauth_password }));
        toast.success(`${user.username} is now ${roleName(pending.role)}.`);
        break;
      }
      case 'password': {
        if (newPw.length < MIN_PASSWORD) throw new Error(`The new password needs at least ${MIN_PASSWORD} characters.`);
        await api.updateUser(user.id, { password: newPw, reauth_password });
        toast.success(`Password for ${user.username} changed. They are signed out everywhere.`);
        break;
      }
      case 'totp': {
        replace(await api.updateUser(user.id, { reset_totp: true, reauth_password }));
        toast.success(`Two-factor authentication for ${user.username} was reset.`);
        break;
      }
      case 'delete': {
        await api.deleteUser(user.id, { reauth_password });
        setData(users.filter((x) => x.id !== user.id));
        toast.success(`${user.username} deleted.`);
        break;
      }
    }
    setPending(null);
    setNewPw('');
  };

  const dialog = pending ? dialogText(pending) : null;

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
                          onChange={(e) => {
                            const role = e.target.value as Role;
                            if (role !== u.role) open({ kind: 'role', user: u, role });
                          }}
                          disabled={!canControl || self || lastAdmin}
                          className="py-1.5"
                        >
                          <option value="admin">Admin</option>
                          <option value="viewer">Viewer</option>
                        </Select>
                      </td>
                      <td className={td}>
                        {/* Required for every account (plan 10): without it the user only sees the setup page. */}
                        {u.totp_enabled ? <Badge tone="ok">On</Badge> : <Badge tone="warn">Not set up</Badge>}
                      </td>
                      <td className={cx(td, 'text-muted')}>{formatDate(u.created_at)}</td>
                      <td className={cx(td, 'whitespace-nowrap text-right')}>
                        {self ? (
                          <Link to="/settings?tab=account" className="mr-2 text-sm text-accent underline underline-offset-2">
                            Your password and 2FA: My account
                          </Link>
                        ) : (
                          <>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={!canControl}
                              aria-label={`Reset password for ${u.username}`}
                              onClick={() => open({ kind: 'password', user: u })}
                            >
                              Reset password
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={!canControl || !u.totp_enabled}
                              title={u.totp_enabled ? `Clear ${u.username}’s authenticator (lost phone)` : `${u.username} has no two-factor authentication to reset`}
                              aria-label={`Reset two-factor authentication for ${u.username}`}
                              onClick={() => open({ kind: 'totp', user: u })}
                            >
                              Reset 2FA
                            </Button>
                          </>
                        )}
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-danger"
                          disabled={!canControl || self || lastAdmin}
                          title={self ? 'You cannot delete yourself' : lastAdmin ? 'Cannot delete the last admin' : undefined}
                          aria-label={`Delete ${u.username}`}
                          onClick={() => open({ kind: 'delete', user: u })}
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

      <ReauthDialog
        open={Boolean(pending)}
        onClose={() => setPending(null)}
        title={dialog?.title ?? ''}
        description={dialog?.description}
        confirmLabel={dialog?.confirm ?? 'Confirm'}
        danger={pending?.kind === 'totp' || pending?.kind === 'delete'}
        onConfirm={confirm}
      >
        {pending?.kind === 'password' ? (
          <Field label={`New password for ${pending.user.username}`} help={`At least ${MIN_PASSWORD} characters. They are signed out on every device.`}>
            {({ id, describedBy }) => (
              <TextInput id={id} type="password" autoComplete="new-password" value={newPw} onChange={(e) => setNewPw(e.target.value)} aria-describedby={describedBy} data-autofocus />
            )}
          </Field>
        ) : pending?.kind === 'totp' ? (
          <p className="text-sm text-muted">Use this when their phone with the authenticator app is lost or reset.</p>
        ) : null}
      </ReauthDialog>
    </div>
  );
}

function dialogText(p: Pending): { title: string; description: string; confirm: string } {
  const name = p.user.username;
  switch (p.kind) {
    case 'role':
      return {
        title: `Make ${name} ${roleName(p.role)}?`,
        description:
          p.role === 'admin'
            ? 'Admins can control the phone with root access, read its messages and manage users.'
            : 'They keep monitoring but lose controls, the root shell, messages and user management. Their open Dashboards switch over at once.',
        confirm: 'Change role',
      };
    case 'password':
      return { title: `Reset password for ${name}`, description: 'Sets a new password for them; tell them through a safe channel.', confirm: 'Set password' };
    case 'totp':
      return { title: `Reset two-factor authentication for ${name}?`, description: resetTotpExplanation(p.user), confirm: 'Reset 2FA' };
    case 'delete':
      return { title: `Delete ${name}?`, description: 'They are signed out and cannot sign in again. This cannot be undone.', confirm: 'Delete user' };
  }
}
