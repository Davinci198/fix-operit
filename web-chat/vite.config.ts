import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { nodePolyfills } from 'vite-plugin-node-polyfills';

export default defineConfig({
  plugins: [
    react(),
    nodePolyfills({
      include: ['crypto', 'stream', 'events', 'path', 'util', 'async_hooks', 'module'],
      globals: {
        Buffer: true,
        global: true,
        process: true,
      },
    }),
  ],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'ES2020',
  },
  resolve: {
    alias: {
      '@deepseek-ai/dsh-agent': '/src/dsh-brain/agent',
      '@deepseek-ai/dsh-session': '/src/dsh-brain/session',
      '@deepseek-ai/dsh-tools': '/src/dsh-brain/tools',
      '@deepseek-ai/dsh-llm': '/src/dsh-brain/llm',
      '@deepseek-ai/dsh-scope': '/src/dsh-brain/scope',
      '@deepseek-ai/dsh-system-prompt': '/src/dsh-brain/system-prompt',
      '@deepseek-ai/dsh-invariants': '/src/dsh-brain/invariants',
      '@deepseek-ai/dsh-typert-protocol': '/src/dsh-brain/typert/protocol',
      '@deepseek-ai/dsh-typert-registry': '/src/dsh-brain/typert/registry',
      '@deepseek-ai/dsh-subagent': '/src/dsh-brain/subagent/core',
      '@deepseek-ai/dsh-subagent-tool': '/src/dsh-brain/subagent/tool',
      '@deepseek-ai/dsh-subagent-control': '/src/dsh-brain/subagent/control',
      '@deepseek-ai/dsh-subagent-report': '/src/dsh-brain/subagent/report',
      '@deepseek-ai/dsh-subagent-fork': '/src/dsh-brain/subagent/fork',
      '@deepseek-ai/dsh-subagent-spawn': '/src/dsh-brain/subagent/spawn',
      '@deepseek-ai/dsh-subagent-acp': '/src/dsh-brain/subagent/acp',
      '@deepseek-ai/dsh-subagent-claude-code': '/src/dsh-brain/subagent/claude',
      '@deepseek-ai/dsh-subagent-codex': '/src/dsh-brain/subagent/codex',
      '@deepseek-ai/dsh-subagent-sdk': '/src/dsh-brain/subagent/sdk',
      '@deepseek-ai/dsh-user-approval': '/src/dsh-brain/user-approval',
      '@deepseek-ai/dsh-code-runtime': '/src/dsh-brain/code-runtime',
      '@deepseek-ai/dsh-commands': '/src/dsh-brain/commands',
      '@deepseek-ai/dsh-attachment': '/src/dsh-brain/attachment',
      '@deepseek-ai/dsh-session-projection': '/src/dsh-brain/session-projection',
      '@deepseek-ai/dsh-session-title': '/src/dsh-brain/session-title',
      '@deepseek-ai/dsh-host-apiproxy': '/src/dsh-brain/host-apiproxy',
      '@deepseek-ai/dsh-client-connection': '/src/dsh-brain/client-connection',
      '@deepseek-ai/dsh-api-remotes': '/src/dsh-brain/api-remotes',
      '@deepseek-ai/dsh-client-ui-slots': '/src/dsh-brain/client-ui-slots',
      '@deepseek-ai/cordis': '/src/dsh-brain/cordis',
      '@deepseek-ai/dsh-brand': '/src/dsh-brain/brand',
      '@deepseek-ai/schemastery': '/src/dsh-brain/schemastery',
    },
  },
  optimizeDeps: {
    include: ['crypto', 'stream', 'events', 'path', 'util', 'async_hooks', 'module'],
  },
});
