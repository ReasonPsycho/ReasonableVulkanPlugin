package com.example

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
class ReasonableVulkanService @JvmOverloads constructor(initialPort: Int = DEFAULT_PORT) : Disposable {
    private val logger = Logger.getInstance(ReasonableVulkanService::class.java)

    enum class EnginePlayState {
        STOPPED,
        PLAYING,
        PAUSED
    }

    companion object {
        const val DEFAULT_PORT = 63456
        val ENGINE_NOTIFICATION_PORTS = listOf(63457, 63455, 8080, 8081, 9000, 3000, 27015, 12345, 5000)

        fun getInstance(): ReasonableVulkanService = service()
    }

    private var server: HttpServer? = null
    private val broadcastExecutor = Executors.newCachedThreadPool()
    val openedFilesHistory = CopyOnWriteArrayList<String>()
    val activityHistory = CopyOnWriteArrayList<String>()

    var serverRunning: Boolean = false
        private set
    var serverPort: Int = initialPort
        private set

    var isPlaying: Boolean = false
        private set
    var isPaused: Boolean = false
        private set

    val playState: EnginePlayState
        get() = when {
            !isPlaying -> EnginePlayState.STOPPED
            isPaused -> EnginePlayState.PAUSED
            else -> EnginePlayState.PLAYING
        }

    val lastStateChangeTimestamp = AtomicLong(0)

    private val stateChangeListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        startServer(initialPort)
    }

    fun ensureStarted() {
        if (!serverRunning) {
            startServer(DEFAULT_PORT)
        }
    }

    @Synchronized
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        if (serverRunning) {
            return true
        }

        return try {
            val address = InetSocketAddress("127.0.0.1", port)
            val newServer = HttpServer.create(address, 0)
            val openHandler = OpenFileHandler()
            val statusHandler = StatusHandler()
            val playHandler = PlayHandler()
            val pauseHandler = PauseHandler()
            val stopHandler = StopHandler()
            val stateHandler = StateHandler()
            val commandHandler = CommandHandler()

            newServer.createContext("/open", openHandler)
            newServer.createContext("/api/open", openHandler)

            newServer.createContext("/status", statusHandler)
            newServer.createContext("/ping", statusHandler)
            newServer.createContext("/api/status", statusHandler)

            newServer.createContext("/play", playHandler)
            newServer.createContext("/api/play", playHandler)

            newServer.createContext("/pause", pauseHandler)
            newServer.createContext("/api/pause", pauseHandler)

            newServer.createContext("/stop", stopHandler)
            newServer.createContext("/api/stop", stopHandler)

            newServer.createContext("/state", stateHandler)
            newServer.createContext("/play-state", stateHandler)
            newServer.createContext("/api/state", stateHandler)

            newServer.createContext("/command", commandHandler)
            newServer.createContext("/events", commandHandler)
            newServer.createContext("/sync", commandHandler)
            newServer.createContext("/api/events", commandHandler)
            newServer.createContext("/api/command", commandHandler)

            newServer.executor = Executors.newCachedThreadPool()
            newServer.start()
            server = newServer
            serverPort = newServer.address.port
            serverRunning = true
            logger.info("ReasonableVulkan bridge server started on port $serverPort")
            true
        } catch (e: Exception) {
            logger.warn("Failed to start ReasonableVulkan bridge server on port $port", e)
            serverRunning = false
            false
        }
    }

    @Synchronized
    fun stopServer() {
        try {
            server?.stop(0)
            server = null
            serverRunning = false
            logger.info("ReasonableVulkan bridge server stopped")
        } catch (e: Exception) {
            logger.warn("Error stopping ReasonableVulkan bridge server", e)
        }
    }

    @Synchronized
    fun restartServer(port: Int = DEFAULT_PORT): Boolean {
        stopServer()
        return startServer(port)
    }

    override fun dispose() {
        stopServer()
        broadcastExecutor.shutdownNow()
        stateChangeListeners.clear()
    }

    fun setPlayingState(playing: Boolean, paused: Boolean = false, sendToEngine: Boolean = true) {
        val effectivePaused = if (playing) paused else false
        val stateChanged = (isPlaying != playing || isPaused != effectivePaused)
        if (stateChanged) {
            isPlaying = playing
            isPaused = effectivePaused
            val now = System.currentTimeMillis()
            lastStateChangeTimestamp.set(now)
            val currentState = playState
            val timeStr = SimpleDateFormat("HH:mm:ss").format(Date(now))
            val logEntry = "[$timeStr] Engine state: $currentState (playing=$isPlaying, paused=$isPaused)"
            activityHistory.add(0, logEntry)
            while (activityHistory.size > 100) {
                activityHistory.removeAt(activityHistory.size - 1)
            }
            logger.info("Engine play state updated to: $currentState (playing=$isPlaying, paused=$isPaused)")
            notifyStateChangeListeners()
        }

        if (sendToEngine) {
            val cmd = when {
                !playing -> "STOP"
                effectivePaused -> "PAUSE"
                else -> "PLAY"
            }
            sendCommand(cmd)
        }
    }

    fun setPlayState(newState: EnginePlayState, sendToEngine: Boolean = true) {
        when (newState) {
            EnginePlayState.STOPPED -> setPlayingState(playing = false, paused = false, sendToEngine = sendToEngine)
            EnginePlayState.PLAYING -> setPlayingState(playing = true, paused = false, sendToEngine = sendToEngine)
            EnginePlayState.PAUSED -> setPlayingState(playing = true, paused = true, sendToEngine = sendToEngine)
        }
    }

    fun setPaused(paused: Boolean, sendToEngine: Boolean = true) {
        if (isPlaying) {
            setPlayingState(playing = true, paused = paused, sendToEngine = sendToEngine)
        } else if (paused) {
            setPlayingState(playing = true, paused = true, sendToEngine = sendToEngine)
        }
    }

    fun sendCommand(command: String) {
        val now = System.currentTimeMillis()
        val cmdUpper = command.uppercase().trim()
        val signalFilenames = listOf(
            "rv_control", ".rv_control", "rv_control.signal", ".rv_control.signal",
            "rv_command", ".rv_command", "rv_command.signal", ".rv_command.signal",
            "rv_state", ".rv_state", "rv_state.signal", ".rv_state.signal"
        )
        val plainContent = "$cmdUpper;$now"
        val jsonContent = """{"command": "$cmdUpper", "action": "$cmdUpper", "state": "$cmdUpper", "isPlaying": $isPlaying, "isPaused": $isPaused, "timestamp": $now}"""

        for (filename in signalFilenames) {
            val content = if (filename.endsWith(".signal")) jsonContent else plainContent
            writeSignalFile(filename, content)
        }

        val jsonNotification = """{"command": "$cmdUpper", "action": "$cmdUpper", "state": "$cmdUpper", "isPlaying": $isPlaying, "isPaused": $isPaused, "timestamp": $now}"""
        broadcastToEngine(listOf("/command", "/control", "/state", "/play-state", "/api/command", "/api/state", "/events"), jsonNotification)
    }

    private fun writeSignalFile(filename: String, content: String) {
        try {
            // Write to system temp directory
            val tmpDir = System.getProperty("java.io.tmpdir")
            if (tmpDir != null) {
                val tmpFile = File(tmpDir, filename)
                Files.writeString(tmpFile.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                if (!filename.startsWith(".")) {
                    val dotTmpFile = File(tmpDir, ".$filename")
                    Files.writeString(dotTmpFile.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                }
            }

            // Write to open project directories
            val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
            for (proj in openProjects) {
                val basePath = proj.basePath
                if (basePath != null) {
                    val projFile = File(basePath, filename)
                    Files.writeString(projFile.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    if (!filename.startsWith(".")) {
                        val dotProjFile = File(basePath, ".$filename")
                        Files.writeString(dotProjFile.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    }
                }
            }

            // Write to current working directory
            val cwdFile = File(".", filename)
            Files.writeString(cwdFile.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            if (!filename.startsWith(".")) {
                val dotCwdFile = File(".", ".$filename")
                Files.writeString(dotCwdFile.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            }
        } catch (_: Exception) {}
    }

    private fun broadcastToEngine(paths: List<String>, jsonPayload: String) {
        broadcastExecutor.submit {
            for (port in ENGINE_NOTIFICATION_PORTS) {
                for (path in paths) {
                    try {
                        val uri = URI("http://127.0.0.1:$port$path")
                        val conn = uri.toURL().openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 80
                        conn.readTimeout = 80
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.outputStream.use { os ->
                            os.write(jsonPayload.toByteArray(StandardCharsets.UTF_8))
                        }
                        val responseCode = conn.responseCode
                        if (responseCode in 200..299) {
                            logger.info("Successfully notified engine on port $port at $path")
                        }
                        conn.disconnect()
                    } catch (_: Exception) {
                        // Connection refused or timed out is expected when engine is not on this port
                    }
                }
            }
        }
    }

    fun play() {
        setPlayingState(playing = true, paused = false, sendToEngine = true)
    }

    fun pause() {
        setPlayingState(playing = true, paused = true, sendToEngine = true)
    }

    fun resume() {
        setPlayingState(playing = true, paused = false, sendToEngine = true)
    }

    fun stop() {
        setPlayingState(playing = false, paused = false, sendToEngine = true)
    }

    fun togglePlay() {
        if (isPlaying) {
            stop()
        } else {
            play()
        }
    }

    fun togglePause() {
        if (isPlaying) {
            if (isPaused) {
                resume()
            } else {
                pause()
            }
        } else {
            sendCommand("TOGGLE_PAUSE")
        }
    }

    fun addStateChangeListener(listener: () -> Unit) {
        stateChangeListeners.add(listener)
    }

    fun removeStateChangeListener(listener: () -> Unit) {
        stateChangeListeners.remove(listener)
    }

    private fun notifyStateChangeListeners() {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.invokeLater {
                for (listener in stateChangeListeners) {
                    try { listener() } catch (e: Exception) { logger.warn("Error in state change listener", e) }
                }
            }
        } else {
            for (listener in stateChangeListeners) {
                try { listener() } catch (e: Exception) { logger.warn("Error in state change listener", e) }
            }
        }
    }

    fun openFileInIde(rawFilePath: String): Boolean {
        var cleanPath = rawFilePath.trim()
        if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"") && cleanPath.length >= 2) {
            cleanPath = cleanPath.substring(1, cleanPath.length - 1).trim()
        }
        cleanPath = cleanPath.replace('\\', '/')

        if (cleanPath.isEmpty()) {
            return false
        }

        openedFilesHistory.add(0, cleanPath)
        while (openedFilesHistory.size > 100) {
            openedFilesHistory.removeAt(openedFilesHistory.size - 1)
        }

        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss").format(Date(now))
        val fileName = File(cleanPath).name
        activityHistory.add(0, "[$timeStr] Opened: $fileName")
        while (activityHistory.size > 100) {
            activityHistory.removeAt(activityHistory.size - 1)
        }

        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.invokeLater {
                try {
                    var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(cleanPath)
                    if (virtualFile == null) {
                        try {
                            val pathObj = Path.of(cleanPath)
                            virtualFile = VfsUtil.findFile(pathObj, true)
                        } catch (_: Exception) {}
                    }

                    val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
                    if (openProjects.isEmpty()) {
                        logger.warn("No open projects found to open file: $cleanPath")
                        return@invokeLater
                    }

                    val targetProject = openProjects.firstOrNull { project ->
                        val basePath = project.basePath?.replace('\\', '/')
                        basePath != null && cleanPath.startsWith(basePath)
                    } ?: openProjects.first()

                    if (virtualFile != null && virtualFile.isValid) {
                        FileEditorManager.getInstance(targetProject).openFile(virtualFile, true)
                        focusIdeWindow(targetProject)
                    } else {
                        val ioFile = File(cleanPath)
                        val refreshedVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                        if (refreshedVf != null && refreshedVf.isValid) {
                            FileEditorManager.getInstance(targetProject).openFile(refreshedVf, true)
                            focusIdeWindow(targetProject)
                        } else {
                            logger.warn("Could not find VirtualFile for path: $cleanPath")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error opening file in IDE: $cleanPath", e)
                }
            }
        } else {
            logger.info("ApplicationManager is not available (running outside IDE). Registered path: $cleanPath")
        }

        return true
    }

    private fun focusIdeWindow(project: Project) {
        try {
            val frame = WindowManager.getInstance().getFrame(project)
            if (frame != null) {
                frame.toFront()
                frame.requestFocus()
                IdeFocusManager.getInstance(project).requestFocus(frame, true)
            }
        } catch (e: Exception) {
            logger.warn("Failed to bring IDE window to front", e)
        }
    }

    private inner class OpenFileHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                addCorsHeaders(exchange)
                if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                    exchange.sendResponseHeaders(200, -1)
                    return
                }

                var filePath: String? = null

                if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                    val query = exchange.requestURI.query ?: ""
                    val queryParams = parseQueryParams(query)
                    filePath = queryParams["file"]
                } else if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
                    if (body.startsWith("{") && body.contains("\"file\"")) {
                        val regex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                        val match = regex.find(body)
                        filePath = match?.groups?.get(1)?.value ?: body
                    } else {
                        filePath = body
                    }
                }

                if (filePath.isNullOrBlank()) {
                    val response = """{"error": "Missing 'file' parameter"}"""
                    sendJsonResponse(exchange, 400, response)
                    return
                }

                val opened = openFileInIde(filePath)
                val response = """{"status": "ok", "file": "$filePath", "opened": $opened}"""
                sendJsonResponse(exchange, 200, response)
            } catch (e: Exception) {
                logger.error("Exception in OpenFileHandler", e)
                val response = """{"error": "${escapeJson(e.message ?: "Unknown error")}"}"""
                sendJsonResponse(exchange, 500, response)
            }
        }
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            addCorsHeaders(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(200, -1)
                return
            }

            val response = """{
  "status": "running",
  "port": $serverPort,
  "isPlaying": $isPlaying,
  "isPaused": $isPaused,
  "playState": "$playState",
  "openedFilesCount": ${openedFilesHistory.size}
}""".trimIndent()
            sendJsonResponse(exchange, 200, response)
        }
    }

    private inner class PlayHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            addCorsHeaders(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(200, -1)
                return
            }
            play()
            val response = """{"status": "ok", "isPlaying": $isPlaying, "isPaused": $isPaused, "state": "$playState", "timestamp": ${lastStateChangeTimestamp.get()}}"""
            sendJsonResponse(exchange, 200, response)
        }
    }

    private inner class PauseHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            addCorsHeaders(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(200, -1)
                return
            }
            val query = exchange.requestURI.query ?: ""
            val queryParams = parseQueryParams(query)
            if (queryParams["action"].equals("toggle", ignoreCase = true)) {
                togglePause()
            } else if (queryParams["action"].equals("resume", ignoreCase = true) || queryParams["action"].equals("unpause", ignoreCase = true)) {
                resume()
            } else {
                pause()
            }
            val response = """{"status": "ok", "isPlaying": $isPlaying, "isPaused": $isPaused, "state": "$playState", "timestamp": ${lastStateChangeTimestamp.get()}}"""
            sendJsonResponse(exchange, 200, response)
        }
    }

    private inner class StopHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            addCorsHeaders(exchange)
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(200, -1)
                return
            }
            stop()
            val response = """{"status": "ok", "isPlaying": $isPlaying, "isPaused": $isPaused, "state": "$playState", "timestamp": ${lastStateChangeTimestamp.get()}}"""
            sendJsonResponse(exchange, 200, response)
        }
    }

    private inner class StateHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                addCorsHeaders(exchange)
                if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                    exchange.sendResponseHeaders(200, -1)
                    return
                }

                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
                    var requestedState: String? = null
                    var playingOpt: Boolean? = null
                    var pausedOpt: Boolean? = null

                    if (body.startsWith("{")) {
                        val stateRegex = """"state"\s*:\s*"([^"]+)"""".toRegex()
                        requestedState = stateRegex.find(body)?.groups?.get(1)?.value

                        val isPlayingRegex = """"(?:isPlaying|playing)"\s*:\s*(true|false)""".toRegex()
                        isPlayingRegex.find(body)?.groups?.get(1)?.value?.let {
                            playingOpt = it.toBoolean()
                        }

                        val isPausedRegex = """"(?:isPaused|paused)"\s*:\s*(true|false)""".toRegex()
                        isPausedRegex.find(body)?.groups?.get(1)?.value?.let {
                            pausedOpt = it.toBoolean()
                        }
                    } else if (body.isNotEmpty()) {
                        val parts = body.split(";")
                        requestedState = parts[0]
                    }

                    if (playingOpt != null) {
                        val p = playingOpt
                        val pa = pausedOpt ?: (if (requestedState.equals("PAUSED", ignoreCase = true)) true else isPaused)
                        setPlayingState(playing = p, paused = if (p) pa else false, sendToEngine = false)
                    } else if (requestedState != null) {
                        when (requestedState.uppercase()) {
                            "PLAY", "PLAYING" -> setPlayingState(playing = true, paused = false, sendToEngine = false)
                            "PAUSE", "PAUSED" -> setPlayingState(playing = true, paused = true, sendToEngine = false)
                            "RESUME", "UNPAUSE" -> setPlayingState(playing = true, paused = false, sendToEngine = false)
                            "STOP", "STOPPED" -> setPlayingState(playing = false, paused = false, sendToEngine = false)
                            else -> logger.warn("Unknown play state requested: $requestedState")
                        }
                    }
                }

                val response = """{"status": "ok", "isPlaying": $isPlaying, "isPaused": $isPaused, "state": "$playState", "timestamp": ${lastStateChangeTimestamp.get()}}"""
                sendJsonResponse(exchange, 200, response)
            } catch (e: Exception) {
                logger.error("Exception in StateHandler", e)
                val response = """{"error": "${escapeJson(e.message ?: "Unknown error")}"}"""
                sendJsonResponse(exchange, 500, response)
            }
        }
    }

    private inner class CommandHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                addCorsHeaders(exchange)
                if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                    exchange.sendResponseHeaders(200, -1)
                    return
                }

                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
                    var command: String? = null

                    if (body.startsWith("{")) {
                        val cmdRegex = """"command"\s*:\s*"([^"]+)"""".toRegex()
                        command = cmdRegex.find(body)?.groups?.get(1)?.value
                    } else {
                        command = body
                    }

                    when (command?.lowercase()) {
                        "play" -> play()
                        "pause" -> pause()
                        "resume", "unpause" -> resume()
                        "toggle_pause", "togglepause" -> togglePause()
                        "stop" -> stop()
                        "toggle_play", "toggleplay" -> togglePlay()
                        else -> logger.warn("Unknown command received: $command")
                    }
                }

                val response = """{
  "status": "ok",
  "isPlaying": $isPlaying,
  "isPaused": $isPaused,
  "state": "$playState",
  "timestamp": ${System.currentTimeMillis()}
}""".trimIndent()
                sendJsonResponse(exchange, 200, response)
            } catch (e: Exception) {
                logger.error("Exception in CommandHandler", e)
                val response = """{"error": "${escapeJson(e.message ?: "Unknown error")}"}"""
                sendJsonResponse(exchange, 500, response)
            }
        }
    }

    private fun addCorsHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val pair = param.split("=", limit = 2)
            if (pair.size == 2) {
                val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                result[key] = value
            } else if (pair.size == 1 && pair[0].isNotEmpty()) {
                val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                result[key] = ""
            }
        }
        return result
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}
