package com.example

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class ReasonableVulkanServiceTest {

    private lateinit var service: ReasonableVulkanService

    @Before
    fun setUp() {
        // Use port 0 to bind to any free dynamic port for reliable isolated testing
        service = ReasonableVulkanService(initialPort = 0)
    }

    @After
    fun tearDown() {
        service.dispose()
    }

    @Test
    fun testServerStatus() {
        assertTrue(service.serverRunning)

        val url = URI("http://127.0.0.1:${service.serverPort}/status").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        assertEquals(200, connection.responseCode)
        val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        assertTrue(body.contains("\"status\": \"running\""))
        assertTrue(body.contains("\"isPlaying\": false"))
        assertTrue(body.contains("\"isPaused\": false"))
        assertTrue(body.contains("\"playState\": \"STOPPED\""))
    }

    @Test
    fun testOpenEndpointGet() {
        val testPath = "C:/test/asset/sample.frag"
        val encodedPath = "C%3A%2Ftest%2Fasset%2Fsample.frag"
        val url = URI("http://127.0.0.1:${service.serverPort}/open?file=$encodedPath").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        assertEquals(200, connection.responseCode)
        val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        assertTrue(body.contains("\"status\": \"ok\""))
        assertTrue(service.openedFilesHistory.contains(testPath))
    }

    @Test
    fun testOpenEndpointPost() {
        val testPath = "C:/test/asset/sample.vert"
        val url = URI("http://127.0.0.1:${service.serverPort}/open").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use {
            it.write(testPath.toByteArray(StandardCharsets.UTF_8))
        }

        assertEquals(200, connection.responseCode)
        val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        assertTrue(body.contains("\"status\": \"ok\""))
        assertTrue(service.openedFilesHistory.contains(testPath))
    }

    @Test
    fun testPlayStopPauseFunctionality() {
        var stateNotified = false
        service.addStateChangeListener {
            stateNotified = true
        }

        assertEquals(ReasonableVulkanService.EnginePlayState.STOPPED, service.playState)
        assertFalse(service.isPlaying)
        assertFalse(service.isPaused)

        // Play
        service.play()
        assertEquals(ReasonableVulkanService.EnginePlayState.PLAYING, service.playState)
        assertTrue(service.isPlaying)
        assertFalse(service.isPaused)
        assertTrue(stateNotified)

        // Pause (isPlaying remains true, isPaused becomes true)
        stateNotified = false
        service.pause()
        assertEquals(ReasonableVulkanService.EnginePlayState.PAUSED, service.playState)
        assertTrue(service.isPlaying)
        assertTrue(service.isPaused)
        assertTrue(stateNotified)

        // Toggle pause -> back to playing (isPlaying remains true, isPaused becomes false)
        service.togglePause()
        assertEquals(ReasonableVulkanService.EnginePlayState.PLAYING, service.playState)
        assertTrue(service.isPlaying)
        assertFalse(service.isPaused)

        // Pause again then toggle play -> must stop
        service.pause()
        assertTrue(service.isPlaying)
        assertTrue(service.isPaused)
        service.togglePlay()
        assertEquals(ReasonableVulkanService.EnginePlayState.STOPPED, service.playState)
        assertFalse(service.isPlaying)
        assertFalse(service.isPaused)

        // Toggle play -> from stopped to playing
        service.togglePlay()
        assertEquals(ReasonableVulkanService.EnginePlayState.PLAYING, service.playState)
        assertTrue(service.isPlaying)
        assertFalse(service.isPaused)

        // Toggle play -> from playing to stopped
        service.togglePlay()
        assertEquals(ReasonableVulkanService.EnginePlayState.STOPPED, service.playState)
        assertFalse(service.isPlaying)
        assertFalse(service.isPaused)
    }

    @Test
    fun testPlayStopEndpoints() {
        // POST /play
        val playUrl = URI("http://127.0.0.1:${service.serverPort}/play").toURL()
        val playConn = playUrl.openConnection() as HttpURLConnection
        playConn.requestMethod = "POST"
        assertEquals(200, playConn.responseCode)
        val playBody = playConn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        assertTrue(playBody.contains("\"state\": \"PLAYING\""))
        assertEquals(ReasonableVulkanService.EnginePlayState.PLAYING, service.playState)

        // POST /pause
        val pauseUrl = URI("http://127.0.0.1:${service.serverPort}/pause").toURL()
        val pauseConn = pauseUrl.openConnection() as HttpURLConnection
        pauseConn.requestMethod = "POST"
        assertEquals(200, pauseConn.responseCode)
        assertEquals(ReasonableVulkanService.EnginePlayState.PAUSED, service.playState)

        // POST /stop
        val stopUrl = URI("http://127.0.0.1:${service.serverPort}/stop").toURL()
        val stopConn = stopUrl.openConnection() as HttpURLConnection
        stopConn.requestMethod = "POST"
        assertEquals(200, stopConn.responseCode)
        assertEquals(ReasonableVulkanService.EnginePlayState.STOPPED, service.playState)

        // POST /state
        val stateUrl = URI("http://127.0.0.1:${service.serverPort}/state").toURL()
        val stateConn = stateUrl.openConnection() as HttpURLConnection
        stateConn.requestMethod = "POST"
        stateConn.doOutput = true
        stateConn.outputStream.use {
            it.write("""{"state": "PLAYING"}""".toByteArray(StandardCharsets.UTF_8))
        }
        assertEquals(200, stateConn.responseCode)
        assertEquals(ReasonableVulkanService.EnginePlayState.PLAYING, service.playState)
    }

    @Test
    fun testCommandEndpoint() {
        val commandUrl = URI("http://127.0.0.1:${service.serverPort}/command").toURL()
        val commandConn = commandUrl.openConnection() as HttpURLConnection
        commandConn.requestMethod = "POST"
        commandConn.doOutput = true
        commandConn.outputStream.use {
            it.write("""{"command": "play"}""".toByteArray(StandardCharsets.UTF_8))
        }
        assertEquals(200, commandConn.responseCode)
        assertEquals(ReasonableVulkanService.EnginePlayState.PLAYING, service.playState)

        val cmdStopConn = commandUrl.openConnection() as HttpURLConnection
        cmdStopConn.requestMethod = "POST"
        cmdStopConn.doOutput = true
        cmdStopConn.outputStream.use {
            it.write("""{"command": "stop"}""".toByteArray(StandardCharsets.UTF_8))
        }
        assertEquals(200, cmdStopConn.responseCode)
        assertEquals(ReasonableVulkanService.EnginePlayState.STOPPED, service.playState)
    }

    @Test
    fun testSignalFilesWrittenOnCommand() {
        val tmpDir = System.getProperty("java.io.tmpdir")
        val controlFile = java.io.File(tmpDir, "plugin/rv_control")
        val controlSignalFile = java.io.File(tmpDir, "plugin/rv_control.signal")

        service.stop()
        assertTrue(controlFile.exists())
        val controlContent = controlFile.readText()
        assertTrue(controlContent.startsWith("STOP;"))

        assertTrue(controlSignalFile.exists())
        val jsonContent = controlSignalFile.readText()
        assertTrue(jsonContent.contains("\"command\": \"STOP\""))

        service.play()
        val playContent = controlFile.readText()
        assertTrue(playContent.startsWith("PLAY;"))
    }
}
