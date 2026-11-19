import { defineConfig } from 'tsup';

// One ESM file for `node dist/index.js`. The shared contract (@hsm/shared) is TypeScript source
// in a workspace package, so it is bundled; real npm dependencies stay external and are loaded
// from node_modules at runtime.
export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm'],
  platform: 'node',
  target: 'node20',
  outDir: 'dist',
  clean: true,
  sourcemap: true,
  splitting: false,
  dts: false,
  noExternal: ['@hsm/shared'],
  external: [
    'fastify',
    '@fastify/cors',
    '@fastify/rate-limit',
    '@fastify/static',
    '@fastify/websocket',
    '@libsql/client',
    'web-push',
    'ws',
    'zod',
    'pino-pretty',
  ],
});
