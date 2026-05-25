import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import fs from 'fs';
import path from 'path';
import {defineConfig, loadEnv} from 'vite';

const createExternalConfigMiddleware = (projectRoot: string) => {
  const configDir = process.env.ARCLIGHT_CONFIG_DIR
    ? path.resolve(process.env.ARCLIGHT_CONFIG_DIR)
    : path.resolve(projectRoot, 'config');
  const resourceDir = path.resolve(projectRoot, 'backend', 'src', 'main', 'resources');

  return (request: { url?: string }, response: {
    statusCode: number;
    setHeader: (name: string, value: string) => void;
    end: (body?: string) => void;
  }, next: () => void) => {
    if (!request.url?.startsWith('/config/')) {
      next();
      return;
    }

    const fileName = path.basename(decodeURIComponent(request.url.split('?')[0]));
    if (!/^[\w.-]+\.ya?ml$/i.test(fileName)) {
      response.statusCode = 400;
      response.end('Invalid config file name');
      return;
    }

    const externalPath = path.resolve(configDir, fileName);
    const fallbackPath = path.resolve(resourceDir, fileName);
    const targetPath = fs.existsSync(externalPath) ? externalPath : fallbackPath;
    if (!fs.existsSync(targetPath)) {
      response.statusCode = 404;
      response.end('Config file not found');
      return;
    }

    response.setHeader('Content-Type', 'application/yaml; charset=utf-8');
    response.end(fs.readFileSync(targetPath, 'utf8'));
  };
};

const externalConfigPlugin = (projectRoot: string) => {
  const externalConfigMiddleware = createExternalConfigMiddleware(projectRoot);
  return {
    name: 'arklight-external-config',
    configureServer(server: { middlewares: { use: (handler: ReturnType<typeof createExternalConfigMiddleware>) => void } }) {
      server.middlewares.use(externalConfigMiddleware);
    },
    configurePreviewServer(server: { middlewares: { use: (handler: ReturnType<typeof createExternalConfigMiddleware>) => void } }) {
      server.middlewares.use(externalConfigMiddleware);
    },
  };
};

export default defineConfig(({mode}) => {
  const env = loadEnv(mode, '.', '');
  const projectRoot = path.resolve(__dirname, '..', '..');
  const projectCharaRoot = path.resolve(projectRoot, 'chara');
  return {
    base: mode === 'production' ? '/chat-ui/' : '/',
    plugins: [vue(), tailwindcss(), externalConfigPlugin(projectRoot)],
    define: {
      'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
        '@project-chara': projectCharaRoot,
      },
    },
    server: {
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modify—file watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      fs: {
        allow: [projectRoot],
      },
    },
  };
});
