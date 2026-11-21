import { createRequire } from 'node:module';
import fastifyCors from '@fastify/cors';
import fastifyRateLimit from '@fastify/rate-limit';
import fastifyWebsocket from '@fastify/websocket';
import { PROTOCOL } from '@hsm/shared';
import Fastify, { LogController, type FastifyError, type FastifyInstance, type FastifyServerOptions } from 'fastify';
import { makeGuards } from './auth/guard.js';
import { AuthService } from './auth/service.js';
import { trustProxyOption, type Config } from './config.js';
import type { AppContext } from './context.js';
import { Db } from './db/client.js';
import { migrate } from './db/migrations.js';
import { ApiHttpError } from './errors.js';
import { Jobs } from './jobs.js';
import { PushService, type PushSender } from './notify/push.js';
import { TelegramNotifier, type FetchFn } from './notify/telegram.js';
import { registerAlertRoutes } from './routes/alerts.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerDataRoutes } from './routes/data.js';
import { registerDeviceRoutes } from './routes/devices.js';
import { registerConfigRoute, registerHealthRoute } from './routes/misc.js';
import { registerStatic } from './routes/static.js';
import { registerScreenRoutes } from './routes/screen.js';
import { registerUserRoutes } from './routes/users.js';
import { registerSecurityHeaders } from './security.js';
import { AlertService } from './services/alerts.js';
import { CommandService } from './services/commands.js';
import { DeviceService } from './services/devices.js';
import { FinderService } from './services/finder.js';
import { RecordService } from './services/records.js';
import { ScreenService } from './services/screen.js';
import { SettingsService } from './services/settings.js';
import { TelemetryService } from './services/telemetry.js';
import { systemClock, type Clock } from './util.js';
import { handleAgentSocket } from './ws/agent.js';
import { handleDashboardSocket } from './ws/dashboard.js';
import { Hub } from './ws/hub.js';
import { handleScreenAgentSocket, handleScreenViewSocket } from './ws/screen.js';

export interface BuildOptions {
  clock?: Clock;
  /** Fastify logger option; defaults to pino at LOG_LEVEL (pretty in an interactive terminal). */
  logger?: FastifyServerOptions['logger'];
  /** Replaces global fetch for Telegram (tests). */
  fetch?: FetchFn;
  /** Replaces web-push delivery (tests). */
  pushSender?: PushSender;
}

export interface HsmServer {
  app: FastifyInstance;
  ctx: AppContext;
  jobs: Jobs;
  /** Starts listening and the background jobs; resolves to the bound address. */
  listen(): Promise<string>;
  /** Graceful shutdown: close sockets with 1001, flush state and rollups, close the DB. */
  close(): Promise<void>;
}

const BODY_LIMIT = 256 * 1024;
/**
 * permessage-deflate for the Agent socket (plan 8.2 data budget, F33): JSON telemetry compresses
 * several-fold. OkHttp negotiates it and compresses its own messages from 1 KiB; the Server does
 * the same for what it sends. Dashboards do not negotiate it (see the /ws/dashboard route).
 */
const AGENT_DEFLATE = {
  threshold: 1024,
  concurrencyLimit: 10,
  zlibDeflateOptions: { level: 6, memLevel: 8 },
} as const;
/** Longest a shutdown waits for pending "back online" alerts. */
const SHUTDOWN_ALERT_WAIT_MS = 5_000;

/** Resolves when `p` settles or after `ms`, whichever comes first (never rejects). */
async function settleWithin(p: Promise<unknown>, ms: number): Promise<void> {
  let timer: NodeJS.Timeout | undefined;
  await Promise.race([
    p.then(
      () => undefined,
      () => undefined,
    ),
    new Promise<void>((resolve) => {
      timer = setTimeout(resolve, ms);
      timer.unref();
    }),
  ]);
  if (timer) clearTimeout(timer);
}

function defaultLogger(config: Config): FastifyServerOptions['logger'] {
  const opts: Record<string, unknown> = {
    level: config.logLevel,
    redact: {
      paths: [
        'req.headers.authorization',
        'req.headers.cookie',
        '*.password',
        '*.reauth_password',
        '*.token',
        '*.device_token',
        '*.pending_token',
      ],
      censor: '[redacted]',
    },
  };
  if (process.stdout.isTTY && config.nodeEnv !== 'production') {
    try {
      createRequire(import.meta.url).resolve('pino-pretty');
      opts.transport = { target: 'pino-pretty', options: { translateTime: 'SYS:HH:MM:ss', ignore: 'pid,hostname' } };
    } catch {
      // pino-pretty is a dev dependency; plain JSON logs otherwise.
    }
  }
  return opts;
}

export async function buildServer(config: Config, options: BuildOptions = {}): Promise<HsmServer> {
  const clock = options.clock ?? systemClock;
  const app = Fastify({
    logger: options.logger ?? defaultLogger(config),
    trustProxy: trustProxyOption(config.trustProxy),
    bodyLimit: BODY_LIMIT,
    // Requests are logged by the onResponse hook below (one line, no headers).
    logController: new LogController({ disableRequestLogging: true }),
    requestIdHeader: false,
    return503OnClosing: true,
  });

  const db = await Db.open(config.databaseUrl, config.databaseAuthToken);
  const applied = await migrate(db);
  if (applied.length) app.log.info({ versions: applied }, 'database migrated');

  const ctx = { config, clock, log: app.log, db, startedAt: clock.now() } as AppContext;
  ctx.hub = new Hub(ctx);
  ctx.auth = new AuthService(ctx);
  ctx.devices = new DeviceService(ctx);
  ctx.settings = new SettingsService(ctx);
  ctx.telemetry = new TelemetryService(ctx);
  ctx.commands = new CommandService(ctx);
  ctx.records = new RecordService(ctx);
  ctx.finder = new FinderService(ctx);
  ctx.alerts = new AlertService(ctx);
  ctx.telegram = new TelegramNotifier(config.telegramBotToken, config.telegramChatId, app.log, options.fetch);
  ctx.push = new PushService(ctx, options.pushSender);
  ctx.screen = new ScreenService(ctx);

  // Rebuild all state from the database: after a free-tier spin-down this is all there is.
  await ctx.auth.load();
  await ctx.auth.bootstrapAdmin();
  await ctx.devices.load();
  await ctx.finder.loadBaselines();
  await ctx.commands.load();
  await ctx.alerts.load();
  await ctx.push.init();
  try {
    await ctx.telemetry.catchUp();
  } catch (err) {
    app.log.warn({ err }, 'rollup catch-up failed; continuing');
  }

  // --- plugins ---------------------------------------------------------------------------------
  await app.register(fastifyCors, {
    origin: config.corsOrigins.length ? config.corsOrigins : false,
    methods: ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'],
    allowedHeaders: ['Authorization', 'Content-Type'],
    credentials: false,
    maxAge: 600,
  });
  await app.register(fastifyRateLimit, {
    global: false,
    errorResponseBuilder: (_req, context) =>
      new ApiHttpError(429, 'rate_limited', `Too many requests. Try again in ${context.after}.`),
  });
  await app.register(fastifyWebsocket, {
    options: { maxPayload: PROTOCOL.max_message_bytes, perMessageDeflate: AGENT_DEFLATE },
    preClose(done) {
      ctx.screen.closeAll();
      ctx.hub.closeAll(1001, 'server shutting down');
      const wss = app.websocketServer;
      // Give clients a moment to finish the close handshake, then drop whatever is left.
      const t = setTimeout(() => {
        for (const c of wss.clients) c.terminate();
      }, 1000);
      t.unref();
      wss.close(() => {
        clearTimeout(t);
        done();
      });
    },
  });
  registerSecurityHeaders(app, config);

  // Accept an empty body with Content-Type: application/json (e.g. POST /api/pair/codes).
  const jsonParser = app.getDefaultJsonParser('error', 'error');
  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'string' }, (req, body, done) => {
    if (typeof body === 'string' && body.trim() === '') return done(null, undefined);
    return jsonParser(req, body as string, done);
  });

  app.setErrorHandler((err: FastifyError | ApiHttpError, req, reply) => {
    if (err instanceof ApiHttpError) {
      return reply.code(err.statusCode).send({ error: err.code, message: err.message });
    }
    const status = err.statusCode ?? 500;
    if (status === 429) return reply.code(429).send({ error: 'rate_limited', message: 'Too many requests' });
    if (status >= 400 && status < 500) {
      const code = status === 401 ? 'unauthorized' : status === 403 ? 'forbidden' : status === 404 ? 'not_found' : 'bad_request';
      return reply.code(status).send({ error: code, message: err.message });
    }
    req.log.error({ err }, 'request failed');
    return reply.code(500).send({ error: 'internal', message: 'Internal server error' });
  });

  app.addHook('onResponse', async (req, reply) => {
    const path = req.url.split('?')[0];
    const status = reply.statusCode;
    const level = status >= 500 ? 'error' : status >= 400 && status !== 401 && status !== 404 ? 'info' : 'debug';
    req.log[level]({ method: req.method, path, status, ms: Math.round(reply.elapsedTime), ip: req.ip }, 'request');
  });

  // --- routes ----------------------------------------------------------------------------------
  app.get('/ws/agent', { websocket: true }, (socket, req) => handleAgentSocket(ctx, socket, req));
  app.get(
    '/ws/dashboard',
    {
      websocket: true,
      // The upgrade happens after this hook: without the offer, ws does not negotiate compression
      // for Dashboards (many small broadcast frames; only the Agent's data budget needs it).
      onRequest: async (req) => {
        delete req.raw.headers['sec-websocket-extensions'];
      },
    },
    (socket, req) => handleDashboardSocket(ctx, socket, req),
  );
  // Remote screen relay (docs/protocol.md section 8). No compression: H.264 does not shrink.
  const noDeflate = async (req: { raw: { headers: Record<string, unknown> } }) => {
    delete req.raw.headers['sec-websocket-extensions'];
  };
  app.get('/ws/screen/agent', { websocket: true, onRequest: noDeflate }, (socket, req) =>
    handleScreenAgentSocket(ctx, socket, req),
  );
  app.get('/ws/screen/view', { websocket: true, onRequest: noDeflate }, (socket) => handleScreenViewSocket(ctx, socket));
  registerHealthRoute(app, ctx);
  const guards = makeGuards(ctx);
  await app.register(
    async (api) => {
      api.decorateRequest('auth', null);
      registerConfigRoute(api, ctx);
      registerAuthRoutes(api, ctx, guards);
      registerUserRoutes(api, ctx, guards);
      registerDeviceRoutes(api, ctx, guards);
      registerDataRoutes(api, ctx, guards);
      registerAlertRoutes(api, ctx, guards);
      registerScreenRoutes(api, ctx, guards);
    },
    { prefix: '/api' },
  );
  await registerStatic(app, ctx);

  const jobs = new Jobs(ctx);
  let closed = false;

  return {
    app,
    ctx,
    jobs,
    async listen() {
      const address = await app.listen({ port: config.port, host: config.host });
      jobs.start();
      // Offline check and the other periodic work once right away (protocol 6: "once at start").
      void jobs.tick();
      return address;
    },
    async close() {
      if (closed) return;
      closed = true;
      jobs.stop();
      ctx.hub.closeAll(1001, 'server shutting down');
      // "Back online" alerts still waiting for the backlog live in memory only (F24).
      await settleWithin(
        ctx.alerts.processPendingBackOnline(true).catch((err) => app.log.error({ err }, 'failed to send pending alerts during shutdown')),
        SHUTDOWN_ALERT_WAIT_MS,
      );
      try {
        await ctx.devices.flush();
        await ctx.telemetry.rollupDirty(true);
      } catch (err) {
        app.log.error({ err }, 'failed to flush state during shutdown');
      }
      await app.close();
      db.close();
    },
  };
}
