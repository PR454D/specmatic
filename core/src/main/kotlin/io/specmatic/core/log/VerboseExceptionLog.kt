package io.specmatic.core.log

import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

class VerboseExceptionLog(val e: Throwable, val msg: String?): LogMessage {
    override fun toJSONObject(): JSONObjectValue {
        val data: Map<String, Value> = mapOf(
            "className" to StringValue(e.javaClass.name),
            "cause" to StringValue(exceptionCauseMessage(e)),
            "stackTrace" to StringValue(e.stackTraceToString())
        )

        val message: Map<String, Value> = msg?.let {
            mapOf("message" to StringValue(msg))
        } ?: emptyMap()

        return JSONObjectValue(data.plus(message))
    }

    override fun toLogString(): String {
        val message = when(msg) {
            null -> exceptionCauseMessage(e)
            else -> "${msg}: ${e.localizedMessage ?: e.message ?: e.javaClass.name}"
        }

        return "$message${System.lineSeparator()}${e.stackTraceToString()}"
    }
}