package com.ai.assistance.operit.data.agent

import android.content.Context
import android.os.FileObserver
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DshBrain - Minimal wrapper to run DeepSeek Harness (dsh) in ro-operit's Ubuntu environment.
 *
 * Runs `dsh web --host 127.0.0.1 --port 3082 --no-open` directly in the existing Ubuntu
 * environment (Termux/proot-distro) that ro-operit provides, and exposes it via:
 * - WebView at http://127.0.0.1:3082
 * - Tools: dsh_start, dsh_stop, dsh_run, dsh_status, dsh_sync for AI to control the dsh session
 * - Bidirectional chat sync between Operit Dev Chat and DSH Web UI via shared sync file
 */
class DshBrain private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DshBrain? = null
        private const val TAG = "DshBrain"
        private const val DEFAULT_PORT = 3082
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val SESSION_ID = "super_admin_default_session"
        private const val SYNC_FILE_NAME = "dsh_operit_sync.json"
        private const val SYNC_ORIGIN_DSH = "dsh_webui"
        private const val SYNC_ORIGIN_OPERIT = "operit_dev_chat"
        private const val SYNC_CHANNEL_BUFFER = 100

        fun getInstance(context: Context): DshBrain {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DshBrain(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Get DSH home directory from environment
         */
        suspend fun getDshHome(): String {
            val result = AndroidShellExecutor.executeShellCommand("bash -c 'echo \$DSH_HOME'")
            return result.stdout.trim().takeIf { it.isNotBlank() } ?: "/root/.dsh"
        }

        /**
         * Get sync file paths dynamically
         */
        suspend fun getSyncPaths(context: Context): Pair<String, String> {
            val dshHome = getDshHome()
            val dshSessionFile = "$dshHome/profiles/web/sessions/$SESSION_ID.json"
            val operitSyncFile = "${context.filesDir.absolutePath}/$SYNC_FILE_NAME"
            return Pair(operitSyncFile, dshSessionFile)
        }
    }

    // State
    private val isRunning = AtomicBoolean(false)
    private val port = AtomicReference<Int>(DEFAULT_PORT)
    private val host = AtomicReference<String>(DEFAULT_HOST)
    private val webUrl = AtomicReference<String>("")
    private var monitorJob: Job? = null
    private var shellProcess: ShellProcess? = null
    private var syncObserverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val syncChannel = Channel<SyncMessage>(SYNC_CHANNEL_BUFFER)
    private val processedMessageIds = ConcurrentHashMap<String, Long>()
    private val originId = UUID.randomUUID().toString()
    private var operitSyncFilePath: String = ""
    private var dshSessionFilePath: String = ""

    /**
     * Get DSH home directory from environment
     */
    suspend fun getDshHome(): String {
        val result = AndroidShellExecutor.executeShellCommand("bash -c 'echo \$DSH_HOME'")
        return result.stdout.trim().takeIf { it.isNotBlank() } ?: "/home/dsh"
    }

    /**
     * Get sync file paths dynamically
     */
    suspend fun getSyncPaths(context: Context): Pair<String, String> {
        val dshHome = getDshHome()
        val dshSessionFile = "$dshHome/.dsh/profiles/web/sessions/$SESSION_ID.json"
        val operitSyncFile = "${context.filesDir.absolutePath}/$SYNC_FILE_NAME"
        return Pair(operitSyncFile, dshSessionFile)
    }

    /**
     * Find the actual dsh binary path dynamically
     */
    private fun findDshBinary(): String {
        val paths = mutableListOf<String>()

        // Check NVM versions dynamically (any version) - /root
        val nvmRoot = File("/root/.nvm/versions/node")
        if (nvmRoot.exists()) {
            nvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val dshPath = File(versionDir, "bin/dsh")
                if (dshPath.exists()) paths.add(dshPath.absolutePath)
            }
        }

        // Also check .config/nvm - /root
        val configNvmRoot = File("/root/.config/nvm/versions/node")
        if (configNvmRoot.exists()) {
            configNvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val dshPath = File(versionDir, "bin/dsh")
                if (dshPath.exists()) paths.add(dshPath.absolutePath)
            }
        }

        // Check NVM versions dynamically (any version) - /home/dsh (ro-dsh mobile)
        val homeNvmRoot = File("/home/dsh/.nvm/versions/node")
        if (homeNvmRoot.exists()) {
            homeNvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val dshPath = File(versionDir, "bin/dsh")
                if (dshPath.exists()) paths.add(dshPath.absolutePath)
            }
        }

        // Also check .config/nvm - /home/dsh
        val homeConfigNvmRoot = File("/home/dsh/.config/nvm/versions/node")
        if (homeConfigNvmRoot.exists()) {
            homeConfigNvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val dshPath = File(versionDir, "bin/dsh")
                if (dshPath.exists()) paths.add(dshPath.absolutePath)
            }
        }

        // Standard npm global paths
        paths.addAll(listOf(
            "/root/.npm-global/bin/dsh",
            "/home/dsh/.npm-global/bin/dsh",
            "/usr/local/bin/dsh",
            "/home/dsh/.local/bin/dsh"
        ))

        return paths.firstOrNull { File(it).exists() } ?: "/home/dsh/.npm-global/bin/dsh"
    }

    /**
     * Find the actual node binary path dynamically (for PATH)
     */
    private fun findNodeBinary(): String {
        val paths = mutableListOf<String>()

        // Check NVM versions dynamically (any version) - /root
        val nvmRoot = File("/root/.nvm/versions/node")
        if (nvmRoot.exists()) {
            nvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val nodePath = File(versionDir, "bin/node")
                if (nodePath.exists()) paths.add(nodePath.parent?.absolutePath ?: "")
            }
        }

        // Also check .config/nvm - /root
        val configNvmRoot = File("/root/.config/nvm/versions/node")
        if (configNvmRoot.exists()) {
            configNvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val nodePath = File(versionDir, "bin/node")
                if (nodePath.exists()) paths.add(nodePath.parent?.absolutePath ?: "")
            }
        }

        // Check NVM versions dynamically (any version) - /home/dsh (ro-dsh mobile)
        val homeNvmRoot = File("/home/dsh/.nvm/versions/node")
        if (homeNvmRoot.exists()) {
            homeNvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val nodePath = File(versionDir, "bin/node")
                if (nodePath.exists()) paths.add(nodePath.parent?.absolutePath ?: "")
            }
        }

        // Also check .config/nvm - /home/dsh
        val homeConfigNvmRoot = File("/home/dsh/.config/nvm/versions/node")
        if (homeConfigNvmRoot.exists()) {
            homeConfigNvmRoot.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val nodePath = File(versionDir, "bin/node")
                if (nodePath.exists()) paths.add(nodePath.parent?.absolutePath ?: "")
            }
        }

        // Standard npm global paths
        paths.addAll(listOf(
            "/root/.npm-global/bin",
            "/home/dsh/.npm-global/bin",
            "/usr/local/bin",
            "/home/dsh/.local/bin"
        ))

        return paths.firstOrNull { File(it).exists() } ?: "/home/dsh/.npm-global/bin"
    }

    /**
     * Build the PATH string for shell commands
     */
    private fun buildShellPath(): String {
        val nodeBin = findNodeBinary()
        return "PATH=$nodeBin:/root/.npm-global/bin:/home/dsh/.npm-global/bin:/usr/local/bin:/usr/bin:/bin"
    }

    /**
     * Check if dsh is installed in Ubuntu environment
     * Checks filesystem paths in the Ubuntu root
     */
    fun isDshInstalled(): Boolean {
        return findDshBinary() != "/root/.npm-global/bin/dsh" || File("/root/.npm-global/bin/dsh").exists()
    }

    data class SyncMessage(
        val id: String,
        val originId: String,
        val source: String,
        val content: String,
        val timestamp: Long,
        val role: String
    )

    /**
     * Start dsh web server in Ubuntu environment
     * @param port Port to run dsh web on (default 3082)
     * @param host Host to bind to (default 127.0.0.1)
     * @return true if started successfully
     */
    suspend fun start(port: Int = DEFAULT_PORT, host: String = DEFAULT_HOST): Boolean {
        if (isRunning.get()) {
            AppLogger.w(TAG, "DshBrain already running on port ${this.port.get()}")
            return true
        }

        this.port.set(port)
        this.host.set(host)

        // Initialize sync paths
        val paths = DshBrain.getSyncPaths(context)
        operitSyncFilePath = paths.first
        dshSessionFilePath = paths.second

        try {
            // Check if dsh is installed
            val isInstalled = isDshInstalled()
            val dshBinary = findDshBinary()
            val dshHome = getDshHome()
            AppLogger.e(TAG, "isInstalled check: $isInstalled, binary=$dshBinary, home=$dshHome")

            if (!isInstalled) {
                AppLogger.d(TAG, "dsh not found, installing...")
                val installResult = AndroidShellExecutor.executeShellCommand(
                    "bash -c \"npm config set registry https://registry.npmjs.org/ && npm i -g @deepseek-ai/dsh@latest\""
                )
                if (!installResult.success) {
                    AppLogger.e(TAG, "Failed to install dsh: ${installResult.stderr}")
                    return false
                }
            }

            // Ensure sync directories exist
            setupSyncFiles()

            // Build command to start dsh web with --no-open
            // DSH doesn't support --host 0.0.0.0 for safety, use 127.0.0.1 with --trusted-host
            val command = "DSH_PERMISSION_MODE=danger-full-access $dshBinary web --host 127.0.0.1 --port $port --no-open --trusted-host 127.0.0.1:$port --trusted-host localhost:$port"
            val fullCommand = "bash -c ${escapeForShell("${buildShellPath()}; $command")}"

            AppLogger.d(TAG, "Starting dsh web: $fullCommand")

            // Start process directly in Ubuntu (no proot-distro wrapper needed)
            shellProcess = AndroidShellExecutor.startShellProcess(fullCommand)

            // Monitor process output for URL with token
            startOutputMonitor()

            // Start sync observer for DSH session file changes
            startSyncObserver()

            // Give it a moment to start (5s for dsh to fully initialize)
            delay(5000)

            // Verify it's responding and extract URL
            val healthCheck = checkHealth(port)
            if (healthCheck) {
                isRunning.set(true)
                val url = "http://127.0.0.1:$port"
                webUrl.set(url)
                AppLogger.i(TAG, "DshBrain started successfully on $url")
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
     * Stop dsh web server and sync observer
     */
    suspend fun stop(): Boolean {
        if (!isRunning.get()) {
            return true
        }

        // Stop sync observer
        syncObserverJob?.cancel()
        syncObserverJob = null

        // Kill the dsh process
        AndroidShellExecutor.executeShellCommand("pkill -f \"dsh web.*${port.get()}\"")

        shellProcess?.destroy()
        shellProcess = null

        monitorJob?.cancel()
        monitorJob = null

        isRunning.set(false)
        webUrl.set("")
        AppLogger.i(TAG, "DshBrain stopped")
        return true
    }

    /**
     * Check if dsh web server is running
     */
    fun isRunning(): Boolean = runBlocking {
        if (isRunning.get()) return@runBlocking true
        // Fallback check via pgrep and curl
        return@runBlocking try {
            val portNum = port.get()
            val pgrepCmd = """bash -c 'pgrep -f "dsh.*web"'"""
            val pgrepResult = AndroidShellExecutor.executeShellCommand(pgrepCmd)
            val processRunning = pgrepResult.success && pgrepResult.stdout.trim().isNotBlank()
            if (!processRunning) return@runBlocking false
            val curlCmd = """bash -c 'curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:${portNum}/'"""
            val curlResult = AndroidShellExecutor.executeShellCommand(curlCmd)
            val code = curlResult.stdout.trim()
            code in listOf("200", "401", "303")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the URL for the WebView (with token if available)
     */
    fun getWebUrl(): String {
        val baseUrl = webUrl.get().takeIf { it.isNotBlank() } ?: "http://127.0.0.1:${port.get()}"
        // Try to extract token from /tmp/dsh.log
        return try {
            val logFile = File("/tmp/dsh.log")
            if (logFile.exists()) {
                val logContent = logFile.readText()
                val tokenRegex = Regex("token=([A-Za-z0-9]+)")
                val match = tokenRegex.find(logContent)
                if (match != null) {
                    val token = match.groupValues[1]
                    val portNum = port.get()
                    "http://127.0.0.1:$portNum/?token=$token"
                } else {
                    AppLogger.w(TAG, "Token not found in /tmp/dsh.log, using base URL")
                    baseUrl
                }
            } else {
                baseUrl
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to extract token from log: ${e.message}")
            baseUrl
        }
    }

    /**
     * Get sync status
     */
    fun getSyncStatus(): String {
        val syncFile = File(operitSyncFilePath)
        val dshSessionFile = File(dshSessionFilePath)
        return "Sync: operit=${syncFile.exists()} dsh=${dshSessionFile.exists()} origin=$originId processed=${processedMessageIds.size}"
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

        // Execute command directly in Ubuntu
        val fullCommand = "bash -c ${escapeForShell("${buildShellPath()}; $command")}"
        val result = AndroidShellExecutor.executeShellCommand(fullCommand)

        return if (result.success) {
            result.stdout
        } else {
            "Error (exit ${result.exitCode}): ${result.stderr.ifEmpty { result.stdout }}"
        }
    }

    /**
     * Sync a message from Operit Dev Chat to DSH session
     * @param source Source identifier (e.g., "operit_dev_chat")
     * @param message Message content
     * @param role Message role ("user" or "assistant")
     */
    suspend fun syncMessage(source: String, message: String, role: String = "user"): Boolean {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val syncMessage = SyncMessage(
            id = messageId,
            originId = originId,
            source = source,
            content = message,
            timestamp = timestamp,
            role = role
        )

        // Write to sync file (both operit and proot paths)
        return try {
            writeSyncMessage(syncMessage)
            // Also append to DSH session file
            appendToDshSession(syncMessage)
            AppLogger.d(TAG, "Synced message from $source to DSH: ${message.take(50)}...")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to sync message to DSH", e)
            false
        }
    }

    /**
     * Process incoming sync messages from DSH session file
     */
    private fun processIncomingSyncMessage(syncMessage: SyncMessage) {
        // Skip if we originated this message (prevent loop)
        if (syncMessage.originId == originId) {
            AppLogger.d(TAG, "Skipping own message (origin match)")
            return
        }

        // Skip if already processed (deduplication)
        val existingTimestamp = processedMessageIds[syncMessage.id]
        if (existingTimestamp != null && existingTimestamp >= syncMessage.timestamp) {
            AppLogger.d(TAG, "Skipping already processed message")
            return
        }

        // Mark as processed
        processedMessageIds[syncMessage.id] = syncMessage.timestamp

        // Clean old entries (keep last 1000)
        if (processedMessageIds.size > 1000) {
            val oldest = processedMessageIds.entries.minByOrNull { it.value }?.key
            oldest?.let { processedMessageIds.remove(it) }
        }

        // Forward to Operit memory via channel (to be consumed by MemoryProvider)
        syncChannel.trySend(syncMessage)
        AppLogger.d(TAG, "Received sync message from DSH: ${syncMessage.content.take(50)}...")
    }

    /**
     * Get the sync channel for consumers (e.g., MemoryProvider)
     */
    fun getSyncChannel(): Channel<SyncMessage> = syncChannel

    // Private helpers

    /** Setup sync files and directories */
    private suspend fun setupSyncFiles() {
        // Create operit sync directory
        File(operitSyncFilePath).parentFile?.mkdirs()

        // Create dsh profiles directory and bind mount via symlink
        val dshProfilesDir = dshSessionFilePath.substringBeforeLast("/")
        val createDirsCmd = "bash -c ${escapeForShell("mkdir -p $dshProfilesDir && mkdir -p /root && ln -sf $operitSyncFilePath /root/dsh_operit_sync.json")}"
        AndroidShellExecutor.executeShellCommand(createDirsCmd)

        // Initialize sync file if not exists
        val initSync = """
            {
              "messages": [],
              "last_sync": 0,
              "version": 1
            }
        """.trimIndent()
        File(operitSyncFilePath).writeText(initSync)
    }

    /** Start monitoring dsh process stdout for URL */
    private fun startOutputMonitor() {
        shellProcess?.let { process ->
            monitorJob = scope.launch {
                try {
                    process.stdout.collect { line ->
                        AppLogger.d(TAG, "DSH stdout: $line")
                        // Parse URL from stdout (dsh web prints: "dsh web: http://127.0.0.1:PORT/?token=XXX")
                        val urlRegex = "dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)"
                        val match = urlRegex.toRegex().find(line)
                        match?.let {
                            val url = it.groupValues[1]
                            webUrl.set(url)
                            AppLogger.i(TAG, "Parsed DSH Web URL with token: $url")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Output monitor error", e)
                }
            }
        }
    }

    /** Start FileObserver on DSH session file for incoming messages */
    private fun startSyncObserver() {
        syncObserverJob = scope.launch {
            try {
                // First, ensure the session file exists
                val checkCmd = "bash -c ${escapeForShell("test -f $dshSessionFilePath && echo EXISTS || echo MISSING")}"
                val checkResult = AndroidShellExecutor.executeShellCommand(checkCmd)
                if (checkResult.stdout.trim() == "MISSING") {
                    // Create empty session file
                    val initSession = """
                        {
                          "id": "$SESSION_ID",
                          "messages": [],
                          "created_at": ${System.currentTimeMillis()},
                          "updated_at": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    val writeCmd = "bash -c ${escapeForShell("cat > $dshSessionFilePath << 'EOF'\n$initSession\nEOF")}"
                    AndroidShellExecutor.executeShellCommand(writeCmd)
                }

                // Use a polling approach since FileObserver doesn't work across proot
                while (isRunning.get()) {
                    delay(2000)
                    pollDshSessionForNewMessages()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Sync observer error", e)
            }
        }
    }

    /** Poll DSH session file for new messages from DSH Web UI */
    private suspend fun pollDshSessionForNewMessages() {
        try {
            val readCmd = "bash -c ${escapeForShell("cat $dshSessionFilePath")}"
            val result = AndroidShellExecutor.executeShellCommand(readCmd)
            if (result.success && result.stdout.isNotBlank()) {
                val sessionJson = JSONObject(result.stdout)
                val messages = sessionJson.optJSONArray("messages")
                if (messages != null) {
                    for (i in 0 until messages.length()) {
                        val msg = messages.getJSONObject(i)
                        val msgId = msg.optString("id", "")
                        val msgOriginId = msg.optString("origin_id", "")
                        val msgSource = msg.optString("source", "")
                        val msgContent = msg.optString("content", "")
                        val msgTimestamp = msg.optLong("timestamp", 0)
                        val msgRole = msg.optString("role", "user")

                        if (msgId.isNotBlank() && msgSource == SYNC_ORIGIN_DSH) {
                            val syncMsg = SyncMessage(
                                id = msgId,
                                originId = msgOriginId,
                                source = msgSource,
                                content = msgContent,
                                timestamp = msgTimestamp,
                                role = msgRole
                            )
                            processIncomingSyncMessage(syncMsg)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to poll DSH session", e)
        }
    }

    /** Write sync message to shared sync file */
    private suspend fun writeSyncMessage(syncMessage: SyncMessage): Boolean {
        try {
            val syncFile = File(operitSyncFilePath)
            val json = if (syncFile.exists()) {
                JSONObject(syncFile.readText())
            } else {
                JSONObject("""{"messages":[],"last_sync":0,"version":1}""")
            }

            val messages = json.optJSONArray("messages") ?: JSONArray()
            val msgObj = JSONObject().apply {
                put("id", syncMessage.id)
                put("origin_id", syncMessage.originId)
                put("source", syncMessage.source)
                put("content", syncMessage.content)
                put("timestamp", syncMessage.timestamp)
                put("role", syncMessage.role)
            }
            messages.put(msgObj)
            json.put("messages", messages)
            json.put("last_sync", System.currentTimeMillis())

            // Write to operit sync file
            FileWriter(syncFile).use { it.write(json.toString(2)) }

            // Also write to proot sync file (symlinked)
            val writeProotCmd = "bash -c ${escapeForShell("cat > /root/dsh_operit_sync.json << 'EOF'\n${json.toString(2)}\nEOF")}"
            AndroidShellExecutor.executeShellCommand(writeProotCmd)

            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write sync message", e)
            return false
        }
    }

    /** Append message to DSH session file */
    private suspend fun appendToDshSession(syncMessage: SyncMessage): Boolean {
        try {
            val readCmd = "bash -c ${escapeForShell("cat $dshSessionFilePath")}"
            val readResult = AndroidShellExecutor.executeShellCommand(readCmd)
            val sessionJson = if (readResult.success && readResult.stdout.isNotBlank()) {
                JSONObject(readResult.stdout)
            } else {
                JSONObject("""{"id":"$SESSION_ID","messages":[],"created_at":${System.currentTimeMillis()},"updated_at":${System.currentTimeMillis()}}""")
            }

            val messages = sessionJson.optJSONArray("messages") ?: JSONArray()
            val msgObj = JSONObject().apply {
                put("id", syncMessage.id)
                put("origin_id", syncMessage.originId)
                put("source", syncMessage.source)
                put("content", syncMessage.content)
                put("timestamp", syncMessage.timestamp)
                put("role", syncMessage.role)
            }
            messages.put(msgObj)
            sessionJson.put("messages", messages)
            sessionJson.put("updated_at", System.currentTimeMillis())

            val writeCmd = "bash -c ${escapeForShell("cat > $dshSessionFilePath << 'EOF'\n${sessionJson.toString(2)}\nEOF")}"
            val writeResult = AndroidShellExecutor.executeShellCommand(writeCmd)
            return writeResult.success
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to append to DSH session", e)
            return false
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
            responseCode == 200 || responseCode == 401 || responseCode == 303
        } catch (e: Exception) {
            // If /api/health doesn't exist, try root with pgrep and curl fallback
            try {
                // Check process via pgrep
                val pgrepCmd = """bash -c 'pgrep -f "dsh.*web"'"""
                val pgrepResult = AndroidShellExecutor.executeShellCommand(pgrepCmd)
                val processRunning = pgrepResult.success && pgrepResult.stdout.trim().isNotBlank()
                if (processRunning) return true

                // Try curl for HTTP code
                val cmd = """bash -c 'curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:${port}/'"""
                val curlResult = AndroidShellExecutor.executeShellCommand(cmd)
                val code = curlResult.stdout.trim()
                if (code in listOf("200", "401", "303")) return true

                val url = java.net.URL("http://127.0.0.1:$port/")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                connection.disconnect()
                responseCode == 200 || responseCode == 401 || responseCode == 303
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
                // Bypass isRunning check for npm install commands (e.g., dsh_install via dsh_run)
                val isInstallCommand = command.contains("npm i -g") || command.contains("npm install -g")
                if (!brain.isRunning() && !isInstallCommand) {
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
 * Tool executor for dsh_start - starts the dsh web server with auto token parsing
 */
class DshStartToolExecutor(private val context: Context) : ToolExecutor {
    private val TAG = "DshStartToolExecutor"

    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val port = tool.parameters.find { it.name == "port" }?.value?.toIntOrNull() ?: 3082
            val host = tool.parameters.find { it.name == "host" }?.value ?: "127.0.0.1"

            val brain = DshBrain.getInstance(context)
            val success = brain.start(port, host)

            if (success) {
                val url = brain.getWebUrl()
                val syncStatus = brain.getSyncStatus()
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("DshBrain started on $url\n$syncStatus"),
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

            // Also try to kill any remaining dsh web processes directly in Ubuntu
            try {
                AndroidShellExecutor.executeShellCommand("bash -c \"pkill -f 'dsh web' || true\"")
            } catch (e: Exception) {
                // Ignore
            }

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
 * Tool executor for dsh_status - checks if dsh is running with full URL
 */
class DshStatusToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val brain = DshBrain.getInstance(context)
            val internalRunning = brain.isRunning()

            // Check if dsh web process is running via pgrep
            val port = brain.getWebUrl().substringAfterLast(":").toIntOrNull() ?: 3082
            val processRunning = try {
                val pgrepResult = AndroidShellExecutor.executeShellCommand(
                    "bash -c \"pgrep -f \"dsh.*web\" \""
                )
                pgrepResult.success && pgrepResult.stdout.trim().isNotBlank()
            } catch (e: Exception) {
                false
            }

            // Check HTTP response code via curl
            val httpCodeRunning = try {
                val curlResult = AndroidShellExecutor.executeShellCommand(
                    "bash -c \"curl -s -o /dev/null -w \"%{http_code}\" http://127.0.0.1:$port/\""
                )
                val code = curlResult.stdout.trim()
                code in listOf("200", "401", "303")
            } catch (e: Exception) {
                false
            }

            val running = internalRunning || processRunning || httpCodeRunning
            val url = if (running) brain.getWebUrl() else "not running"
            val syncStatus = if (running) brain.getSyncStatus() else "sync: stopped"

            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData("DshBrain status: ${if (running) "running" else "stopped"} at $url\n$syncStatus"),
                error = null
            )
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        return ToolValidationResult(valid = true)
    }
}

/**
 * Tool executor for dsh_sync - manually trigger sync operations
 */
class DshSyncToolExecutor(private val context: Context) : ToolExecutor {
    private val TAG = "DshSyncToolExecutor"

    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val action = tool.parameters.find { it.name == "action" }?.value ?: "status"
            val brain = DshBrain.getInstance(context)

            return@runBlocking when (action) {
                "status" -> {
                    val internalRunning = brain.isRunning()
                    val port = brain.getWebUrl().substringAfterLast(":").toIntOrNull() ?: 3082
                    val actuallyRunning = try {
                        val result = AndroidShellExecutor.executeShellCommand(
                            "bash -c \"curl -s -m 3 http://127.0.0.1:$port/ | head -c 100\""
                        )
                        result.success && result.stdout.isNotBlank()
                    } catch (e: Exception) {
                        false
                    }
                    val running = internalRunning || actuallyRunning
                    val url = if (running) brain.getWebUrl() else "not running"
                    val syncStatus = if (running) brain.getSyncStatus() else "sync: stopped"
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("DshBrain status: ${if (running) "running" else "stopped"} at $url\n$syncStatus"),
                        error = null
                    )
                }
                "push_test" -> {
                    val message = tool.parameters.find { it.name == "message" }?.value ?: "Test message from Operit"
                    val success = brain.syncMessage("operit_dev_chat", message, "user")
                    ToolResult(
                        toolName = tool.name,
                        success = success,
                        result = StringResultData(if (success) "Test message pushed to DSH" else "Failed to push test message"),
                        error = if (success) null else "Sync failed"
                    )
                }
                "poll_now" -> {
                    // Trigger immediate poll (sync observer runs on interval)
                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData("Sync poll triggered (runs automatically every 2s)"),
                        error = null
                    )
                }
                else -> {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Unknown action: $action. Use: status, push_test, poll_now"
                    )
                }
            }
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val action = tool.parameters.find { it.name == "action" }?.value
        return if (action.isNullOrBlank()) {
            ToolValidationResult(valid = false, errorMessage = "Action parameter is required")
        } else {
            ToolValidationResult(valid = true)
        }
    }
}

/**
 * Tool executor for dsh_webview_url - gets the DSH WebView URL with token
 */
class DshWebviewUrlToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        return runBlocking {
            val brain = DshBrain.getInstance(context)
            val url = brain.getWebUrl()
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(url),
                error = if (url == "not running" || url.isBlank()) "DSH not running" else null
            )
        }
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        return ToolValidationResult(valid = true)
    }
}
