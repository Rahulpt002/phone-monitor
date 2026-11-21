// Remote screen: live view and control of the phone's screen from the Dashboard.
//
// Flow (docs/protocol.md section 8):
//   1. An admin calls POST /screen/sessions with their password and a fresh TOTP code.
//   2. The Server sends `screen_start` to the Agent over /ws/agent.
//   3. The Agent starts the bundled scrcpy server as root and opens /ws/screen/agent?session=<id>
//      (device token in the Authorization header). The viewer opens /ws/screen/view and sends
//      `screen_auth` (session token + session id).
//   4. The Server relays the Agent's video (binary frames, ScreenFrame below) and `screen_meta`
//      to the viewer, and the viewer's control messages (JSON) to the Agent.
//   5. The session ends on DELETE /screen/sessions/:id, a `screen_stop` from the viewer, the
//      viewer being gone for SCREEN.viewer_grace_s, max_duration_s, the Agent link dropping, or
//      the user's session ending. Start and end are stored as `screen_session` events.
//
// These sockets carry no telemetry and are not buffered: nothing is stored except the events.

/** Video quality presets. The Agent passes them to scrcpy (max_size = longest side in px). */
export type ScreenQuality = 'low' | 'standard' | 'high';

export interface ScreenQualitySpec {
  max_size: number;
  bit_rate: number; // bits per second (peak; a still screen sends far less)
  max_fps: number;
  label: string;
}

export const SCREEN_QUALITY: Record<ScreenQuality, ScreenQualitySpec> = {
  low: { max_size: 960, bit_rate: 800_000, max_fps: 15, label: 'Low (saves data)' },
  standard: { max_size: 1280, bit_rate: 2_000_000, max_fps: 24, label: 'Standard' },
  high: { max_size: 1600, bit_rate: 4_000_000, max_fps: 30, label: 'High' },
};

export const SCREEN_QUALITIES: ScreenQuality[] = ['low', 'standard', 'high'];

export const SCREEN = {
  /** Hard cap on one session; start a new one to continue. */
  max_duration_s: 30 * 60,
  /** The Agent must connect its relay socket within this time after `screen_start`. */
  agent_connect_timeout_s: 30,
  /** The session ends when no viewer has been connected for this long. */
  viewer_grace_s: 30,
  /** Largest binary frame either side sends; bigger video packets are split (ScreenFrame). */
  max_chunk_bytes: 256 * 1024,
  /** INJECT_TEXT limit in UTF-8 bytes (scrcpy's own limit is 300). */
  max_text_bytes: 300,
  /** The Server drops video for a viewer whose socket has this much unsent data. */
  max_viewer_buffered_bytes: 2 * 1024 * 1024,
  /** scrcpy version of the bundled server jar; must match the jar exactly. */
  scrcpy_version: '5.0.1',
} as const;

// ---------------------------------------------------------------------------------------------
// Binary video frames (Agent -> Server -> viewer, unchanged by the Server)
// ---------------------------------------------------------------------------------------------
//
// Byte 0 is the frame kind:
//   SCREEN_FRAME_START (1): bytes 1..12 are scrcpy's 12-byte packet header, followed by the
//     first chunk of the payload. Header: u64 big-endian ptsAndFlags (bit 62 = codec config,
//     bit 61 = key frame, bits 60..0 = presentation time in microseconds), then u32 big-endian
//     payload size (the whole packet, not this chunk).
//   SCREEN_FRAME_CONT (2): the next chunk of the same packet's payload.
// A packet is complete when the chunks add up to the payload size. Payloads are H.264 Annex B
// (start codes included); config packets hold SPS + PPS and come before the first key frame.

export const SCREEN_FRAME_START = 1;
export const SCREEN_FRAME_CONT = 2;
export const SCREEN_HEADER_BYTES = 12;
export const SCREEN_FLAG_CONFIG = 1n << 62n;
export const SCREEN_FLAG_KEY = 1n << 61n;
export const SCREEN_PTS_MASK = (1n << 61n) - 1n;

export interface ScreenPacketHeader {
  config: boolean;
  key: boolean;
  pts_us: number;
  size: number;
}

/** Reads the scrcpy header of a SCREEN_FRAME_START frame; null when it is not one. */
export function readScreenPacketHeader(frame: Uint8Array): ScreenPacketHeader | null {
  if (frame.length < 1 + SCREEN_HEADER_BYTES || frame[0] !== SCREEN_FRAME_START) return null;
  const view = new DataView(frame.buffer, frame.byteOffset + 1, SCREEN_HEADER_BYTES);
  const flags = view.getBigUint64(0, false);
  const size = view.getUint32(8, false);
  return {
    config: (flags & SCREEN_FLAG_CONFIG) !== 0n,
    key: (flags & SCREEN_FLAG_KEY) !== 0n,
    pts_us: Number(flags & SCREEN_PTS_MASK),
    size,
  };
}

// ---------------------------------------------------------------------------------------------
// Agent link (/ws/agent) messages
// ---------------------------------------------------------------------------------------------

/** Server -> Agent: start streaming for this session. */
export interface ScreenStartMsg {
  t: 'screen_start';
  session_id: string;
  quality: ScreenQuality;
  max_size: number;
  bit_rate: number;
  max_fps: number;
  max_duration_s: number;
  user?: string | null;
}

/** Server -> Agent: stop the session (also sent for sessions the Agent no longer knows). */
export interface ScreenStopMsg {
  t: 'screen_stop';
  session_id: string;
  reason: ScreenEndReason;
}

/** Agent -> Server: progress of a session. Not buffered (a stale report is meaningless). */
export interface ScreenStateMsg {
  t: 'screen_state';
  session_id: string;
  state: 'starting' | 'streaming' | 'ended' | 'failed';
  error?: string | null;
}

// ---------------------------------------------------------------------------------------------
// Relay sockets: /ws/screen/agent and /ws/screen/view
// ---------------------------------------------------------------------------------------------

/** Agent -> viewer (relayed): video geometry, sent after scrcpy's session packet and on rotation. */
export interface ScreenMetaMsg {
  t: 'screen_meta';
  codec: 'h264';
  width: number;
  height: number;
  device_name?: string | null;
}

/** Viewer -> Server: first message on /ws/screen/view. */
export interface ScreenAuthMsg {
  t: 'screen_auth';
  token: string;
  session_id: string;
}

export type ScreenEndReason =
  | 'stopped' // the user pressed Stop (or DELETE /screen/sessions/:id)
  | 'replaced' // a new session for the same phone started
  | 'max_duration'
  | 'viewer_gone'
  | 'agent_timeout' // the Agent never connected its relay socket
  | 'agent_gone' // the Agent's link or relay socket closed
  | 'agent_failed' // the Agent reported it could not start (error in `message`)
  | 'session_ended' // the user's sign-in session ended or their role changed
  | 'unpaired'
  | 'server_shutdown';

/** Server -> viewer: session progress. `ended` is final; the socket closes right after. */
export interface ScreenStatusMsg {
  t: 'screen_status';
  state: 'waiting' | 'starting' | 'streaming' | 'ended';
  reason?: ScreenEndReason | null;
  message?: string | null;
}

/** Keys the viewer can press (Android KeyEvent codes live in the Agent). */
export type ScreenKey =
  | 'back'
  | 'home'
  | 'recents'
  | 'power'
  | 'volume_up'
  | 'volume_down'
  | 'enter'
  | 'delete'
  | 'menu';

export const SCREEN_KEYS: ScreenKey[] = ['back', 'home', 'recents', 'power', 'volume_up', 'volume_down', 'enter', 'delete', 'menu'];

/** Coordinates are fractions of the video frame (0..1), so they survive any display scaling. */
export interface ScreenTouchMsg {
  t: 'touch';
  action: 'down' | 'move' | 'up' | 'cancel';
  pointer: number; // 0..9
  x: number;
  y: number;
}

export interface ScreenScrollMsg {
  t: 'scroll';
  x: number;
  y: number;
  dx: number; // -16..16, positive = right
  dy: number; // -16..16, positive = down (content moves up)
}

export interface ScreenKeyMsg {
  t: 'key';
  key: ScreenKey;
}

export interface ScreenTextMsg {
  t: 'text';
  text: string; // at most SCREEN.max_text_bytes UTF-8 bytes
}

export interface ScreenPanelMsg {
  t: 'panel';
  panel: 'notifications' | 'settings' | 'collapse';
}

/** Ask for a new key frame (after a decoder error or dropped video). */
export interface ScreenKeyframeMsg {
  t: 'keyframe';
}

/** Viewer -> Server: end the session. */
export interface ScreenStopRequestMsg {
  t: 'screen_stop';
}

export interface ScreenPingMsg {
  t: 'ping';
}

/** Control messages the Server forwards from the viewer to the Agent, validated. */
export type ScreenControlMsg =
  | ScreenTouchMsg
  | ScreenScrollMsg
  | ScreenKeyMsg
  | ScreenTextMsg
  | ScreenPanelMsg
  | ScreenKeyframeMsg;

export type ScreenViewerToServer = ScreenAuthMsg | ScreenControlMsg | ScreenStopRequestMsg | ScreenPingMsg;
export type ScreenServerToViewer = ScreenStatusMsg | ScreenMetaMsg | { t: 'pong' } | { t: 'auth_failed'; message: string };

/** Bytes of `text` in UTF-8. */
export function utf8Length(text: string): number {
  return new TextEncoder().encode(text).length;
}
