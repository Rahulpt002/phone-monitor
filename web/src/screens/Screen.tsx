/**
 * Remote screen: live view and control of the hotspot phone's screen (docs/protocol.md section 8).
 * Admins only; starting needs the password and a fresh authenticator code.
 */
import { SCREEN, SCREEN_QUALITIES, SCREEN_QUALITY, type ScreenKey, type ScreenQuality, type ScreenSessionResponse } from '@hsm/shared';
import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode, type WheelEvent } from 'react';
import { ReauthDialog } from '../components/settings/ReauthDialog';
import { Field, Segmented, TextInput } from '../components/ui/forms';
import { IconScreen } from '../components/ui/icons';
import { Badge, Button, Card, Notice, PageHeader, cx, type Tone } from '../components/ui/primitives';
import { api, isApiError } from '../lib/api';
import { useDocumentTitle, useNow } from '../lib/hooks';
import { megabytesPerHour, pointerToVideo } from '../lib/screen/stream';
import { ScreenViewer, webCodecsSupported, type ViewerState } from '../lib/screen/viewer';
import { useCanControl, useDeviceId, useSnapshot } from '../store/selectors';
import { useSession } from '../store/session';
import { toast } from '../store/toasts';

const QUALITY_KEY = 'hsm.screen.quality';

function storedQuality(): ScreenQuality {
  try {
    const v = localStorage.getItem(QUALITY_KEY);
    if (v && (SCREEN_QUALITIES as string[]).includes(v)) return v as ScreenQuality;
  } catch {
    // storage unavailable
  }
  return 'standard';
}

export function ScreenScreen() {
  useDocumentTitle('Screen');
  const canControl = useCanControl();
  const deviceId = useDeviceId();
  const snapshot = useSnapshot();
  const [quality, setQuality] = useState<ScreenQuality>(storedQuality);
  const [asking, setAsking] = useState(false);
  const [code, setCode] = useState('');
  const [session, setSession] = useState<ScreenSessionResponse | null>(null);

  useEffect(() => {
    try {
      localStorage.setItem(QUALITY_KEY, quality);
    } catch {
      // storage unavailable
    }
  }, [quality]);
  useEffect(() => {
    if (asking) setCode('');
  }, [asking]);

  const cap = snapshot?.state.capabilities?.screen_mirror;
  const online = snapshot?.online ?? false;
  const supported = webCodecsSupported();
  const blocker: ReactNode = !canControl
    ? 'Set up two-factor authentication to use the remote screen.'
    : !supported
      ? 'This browser cannot decode the video. Use a recent Chrome, Edge or Safari over HTTPS.'
      : !snapshot
        ? 'Loading the phone…'
        : !online
          ? 'The phone is offline. The screen needs a live connection.'
          : !cap
            ? 'This phone has not reported remote-screen support yet. Install the latest Agent app on it.'
            : !cap.supported
              ? `This phone cannot stream its screen: ${cap.note ?? 'not supported'}.`
              : null;

  const q = SCREEN_QUALITY[quality];

  return (
    <div className="space-y-4">
      <PageHeader title="Screen" subtitle="See and control the hotspot phone's screen live, from anywhere." />
      {session ? (
        <LiveScreen key={session.session_id} session={session} onDone={() => setSession(null)} />
      ) : (
        <Card title="Start the remote screen">
          <div className="space-y-4">
            <p className="text-sm text-muted">
              The phone streams its screen through your server, and your taps go back to it. It runs only while this page is
              open, for at most {SCREEN.max_duration_s / 60} minutes at a time, and every session is recorded in the timeline.
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-sm font-medium text-fg">Quality</span>
              <Segmented
                label="Quality"
                size="md"
                value={quality}
                onChange={setQuality}
                options={SCREEN_QUALITIES.map((v) => ({ value: v, label: SCREEN_QUALITY[v].label.replace(/ \(.*\)$/, '') }))}
              />
            </div>
            <p className="text-xs text-faint">
              {q.label}: up to {q.max_size} px, {q.max_fps} frames/s. Uses the hotspot&apos;s mobile data: at most about{' '}
              {megabytesPerHour(q.bit_rate)} MB per hour while the screen changes, far less while it is still.
            </p>
            {blocker ? <Notice tone="warn">{blocker}</Notice> : null}
            <Button variant="primary" size="lg" disabled={!!blocker || !deviceId} onClick={() => setAsking(true)}>
              <IconScreen className="text-lg" /> Start screen
            </Button>
          </div>
        </Card>
      )}

      <ReauthDialog
        open={asking}
        onClose={() => setAsking(false)}
        title="Start the remote screen?"
        description="Anyone with the screen can control the phone. Confirm with your password and a fresh code from your authenticator app."
        confirmLabel="Start"
        onConfirm={async (password) => {
          if (!deviceId) throw new Error('No phone selected.');
          if (!/^\d{6}$/.test(code.trim())) throw new Error('Enter the 6-digit code from your authenticator app.');
          try {
            const s = await api.createScreenSession({ device_id: deviceId, quality, password, code: code.trim() });
            setSession(s);
            setAsking(false);
          } catch (e) {
            if (isApiError(e, 'bad_request')) {
              setCode('');
              throw new Error('Wrong or already used code. Wait for the next code and try again.');
            }
            if (isApiError(e, 'device_offline')) throw new Error('The phone went offline.');
            throw e;
          }
        }}
      >
        <Field label="Authenticator code" help="The 6-digit code from your authenticator app.">
          {({ id, describedBy }) => (
            <TextInput
              id={id}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              pattern="[0-9]*"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              aria-describedby={describedBy}
              className="font-mono tracking-[0.3em]"
            />
          )}
        </Field>
      </ReauthDialog>
    </div>
  );
}

const PHASE: Record<ViewerState['phase'], { label: string; tone: Tone }> = {
  connecting: { label: 'Connecting', tone: 'info' },
  waiting: { label: 'Waiting for the phone', tone: 'info' },
  starting: { label: 'Starting', tone: 'info' },
  streaming: { label: 'Live', tone: 'ok' },
  reconnecting: { label: 'Reconnecting', tone: 'warn' },
  ended: { label: 'Ended', tone: 'neutral' },
};

function LiveScreen({ session, onDone }: { session: ScreenSessionResponse; onDone: () => void }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const frameRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<ScreenViewer | null>(null);
  const [state, setState] = useState<ViewerState | null>(null);
  const [typing, setTyping] = useState(false);
  const [text, setText] = useState('');
  const now = useNow(1000);
  const pointers = useRef(new Map<number, number>());
  const pendingMoves = useRef(new Map<number, { x: number; y: number }>());
  const raf = useRef<number | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const token = useSession.getState().token;
    if (!canvas || !token) return;
    const v = new ScreenViewer({ sessionId: session.session_id, token, canvas, onState: setState });
    viewerRef.current = v;
    const onVis = () => (document.visibilityState === 'hidden' ? v.pause() : v.resume());
    document.addEventListener('visibilitychange', onVis);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      // Leaving the page ends the session (no video nobody watches).
      if (v.current.phase !== 'ended') v.stop();
      v.close();
      viewerRef.current = null;
    };
  }, [session.session_id]);

  // Keep the viewing phone's own screen on while streaming.
  useEffect(() => {
    if (state?.phase !== 'streaming' || !('wakeLock' in navigator)) return;
    let lock: WakeLockSentinel | null = null;
    let cancelled = false;
    navigator.wakeLock
      .request('screen')
      .then((l) => {
        if (cancelled) void l.release();
        else lock = l;
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      void lock?.release();
    };
  }, [state?.phase]);

  useEffect(() => {
    if (state?.phase === 'ended' && state.reason && state.reason !== 'stopped') {
      toast.info(state.message ?? 'The screen session ended.');
    }
  }, [state?.phase, state?.reason, state?.message]);

  const send = useCallback((msg: Parameters<ScreenViewer['send']>[0]) => viewerRef.current?.send(msg), []);
  const key = (k: ScreenKey) => send({ t: 'key', key: k });

  const toVideo = (e: { clientX: number; clientY: number }) => {
    const c = canvasRef.current;
    if (!c || !state?.width || !state.height) return null;
    const r = c.getBoundingClientRect();
    return pointerToVideo(e.clientX, e.clientY, r, state.width, state.height);
  };

  const flushMoves = () => {
    raf.current = null;
    for (const [pointer, p] of pendingMoves.current) send({ t: 'touch', action: 'move', pointer, x: p.x, y: p.y });
    pendingMoves.current.clear();
  };

  const onPointerDown = (e: PointerEvent<HTMLCanvasElement>) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const p = toVideo(e);
    if (!p) return;
    const used = new Set(pointers.current.values());
    let idx = 0;
    while (used.has(idx) && idx < 9) idx++;
    pointers.current.set(e.pointerId, idx);
    e.currentTarget.setPointerCapture(e.pointerId);
    e.currentTarget.focus({ preventScroll: true });
    send({ t: 'touch', action: 'down', pointer: idx, x: p.x, y: p.y });
  };
  const onPointerMove = (e: PointerEvent<HTMLCanvasElement>) => {
    const idx = pointers.current.get(e.pointerId);
    if (idx === undefined) return;
    const p = toVideo(e);
    if (!p) return;
    pendingMoves.current.set(idx, p);
    raf.current ??= requestAnimationFrame(flushMoves);
  };
  const onPointerEnd = (e: PointerEvent<HTMLCanvasElement>, action: 'up' | 'cancel') => {
    const idx = pointers.current.get(e.pointerId);
    if (idx === undefined) return;
    pointers.current.delete(e.pointerId);
    pendingMoves.current.delete(idx);
    const p = toVideo(e) ?? { x: 0, y: 0 };
    // Outside the picture: finish the gesture at the clamped edge.
    send({ t: 'touch', action, pointer: idx, x: Math.min(1, Math.max(0, p.x)), y: Math.min(1, Math.max(0, p.y)) });
  };
  const onWheel = (e: WheelEvent<HTMLCanvasElement>) => {
    const p = toVideo(e);
    if (!p) return;
    const clamp = (v: number) => Math.max(-16, Math.min(16, v));
    send({ t: 'scroll', x: p.x, y: p.y, dx: clamp(e.deltaX / 100), dy: clamp(e.deltaY / 100) });
  };
  const onKeyDown = (e: KeyboardEvent<HTMLCanvasElement>) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const map: Record<string, ScreenKey> = { Enter: 'enter', Backspace: 'delete', Escape: 'back' };
    const k = map[e.key];
    if (k) key(k);
    else if (e.key.length === 1) send({ t: 'text', text: e.key });
    else return;
    e.preventDefault();
  };

  const sendText = () => {
    const t = text;
    if (!t) return;
    send({ t: 'text', text: t.slice(0, SCREEN.max_text_bytes) });
    setText('');
  };

  const fullscreen = () => {
    const el = frameRef.current;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void el.requestFullscreen?.().catch(() => undefined);
  };

  const phase = state?.phase ?? 'connecting';
  const ended = phase === 'ended';
  const left = Math.max(0, Math.round((Date.parse(session.expires_at) - now) / 1000));
  const aspect = state?.width && state.height ? `${state.width} / ${state.height}` : '9 / 20';
  const mb = (state?.bytes ?? 0) / 1024 / 1024;

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
      <div className="flex flex-col items-center gap-3">
        <div
          ref={frameRef}
          className="relative w-full max-w-[min(100%,calc((100dvh-15rem)*0.45))] overflow-hidden rounded-[2rem] border-4 border-surface-3 bg-black shadow-lg"
          style={{ aspectRatio: aspect }}
        >
          <canvas
            ref={canvasRef}
            tabIndex={0}
            aria-label="Phone screen. Tap to touch; type to send keys."
            className={cx('block h-full w-full touch-none select-none outline-none', ended && 'opacity-40')}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={(e) => onPointerEnd(e, 'up')}
            onPointerCancel={(e) => onPointerEnd(e, 'cancel')}
            onWheel={onWheel}
            onKeyDown={onKeyDown}
            onContextMenu={(e) => e.preventDefault()}
          />
          {phase !== 'streaming' ? (
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-2 p-6 text-center text-white/90">
              {!ended ? <span className="size-8 animate-spin rounded-full border-2 border-white/30 border-t-white" /> : null}
              <span className="text-sm font-medium">{ended ? state?.message ?? 'Ended.' : PHASE[phase].label + '…'}</span>
            </div>
          ) : null}
        </div>

        {/* The phone's navigation bar. */}
        <div className="flex w-full max-w-sm items-center justify-around rounded-2xl border border-border bg-surface p-1.5">
          <NavKey label="Recents" disabled={ended} onClick={() => key('recents')}>
            <path d="M6 5v14M10 5v14M14 5v14" transform="translate(2 0)" />
          </NavKey>
          <NavKey label="Home" disabled={ended} onClick={() => key('home')}>
            <rect x="6" y="6" width="12" height="12" rx="3.5" />
          </NavKey>
          <NavKey label="Back" disabled={ended} onClick={() => key('back')}>
            <path d="m14 6-6 6 6 6" />
          </NavKey>
        </div>
      </div>

      <div className="space-y-4">
        <Card
          title="Session"
          actions={<Badge tone={PHASE[phase].tone}>{PHASE[phase].label}</Badge>}
          footer={
            ended ? (
              <Button variant="primary" onClick={onDone}>
                Start a new session
              </Button>
            ) : (
              <Button variant="danger" onClick={() => viewerRef.current?.stop()}>
                Stop screen
              </Button>
            )
          }
        >
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <dt className="text-muted">Quality</dt>
            <dd className="text-right font-medium text-fg">{SCREEN_QUALITY[session.quality].label.replace(/ \(.*\)$/, '')}</dd>
            <dt className="text-muted">Picture</dt>
            <dd className="tabular text-right font-medium text-fg">{state?.width ? `${state.width} × ${state.height}` : '–'}</dd>
            <dt className="text-muted">Frames</dt>
            <dd className="tabular text-right font-medium text-fg">{phase === 'streaming' ? `${state?.fps ?? 0}/s` : '–'}</dd>
            <dt className="text-muted">Data used</dt>
            <dd className="tabular text-right font-medium text-fg">{mb >= 10 ? mb.toFixed(0) : mb.toFixed(1)} MB</dd>
            <dt className="text-muted">Time left</dt>
            <dd className="tabular text-right font-medium text-fg">
              {ended ? '–' : `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`}
            </dd>
          </dl>
        </Card>

        <Card title="Buttons">
          <div className="grid grid-cols-3 gap-2">
            <Button disabled={ended} onClick={() => key('power')}>Power</Button>
            <Button disabled={ended} onClick={() => key('volume_down')}>Vol −</Button>
            <Button disabled={ended} onClick={() => key('volume_up')}>Vol +</Button>
            <Button disabled={ended} onClick={() => send({ t: 'panel', panel: 'notifications' })}>Notifications</Button>
            <Button disabled={ended} onClick={() => send({ t: 'panel', panel: 'settings' })}>Quick settings</Button>
            <Button disabled={ended} onClick={() => send({ t: 'panel', panel: 'collapse' })}>Close panels</Button>
            <Button disabled={ended} onClick={() => setTyping((t) => !t)}>{typing ? 'Hide keyboard' : 'Type text'}</Button>
            <Button disabled={ended} onClick={() => send({ t: 'keyframe' })}>Refresh</Button>
            <Button onClick={fullscreen}>Full screen</Button>
          </div>
          {typing && !ended ? (
            <form
              className="mt-3 space-y-2"
              onSubmit={(e) => {
                e.preventDefault();
                sendText();
              }}
            >
              <Field label="Text to type on the phone" help="Tap a text field on the phone first.">
                {({ id }) => (
                  <TextInput id={id} value={text} onChange={(e) => setText(e.target.value)} autoComplete="off" autoCapitalize="off" />
                )}
              </Field>
              <div className="flex gap-2">
                <Button type="submit" variant="primary" disabled={!text}>
                  Send text
                </Button>
                <Button onClick={() => key('delete')}>⌫ Delete</Button>
                <Button onClick={() => key('enter')}>↵ Enter</Button>
              </div>
            </form>
          ) : null}
        </Card>
        <p className="text-xs text-faint">
          On a computer: click to tap, drag to swipe, scroll the wheel to scroll, and type with the screen selected (Esc is
          Back).
        </p>
      </div>
    </div>
  );
}

function NavKey({ label, disabled, onClick, children }: { label: string; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className="flex h-11 w-16 items-center justify-center rounded-xl text-fg hover:bg-surface-2 active:bg-surface-3 disabled:opacity-40"
    >
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        {children}
      </svg>
    </button>
  );
}
