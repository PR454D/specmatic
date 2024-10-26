package application

import io.specmatic.core.Feature
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.SPECMATIC_STUB_DICTIONARY
import io.specmatic.core.examples.server.ExamplesInteractiveServer
import io.specmatic.core.examples.server.ExamplesInteractiveServer.Companion.validateSingleExample
import io.specmatic.core.examples.server.implicitExternalExampleDirFrom
import io.specmatic.core.examples.server.loadExternalExamples
import io.specmatic.core.log.*
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.*
import io.specmatic.mock.ScenarioStub
import picocli.CommandLine.*
import java.io.File
import java.lang.Thread.sleep
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(
    name = "examples",
    mixinStandardHelpOptions = true,
    description = ["Generate externalised JSON example files with API requests and responses"],
    subcommands = [ExamplesCommand.Validate::class, ExamplesCommand.Interactive::class]
)
class ExamplesCommand : Callable<Int> {
    @Option(
        names = ["--filter-name"],
        description = ["Use only APIs with this value in their name"],
        defaultValue = "\${env:SPECMATIC_FILTER_NAME}"
    )
    var filterName: String = ""

    @Option(
        names = ["--filter-not-name"],
        description = ["Use only APIs which do not have this value in their name"],
        defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}"
    )
    var filterNotName: String = ""

    @Option(
        names = ["--extensive"],
        description = ["Generate all examples (by default, generates one example per 2xx API)"],
        defaultValue = "false"
    )
    var extensive: Boolean = false

    @Parameters(index = "0", description = ["Contract file path"], arity = "0..1")
    var contractFile: File? = null

    @Option(names = ["--debug"], description = ["Debug logs"])
    var verbose = false

    @Option(names = ["--dictionary"], description = ["External Dictionary File Path, defaults to dictionary.json"])
    var dictionaryFile: File? = null

    @Option(
        names= ["--filter"],
        description = [
            """
Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)
- `HEADERS`: Request headers (e.g., Accept, X-Request-ID)
- `QUERY-PARAM`: Query parameters (e.g., status, productId)
- `EXAMPLE-NAME`: Example name (e.g., create-product, active-status)

To specify multiple values for the same filter, separate them with commas. 
For example, to filter by HTTP methods: 
--filter="METHOD=GET,POST"

You can supply multiple filters as well. 
For example:
--filter="METHOD=GET,POST" --filter="PATH=/users"
           """
        ],
        required = false
    )
    var filter: List<String> = emptyList()

    @Option(
        names= ["--filter-not"],
        description = [
            """
Filter tests not matching the specified criteria

This option supports the same filtering keys and syntax as the --filter option.
For example:
--filterNot="STATUS=400" --filterNot="METHOD=PATCH,PUT"
           """
        ],
        required = false
    )
    var filterNot: List<String> = emptyList()

    override fun call(): Int {
        if (contractFile == null) {
            println("No contract file provided. Use a subcommand or provide a contract file. Use --help for more details.")
            return 1
        }
        if (!contractFile!!.exists()) {
            logger.log("Could not find file ${contractFile!!.path}")
            return 1
        }

        configureLogger(this.verbose)

        try {
            dictionaryFile?.also {
                System.setProperty(SPECMATIC_STUB_DICTIONARY, it.path)
            }

            ExamplesInteractiveServer.generate(
                contractFile!!,
                ExamplesInteractiveServer.ScenarioFilter(filterName, filterNotName, filter, filterNot),
                extensive,
            )
        } catch (e: Throwable) {
            logger.log(e)
            return 1
        }

        return 0
    }

    @Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = ["Validate the examples"]
    )
    class Validate : Callable<Int> {
        @Option(
            names= ["--filter"],
            description = [
                """
Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)
- `HEADERS`: Request headers (e.g., Accept, X-Request-ID)
- `QUERY-PARAM`: Query parameters (e.g., status, productId)
- `EXAMPLE-NAME`: Example name (e.g., create-product, active-status)

To specify multiple values for the same filter, separate them with commas. 
For example, to filter by HTTP methods: 
--filter="METHOD=GET,POST"

You can supply multiple filters as well. 
For example:
--filter="METHOD=GET,POST" --filter="PATH=/users"
           """
            ],
            required = false
        )
        var filter: List<String> = emptyList()

        @Option(
            names= ["--filter-not"],
            description = [
                """
Filter tests not matching the specified criteria

This option supports the same filtering keys and syntax as the --filter option.
For example:
--filterNot="STATUS=400" --filterNot="METHOD=PATCH,PUT"
           """
            ],
            required = false
        )
        var filterNot: List<String> = emptyList()

        @Option(names = ["--contract-file", "--spec-file"], description = ["Contract file path"], required = false)
        var contractFile: File? = null

        @Option(names = ["--example-file"], description = ["Example file path"], required = false)
        val exampleFile: File? = null

        @Option(names = ["--example-dir"], description = ["Examples directory path associated to a single spec"], required = false)
        val exampleDir: File? = null

        @Option(names = ["--specs-dir"], description = ["Specs directory path"], required = false)
        val specsDir: File? = null

        @Option(
            names = ["--examples-dir"],
            description = ["Examples directory path containing multiple example directories associated to multiple specs"],
            required = false
        )
        val examplesDir: File? = null

        @Option(names = ["--debug"], description = ["Debug logs"])
        var verbose = false

        @Option(
            names = ["--filter-name"],
            description = ["Validate examples of only APIs with this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NAME}"
        )
        var filterName: String = ""

        @Option(
            names = ["--filter-not-name"],
            description = ["Validate examples of only APIs which do not have this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}"
        )
        var filterNotName: String = ""

        override fun call(): Int {
            if(contractFile != null && exampleFile != null) return validateExampleFile(contractFile!!, exampleFile)

            if (contractFile != null && exampleDir != null) {
                val (exitCode, validationResults) = validateExamplesDir(contractFile!!, exampleDir)

                printValidationResult(validationResults, "Example directory")
                if (exitCode == 1) return 1
                if (validationResults.containsFailure()) return 1
                return 0
            }

            if(contractFile != null) return validateImplicitExamplesFrom(contractFile!!)

            if(specsDir != null && examplesDir != null) {
                val exitCode = validateAllExamplesAssociatedToEachSpecIn(specsDir, examplesDir)
                return exitCode
            }

            logger.log("No valid options provided.")
            return 1
        }

        private fun validateExampleFile(contractFile: File, exampleFile: File): Int {
            if (!contractFile.exists()) {
                logger.log("Could not find file ${contractFile.path}")
                return 1
            }

            configureLogger(this.verbose)

            try {
                validateSingleExample(contractFile, exampleFile).throwOnFailure()

                logger.log("The provided example ${exampleFile.name} is valid.")
                return 0
            } catch (e: ContractException) {
                logger.log("The provided example ${exampleFile.name} is invalid. Reason:\n")
                logger.log(exceptionCauseMessage(e))
                return 1
            }
        }

        private fun validateExamplesDir(contractFile: File, examplesDir: File, enableLogging: Boolean = true): Pair<Int, Map<String, Result>> {
            val feature = parseContractFileToFeature(contractFile)
            val (externalExampleDir, externalExamples) = loadExternalExamples(examplesDir = examplesDir)
            if (!externalExampleDir.exists()) {
                logger.log("$externalExampleDir does not exist, did not find any files to validate")
                return 1 to emptyMap()
            }
            if (externalExamples.none()) {
                logger.log("No example files found in $externalExampleDir")
                return 1 to emptyMap()
            }
            return 0 to validateExternalExamples(feature, externalExamples, enableLogging)
        }

        private fun validateAllExamplesAssociatedToEachSpecIn(
            specsDir: File,
            examplesDir: File
        ): Int {
            val validationResults = specsDir.walk().filter { it.isFile }.flatMapIndexed { index, it ->
                val associatedExamplesDir = examplesDir.getAssociatedExampleDirFor(it) ?: return@flatMapIndexed emptyList()

                logger.log("${index.inc()}. Validating examples in ${associatedExamplesDir.name} associated to ${it.name}...${System.lineSeparator()}")
                val results = validateExamplesDir(it, associatedExamplesDir, false).second.entries.map { entry ->
                    entry.toPair()
                }

                printValidationResult(results.toMap(), "The ${associatedExamplesDir.name} Directory")
                logger.log(System.lineSeparator())
                results
            }.toMap()
            logger.log("Summary:")
            printValidationResult(validationResults, "Overall")
            if (validationResults.containsFailure()) return 1
            return 0
        }

        private fun validateImplicitExamplesFrom(contractFile: File): Int {
            val feature = parseContractFileToFeature(contractFile)

            val (validateInline, validateExternal) = getValidateInlineAndValidateExternalFlags()

            val inlineExampleValidationResults = if (!validateInline) emptyMap()
            else validateImplicitInlineExamples(feature)

            val externalExampleValidationResults = if (!validateExternal) emptyMap()
            else {
                val (exitCode, validationResults)
                        = validateExamplesDir(contractFile, implicitExternalExampleDirFrom(contractFile))
                if(exitCode == 1) exitProcess(1)
                validationResults
            }

            val hasFailures =
                inlineExampleValidationResults.containsFailure() || externalExampleValidationResults.containsFailure()

            printValidationResult(inlineExampleValidationResults, "Inline example")
            printValidationResult(externalExampleValidationResults, "Example file")

            if (hasFailures) return 1
            return 0
        }

        private fun validateImplicitInlineExamples(feature: Feature): Map<String, Result> {
            return ExamplesInteractiveServer.validateMultipleExamples(
                feature,
                examples = feature.stubsFromExamples.mapValues {
                    it.value.map { ScenarioStub(it.first, it.second) }
                },
                inline = true,
                scenarioFilter = ExamplesInteractiveServer.ScenarioFilter(
                    filterName,
                    filterNotName,
                    filter,
                    filterNot
                )
            )
        }

        private fun validateExternalExamples(
            feature: Feature,
            externalExamples: Map<String, List<ScenarioStub>>,
            enableLogging: Boolean = true
        ): Map<String, Result> {
            return ExamplesInteractiveServer.validateMultipleExamples(
                feature,
                examples = externalExamples,
                scenarioFilter = ExamplesInteractiveServer.ScenarioFilter(
                    filterName,
                    filterNotName,
                    filter,
                    filterNot
                ),
                enableLogging = enableLogging
            )
        }

        private fun getValidateInlineAndValidateExternalFlags(): Pair<Boolean, Boolean> {
            return when {
                !Flags.getBooleanValue("VALIDATE_INLINE_EXAMPLES") && !Flags.getBooleanValue(
                    "IGNORE_INLINE_EXAMPLES"
                ) -> true to true

                else -> Flags.getBooleanValue("VALIDATE_INLINE_EXAMPLES") to Flags.getBooleanValue("IGNORE_INLINE_EXAMPLES")
            }
        }

        private fun printValidationResult(validationResults: Map<String, Result>, tag: String) {
            if (validationResults.isEmpty())
                return

            val titleTag = tag.split(" ").joinToString(" ") { if (it.isBlank()) it else it.capitalizeFirstChar() }

            if (validationResults.containsFailure()) {
                println()
                logger.log("=============== $titleTag Validation Results ===============")

                validationResults.forEach { (exampleFileName, result) ->
                    if (!result.isSuccess()) {
                        logger.log(System.lineSeparator() + "$tag $exampleFileName has the following validation error(s):")
                        logger.log(result.reportString())
                    }
                }
            }

            println()
            val summaryTitle = "=============== $titleTag Validation Summary ==============="
            logger.log(summaryTitle)
            logger.log(Results(validationResults.values.toList()).summary())
            logger.log("=".repeat(summaryTitle.length))
        }

        private fun Map<String, Result>.containsFailure(): Boolean {
            return this.any { it.value is Result.Failure }
        }

        private fun File.getAssociatedExampleDirFor(specFile: File): File? {
            return this.walk().firstOrNull { exampleDir ->
                exampleDir.isFile.not() && exampleDir.nameWithoutExtension == "${specFile.nameWithoutExtension}_examples"
            }
        }
    }

    @Command(
        name = "interactive",
        mixinStandardHelpOptions = true,
        description = ["Run the example generation interactively"]
    )
    class Interactive : Callable<Unit> {
        @Option(
            names= ["--filter"],
            description = [
                """
Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)
- `HEADERS`: Request headers (e.g., Accept, X-Request-ID)
- `QUERY-PARAM`: Query parameters (e.g., status, productId)
- `EXAMPLE-NAME`: Example name (e.g., create-product, active-status)

To specify multiple values for the same filter, separate them with commas. 
For example, to filter by HTTP methods: 
--filter="METHOD=GET,POST"

You can supply multiple filters as well. 
For example:
--filter="METHOD=GET,POST" --filter="PATH=/users"
           """
            ],
            required = false
        )
        var filter: List<String> = emptyList()

        @Option(
            names= ["--filter-not"],
            description = [
                """
Filter tests not matching the specified criteria

This option supports the same filtering keys and syntax as the --filter option.
For example:
--filterNot="STATUS=400" --filterNot="METHOD=PATCH,PUT"
           """
            ],
            required = false
        )
        var filterNot: List<String> = emptyList()

        @Option(names = ["--contract-file"], description = ["Contract file path"], required = false)
        var contractFile: File? = null

        @Option(
            names = ["--filter-name"],
            description = ["Use only APIs with this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NAME}"
        )
        var filterName: String = ""

        @Option(
            names = ["--filter-not-name"],
            description = ["Use only APIs which do not have this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}"
        )
        var filterNotName: String = ""

        @Option(names = ["--debug"], description = ["Debug logs"])
        var verbose = false

        @Option(names = ["--dictionary"], description = ["External Dictionary File Path"])
        var dictFile: File? = null

        @Option(names = ["--testBaseURL"], description = ["The baseURL of system to test"], required = false)
        var testBaseURL: String? = null

        var server: ExamplesInteractiveServer? = null

        override fun call() {
            configureLogger(verbose)

            try {
                if (contractFile != null && !contractFile!!.exists())
                    exitWithMessage("Could not find file ${contractFile!!.path}")

                server = ExamplesInteractiveServer("0.0.0.0", 9001, testBaseURL, contractFile, filterName, filterNotName, filter, filterNot, dictFile)
                addShutdownHook()

                consoleLog(StringLog("Examples Interactive server is running on http://0.0.0.0:9001/_specmatic/examples. Ctrl + C to stop."))
                while (true) sleep(10000)
            } catch (e: Exception) {
                logger.log(exceptionCauseMessage(e))
                exitWithMessage(e.message.orEmpty())
            }
        }

        private fun addShutdownHook() {
            Runtime.getRuntime().addShutdownHook(object : Thread() {
                override fun run() {
                    try {
                        println("Shutting down examples interactive server...")
                        server?.close()
                    } catch (e: InterruptedException) {
                        currentThread().interrupt()
                    } catch (e: Throwable) {
                        logger.log(e)
                    }
                }
            })
        }
    }
}

private fun configureLogger(verbose: Boolean) {
    val logPrinters = listOf(ConsolePrinter)

    logger = if (verbose)
        Verbose(CompositePrinter(logPrinters))
    else
        NonVerbose(CompositePrinter(logPrinters))
}
