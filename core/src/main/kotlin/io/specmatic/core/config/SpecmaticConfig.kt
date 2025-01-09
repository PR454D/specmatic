package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.Configuration.Companion.configFilePath
import io.specmatic.core.Feature
import io.specmatic.core.config.v1.*
import io.specmatic.core.log.logger
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.Value
import java.io.File

const val APPLICATION_NAME = "Specmatic"
const val APPLICATION_NAME_LOWER_CASE = "specmatic"
const val CONFIG_FILE_NAME_WITHOUT_EXT = "specmatic"
const val DEFAULT_TIMEOUT_IN_MILLISECONDS: Long = 6000L
const val CONTRACT_EXTENSION = "spec"
const val YAML = "yaml"
const val WSDL = "wsdl"
const val YML = "yml"
const val JSON = "json"
val CONFIG_EXTENSIONS = listOf(YAML, YML, JSON)
val OPENAPI_FILE_EXTENSIONS = listOf(YAML, YML, JSON)
val CONTRACT_EXTENSIONS = listOf(CONTRACT_EXTENSION, WSDL) + OPENAPI_FILE_EXTENSIONS
const val DATA_DIR_SUFFIX = "_data"
const val TEST_DIR_SUFFIX = "_tests"
const val EXAMPLES_DIR_SUFFIX = "_examples"
const val DICTIONARY_FILE_SUFFIX = "_dictionary.json"
const val SPECMATIC_GITHUB_ISSUES = "https://github.com/znsio/specmatic/issues"
const val DEFAULT_WORKING_DIRECTORY = ".$APPLICATION_NAME_LOWER_CASE"

const val SPECMATIC_STUB_DICTIONARY = "SPECMATIC_STUB_DICTIONARY"

const val MISSING_CONFIG_FILE_MESSAGE = "Config file does not exist. (Could not find file ./specmatic.json OR ./specmatic.yaml OR ./specmatic.yml)"

fun invalidContractExtensionMessage(filename: String): String {
    return "The file $filename does not seem like a contract file. Valid extensions for contract files are ${CONTRACT_EXTENSIONS.joinToString(", ")}"
}

fun String.isContractFile(): Boolean {
    return File(this).extension in CONTRACT_EXTENSIONS
}

fun String.loadContract(): Feature {
    if(!this.isContractFile())
        throw ContractException(invalidContractExtensionMessage(this))

    return parseContractFileToFeature(File(this))
}

data class SpecmaticConfig(
    @field:JsonAlias("contract_repositories")
    val sources: List<Source> = emptyList(),
    val auth: Auth? = null,
    val pipeline: Pipeline? = null,
    val environments: Map<String, Environment>? = null,
    val hooks: Map<String, String> = emptyMap(),
    val repository: RepositoryInfo? = null,
    val report: ReportConfiguration? = null,
    val security: SecurityConfiguration? = null,
    val test: TestConfiguration? = TestConfiguration(),
    val stub: StubConfiguration = StubConfiguration(),
    @field:JsonAlias("virtual_service")
    val virtualService: VirtualServiceConfiguration = VirtualServiceConfiguration(),
    val examples: List<String> = getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList(),
    val workflow: WorkflowConfiguration? = null,
    val ignoreInlineExamples: Boolean = getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES),
    val additionalExampleParamsFilePath: String? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE),
    @field:JsonAlias("attribute_selection_pattern")
    val attributeSelectionPattern: AttributeSelectionPattern = AttributeSelectionPattern(),
    @field:JsonAlias("all_patterns_mandatory")
    val allPatternsMandatory: Boolean = getBooleanValue(Flags.ALL_PATTERNS_MANDATORY),
    @field:JsonAlias("default_pattern_values")
    val defaultPatternValues: Map<String, Any> = emptyMap(),
    val version: Int? = null
) {
    @JsonIgnore
    fun attributeSelectionQueryParamKey(): String {
        return attributeSelectionPattern.queryParamKey
    }

    @JsonIgnore
    fun isExtensibleSchemaEnabled(): Boolean {
        return (test?.allowExtensibleSchema == true)
    }
    @JsonIgnore
    fun isResiliencyTestingEnabled(): Boolean {
        return (test?.resiliencyTests?.enable != ResiliencyTestSuite.none)
    }
    @JsonIgnore
    fun isOnlyPositiveTestingEnabled(): Boolean {
        return (test?.resiliencyTests?.enable == ResiliencyTestSuite.positiveOnly)
    }
    @JsonIgnore
    fun isResponseValueValidationEnabled(): Boolean {
        return (test?.validateResponseValues == true)
    }
    @JsonIgnore
    fun parsedDefaultPatternValues(): Map<String, Value> {
        return parsedJSONObject(ObjectMapper().writeValueAsString(defaultPatternValues)).jsonObject
    }
}

fun loadSpecmaticConfigOrDefault(configFileName: String? = null): SpecmaticConfig {
    return if(configFileName == null)
        SpecmaticConfig()
    else try {
        loadSpecmaticConfig(configFileName)
    }
    catch (e: ContractException) {
        logger.log(exceptionCauseMessage(e))
        SpecmaticConfig()
    }
}

fun loadSpecmaticConfig(configFileName: String? = null): SpecmaticConfig {
    val configFile = File(configFileName ?: configFilePath)
    if (!configFile.exists()) {
        throw ContractException("Could not find the Specmatic configuration at path ${configFile.canonicalPath}")
    }
    try {
        return SpecmaticConfigMapper().read(configFile)
    } catch(e: LinkageError) {
        logger.log(e, "A dependency version conflict has been detected. If you are using Spring in a maven project, a common resolution is to set the property <kotlin.version></kotlin.version> to your pom project.")
        throw e
    } catch (e: Throwable) {
        logger.log(e, "Your configuration file may have some missing configuration sections. Please ensure that the ${configFile.path} file adheres to the schema described at: https://specmatic.io/documentation/specmatic_json.html")
        throw Exception("Your configuration file may have some missing configuration sections. Please ensure that the ${configFile.path} file adheres to the schema described at: https://specmatic.io/documentation/specmatic_json.html", e)
    }
}