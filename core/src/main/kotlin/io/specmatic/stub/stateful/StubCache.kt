package io.specmatic.stub.stateful

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

const val DEFAULT_CACHE_RESPONSE_ID_KEY = "id"
const val REQUEST_BODY_KEY = "requestBody"
const val RESPONSE_BODY_KEY = "responseBody"
const val STATUS_CODE_KEY = "statusCode"
const val METHOD_KEY = "method"
const val REQUEST_HEADERS_KEY = "requestHeaders"
const val RESPONSE_HEADERS_KEY = "responseHeaders"

data class CachedResponse(
    val path: String,
    val responseBody: JSONObjectValue
)

class StubCache {
    private val cachedResponses = mutableListOf<CachedResponse>()
    private val lock = ReentrantLock()

    fun addResponse(
        path: String,
        responseBody: JSONObjectValue,
        idKey: String,
        idValue: String
    ) = lock.withLock {
        val existingResponse = findResponseFor(path, idKey, idValue)
        if(existingResponse == null) {
            cachedResponses.add(
                CachedResponse(path, responseBody)
            )
        }
    }

    fun addAcceptedResponse(
        path: String,
        finalResponseBody: JSONObjectValue,
        httpResponse: HttpResponse,
        httpRequest: HttpRequest,
        idKey: String,
        idValue: String,
    ) = lock.withLock {
        val requestBody = httpRequest.body as JSONObjectValue
        val existingResponse = findResponseFor(path, idKey, idValue)
        val responseIdKey = finalResponseBody.findFirstChildByPath(DEFAULT_CACHE_RESPONSE_ID_KEY) ?: EmptyString

        val responseToBeCached = JSONObjectValue(
            mapOf(
                DEFAULT_CACHE_RESPONSE_ID_KEY to responseIdKey,
                REQUEST_BODY_KEY to requestBody,
                RESPONSE_BODY_KEY to finalResponseBody,
                STATUS_CODE_KEY to parsedValue(httpResponse.status.toString()),
                METHOD_KEY to parsedValue(httpRequest.method.orEmpty()),
                REQUEST_HEADERS_KEY to parsedValue(asString(httpRequest.headers.toHeadersList())),
                RESPONSE_HEADERS_KEY to parsedValue(asString(httpResponse.headers.toHeadersList())),
            )
        )
        if(existingResponse == null) {
            cachedResponses.add(
                CachedResponse(path, responseToBeCached)
            )
        }
    }

    fun updateResponse(
        path: String,
        responseBody: JSONObjectValue,
        idKey: String,
        idValue: String
    ) = lock.withLock {
        deleteResponse(path, idKey, idValue)
        addResponse(
            path,
            responseBody,
            idKey,
            idValue
        )
    }

    fun findResponseFor(path: String, idKey: String, idValue: String): CachedResponse? = lock.withLock {
        return cachedResponses.filter {
            it.path == path
        }.firstOrNull {
            idValueFor(idKey, it.responseBody) == idValue
        }
    }

    fun findAllResponsesFor(
        path: String,
        attributeSelectionKeys: Set<String>,
        filter: Map<String, String> = emptyMap()
    ): JSONArrayValue = lock.withLock {
        val responseBodies = cachedResponses.filter {
            it.path == path
        }.map{ it.responseBody }.filter {
            it.jsonObject.satisfiesFilter(filter)
        }.map {
            it.removeKeysNotPresentIn(attributeSelectionKeys)
        }
        return JSONArrayValue(responseBodies)
    }

    fun deleteResponse(path: String, idKey: String, idValue: String) {
        val existingResponse = findResponseFor(path, idKey, idValue) ?: return
        lock.withLock {
            cachedResponses.remove(existingResponse)
        }
    }

    private fun Map<String, Value>.satisfiesFilter(filter: Map<String, String>): Boolean {
        if(filter.isEmpty()) return true

        return filter.all { (filterKey, filterValue) ->
            if(this.containsKey(filterKey).not()) return@all true

            val actualValue = this.getValue(filterKey)
            actualValue.toStringLiteral() == filterValue
        }
    }

    private fun Map<String, String>.toHeadersList(): List<Map<String, String>> {
        return entries.map { mapOf("name" to it.key, "value" to it.value) }
    }

    private fun asString(value: Any): String {
        return ObjectMapper().writeValueAsString(value).orEmpty()
    }

    companion object {
        fun idValueFor(idKey: String, body: JSONObjectValue): String {
            return body.findFirstChildByPath(idKey)?.toStringLiteral().orEmpty()
        }
    }
}