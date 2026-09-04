/* METADATA
{
  "name": "dsh_toolpkg",
  "display_name": {
    "zh": "DeepSeek Harness (DSH) 控制面板",
    "en": "DeepSeek Harness (DSH) Control Panel"
  },
  "description": {
    "zh": "在 RO-Operit 中管理 DeepSeek Harness 会话。启动/停止 DSH Web 服务器，查看状态，在 Ubuntu 容器中执行命令，并通过内嵌 WebView 访问完整的 DSH 界面。",
    "en": "Manage DeepSeek Harness sessions in RO-Operit. Start/stop DSH web server, check status, execute commands in Ubuntu container, and access full DSH UI via embedded WebView."
  },
  "category": "Development",
  "enabledByDefault": false,
  "tools": [
    {
      "name": "dsh_start",
      "description": {
        "zh": "启动 DSH Web 服务器（在 Ubuntu 容器中运行 dsh web）。",
        "en": "Start DSH Web server (runs dsh web in Ubuntu container)."
      },
      "parameters": [
        {
          "name": "port",
          "description": { "zh": "端口号（默认 3082）", "en": "Port number (default 3082)" },
          "type": "number",
          "required": false
        },
        {
          "name": "host",
          "description": { "zh": "绑定主机（默认 0.0.0.0）", "en": "Bind host (default 0.0.0.0)" },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "dsh_stop",
      "description": {
        "zh": "停止 DSH Web 服务器。",
        "en": "Stop DSH Web server."
      },
      "parameters": []
    },
    {
      "name": "dsh_status",
      "description": {
        "zh": "检查 DSH 运行状态并获取 WebView URL。",
        "en": "Check DSH running status and get WebView URL."
      },
      "parameters": []
    },
    {
      "name": "dsh_run",
      "description": {
        "zh": "在 DSH Ubuntu 会话中执行 Shell 命令。",
        "en": "Execute a shell command in the DSH Ubuntu session."
      },
      "parameters": [
        {
          "name": "command",
          "description": { "zh": "要执行的 Shell 命令", "en": "Shell command to execute" },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "dsh_webview_url",
      "description": {
        "zh": "获取 DSH WebView 的 URL（用于内嵌或外部浏览器访问）。",
        "en": "Get the DSH WebView URL (for embedding or external browser)."
      },
      "parameters": []
    },
    {
      "name": "dsh_install",
      "description": {
        "zh": "在 Ubuntu 容器中安装/更新 DSH CLI（npm i -g @deepseek-ai/dsh）。",
        "en": "Install/update DSH CLI in Ubuntu container (npm i -g @deepseek-ai/dsh)."
      },
      "parameters": []
    }
  ]
}
*/

/// <reference path="../types/index.d.ts" />

const DSH_DEFAULT_PORT = 3082;
const DSH_DEFAULT_HOST = '0.0.0.0';

// ==================== TOOL IMPLEMENTATIONS ====================

export async function dsh_start(params: { port?: number; host?: string } = {}) {
  const port = params.port || DSH_DEFAULT_PORT;
  const host = params.host || DSH_DEFAULT_HOST;

  const result = await toolCall('dsh_start', { port, host });

  if (result?.success) {
    const url = `http://127.0.0.1:${port}`;
    complete({
      success: true,
      message: `✅ DSH 已启动: ${url}`,
      data: { url, port, host }
    });
    return { success: true, url, port, host };
  } else {
    const error = result?.error || 'Unknown error';
    complete({
      success: false,
      message: `❌ DSH 启动失败: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}

export async function dsh_stop() {
  const result = await toolCall('dsh_stop', {});

  if (result?.success) {
    complete({
      success: true,
      message: '✅ DSH 已停止',
      data: {}
    });
    return { success: true };
  } else {
    const error = result?.error || 'Unknown error';
    complete({
      success: false,
      message: `❌ DSH 停止失败: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}

export async function dsh_status() {
  const result = await toolCall('dsh_status', {});

  if (result?.success) {
    const running = result?.result?.includes?.('running') ?? false;
    const url = running ? `http://127.0.0.1:${DSH_DEFAULT_PORT}` : 'not running';
    
    complete({
      success: true,
      message: running ? `✅ DSH 运行中: ${url}` : '⏹️ DSH 未运行',
      data: { running, url }
    });
    return { success: true, running, url };
  } else {
    const error = result?.error || 'Unknown error';
    complete({
      success: false,
      message: `❌ 状态检查失败: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}

export async function dsh_run(params: { command: string }) {
  const { command } = params;

  if (!command || command.trim() === '') {
    complete({
      success: false,
      message: '❌ 命令不能为空',
      data: { error: 'Command is required' }
    });
    return { success: false, error: 'Command is required' };
  }

  const result = await toolCall('dsh_run', { command });

  if (result?.success) {
    const output = result?.result || '';
    complete({
      success: true,
      message: `✅ 命令执行完成`,
      data: { output, command }
    });
    return { success: true, output, command };
  } else {
    const error = result?.error || 'Unknown error';
    complete({
      success: false,
      message: `❌ 执行失败: ${error}`,
      data: { error, command }
    });
    return { success: false, error, command };
  }
}

export async function dsh_webview_url() {
  const url = `http://127.0.0.1:${DSH_DEFAULT_PORT}`;
  
  complete({
    success: true,
    message: `🌐 DSH WebView URL: ${url}`,
    data: { url }
  });
  return { success: true, url };
}

export async function dsh_install() {
  // Execute npm install in Ubuntu via shell
  const result = await toolCall('dsh_run', {
    command: 'npm config set registry https://registry.npmjs.org/ && npm i -g @deepseek-ai/dsh'
  });

  if (result?.success) {
    complete({
      success: true,
      message: '✅ DSH 安装完成',
      data: { output: result.result }
    });
    return { success: true, output: result.result };
  } else {
    const error = result?.error || 'Unknown error';
    complete({
      success: false,
      message: `❌ DSH 安装失败: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}

// ==================== MAIN (Self-test) ====================

export async function main(params?: {
  test?: boolean;
  command?: string;
}) {
  if (!params?.test) {
    complete({
      success: true,
      message: 'DSH ToolPkg loaded. Use dsh_start/stop/status/run tools.',
      data: { tools: ['dsh_start', 'dsh_stop', 'dsh_status', 'dsh_run', 'dsh_webview_url', 'dsh_install'] }
    });
    return;
  }

  const results: Record<string, any> = {};

  // Test 1: Status
  console.log('[DSH ToolPkg] Testing dsh_status...');
  results.status = await dsh_status();

  // Test 2: Start (if not running)
  if (!results.status.running) {
    console.log('[DSH ToolPkg] Testing dsh_start...');
    results.start = await dsh_start({ port: DSH_DEFAULT_PORT, host: DSH_DEFAULT_HOST });
  } else {
    results.start = { success: true, skipped: true, reason: 'Already running' };
  }

  // Test 3: Run command
  if (params.command || results.start.success) {
    const testCmd = params.command || 'echo "Hello from DSH Ubuntu" && whoami && pwd';
    console.log('[DSH ToolPkg] Testing dsh_run...');
    results.run = await dsh_run({ command: testCmd });
  }

  // Test 4: WebView URL
  results.webview = await dsh_webview_url();

  const okCount = Object.values(results).filter(r => r.success).length;
  const failCount = Object.values(results).filter(r => r.success === false).length;

  complete({
    success: failCount === 0,
    message: failCount === 0
      ? `✅ DSH ToolPkg 自测通过 (${okCount}/${Object.keys(results).length})`
      : `❌ DSH ToolPkg 自测失败 (${failCount}/${Object.keys(results).length})`,
    data: { results }
  });
}

// Export all tools for CommonJS
exports.dsh_start = dsh_start;
exports.dsh_stop = dsh_stop;
exports.dsh_status = dsh_status;
exports.dsh_run = dsh_run;
exports.dsh_webview_url = dsh_webview_url;
exports.dsh_install = dsh_install;
exports.main = main;
