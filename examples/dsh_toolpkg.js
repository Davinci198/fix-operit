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
    },
    {
      "name": "dsh_sync",
      "description": {
        "zh": "控制 DSH 与 Operit 聊天的双向同步：status/push_test/poll_now。",
        "en": "Control bidirectional chat sync between DSH and Operit chat: status/push_test/poll_now."
      },
      "parameters": [
        {
          "name": "action",
          "description": { "zh": "同步动作: status, push_test, poll_now", "en": "Sync action: status, push_test, poll_now" },
          "type": "string",
          "required": true
        },
        {
          "name": "message",
          "description": { "zh": "测试消息内容（用于 push_test）", "en": "Test message content (for push_test)" },
          "type": "string",
          "required": false
        }
      ]
    }
  ]
}
*/

"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getOwnPropSymbols = Object.getOwnPropertySymbols;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __propIsEnum = Object.prototype.propertyIsEnumerable;
var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
var __spreadValues = (a, b) => {
  for (var prop in b || (b = {}))
    if (__hasOwnProp.call(b, prop))
      __defNormalProp(a, prop, b[prop]);
  if (__getOwnPropSymbols)
    for (var prop of __getOwnPropSymbols(b)) {
      if (__propIsEnum.call(b, prop))
        __defNormalProp(a, prop, b[prop]);
    }
  return a;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// dsh_toolpkg.ts
var dsh_toolpkg_exports = {};
__export(dsh_toolpkg_exports, {
  dsh_install: () => dsh_install,
  dsh_run: () => dsh_run,
  dsh_start: () => dsh_start,
  dsh_status: () => dsh_status,
  dsh_stop: () => dsh_stop,
  dsh_sync: () => dsh_sync,
  dsh_webview_url: () => dsh_webview_url,
  main: () => main
});
module.exports = __toCommonJS(dsh_toolpkg_exports);
var DSH_DEFAULT_PORT = 3082;
var DSH_DEFAULT_HOST = "0.0.0.0";
async function dsh_start(params = {}) {
  const port = params.port || DSH_DEFAULT_PORT;
  const host = params.host || DSH_DEFAULT_HOST;
  const result = await toolCall("dsh_start", { port, host });
  if (result == null ? void 0 : result.success) {
    const url = `http://127.0.0.1:${port}`;
    complete({
      success: true,
      message: `\u2705 DSH \u5DF2\u542F\u52A8: ${url}`,
      data: { url, port, host }
    });
    return { success: true, url, port, host };
  } else {
    const error = (result == null ? void 0 : result.error) || "Unknown error";
    complete({
      success: false,
      message: `\u274C DSH \u542F\u52A8\u5931\u8D25: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}
async function dsh_stop() {
  const result = await toolCall("dsh_stop", {});
  if (result == null ? void 0 : result.success) {
    complete({
      success: true,
      message: "\u2705 DSH \u5DF2\u505C\u6B62",
      data: {}
    });
    return { success: true };
  } else {
    const error = (result == null ? void 0 : result.error) || "Unknown error";
    complete({
      success: false,
      message: `\u274C DSH \u505C\u6B62\u5931\u8D25: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}
async function dsh_status() {
  var _a, _b, _c;
  const result = await toolCall("dsh_status", {});
  if (result == null ? void 0 : result.success) {
    const running = (_c = (_b = (_a = result == null ? void 0 : result.result) == null ? void 0 : _a.includes) == null ? void 0 : _b.call(_a, "running")) != null ? _c : false;
    const url = running ? `http://127.0.0.1:${DSH_DEFAULT_PORT}` : "not running";
    complete({
      success: true,
      message: running ? `\u2705 DSH \u8FD0\u884C\u4E2D: ${url}` : "\u23F9\uFE0F DSH \u672A\u8FD0\u884C",
      data: { running, url }
    });
    return { success: true, running, url };
  } else {
    const error = (result == null ? void 0 : result.error) || "Unknown error";
    complete({
      success: false,
      message: `\u274C \u72B6\u6001\u68C0\u67E5\u5931\u8D25: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}
async function dsh_run(params) {
  const { command } = params;
  if (!command || command.trim() === "") {
    complete({
      success: false,
      message: "\u274C \u547D\u4EE4\u4E0D\u80FD\u4E3A\u7A7A",
      data: { error: "Command is required" }
    });
    return { success: false, error: "Command is required" };
  }
  const result = await toolCall("dsh_run", { command });
  if (result == null ? void 0 : result.success) {
    const output = (result == null ? void 0 : result.result) || "";
    complete({
      success: true,
      message: `\u2705 \u547D\u4EE4\u6267\u884C\u5B8C\u6210`,
      data: { output, command }
    });
    return { success: true, output, command };
  } else {
    const error = (result == null ? void 0 : result.error) || "Unknown error";
    complete({
      success: false,
      message: `\u274C \u6267\u884C\u5931\u8D25: ${error}`,
      data: { error, command }
    });
    return { success: false, error, command };
  }
}
async function dsh_webview_url() {
  const url = `http://127.0.0.1:${DSH_DEFAULT_PORT}`;
  complete({
    success: true,
    message: `\u{1F310} DSH WebView URL: ${url}`,
    data: { url }
  });
  return { success: true, url };
}
async function dsh_install() {
  const result = await toolCall("dsh_run", {
    command: "npm config set registry https://registry.npmjs.org/ && npm i -g @deepseek-ai/dsh"
  });
  if (result == null ? void 0 : result.success) {
    complete({
      success: true,
      message: "\u2705 DSH \u5B89\u88C5\u5B8C\u6210",
      data: { output: result.result }
    });
    return { success: true, output: result.result };
  } else {
    const error = (result == null ? void 0 : result.error) || "Unknown error";
    complete({
      success: false,
      message: `\u274C DSH \u5B89\u88C5\u5931\u8D25: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}
async function dsh_sync(params) {
  const { action, message } = params;
  const validActions = ["status", "push_test", "poll_now"];
  if (!validActions.includes(action)) {
    complete({
      success: false,
      message: `\u274C \u65E0\u6548\u52A8\u4F5C: ${action}. \u652F\u6301: ${validActions.join(", ")}`,
      data: { error: `Invalid action: ${action}` }
    });
    return { success: false, error: `Invalid action: ${action}` };
  }
  const toolParams = { action };
  if (message) toolParams.message = message;
  const result = await toolCall("dsh_sync", toolParams);
  if (result == null ? void 0 : result.success) {
    complete({
      success: true,
      message: `\u2705 \u540C\u6B65 ${action}: ${result.result}`,
      data: result
    });
    return __spreadValues({ success: true }, result);
  } else {
    const error = (result == null ? void 0 : result.error) || "Unknown error";
    complete({
      success: false,
      message: `\u274C \u540C\u6B65 ${action} \u5931\u8D25: ${error}`,
      data: { error }
    });
    return { success: false, error };
  }
}
async function main(params) {
  if (!(params == null ? void 0 : params.test)) {
    complete({
      success: true,
      message: "DSH ToolPkg loaded. Use dsh_start/stop/status/run tools.",
      data: { tools: ["dsh_start", "dsh_stop", "dsh_status", "dsh_run", "dsh_webview_url", "dsh_install", "dsh_sync"] }
    });
    return;
  }
  const results = {};
  console.log("[DSH ToolPkg] Testing dsh_status...");
  results.status = await dsh_status();
  if (!results.status.running) {
    console.log("[DSH ToolPkg] Testing dsh_start...");
    results.start = await dsh_start({ port: DSH_DEFAULT_PORT, host: DSH_DEFAULT_HOST });
  } else {
    results.start = { success: true, skipped: true, reason: "Already running" };
  }
  if (params.command || results.start.success) {
    const testCmd = params.command || 'echo "Hello from DSH Ubuntu" && whoami && pwd';
    console.log("[DSH ToolPkg] Testing dsh_run...");
    results.run = await dsh_run({ command: testCmd });
  }
  results.webview = await dsh_webview_url();
  const okCount = Object.values(results).filter((r) => r.success).length;
  const failCount = Object.values(results).filter((r) => r.success === false).length;
  complete({
    success: failCount === 0,
    message: failCount === 0 ? `\u2705 DSH ToolPkg \u81EA\u6D4B\u901A\u8FC7 (${okCount}/${Object.keys(results).length})` : `\u274C DSH ToolPkg \u81EA\u6D4B\u5931\u8D25 (${failCount}/${Object.keys(results).length})`,
    data: { results }
  });
}
exports.dsh_start = dsh_start;
exports.dsh_stop = dsh_stop;
exports.dsh_status = dsh_status;
exports.dsh_run = dsh_run;
exports.dsh_webview_url = dsh_webview_url;
exports.dsh_install = dsh_install;
exports.dsh_sync = dsh_sync;
exports.main = main;
