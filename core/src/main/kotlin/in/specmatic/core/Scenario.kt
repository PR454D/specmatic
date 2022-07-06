package `in`.specmatic.core

import `in`.specmatic.core.pattern.*
import `in`.specmatic.core.utilities.capitalizeFirstChar
import `in`.specmatic.core.utilities.exceptionCauseMessage
import `in`.specmatic.core.utilities.mapZip
import `in`.specmatic.core.value.KafkaMessage
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.True
import `in`.specmatic.core.value.Value
import `in`.specmatic.test.ContractTest
import `in`.specmatic.test.ScenarioTest
import `in`.specmatic.test.ScenarioTestGenerationFailure
import `in`.specmatic.test.TestExecutor

object ContractAndStubMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Contract expected $expected but stub contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the stub was not in the contract"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the contract was not found in the stub"
    }
}

data class Scenario(
    val name: String,
    val httpRequestPattern: HttpRequestPattern,
    val httpResponsePattern: HttpResponsePattern,
    val expectedFacts: Map<String, Value>,
    val examples: List<Examples>,
    val patterns: Map<String, Pattern>,
    val fixtures: Map<String, Value>,
    val kafkaMessagePattern: KafkaMessagePattern? = null,
    val ignoreFailure: Boolean = false,
    val references: Map<String, References> = emptyMap(),
    val bindings: Map<String, String> = emptyMap(),
    val isNegative: Boolean = false
) {
    constructor(scenarioInfo: ScenarioInfo) : this(
        scenarioInfo.scenarioName,
        scenarioInfo.httpRequestPattern,
        scenarioInfo.httpResponsePattern,
        scenarioInfo.expectedServerState,
        scenarioInfo.examples,
        scenarioInfo.patterns,
        scenarioInfo.fixtures,
        scenarioInfo.kafkaMessage,
        scenarioInfo.ignoreFailure,
        scenarioInfo.references,
        scenarioInfo.bindings
    )

    private fun serverStateMatches(actualState: Map<String, Value>, resolver: Resolver) =
        expectedFacts.keys == actualState.keys &&
                mapZip(expectedFacts, actualState).all { (key, expectedStateValue, actualStateValue) ->
                    when {
                        actualStateValue == True || expectedStateValue == True -> true
                        expectedStateValue is StringValue && expectedStateValue.isPatternToken() -> {
                            val pattern = resolver.getPattern(expectedStateValue.string)
                            try {
                                resolver.matchesPattern(
                                    key,
                                    pattern,
                                    pattern.parse(actualStateValue.toString(), resolver)
                                ).isTrue()
                            } catch (e: Exception) {
                                false
                            }
                        }
                        else -> expectedStateValue.toStringLiteral() == actualStateValue.toStringLiteral()
                    }
                }

    fun matches(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): Result {
        val resolver = Resolver(serverState, false, patterns).copy(mismatchMessages = mismatchMessages)
        return matches(httpRequest, serverState, resolver, resolver)
    }

    fun matchesStub(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): Result {
        val headersResolver = Resolver(serverState, false, patterns).copy(mismatchMessages = mismatchMessages)
        val nonHeadersResolver = headersResolver.disableOverrideUnexpectedKeycheck()

        return matches(httpRequest, serverState, nonHeadersResolver, headersResolver)
    }

    private fun matches(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        resolver: Resolver,
        headersResolver: Resolver
    ): Result {
        if (!serverStateMatches(serverState, resolver)) {
            return Result.Failure("Facts mismatch", breadCrumb = "FACTS").also { it.updateScenario(this) }
        }
        return httpRequestPattern.matches(httpRequest, resolver, headersResolver).also {
            it.updateScenario(this)
        }
    }

    fun generateHttpResponse(actualFacts: Map<String, Value>): HttpResponse =
        scenarioBreadCrumb(this) {
            Resolver(emptyMap(), false, patterns)
            val resolver = Resolver(actualFacts, false, patterns)
            val facts = combineFacts(expectedFacts, actualFacts, resolver)

            httpResponsePattern.generateResponse(resolver.copy(factStore = CheckFacts(facts)))
        }

    private fun combineFacts(
        expected: Map<String, Value>,
        actual: Map<String, Value>,
        resolver: Resolver
    ): Map<String, Value> {
        val combinedServerState = HashMap<String, Value>()

        for (key in expected.keys + actual.keys) {
            val expectedValue = expected.getValue(key)
            val actualValue = actual.getValue(key)

            when {
                key in expected && key in actual -> {
                    when {
                        expectedValue == actualValue -> combinedServerState[key] = actualValue
                        expectedValue is StringValue && expectedValue.isPatternToken() -> {
                            ifMatches(key, expectedValue, actualValue, resolver) {
                                combinedServerState[key] = actualValue
                            }
                        }
                    }
                }
                key in expected -> combinedServerState[key] = expectedValue
                key in actual -> combinedServerState[key] = actualValue
            }
        }

        return combinedServerState
    }

    private fun ifMatches(
        key: String,
        expectedValue: StringValue,
        actualValue: Value,
        resolver: Resolver,
        code: () -> Unit
    ) {
        val expectedPattern = resolver.getPattern(expectedValue.string)

        try {
            if (resolver.matchesPattern(key, expectedPattern, expectedPattern.parse(actualValue.toString(), resolver))
                    .isTrue()
            )
                code()
        } catch (e: Throwable) {
            throw ContractException("Couldn't match state values. Expected $expectedValue in key $key, actual value is $actualValue")
        }
    }

    fun generateHttpRequest(): HttpRequest =
        scenarioBreadCrumb(this) { httpRequestPattern.generate(Resolver(expectedFacts, false, patterns)) }

    fun matches(httpResponse: HttpResponse, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Result {
        val resolver = Resolver(expectedFacts, false, patterns).copy(mismatchMessages = mismatchMessages)

        if (this.isNegative) {
            return if (is4xxResponse(httpResponse))
                Result.Success().updateScenario(this)
            else
                Result.Failure("Expected 4xx status, but received ${httpResponse.status}", breadCrumb = "RESPONSE.STATUS").updateScenario(this)
        }

        try {
            return httpResponsePattern.matches(httpResponse, resolver).updateScenario(this)
        } catch (exception: Throwable) {
            return Result.Failure("Exception: ${exception.message}")
        }
    }

    private fun is4xxResponse(httpResponse: HttpResponse) = (400..499).contains(httpResponse.status)

    object ContractAndRowValueMismatch : MismatchMessages {
        override fun mismatchMessage(expected: String, actual: String): String {
            return "Contract expected $expected but found value $actual"
        }

        override fun unexpectedKey(keyLabel: String, keyName: String): String {
            return "${
                keyLabel.lowercase().capitalizeFirstChar()
            } named $keyName in the row value was not in the contract"
        }

        override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
            return "${
                keyLabel.lowercase().capitalizeFirstChar()
            } named $keyName in the contract was not found in the row value"
        }
    }

    private fun newBasedOn(row: Row): List<Scenario> {
        val resolver = Resolver(expectedFacts, false, patterns).copy(mismatchMessages = ContractAndRowValueMismatch)

        val newExpectedServerState = newExpectedServerStateBasedOn(row, expectedFacts, fixtures, resolver)

        return when (kafkaMessagePattern) {
            null -> scenarioBreadCrumb(this) {
                attempt {
                    when (isNegative) {
                        false -> httpRequestPattern.newBasedOn(row, resolver)
                        else -> httpRequestPattern.negativeBasedOn(row, resolver.copy(isNegative = true))
                    }.map { newHttpRequestPattern ->
                        Scenario(
                            name,
                            newHttpRequestPattern,
                            httpResponsePattern,
                            newExpectedServerState,
                            examples,
                            patterns,
                            fixtures,
                            kafkaMessagePattern,
                            ignoreFailure,
                            references,
                            bindings,
                            isNegative
                        )
                    }
                }
            }
            else -> {
                kafkaMessagePattern.newBasedOn(row, resolver).map { newKafkaMessagePattern ->
                    Scenario(
                        name,
                        httpRequestPattern,
                        httpResponsePattern,
                        newExpectedServerState,
                        examples,
                        patterns,
                        fixtures,
                        newKafkaMessagePattern,
                        ignoreFailure,
                        references,
                        bindings,
                        isNegative
                    )
                }
            }
        }
    }

    private fun newBasedOnBackwarCompatibility(row: Row): List<Scenario> {
        val resolver = Resolver(expectedFacts, false, patterns)

        val newExpectedServerState = newExpectedServerStateBasedOn(row, expectedFacts, fixtures, resolver)

        return when (kafkaMessagePattern) {
            null -> httpRequestPattern.newBasedOn(resolver).map { newHttpRequestPattern ->
                Scenario(
                    name,
                    newHttpRequestPattern,
                    httpResponsePattern,
                    newExpectedServerState,
                    examples,
                    patterns,
                    fixtures,
                    kafkaMessagePattern,
                    ignoreFailure,
                    references,
                    bindings
                )
            }
            else -> {
                kafkaMessagePattern.newBasedOn(row, resolver).map { newKafkaMessagePattern ->
                    Scenario(
                        name,
                        httpRequestPattern,
                        httpResponsePattern,
                        newExpectedServerState,
                        examples,
                        patterns,
                        fixtures,
                        newKafkaMessagePattern,
                        ignoreFailure,
                        references,
                        bindings
                    )
                }
            }
        }
    }

    fun generateTestScenarios(
        variables: Map<String, String> = emptyMap(),
        testBaseURLs: Map<String, String> = emptyMap()
    ): List<Scenario> {
        val referencesWithBaseURLs = references.mapValues { (_, reference) ->
            reference.copy(variables = variables, baseURLs = testBaseURLs)
        }

        return scenarioBreadCrumb(this) {
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap {
                    it.rows.map { row ->
                        row.copy(variables = variables, references = referencesWithBaseURLs)
                    }
                }
            }.flatMap { row ->
                newBasedOn(row)
            }
        }
    }

    fun generateContractTests(
        variables: Map<String, String> = emptyMap(),
        testBaseURLs: Map<String, String> = emptyMap()
    ): List<ContractTest> {
        val referencesWithBaseURLs = references.mapValues { (_, reference) ->
            reference.copy(variables = variables, baseURLs = testBaseURLs)
        }

        return scenarioBreadCrumb(this) {
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap {
                    it.rows.map { row ->
                        row.copy(variables = variables, references = referencesWithBaseURLs)
                    }
                }
            }.flatMap { row ->
                try {
                    newBasedOn(row).map { ScenarioTest(it) }
                } catch (e: Throwable) {
                    listOf(ScenarioTestGenerationFailure(this, e))
                }
            }
        }
    }

    fun generateBackwardCompatibilityScenarios(
        variables: Map<String, String> = emptyMap(),
        testBaseURLs: Map<String, String> = emptyMap()
    ): List<Scenario> {
        val referencesWithBaseURLs = references.mapValues { (_, reference) ->
            reference.copy(variables = variables, baseURLs = testBaseURLs)
        }

        return scenarioBreadCrumb(this) {
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap {
                    it.rows.map { row ->
                        row.copy(variables = variables, references = referencesWithBaseURLs)
                    }
                }
            }.flatMap { row ->
                newBasedOnBackwarCompatibility(row)
            }
        }
    }

    val resolver: Resolver = Resolver(newPatterns = patterns)

    val serverState: Map<String, Value>
        get() = expectedFacts

    fun matchesMock(
        request: HttpRequest,
        response: HttpResponse,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): Result {
        return scenarioBreadCrumb(this) {
            val resolver = Resolver(
                IgnoreFacts(),
                true,
                patterns,
                findKeyErrorCheck = DefaultKeyCheck.disableOverrideUnexpectedKeycheck(),
                mismatchMessages = mismatchMessages
            )

            val requestMatchResult = attempt(breadCrumb = "REQUEST") { httpRequestPattern.matches(request, resolver) }

            if (requestMatchResult is Result.Failure)
                requestMatchResult.updateScenario(this)

            if (requestMatchResult is Result.Failure && response.status != httpResponsePattern.status)
                return Result.Failure(
                    cause = requestMatchResult,
                    failureReason = FailureReason.RequestMismatchButStatusAlsoWrong
                )

            val responseMatchResult =
                attempt(breadCrumb = "RESPONSE") { httpResponsePattern.matchesMock(response, resolver) }

            if (requestMatchResult is Result.Failure)
                responseMatchResult.updateScenario(this)

            val failures = listOf(requestMatchResult, responseMatchResult).filterIsInstance<Result.Failure>()

            return if (failures.isEmpty())
                Result.Success()
            else
                Result.Failure.fromFailures(failures)
        }
    }

    fun matchesMock(kafkaMessage: KafkaMessage): Result {
        return kafkaMessagePattern?.matches(kafkaMessage, resolver)
            ?: Result.Failure("This scenario does not have a Kafka mock")
    }

    fun resolverAndResponseFrom(response: HttpResponse): Pair<Resolver, HttpResponse> =
        scenarioBreadCrumb(this) {
            attempt(breadCrumb = "RESPONSE") {
                val resolver = Resolver(expectedFacts, false, patterns)
                Pair(resolver, HttpResponsePattern(response).generateResponse(resolver))
            }
        }

    fun testDescription(): String {
        val scenarioDescription = StringBuilder()
        scenarioDescription.append("Scenario: ")
        when {
            name.isNotEmpty() -> scenarioDescription.append("$name ")
        }

        return if (kafkaMessagePattern != null)
            scenarioDescription.append(kafkaMessagePattern.topic).toString()
        else
            scenarioDescription.append(httpRequestPattern.testDescription()).toString()
    }

    fun newBasedOn(scenario: Scenario): Scenario =
        Scenario(
            this.name,
            this.httpRequestPattern,
            this.httpResponsePattern,
            this.expectedFacts,
            scenario.examples,
            this.patterns,
            this.fixtures,
            this.kafkaMessagePattern,
            this.ignoreFailure,
            scenario.references,
            bindings,
            isNegative
        )

    fun newBasedOn(suggestions: List<Scenario>) =
        this.newBasedOn(suggestions.find { it.name == this.name } ?: this)

    fun isA2xxScenario(): Boolean = this.httpResponsePattern.status in 200..299
    fun negativeBasedOn(suggestions: List<Scenario>) = Scenario(
        "-ve: ${this.name}",
        this.httpRequestPattern,
        this.httpResponsePattern,
        this.expectedFacts,
        this.examples,
        this.patterns,
        this.fixtures,
        this.kafkaMessagePattern,
        this.ignoreFailure,
        this.references,
        bindings,
        isNegative = true
    )
}

fun newExpectedServerStateBasedOn(
    row: Row,
    expectedServerState: Map<String, Value>,
    fixtures: Map<String, Value>,
    resolver: Resolver
): Map<String, Value> =
    attempt(errorMessage = "Scenario fact generation failed") {
        expectedServerState.mapValues { (key, value) ->
            when {
                row.containsField(key) -> {
                    val fieldValue = row.getField(key)

                    when {
                        fixtures.containsKey(fieldValue) -> fixtures.getValue(fieldValue)
                        isPatternToken(fieldValue) -> resolver.getPattern(fieldValue).generate(resolver)
                        else -> StringValue(fieldValue)
                    }
                }
                value is StringValue && isPatternToken(value) -> resolver.getPattern(value.string).generate(resolver)
                else -> value
            }
        }
    }

object ContractAndResponseMismatch : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Contract expected $expected but response contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the response was not in the contract"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${
            keyLabel.lowercase().capitalizeFirstChar()
        } named $keyName in the contract was not found in the response"
    }
}

fun executeTest(testScenario: Scenario, testExecutor: TestExecutor): Result {
    val request = testScenario.generateHttpRequest()

    return try {
        testExecutor.setServerState(testScenario.serverState)

        val response = testExecutor.execute(request)

        val result = when (response.headers.getOrDefault(SPECMATIC_RESULT_HEADER, "success")) {
            "failure" -> Result.Failure(response.body.toStringLiteral()).updateScenario(testScenario)
            else -> testScenario.matches(response, ContractAndResponseMismatch)
        }

        result.withBindings(testScenario.bindings, response)
    } catch (exception: Throwable) {
        Result.Failure(exceptionCauseMessage(exception))
            .also { failure -> failure.updateScenario(testScenario) }
    }
}
