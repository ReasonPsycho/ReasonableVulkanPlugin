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
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@Service(Service.Level.APP)
class ReasonableVulkanService : Disposable {
    private val logger = Logger.getInstance(ReasonableVulkanService::class.java)

    companion object {
        const val DEFAULT_PORT = 63456

        fun getInstance(): ReasonableVulkanService = service()
    }

    private var server: HttpServer? = null
    val openedFilesHistory = CopyOnWriteArrayList<String>()
    var serverRunning: Boolean = false
        private set
    var serverPort: Int = DEFAULT_PORT
        private set

    init {
        startServer(DEFAULT_PORT)
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
            newServer.createContext("/open", OpenFileHandler())
            newServer.createContext("/status", StatusHandler())
            newServer.createContext("/ping", StatusHandler())
            newServer.executor = Executors.newCachedThreadPool()
            newServer.start()
            server = newServer
            serverPort = port
            serverRunning = true
            logger.info("ReasonableVulkan bridge server started on port $port")
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
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

                if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                    exchange.sendResponseHeaders(200, -1)
                    return
                }

                var filePath: String? = null

                if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                    val query = exchange.requestURI.query ?: ""
                    for (param in query.split("&")) {
                        val pair = param.split("=", limit = 2)
                        if (pair.size == 2 && pair[0] == "file") {
                            filePath = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                            break
                        }
                    }
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
                val response = """{"error": "${e.message?.replace("\"", "\\\"")}"}"""
                sendJsonResponse(exchange, 500, response)
            }
        }
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            val response = """{"status": "running", "port": $serverPort}"""
            sendJsonResponse(exchange, 200, response)
        }
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
