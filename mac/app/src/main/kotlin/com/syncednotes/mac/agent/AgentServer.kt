package com.syncednotes.mac.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Model Context Protocol server an AI agent works on the shared folder through: Claude
 * Code, Claude Desktop and opencode all speak it, on a subscription, with no API key. It is
 * MCP's Streamable HTTP transport, answered with plain JSON, no event stream.
 *
 * It listens on this Mac's loopback address alone, so nothing on the network can reach it, and
 * only while the notes are unlocked. Every request must carry the token the app made when the
 * folder was shared; a request a web page makes is refused by its Origin, so a site open in a
 * browser cannot reach the notes through it (DNS rebinding).
 */
class AgentServer(
    private val port: Int,
    private val token: String,
    /** Runs one tool, where the notes may be touched: the window's thread, in the app. */
    private val call: (tool: String, arguments: JsonObject) -> AgentNotes.Result,
) : AutoCloseable {
    private var server: HttpServer? = null

    val url: String get() = "http://127.0.0.1:$port/mcp"

    fun start() {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        s.createContext("/mcp") { exchange -> exchange.use { handle(it) } }
        // One request at a time: they are worked through on the window's thread anyway.
        s.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SyncedNotes agent server").apply { isDaemon = true } }
        s.start()
        server = s
    }

    override fun close() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (origin != null && origin != "http://127.0.0.1:$port" && origin != "http://localhost:$port") {
            return reply(exchange, 403, "A web page may not use this server.")
        }
        val given = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        // Compared in constant time, so the token cannot be guessed a character at a time.
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            return reply(exchange, 401, "The token is missing or not this one. Copy it again from the app's Share with AI Agent window.")
        }
        if (exchange.requestMethod != "POST") return reply(exchange, 405, "Send JSON-RPC messages with POST.")
        val body = exchange.requestBody.readNBytes(MAX_REQUEST + 1)
        if (body.size > MAX_REQUEST) return reply(exchange, 413, "The request is too large.")
        val message = runCatching { Json.parseToJsonElement(body.decodeToString()) }.getOrNull()
            ?: return json(exchange, error(JsonNull, -32700, "The request is not JSON."))
        val answers = when (message) {
            is JsonArray -> message.mapNotNull { answer(it) }
            else -> listOfNotNull(answer(message))
        }
        when {
            answers.isEmpty() -> reply(exchange, 202, "")
            message is JsonArray -> json(exchange, JsonArray(answers))
            else -> json(exchange, answers.single())
        }
    }

    /** The response to one JSON-RPC message; none for a notification. */
    internal fun answer(message: JsonElement): JsonElement? {
        val obj = message as? JsonObject ?: return error(JsonNull, -32600, "Not a JSON-RPC message.")
        val id = obj["id"] ?: return null
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", TOOLS) })
            "tools/call" -> result(id, callTool(params))
            else -> error(id, -32601, "Unknown method $method.")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", if (asked in VERSIONS) asked else VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "SyncedNotes")
                put("version", "1.0")
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val (text, failed) = try {
            call(name, arguments).text to false
        } catch (e: AgentNotes.AgentError) {
            e.message.orEmpty() to true
        } catch (e: Exception) {
            "The app could not do that: ${e.message}" to true
        }
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            put("isError", failed)
        }
    }

    private fun result(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun json(exchange: HttpExchange, value: JsonElement) {
        val bytes = value.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun reply(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    companion object {
        /** The versions of MCP this answers in, newest first. */
        val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_REQUEST = 4 * 1024 * 1024

        private const val INSTRUCTIONS =
            "These are the notes of one folder of SyncedNotes, shared with you by its owner; no other note is visible. " +
                "Notes are read and written as Markdown, a line per paragraph: # title, ## heading, ### subheading, lists, " +
                "- [ ] checklists, > quotes, ```c or ```cpp fenced code, --- dividers, **bold**, *italic*, ~~strikethrough~~, `code`. " +
                "A callout, text on a coloured panel, is a quote whose first line names its colour: > [!NOTE] blue, > [!TIP] green, " +
                "> [!WARNING] orange, > [!IMPORTANT] purple, > [!CAUTION] pink, > [!GRAY] gray; each line after it starts with > . " +
                "To change part of a note, prefer replace_in_note: it keeps the formatting Markdown cannot show (colours, " +
                "highlights, underline). Changes are saved at once and sync to the owner's phone."

        private fun tool(name: String, description: String, readOnly: Boolean, destructive: Boolean = false, vararg properties: Triple<String, String, Boolean>) = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    for ((key, about, _) in properties) putJsonObject(key) {
                        put("type", "string")
                        put("description", about)
                    }
                }
                putJsonArray("required") { for ((key, _, required) in properties) if (required) add(JsonPrimitive(key)) }
            }
            putJsonObject("annotations") {
                put("readOnlyHint", readOnly)
                put("destructiveHint", destructive)
                put("openWorldHint", false)
            }
        }

        private val ID = Triple("id", "The note's ID, as list_notes or search_notes gives it.", true)
        private val MARKDOWN = Triple("markdown", "The note's content in Markdown, a line per paragraph.", true)
        private val FOLDER = Triple("folder", "A folder inside the shared one, as list_notes names it (\"Recipes/Cakes\"); leave out for the shared folder itself.", false)

        val TOOLS: JsonArray = buildJsonArray {
            add(tool("list_notes", "Lists the notes in the shared folder and the folders inside it, newest first, with their IDs.", readOnly = true))
            add(tool("search_notes", "Finds the notes in the shared folder whose words start with the ones given.", readOnly = true, properties = arrayOf(Triple("query", "Words to look for.", true))))
            add(tool("read_note", "Reads a note as Markdown.", readOnly = true, properties = arrayOf(ID)))
            add(tool("create_note", "Makes a new note from Markdown. Its first line is its title; start it with # to make it one.", readOnly = false, properties = arrayOf(MARKDOWN, FOLDER)))
            add(
                tool(
                    "replace_in_note",
                    "Replaces one exact stretch of a note's text, keeping all the formatting around it. old_text must appear exactly once, as the note's plain text: without Markdown signs. A line break in new_text starts a new paragraph in the same style.",
                    readOnly = false,
                    properties = arrayOf(ID, Triple("old_text", "The exact text to replace.", true), Triple("new_text", "What to put in its place; empty to delete it.", true)),
                ),
            )
            add(tool("append_to_note", "Adds Markdown at the end of a note, as paragraphs of their own.", readOnly = false, properties = arrayOf(ID, MARKDOWN)))
            add(
                tool(
                    "rewrite_note",
                    "Replaces a whole note with Markdown. Underline, text colours and highlights in it are lost, since Markdown has no way to say them; prefer replace_in_note for changes.",
                    readOnly = false, destructive = true, properties = arrayOf(ID, MARKDOWN),
                ),
            )
            add(tool("create_folder", "Makes a folder inside the shared folder, or inside one of its folders.", readOnly = false, properties = arrayOf(Triple("name", "The new folder's name.", true), Triple("parent", "The folder to make it in, as list_notes names it; leave out for the shared folder itself.", false))))
            add(tool("move_note_to_trash", "Moves a note to the Trash, where the owner can restore it for 30 days.", readOnly = false, destructive = true, properties = arrayOf(ID)))
        }

        /** Runs [tool] with [arguments] on [notes]. */
        fun dispatch(notes: AgentNotes, tool: String, arguments: JsonObject): AgentNotes.Result {
            fun arg(key: String, required: Boolean = true): String? {
                val value = (arguments[key] as? JsonPrimitive)?.contentOrNull
                if (value == null && required) throw AgentNotes.AgentError("$tool needs \"$key\".")
                return value
            }
            return when (tool) {
                "list_notes" -> notes.listNotes()
                "search_notes" -> notes.search(arg("query")!!)
                "read_note" -> notes.read(arg("id")!!)
                "create_note" -> notes.create(arg("markdown")!!, arg("folder", required = false))
                "replace_in_note" -> notes.replace(arg("id")!!, arg("old_text")!!, arg("new_text")!!)
                "append_to_note" -> notes.append(arg("id")!!, arg("markdown")!!)
                "rewrite_note" -> notes.write(arg("id")!!, arg("markdown")!!)
                "create_folder" -> notes.createFolder(arg("name")!!, arg("parent", required = false))
                "move_note_to_trash" -> notes.trash(arg("id")!!)
                else -> throw AgentNotes.AgentError("There is no tool \"$tool\".")
            }
        }
    }
}
