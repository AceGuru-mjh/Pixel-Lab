package com.pixellab.mcp

import com.pixellab.mcp.json.Json
import com.pixellab.mcp.json.JsonElement
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonobj

/**
 * JSON-RPC 2.0 message helpers for pixel-mcp.
 *
 * Pure functions over the hand-rolled [JsonObject] tree: they build request,
 * response and error envelopes exactly as specified by the JSON-RPC 2.0
 * specification, including the standard error codes below.
 */
object JsonRpc {

    /** Invalid JSON was received (JSON-RPC 2.0 parse error). */
    const val PARSE_ERROR: Int = -32700

    /** The JSON sent is not a valid request object. */
    const val INVALID_REQUEST: Int = -32600

    /** The method does not exist / is not available. */
    const val METHOD_NOT_FOUND: Int = -32601

    /** Invalid method parameter(s). */
    const val INVALID_PARAMS: Int = -32602

    /** Internal error while processing the request. */
    const val INTERNAL_ERROR: Int = -32603

    /** Builds a success response envelope around [result]. */
    fun response(id: JsonElement?, result: JsonObject): JsonObject =
        jsonobj {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        }

    /** Builds an error response envelope; [id] may be null (errors raised before the id is known). */
    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject =
        jsonobj {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put(
                "error",
                jsonobj {
                    put("code", code)
                    put("message", message)
                    if (data != null) put("data", data)
                },
            )
        }

    /** Builds a request envelope; a null [id] yields a notification. */
    fun request(method: String, params: JsonObject?, id: JsonElement? = null): JsonObject =
        jsonobj {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    /** [response] serialized to JSON text. */
    fun buildResponse(id: JsonElement?, result: JsonObject): String = Json.write(response(id, result))

    /** [error] serialized to JSON text. */
    fun buildError(id: JsonElement?, code: Int, message: String): String = Json.write(error(id, code, message))
}
