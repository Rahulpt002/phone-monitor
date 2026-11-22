import type { FastifyBaseLogger } from 'fastify';
import type { AuthService } from './auth/service.js';
import type { Config } from './config.js';
import type { Db } from './db/client.js';
import type { FcmService } from './notify/fcm.js';
import type { PushService } from './notify/push.js';
import type { TelegramNotifier } from './notify/telegram.js';
import type { AlertService } from './services/alerts.js';
import type { CommandService } from './services/commands.js';
import type { DeviceService } from './services/devices.js';
import type { FinderService } from './services/finder.js';
import type { RecordService } from './services/records.js';
import type { ScreenService } from './services/screen.js';
import type { SettingsService } from './services/settings.js';
import type { TelemetryService } from './services/telemetry.js';
import type { Clock } from './util.js';
import type { Hub } from './ws/hub.js';

/**
 * Everything a service may need. Services receive the context at construction and read the
 * other services from it at call time, which keeps construction order free of cycles.
 */
export interface AppContext {
  config: Config;
  clock: Clock;
  log: FastifyBaseLogger;
  db: Db;
  /** Epoch ms when this process started (used for the post-restart offline grace period). */
  startedAt: number;
  auth: AuthService;
  devices: DeviceService;
  settings: SettingsService;
  telemetry: TelemetryService;
  commands: CommandService;
  records: RecordService;
  finder: FinderService;
  alerts: AlertService;
  telegram: TelegramNotifier;
  push: PushService;
  fcm: FcmService;
  screen: ScreenService;
  hub: Hub;
}
