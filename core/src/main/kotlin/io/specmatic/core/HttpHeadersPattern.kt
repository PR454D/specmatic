package io.specmatic.core

import io.ktor.http.*
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.isOptional
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

const val HEADERS_BREADCRUMB = "HEADERS"

data class HttpHeadersPattern(
    val pattern: Map<String, Pattern> = emptyMap(),
    val ancestorHeaders: Map<String, Pattern>? = null,
    val contentType: String? = null
) {
    init {
        val uniqueHeaders = pattern.keys.map { it.lowercase() }.distinct()
        if (uniqueHeaders.size < pattern.size) {
            throw ContractException("Headers are not unique: ${pattern.keys.joinToString(", ")}")
        }
    }

    val headerNames = pattern.keys

    fun isEmpty(): Boolean {
        return pattern.isEmpty()
    }

    fun matches(headers: Map<String, String>, resolver: Resolver): Result {
        val result = headers to resolver to
                ::matchEach otherwise
                ::handleError toResult
                ::returnResult

        return when (result) {
            is Result.Failure -> result.breadCrumb("HEADERS")
            else -> result
        }
    }

    private fun isContentTypeAsPerPattern(
        contentTypePattern: Pattern?,
        resolver: Resolver
    ): Boolean {
        return when(contentTypePattern) {
            null -> true
            else -> {
                contentTypePattern.matches(parsedValue(contentType), resolver).isSuccess()
            }
        }
    }

    fun matchContentType(parameters: Pair<Map<String, String>, Resolver>):  MatchingResult<Pair<Map<String, String>, Resolver>> {

        val (headers, resolver) = parameters

        val contentTypeHeaderValueFromRequest = headers[CONTENT_TYPE]
        val contentTypePattern = pattern[CONTENT_TYPE] ?: pattern["$CONTENT_TYPE?"]

        val isContentTypeNotAsPerPattern = isContentTypeAsPerPattern(contentTypePattern, resolver).not()

        if (contentTypePattern != null && isContentTypeNotAsPerPattern) {
            val contentTypeMatchResult = contentTypePattern.matches(
                parsedValue(contentTypeHeaderValueFromRequest),
                resolver
            )

            if (contentTypeMatchResult is Result.Failure) {
                val matchFailure: Result.Failure =
                    contentTypeMatchResult
                        .withFailureReason(FailureReason.ContentTypeMismatch)
                        .breadCrumb(CONTENT_TYPE)

                return MatchFailure(matchFailure)
            }
        } else if (contentType != null && contentTypeHeaderValueFromRequest != null) {
            val parsedContentType = simplifiedContentType(contentType.lowercase())
            val parsedContentTypeHeaderValue = simplifiedContentType(contentTypeHeaderValueFromRequest.lowercase())

            if (parsedContentType != parsedContentTypeHeaderValue) {
                return MatchFailure(
                    Result.Failure(
                        resolver.mismatchMessages.mismatchMessage(contentType, contentTypeHeaderValueFromRequest),
                        breadCrumb = "Content-Type",
                        failureReason = FailureReason.ContentTypeMismatch
                    )
                )
            }
        }

        return MatchSuccess(parameters)
    }


    private fun matchEach(parameters: Pair<Map<String, String>, Resolver>): MatchingResult<Pair<Map<String, String>, Resolver>> {
        val (headers, resolver) = parameters

        val contentTypeMatchResult = matchContentType(parameters)
        if (contentTypeMatchResult is MatchFailure) return contentTypeMatchResult

        val headersWithRelevantKeys = when {
            ancestorHeaders != null -> withoutIgnorableHeaders(headers, ancestorHeaders)
            else -> withoutContentTypeGeneratedBySpecmatic(headers, pattern)
        }

        val keyErrors: List<KeyError> =
            resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys).findKeyErrorListCaseInsensitive(
                pattern,
                headersWithRelevantKeys.mapValues { StringValue(it.value) }
            )

        keyErrors.find { it.name == "SOAPAction" }?.apply {
            return MatchFailure(
                this.missingKeyToResult("header", resolver.mismatchMessages).breadCrumb("SOAPAction")
                    .copy(failureReason = FailureReason.SOAPActionMismatch)
            )
        }

        val keyErrorResults: List<Result.Failure> = keyErrors.map {
            it.missingKeyToResult("header", resolver.mismatchMessages).breadCrumb(withoutOptionality(it.name))
        }

        val lowercasedHeadersWithRelevantKeys = headersWithRelevantKeys.mapKeys { it.key.lowercase() }

        val results: List<Result?> = this.pattern.mapKeys { it.key }.map { (key, pattern) ->
            val keyWithoutOptionality = withoutOptionality(key)
            val sampleValue = lowercasedHeadersWithRelevantKeys[keyWithoutOptionality.lowercase()]

            when {
                sampleValue != null -> {
                    try {
                        val result = resolver.matchesPattern(
                            keyWithoutOptionality,
                            pattern,
                            attempt(breadCrumb = keyWithoutOptionality) {
                                parseOrString(
                                    pattern,
                                    sampleValue,
                                    resolver
                                )
                            })

                        result.breadCrumb(keyWithoutOptionality).failureReason(highlightIfSOAPActionMismatch(key))
                    } catch (e: ContractException) {
                        e.failure().copy(failureReason = highlightIfSOAPActionMismatch(key))
                    } catch (e: Throwable) {
                        Result.Failure(e.localizedMessage, breadCrumb = keyWithoutOptionality)
                            .copy(failureReason = highlightIfSOAPActionMismatch(key))
                    }
                }

                else ->
                    null
            }
        }

        val failures: List<Result.Failure> = keyErrorResults.plus(results.filterIsInstance<Result.Failure>())

        return if (failures.isNotEmpty())
            MatchFailure(Result.Failure.fromFailures(failures))
        else
            MatchSuccess(parameters)
    }

    private fun simplifiedContentType(contentType: String): String {
        return try {
            ContentType.parse(contentType).let {
                "${it.contentType}/${it.contentSubtype}"
            }
        } catch (e: Throwable) {
            contentType
        }
    }

    private fun highlightIfSOAPActionMismatch(missingKey: String): FailureReason? =
        when (withoutOptionality(missingKey)) {
            "SOAPAction" -> FailureReason.SOAPActionMismatch
            else -> null
        }

    private fun withoutIgnorableHeaders(
        headers: Map<String, String>,
        ancestorHeaders: Map<String, Pattern>
    ): Map<String, String> {
        val ancestorHeadersLowerCase = ancestorHeaders.mapKeys { it.key.lowercase() }

        return headers.filterKeys { key ->
            val headerWithoutOptionality = withoutOptionality(key).lowercase()
            ancestorHeadersLowerCase.containsKey(headerWithoutOptionality) || ancestorHeadersLowerCase.containsKey("$headerWithoutOptionality?")
        }
    }

    private fun withoutContentTypeGeneratedBySpecmatic(
        headers: Map<String, String>,
        pattern: Map<String, Pattern>
    ): Map<String, String> {
        val contentTypeHeader = "Content-Type"
        return when {
            contentTypeHeader in headers && contentTypeHeader !in pattern && "$contentTypeHeader?" !in pattern -> headers.minus(
                contentTypeHeader
            )

            else -> headers
        }
    }

    fun generate(resolver: Resolver): Map<String, String> {
        val generatedHeaders = pattern.mapValues { (key, pattern) ->
            attempt(breadCrumb = "HEADERS.$key") {
                toStringLiteral(resolver.withCyclePrevention(pattern) {
                    it.generate("HEADERS", key, pattern)
                })
            }
        }.map { (key, value) -> withoutOptionality(key) to value }.toMap()

        if(contentType == null)
            return generatedHeaders

        val generatedContentTypeValue = generatedHeaders[CONTENT_TYPE] ?: return generatedHeaders.withMediaType()

        if (!contentTypeHeaderIsConst(generatedContentTypeValue, resolver))
            return generatedHeaders.withMediaType()

        return generatedHeaders
    }

    private fun contentTypeHeaderIsConst(
        generatedContentType: String,
        resolver: Resolver,
    ): Boolean {
        val contentTypePattern: Pattern = pattern[CONTENT_TYPE] ?: pattern["${CONTENT_TYPE}?"] ?: return false
        val regeneratedContentType = contentTypePattern.generate(resolver).toStringLiteral()
        return generatedContentType == regeneratedContentType
    }

    private fun Map<String, String>.withMediaType(
    ): Map<String, String> {
        if (contentType.isNullOrBlank()) return this
        return this.plus(CONTENT_TYPE to contentType)
    }

    private fun toStringLiteral(headerValue: Value) = when (headerValue) {
        is JSONObjectValue -> headerValue.toUnformattedStringLiteral()
        else -> headerValue.toStringLiteral()
    }

    fun generateWithAll(resolver: Resolver): Map<String, String> {
        return attempt(breadCrumb = "HEADERS") {
            pattern.mapValues { (key, pattern) ->
                attempt(breadCrumb = key) {
                    pattern.generateWithAll(resolver).toStringLiteral()
                }
            }
        }.map { (key, value) -> withoutOptionality(key) to value }.toMap()
    }

    fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<HttpHeadersPattern>> {

        val filteredPattern = row.withoutOmittedKeys(pattern, resolver.defaultExampleResolver)

        val additionalHeadersPattern = extractFromExampleHeadersNotInSpec(filteredPattern, row)

        val basedOnExamples = forEachKeyCombinationGivenRowIn(
            filteredPattern + additionalHeadersPattern,
            row,
            resolver
        ) { pattern ->
            newMapBasedOn(pattern, row, resolver)
        }

        val generatedWithoutExamples: Sequence<ReturnValue<Map<String, Pattern>>> = resolver.generation.fillInTheMissingMapPatterns(
            basedOnExamples.map { it.value },
            pattern,
            null,
            row,
            resolver
        )

        return (basedOnExamples + generatedWithoutExamples).map { example ->
            example.update { map ->
                map.mapKeys { withoutOptionality(it.key) }
            }.ifValue {
                HttpHeadersPattern(it, contentType = contentType)
            }
        }
    }

    private fun extractFromExampleHeadersNotInSpec(specPattern : Map<String, Pattern>, row: Row): Map<String, Pattern> {
        val additionalHeadersPattern = if (row.requestExample != null) {
            row.requestExample.headers.keys
                .filter { exampleHeaderName -> !specPattern.containsKey(exampleHeaderName) && !specPattern.containsKey("${exampleHeaderName}?") }
                .filter { exampleHeaderName -> exampleHeaderName.lowercase() !in getHeadersToExcludeNotInExamples(row.requestExample) }
                .filter { exampleHeaderName -> exampleHeaderName !in row.requestExample.metadata.securityHeaderNames }
                .associateWith { exampleHeaderName -> ExactValuePattern(StringValue(row.requestExample.headers.getValue(exampleHeaderName))) }
        } else {
            emptyMap()
        }

        return additionalHeadersPattern
    }

    private fun getHeadersToExcludeNotInExamples(exampleRequest: HttpRequest) = setOf(
        "content-type"
    )


    fun negativeBasedOn(
        row: Row,
        resolver: Resolver
    ): Sequence<ReturnValue<HttpHeadersPattern>> = returnValue(breadCrumb = "HEADER") {

        allOrNothingCombinationIn(pattern, row, null, null) { pattern ->
            NegativeNonStringlyPatterns().negativeBasedOn(pattern, row, resolver)
        }.map { patternMapR ->
            patternMapR.ifValue { patternMap ->
                HttpHeadersPattern(
                    patternMap.mapKeys { withoutOptionality(it.key) },
                    contentType = contentType
                )
            }
        }.plus(patternsWithNoRequiredHeaders(pattern))
    }

    private fun patternsWithNoRequiredHeaders(
        patternMap: Map<String, Pattern>
    ): Sequence<ReturnValue<HttpHeadersPattern>> =
        patternsWithNoRequiredKeys(patternMap, "mandatory header not sent").map {
            it.ifValue { pattern ->
                HttpHeadersPattern(pattern, contentType = contentType)
            }
        }

    fun newBasedOn(resolver: Resolver): Sequence<HttpHeadersPattern> =
        allOrNothingCombinationIn(
            pattern,
            Row(),
            null,
            null, returnValues { pattern: Map<String, Pattern> ->
                newBasedOn(pattern, resolver)
            }).map { it.value }.map { patternMap ->
            HttpHeadersPattern(
                patternMap.mapKeys { withoutOptionality(it.key) },
                contentType = contentType
            )
        }

    fun encompasses(other: HttpHeadersPattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        val myRequiredKeys = pattern.keys.filter { !isOptional(it) }
        val otherRequiredKeys = other.pattern.keys.filter { !isOptional(it) }

        val missingHeaderResult: Result = checkAllMissingHeaders(myRequiredKeys, otherRequiredKeys, thisResolver)

        val otherWithoutOptionality = other.pattern.mapKeys { withoutOptionality(it.key) }
        val thisWithoutOptionality = pattern.filterKeys { withoutOptionality(it) in otherWithoutOptionality }
            .mapKeys { withoutOptionality(it.key) }

        val valueResults: List<Result> =
            thisWithoutOptionality.keys.map { headerName ->
                thisWithoutOptionality.getValue(headerName).encompasses(
                    resolvedHop(otherWithoutOptionality.getValue(headerName), otherResolver),
                    thisResolver,
                    otherResolver
                ).breadCrumb(headerName)
            }

        val results = listOf(missingHeaderResult).plus(valueResults)

        return Result.fromResults(results).breadCrumb("HEADER")
    }

    private fun checkAllMissingHeaders(
        myRequiredKeys: List<String>,
        otherRequiredKeys: List<String>,
        resolver: Resolver
    ): Result {
        val failures = myRequiredKeys.filter { it !in otherRequiredKeys }.map { missingFixedKey ->
            MissingKeyError(missingFixedKey).missingKeyToResult("header", resolver.mismatchMessages)
                .breadCrumb(missingFixedKey)
        }

        return Result.fromFailures(failures)
    }

    fun addComplimentaryPatterns(basePatterns: Sequence<ReturnValue<HttpHeadersPattern>>, row: Row, resolver: Resolver): Sequence<ReturnValue<HttpHeadersPattern>> {
        return addComplimentaryPatterns(
            basePatterns.map { it.ifValue { it.pattern } },
            pattern,
            null,
            row,
            resolver,
        ).map {
            it.ifValue {
                HttpHeadersPattern(it, contentType = contentType)
            }
        }
    }

    fun matches(row: Row, resolver: Resolver): Result {
        return matches(this.pattern, row, resolver, "header")
    }

    fun readFrom(
        row: Row,
        resolver: Resolver,
        generateMandatoryEntryIfMissing: Boolean,
    ): Sequence<ReturnValue<HttpHeadersPattern>> {
        return attempt(breadCrumb = HEADERS_BREADCRUMB) {
            readFrom(this.pattern, row, resolver, generateMandatoryEntryIfMissing)
        }.map {
            HasValue(HttpHeadersPattern(it, contentType = contentType))
        }
    }

    fun fillInTheBlanks(headers: Map<String, String>, resolver: Resolver): ReturnValue<Map<String, String>> {
        val headersWithContentType = if (contentType != null && CONTENT_TYPE !in headers) {
            headers.plus(CONTENT_TYPE to contentType)
        } else headers

        if (headersWithContentType.isEmpty() && pattern.isEmpty()) return HasValue(emptyMap())
        val headersValue = headersWithContentType.mapValues { (key, value) ->
            val pattern = pattern[key] ?: pattern["$key?"] ?: return@mapValues StringValue(value)
            runCatching { pattern.parse(value, resolver) }.getOrDefault(StringValue(value))
        }

        return fill(
            jsonPatternMap = pattern, jsonValueMap = headersValue,
            resolver = resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys),
            typeAlias = "($HEADERS_BREADCRUMB)"
        ).realise(
            hasValue = { valuesMap, _ -> HasValue(valuesMap.mapValues { it.value.toStringLiteral() }) },
            orException = { e -> e.cast() }, orFailure = { f -> f.cast() }
        )
    }

    fun fixValue(headers: Map<String, String>, resolver: Resolver): Map<String, String> {
        val headersWithContentType = if (contentType != null) {
            headers.plus(CONTENT_TYPE to contentType)
        } else headers

        if (headersWithContentType.isEmpty() && pattern.isEmpty()) return emptyMap()
        val headersValue = headersWithContentType.mapValues { (key, value) ->
            val pattern = pattern[key] ?: pattern["$key?"] ?: return@mapValues StringValue(value)

            try { pattern.parse(value, resolver) } catch(e: Exception) { StringValue(value) }
        }
        val fixedHeaders = fix(
            jsonPatternMap = pattern, jsonValueMap = headersValue,
            resolver = resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys).withoutAllPatternsAsMandatory(),
            jsonPattern = JSONObjectPattern(pattern, typeAlias = "($HEADERS_BREADCRUMB)")
        )

        return fixedHeaders.mapValues { it.value.toStringLiteral() }
    }

}

internal fun logContentTypeAndPatternMismatchWarning(contentType: String) {
    logger.log("WARNING: The content type schema specified in the specification does not match the media type $contentType")
}

private fun parseOrString(pattern: Pattern, sampleValue: String, resolver: Resolver) =
    try {
        pattern.parse(sampleValue, resolver)
    } catch (e: Throwable) {
        StringValue(sampleValue)
    }

fun Map<String, String>.withoutDynamicHeaders(): Map<String, String> =
    this.filterKeys { key -> key.lowercase() !in DYNAMIC_HTTP_HEADERS.map { it.lowercase() } }

val DYNAMIC_HTTP_HEADERS = listOf(
    HttpHeaders.Authorization,
    HttpHeaders.UserAgent,
    HttpHeaders.Cookie,
    HttpHeaders.Referrer,
    HttpHeaders.AcceptLanguage,
    HttpHeaders.Host,
    HttpHeaders.IfModifiedSince,
    HttpHeaders.IfNoneMatch,
    HttpHeaders.CacheControl,
    HttpHeaders.ContentLength,
    HttpHeaders.Range,
    HttpHeaders.XForwardedFor,
    HttpHeaders.Date,
    HttpHeaders.Server,
    HttpHeaders.Expires,
    HttpHeaders.LastModified,
    HttpHeaders.ETag,
    HttpHeaders.Vary,
    HttpHeaders.AccessControlAllowCredentials,
    HttpHeaders.AccessControlMaxAge,
    HttpHeaders.AccessControlRequestHeaders,
    HttpHeaders.AccessControlRequestMethod
)