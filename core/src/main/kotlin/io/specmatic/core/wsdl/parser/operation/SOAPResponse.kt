package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.wsdl.payload.SOAPPayload

data class SOAPResponse(val responsePayload: SOAPPayload) {
    fun statements(): List<String> {
        val statusStatement = listOf("Then status 200")
        val responseBodyStatement = responsePayload.specmaticStatement()
        return statusStatement.plus(responseBodyStatement)
    }
}