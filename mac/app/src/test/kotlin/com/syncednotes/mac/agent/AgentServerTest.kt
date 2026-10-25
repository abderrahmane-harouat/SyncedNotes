package com.syncednotes.mac.agent

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The MCP server an agent speaks to, on this Mac's loopback address, with a made-up token. */
class AgentServerTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val token = "made-up-token-0000"
    private val calls = ArrayList<Pair<String, JsonObject>>()
    private val server = AgentServer(port, token) { tool, arguments ->
        calls += tool to arguments
        if (tool == "read_note") throw AgentNotes.AgentError("There is no note x in the shared folder.")
        AgentNotes.Result("listed")
    }.also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() = server.close()

    private fun post(body: String, auth: String? = "Bearer $token", origin: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(server.url)).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        if (auth != null) request.header("Authorization", auth)
        if (origin != null) request.header("Origin", origin)
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rpc(id: Int, method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    @Test
    fun anAgentInitializesListsTheToolsAndCallsOne() {
        val init = Json.parseToJsonElement(post(rpc(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")).body()).jsonObject
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
        val tools = Json.parseToJsonElement(post(rpc(2, "tools/list")).body()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "replace_in_note" })
        val called = Json.parseToJsonElement(post(rpc(3, "tools/call", """{"name":"list_notes","arguments":{}}""")).body()).jsonObject
        assertEquals("listed", called["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("list_notes"), calls.map { it.first })
    }

    @Test
    fun aToolThatFailsSaysWhyAsAnErrorResultTheAgentCanRead() {
        val result = Json.parseToJsonElement(post(rpc(4, "tools/call", """{"name":"read_note","arguments":{"id":"x"}}""")).body()).jsonObject["result"]!!.jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        assertTrue("no note" in result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutTheTokenNothingIsAnswered() {
        assertEquals(401, post(rpc(5, "tools/list"), auth = null).statusCode())
        assertEquals(401, post(rpc(6, "tools/list"), auth = "Bearer not-the-token").statusCode())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aWebPageCannotReachTheServerEvenWithTheToken() {
        assertEquals(403, post(rpc(7, "tools/call", """{"name":"list_notes","arguments":{}}"""), origin = "https://example.com").statusCode())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun anOlderProtocolVersionIsAnsweredInThatVersion() {
        val init = Json.parseToJsonElement(post(rpc(8, "initialize", """{"protocolVersion":"2024-11-05"}""")).body()).jsonObject
        assertEquals("2024-11-05", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }
}
