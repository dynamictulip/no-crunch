package com.nocrunch.rider.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class EngineRpcException(message: String) : RuntimeException(message)

/**
 * Minimal JSON-RPC 2.0 client with LSP-style `Content-Length` framing.
 * Reads on its own daemon thread; never touches the EDT. Handlers run on the reader thread and must not block.
 */
class EngineClient(input: InputStream, private val output: OutputStream) : AutoCloseable {
    val gson: Gson = GsonBuilder().serializeNulls().create()

    private val input = BufferedInputStream(input)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<CompletableFuture<Any?>, Class<*>>>()
    @Volatile private var notificationHandler: (String, JsonObject) -> Unit = { _, _ -> }
    @Volatile private var closed = false
    private val reader = Thread(::readLoop, "NoCrunch engine reader").apply { isDaemon = true; start() }

    fun onNotification(handler: (method: String, params: JsonObject) -> Unit) {
        notificationHandler = handler
    }

    fun <T> request(method: String, params: Any?, type: Class<T>): CompletableFuture<T> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<Any?>()
        pending[id] = future to type
        try {
            send(message(method, params).apply { addProperty("id", id) })
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        @Suppress("UNCHECKED_CAST")
        return future as CompletableFuture<T>
    }

    fun notify(method: String, params: Any?) = send(message(method, params))

    private fun message(method: String, params: Any?) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("method", method)
        if (params != null) add("params", gson.toJsonTree(params))
    }

    @Synchronized
    private fun send(json: JsonObject) {
        val body = gson.toJson(json).toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val length = readHeaders() ?: break
                val body = input.readNBytes(length)
                if (body.size < length) break
                dispatch(JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject)
            }
        } catch (_: IOException) {
            // stream closed
        } finally {
            failAll(EngineRpcException("Engine connection closed"))
        }
    }

    /** Returns the Content-Length, or null at end of stream. */
    private fun readHeaders(): Int? {
        var length: Int? = null
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) {
                if (length != null) return length else continue
            }
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toInt()
            }
        }
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun dispatch(json: JsonObject) {
        val id = json["id"]
        if (id != null && !id.isJsonNull && (json.has("result") || json.has("error"))) {
            val (future, type) = pending.remove(id.asInt) ?: return
            val error = json["error"]
            if (error != null && !error.isJsonNull) {
                future.completeExceptionally(EngineRpcException(error.asJsonObject["message"]?.asString ?: error.toString()))
            } else {
                val result: JsonElement? = json["result"]
                future.complete(if (result == null || result.isJsonNull) null else gson.fromJson(result, type))
            }
            return
        }
        val method = json["method"]?.asString ?: return
        val params = json["params"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        try {
            notificationHandler(method, params)
        } catch (_: Exception) {
            // a faulty handler must not kill the reader
        }
    }

    private fun failAll(e: Exception) {
        pending.values.forEach { it.first.completeExceptionally(e) }
        pending.clear()
    }

    override fun close() {
        closed = true
        try { output.close() } catch (_: IOException) {}
        try { input.close() } catch (_: IOException) {}
        failAll(EngineRpcException("Engine client closed"))
    }
}
