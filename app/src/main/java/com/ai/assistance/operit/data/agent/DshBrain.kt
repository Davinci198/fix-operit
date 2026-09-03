package com.ai.assistance.operit.data.agent

import android.content.Context
import android.webkit.WebView
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellProcess
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * DshBrain - Minimal wrapper to run DeepSeek Harness (dsh) in ro-operit's Ubuntu environment.
 *
 * Uses AndroidShellExecutor which handles all permission levels (ROOT, ADMIN, DEBUGGER, ACCESSIBILITY, STANDARD)
 * via ShellExecutorFactory. Runs `dsh web --host 0.0.0.0 --port 3082` inside the existing proot-distro Ubuntu
 * environment that ro-operit already provides, and exposes it via:
 * - WebView at http://127.0.0.1:3082
 * - Tools: dsh_start, dsh_stop, dsh_run, dsh_status for AI to control the dsh session
 */
class DshBrain private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DshBrain? = null
        private const val TAG = "DshBrain"
        private const val DEFAULT_PORT = 3082
        private const val DEFAULT_HOST = "0.0.0.0"

        fun getInstance(context: Context): DshBrain {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DshBrain(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // State
    private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val port = java.util.concurrent.atomic.AtomicReference<Int>(DEFAULT_PORT)
    private val host = java.util.concurrent.atomic.AtomicReference<String>(DEFAULT_HOST)
    private var monitorJob: Job? = null
    private var shellProcess: ShellProcess? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Start dsh web server in Ubuntu environment
     * @param port Port to run dsh web on (default 3082)
     * @param host Host to bind to (default 0.0.0.0)
     * @return true if started successfully
     */
    suspend fun start(port: Int = DEFAULT_PORT, host: String = DEFAULT_HOST): Boolean {
        if (isRunning.get()) {
            AppLogger.w(TAG, "DshBrain already running on port ${this.port.get()}")
            return true
        }

        this.port.set(port)
        this.host.set(host)

        try {
            // Check if dsh is available in Ubuntu (using proot-distro login)
            val checkResult = AndroidShellExecutor.executeShellCommand("proot-distro login ubuntu -- which dsh")
            if (!checkResult.success || checkResult.stdout.trim().isEmpty()) {
                AppLogger.d(TAG, "dsh not found in Ubuntu, installing...")
                val installResult = AndroidShellExecutor.executeShellCommand(
                    "proot-distro login ubuntu -- bash -c \"npm config set registry https://registry.npmjs.org/ && npm i -g @deepseek-ai/dsh\""
                )
                if (!installResult.success) {
                    AppLogger.e(TAG, "Failed to install dsh: ${installResult.stderr}")
                    return false
                }
            }

            // Build command to start dsh web in Ubuntu
            val command = "dsh web --host $host --port $port"
            val fullCommand = "proot-distro login ubuntu -- bash -c ${escapeForShell(command)}"

            AppLogger.d(TAG, "Starting dsh web: $fullCommand")

            // Start process in Ubuntu using ShellExecutor
            shellProcess = AndroidShellExecutor.startShellProcess(fullCommand)

            // Monitor process
            startProcessMonitor()

            // Give it a moment to start
            delay(2000)

            // Verify it's responding
            val healthCheck = checkHealth(port)
            if (healthCheck) {
                isRunning.set(true)
                AppLogger.i(TAG, "DshBrain started successfully on http://$host:$port")
                return true
            } else {
                AppLogger.w(TAG, "DshBrain process started but health check failed")
                stop()
                return false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start DshBrain", e)
            stop()
            return false
        }
    }

    /**
     * Stop dsh web server
     */
    suspend fun stop(): Boolean {
        if (!isRunning.get()) {
            return true
        }

        // Kill the dsh process
        AndroidShellExecutor.executeShellCommand("pkill -f \"dsh web.*${port.get()}\"")

        shellProcess?.destroy()
        shellProcess = null

        monitorJob?.cancel()
        monitorJob = null

        isRunning.set(false)
        AppLogger.i(TAG, "DshBrain stopped")
        return true
    }

    /**
     * Check if dsh web server is running
     */
    fun isRunning(): Boolean {
        return isRunning.get()
    }

    /**
     * Get the URL for the WebView
     */
    fun getWebUrl(): String {
        return "http://127.0.0.1:${port.get()}"
    }

    /**
     * Load the dsh web UI in a WebView
     */
    fun loadInWebView(webView: WebView) {
        val url = getWebUrl()
        AppLogger.d(TAG, "Loading WebView: $url")
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.loadUrl(url)
    }

    /**
     * Execute a command in the dsh Ubuntu session (for AI tool dsh_run)
     */
    suspend fun executeInSession(command: String): String {
        if (!isRunning.get()) {
            return "DshBrain not running. Call start() first."
        }

        // Execute command in Ubuntu via proot-distro
        val fullCommand = "proot-distro login ubuntu -- bash -c ${escapeForShell(command)}"
        val result = AndroidShellExecutor.executeShellCommand(fullCommand)

        return if (result.success) {
            result.stdout
        } else {
            "Error (exit ${result.exitCode}): ${result.stderr.ifEmpty { result.stdout }}"
        }
    }

    // Private helpers

    /** Start monitoring the dsh process */
    private fun startProcessMonitor() {
        shellProcess?.let { process ->
            monitorJob = scope.launch {
                try {
                    val exitCode = process.waitFor()
                    if (isRunning.get()) {
                        AppLogger.w(TAG, "DshBrain process exited unexpectedly with code $exitCode")
                        isRunning.set(false)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Process monitor error", e)
                }
            }
        }
    }

    /** Check if dsh web is responding */
    private suspend fun checkHealth(port: Int): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:$port/api/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            // If /api/health doesn't exist, try root
            try {
                val url = java.net.URL("http://127.0.0.1:$port/")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                connection.disconnect()
                responseCode == 200
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** Escape command for shell execution */
    private fun escapeForShell(command: String): String {
        return "'${command.replace("'", "'\\''")}'"
    }
}

/**
 * Tool executor for dsh_run - allows AI to run commands in the dsh Ubuntu session
 */
class DshRunToolExecutor(private val context: Context) : ToolExecutor {
    private val TAG = "DshRunToolExecutor"

    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val command = tool.parameters.find { it.name == "command" }?.value ?: ""
            if (command.isBlank()) {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Command parameter is required"
                )
            } else {
                val brain = DshBrain.getInstance(context)
                if (!brain.isRunning()) {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "DshBrain not running. Use dsh_start tool first."
                    )
                } else {
                    try {
                        val output = brain.executeInSession(command)
                        ToolResult(
                            toolName = tool.name,
                            success = true,
                            result = StringResultData(output),
                            error = null
                        )
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error executing dsh command", e)
                        ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "Execution failed: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val command = tool.parameters.find { it.name == "command" }?.value
        return if (command.isNullOrBlank()) {
            ToolValidationResult(valid = false, errorMessage = "Command parameter is required")
        } else {
            ToolValidationResult(valid = true)
        }
    }
}

/**
 * Tool executor for dsh_start - starts the dsh web server
 */
class DshStartToolExecutor(private val context: Context) : ToolExecutor {
    private val TAG = "DshStartToolExecutor"

    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val port = tool.parameters.find { it.name == "port" }?.value?.toIntOrNull() ?: 3082
            val host = tool.parameters.find { it.name == "host" }?.value ?: "0.0.0.0"

            val brain = DshBrain.getInstance(context)
            val success = brain.start(port, host)

            if (success) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("DshBrain started on http://$host:$port"),
                    error = null
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Failed to start DshBrain. Check if dsh is installed in Ubuntu."
                )
            }
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        return ToolValidationResult(valid = true)
    }
}

/**
 * Tool executor for dsh_stop - stops the dsh web server
 */
class DshStopToolExecutor(private val context: Context) : ToolExecutor {
    private val TAG = "DshStopToolExecutor"

    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val brain = DshBrain.getInstance(context)
            val success = brain.stop()

            ToolResult(
                toolName = tool.name,
                success = success,
                result = StringResultData(if (success) "DshBrain stopped" else "DshBrain was not running"),
                error = null
            )
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        return ToolValidationResult(valid = true)
    }
}

/**
 * Tool executor for dsh_status - checks if dsh is running
 */
class DshStatusToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val brain = DshBrain.getInstance(context)
            val running = brain.isRunning()
            val url = if (running) brain.getWebUrl() else "not running"

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData("DshBrain status: ${if (running) "running" else "stopped"} at $url"),
                error = null
            )
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        return ToolValidationResult(valid = true)
    }
}
