package com.nocrunch.rider.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class EngineClientTest {
    private fun frame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + body
    }

    private class Pipe {
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 1 shl 16)
        val clientOut = ByteArrayOutputStream()
    }

    @Test
    fun writes_content_length_framed_request() {
        val pipe = Pipe()
        val client = EngineClient(pipe.clientIn, pipe.clientOut)
        client.request("runAll", null, JsonObject::class.java)

        val written = pipe.clientOut.toString(Charsets.UTF_8)
        val header = written.substringBefore("\r\n\r\n")
        val body = written.substringAfter("\r\n\r\n")
        assertEquals("Content-Length: ${body.toByteArray().size}", header)
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals("2.0", json["jsonrpc"].asString)
        assertEquals("runAll", json["method"].asString)
        assertTrue(json.has("id"))
        client.close()
    }

    @Test
    fun parses_split_and_coalesced_frames() {
        val pipe = Pipe()
        val client = EngineClient(pipe.clientIn, pipe.clientOut)
        val seen = LinkedBlockingQueue<String>()
        client.onNotification { method, params -> seen.add(method + ":" + params["n"].asInt) }

        val a = frame("""{"jsonrpc":"2.0","method":"log","params":{"n":1}}""")
        val b = frame("""{"jsonrpc":"2.0","method":"log","params":{"n":2}}""")
        val c = frame("""{"jsonrpc":"2.0","method":"log","params":{"n":3,"text":"é"}}""")
        pipe.toClient.write(a + b) // two messages in one chunk
        pipe.toClient.flush()
        val third = c.size / 3
        pipe.toClient.write(c, 0, third); pipe.toClient.flush()
        Thread.sleep(20)
        pipe.toClient.write(c, third, third); pipe.toClient.flush()
        Thread.sleep(20)
        pipe.toClient.write(c, 2 * third, c.size - 2 * third); pipe.toClient.flush()

        assertEquals("log:1", seen.poll(5, TimeUnit.SECONDS))
        assertEquals("log:2", seen.poll(5, TimeUnit.SECONDS))
        assertEquals("log:3", seen.poll(5, TimeUnit.SECONDS))
        client.close()
    }

    @Test
    fun routes_notifications_and_completes_responses() {
        val pipe = Pipe()
        val client = EngineClient(pipe.clientIn, pipe.clientOut)
        val notes = LinkedBlockingQueue<String>()
        client.onNotification { method, _ -> notes.add(method) }

        val future = client.request("initialize", InitializeParams("/s.slnx", "dotnet", 2, 300), InitializeResult::class.java)
        val sent = JsonParser.parseString(pipe.clientOut.toString(Charsets.UTF_8).substringAfter("\r\n\r\n")).asJsonObject
        assertEquals("/s.slnx", sent["params"].asJsonObject["solutionPath"].asString)
        val id = sent["id"].asInt
        pipe.toClient.write(frame("""{"jsonrpc":"2.0","method":"stateChanged","params":{"state":"idle","queued":0}}"""))
        pipe.toClient.write(frame("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":1,"repoRoot":"/r","mirrorRoot":"/r/m"}}"""))
        pipe.toClient.flush()

        val result = future.get(5, TimeUnit.SECONDS)
        assertEquals(1, result.protocolVersion)
        assertEquals("/r", result.repoRoot)
        assertEquals("stateChanged", notes.poll(5, TimeUnit.SECONDS))
        client.close()
    }

    @Test
    fun initialize_sends_the_test_timeout() {
        val pipe = Pipe()
        val client = EngineClient(pipe.clientIn, pipe.clientOut)
        client.request("initialize", InitializeParams("/s.slnx", "dotnet", 2, 300, testTimeoutSeconds = 45), InitializeResult::class.java)
        val sent = JsonParser.parseString(pipe.clientOut.toString(Charsets.UTF_8).substringAfter("\r\n\r\n")).asJsonObject
        assertEquals(45, sent["params"].asJsonObject["testTimeoutSeconds"].asInt)
        client.close()
    }

    @Test
    fun request_fails_on_error_response() {
        val pipe = Pipe()
        val client = EngineClient(pipe.clientIn, pipe.clientOut)
        val future = client.request("runAll", null, JsonObject::class.java)
        val id = JsonParser.parseString(pipe.clientOut.toString(Charsets.UTF_8).substringAfter("\r\n\r\n")).asJsonObject["id"].asInt
        pipe.toClient.write(frame("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"Call initialize first."}}"""))
        pipe.toClient.flush()

        val e = assertThrows<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertTrue(e.cause!!.message!!.contains("Call initialize first."))
        client.close()
    }

    @Test
    fun request_fails_when_stream_closes() {
        val pipe = Pipe()
        val client = EngineClient(pipe.clientIn, pipe.clientOut)
        val future = client.request("runAll", null, JsonObject::class.java)
        pipe.toClient.close()

        assertThrows<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
    }
}
