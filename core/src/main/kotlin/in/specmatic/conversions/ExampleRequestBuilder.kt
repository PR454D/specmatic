package `in`.specmatic.conversions

import `in`.specmatic.core.HttpPathPattern
import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.pattern.parsedValue

class ExampleRequestBuilder(
    examplePathParams: Map<String, Map<String, String>>,
    exampleHeaderParams: Map<String, Map<String, String>>,
    exampleQueryParams: Map<String, Map<String, String>>,
    val httpPathPattern: HttpPathPattern,
    private val httpMethod: String,
    val securitySchemes: Map<String, OpenAPISecurityScheme>
) {
    fun examplesWithRequestBodies(exampleBodies: Map<String, String?>): Map<String, List<HttpRequest>> {
        val examplesWithBodies: Map<String, List<HttpRequest>> = exampleBodies.mapValues { (exampleName, bodyValue) ->
            val bodies: List<HttpRequest> = if(exampleName in examplesBasedOnParameters) {
                examplesBasedOnParameters.getValue(exampleName).map { exampleRequest ->
                    exampleRequest.copy(body = parsedValue(bodyValue))
                }
            } else {
                val httpRequest = HttpRequest(
                    method = httpMethod,
                    path = httpPathPattern.path,
                    body = parsedValue(bodyValue)
                )

                val requestsWithSecurityParams = securitySchemes.map { (_, securityScheme) ->
                    securityScheme.addTo(httpRequest)
                }

                requestsWithSecurityParams
            }

            bodies
        }

        val examplesWithoutBodies = (examplesBasedOnParameters.keys - exampleBodies.keys).associate { key ->
            key to examplesBasedOnParameters.getValue(key)
        }

        val allExamples = examplesWithBodies + examplesWithoutBodies

        return allExamples
    }

    private val unionOfParameterKeys =
        (exampleQueryParams.keys + examplePathParams.keys + exampleHeaderParams.keys).distinct()

    val examplesBasedOnParameters: Map<String, List<HttpRequest>> = unionOfParameterKeys.associateWith { exampleName ->
        val queryParams = exampleQueryParams[exampleName] ?: emptyMap()
        val pathParams = examplePathParams[exampleName] ?: emptyMap()
        val headerParams = exampleHeaderParams[exampleName] ?: emptyMap()

        val path = toConcretePath(pathParams, httpPathPattern)

        val httpRequest =
            HttpRequest(method = httpMethod, path = path, queryParametersMap = queryParams, headers = headerParams)

        val requestsWithSecurityParams: List<HttpRequest> = securitySchemes.map { (_, securityScheme) ->
            securityScheme.addTo(httpRequest)
        }

        requestsWithSecurityParams
    }

}

private fun toConcretePath(
    pathParams: Map<String, String>,
    httpPathPattern: HttpPathPattern
): String {
    val path = pathParams.entries.fold(httpPathPattern.toOpenApiPath()) { acc, (key, value) ->
        acc.replace("{$key}", value)
    }
    return path
}
