package com.example

import org.junit.After
import org.junit.Assert.assertEquals
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
        service = ReasonableVulkanService()
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
}
