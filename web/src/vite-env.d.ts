/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/react" />

interface ImportMetaEnv {
  /** Server base URL when the Dashboard is hosted separately (e.g. https://hsm.onrender.com). Empty = same origin. */
  readonly VITE_API_BASE?: string;
  readonly VITE_APP_VERSION?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
