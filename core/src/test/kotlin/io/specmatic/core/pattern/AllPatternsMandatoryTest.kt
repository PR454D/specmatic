package io.specmatic.core.pattern

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.DefaultMismatchMessages
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.utilities.Flags.Companion.ALL_PATTERNS_MANDATORY
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AllPatternsMandatoryTest {
    private val spec = """
        openapi: 3.0.0
        info:
          title: test
          version: 1.0.0
        paths:
          /products:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/ProductRequest'
              responses:
                200:
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/components/schemas/Product'
        components:
          schemas:
            ProductRequest:
              type: object
              properties:
                name:
                  type: string
                type:
                  type: string
                inventory:
                  type: number
                details:
                  ${'$'}ref: '#/components/schemas/Details'
              required:
                - name
            Product:
              type: object
              properties:
                id:
                  type: number
                name:
                  type: string
                type:
                  type: string
                inventory:
                  type: number
                details:
                  ${'$'}ref: '#/components/schemas/Details'
              required:
                - id
                - name
            Details:
              type: object
              properties:
                dateOfManufacture:
                  type: string
                  format: date
                dateOfExpiry:
                  type: string
                  format: date
            """.trimIndent()

    @BeforeEach
    fun setup() {
        System.setProperty(ALL_PATTERNS_MANDATORY, "true")
    }

    @AfterEach
    fun reset() {
        System.clearProperty(ALL_PATTERNS_MANDATORY)
    }

    @Test
    fun `should warn about missing optional keys`(@TempDir tempDir: File) {
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleFile = tempDir.resolve("example.json")
        val example = ScenarioStub(
            request = HttpRequest(path = "/products", method = "POST", body = JSONObjectValue(mapOf("name" to StringValue("iPhone")))),
            response = HttpResponse(status = 200, body = JSONArrayValue(
                List(2) { JSONObjectValue(mapOf(
                    "id" to NumberValue(1),
                    "name" to StringValue("iPhone"),
                    "details" to JSONObjectValue(emptyMap())
                ))}
            ))
        )
        exampleFile.writeText(example.toJSON().toStringLiteral())

        val result = feature.matchResultFlagBased(example, DefaultMismatchMessages).toResultIfAny()
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.isPartialFailure()).isTrue()
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            In scenario "POST /products. Response: OK"
            API: POST /products -> 200

            >> REQUEST.BODY.type
            Expected optional key named "type" was missing
            >> REQUEST.BODY.inventory
            Expected optional key named "inventory" was missing
            >> REQUEST.BODY.details
            Expected optional key named "details" was missing
            
            >> RESPONSE.BODY[0].type
            Expected optional key named "type" was missing
            >> RESPONSE.BODY[0].inventory
            Expected optional key named "inventory" was missing
            >> RESPONSE.BODY[0].details.dateOfManufacture
            Expected optional key named "dateOfManufacture" was missing
            >> RESPONSE.BODY[0].details.dateOfExpiry
            Expected optional key named "dateOfExpiry" was missing

            >> RESPONSE.BODY[1].type
            Expected optional key named "type" was missing
            >> RESPONSE.BODY[1].inventory
            Expected optional key named "inventory" was missing
            >> RESPONSE.BODY[1].details.dateOfManufacture
            Expected optional key named "dateOfManufacture" was missing
            >> RESPONSE.BODY[1].details.dateOfExpiry
            Expected optional key named "dateOfExpiry" was missing
            """.trimIndent())
    }

    @Test
    fun `should not be partial failure when mandatory key is missing`(@TempDir tempDir: File) {
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleFile = tempDir.resolve("example.json")
        val example = ScenarioStub(
            request = HttpRequest(path = "/products", method = "POST", body = JSONObjectValue(mapOf(
                "name" to StringValue("iPhone"),
                "details" to JSONObjectValue(mapOf(
                    "dateOfManufacture" to StringValue("2020-01-01"),
                    "dateOfExpiry" to StringValue("2020-01-01")
                ))
            ))),
            response = HttpResponse(status = 200, body = JSONArrayValue(
                List(1) { JSONObjectValue(mapOf("id" to NumberValue(1))) }
            ))
        )
        exampleFile.writeText(example.toJSON().toStringLiteral())

        val result = feature.matchResultFlagBased(example, DefaultMismatchMessages).toResultIfAny()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.isPartialFailure()).isFalse()

        val report = result.reportString()
        println(report)
        assertThat(report).isEqualToNormalizingWhitespace("""
            In scenario "POST /products. Response: OK"
            API: POST /products -> 200

            >> RESPONSE.BODY[0].name
            Expected key named "name" was missing

            >> REQUEST.BODY.type
            Expected optional key named "type" was missing
            >> REQUEST.BODY.inventory
            Expected optional key named "inventory" was missing
            >> RESPONSE.BODY[0].type
            Expected optional key named "type" was missing
            >> RESPONSE.BODY[0].inventory
            Expected optional key named "inventory" was missing
            >> RESPONSE.BODY[0].details
            Expected optional key named "details" was missing
            """.trimIndent())
    }

    @Test
    fun `should not be partial failure when type mismatch`(@TempDir tempDir: File) {
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleFile = tempDir.resolve("example.json")
        val example = ScenarioStub(
            request = HttpRequest(path = "/products", method = "POST", body = JSONObjectValue(
                mapOf("name" to StringValue("iPhone"), "type" to StringValue("phone"), "inventory" to NumberValue(1))
            )),
            response = HttpResponse(status = 200, body = JSONArrayValue(
                List(2) { JSONObjectValue(mapOf("id" to NumberValue(1), "name" to NumberValue(123))) }
            ))
        )
        exampleFile.writeText(example.toJSON().toStringLiteral())

        val result = feature.matchResultFlagBased(example, DefaultMismatchMessages).toResultIfAny()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.isPartialFailure()).isFalse()

        val report = result.reportString()
        println(report)
        assertThat(report).isEqualToNormalizingWhitespace("""
            In scenario "POST /products. Response: OK"
            API: POST /products -> 200

            >> RESPONSE.BODY[0].name
            Expected string, actual was 123 (number)
            >> RESPONSE.BODY[1].name
            Expected string, actual was 123 (number)
            
            >> REQUEST.BODY.details
            Expected optional key named "details" was missing

            >> RESPONSE.BODY[0].type
            Expected optional key named "type" was missing
            >> RESPONSE.BODY[0].inventory
            Expected optional key named "inventory" was missing
            >> RESPONSE.BODY[0].details
            Expected optional key named "details" was missing
            
            >> RESPONSE.BODY[1].type
            Expected optional key named "type" was missing
            >> RESPONSE.BODY[1].inventory
            Expected optional key named "inventory" was missing
            >> RESPONSE.BODY[1].details
            Expected optional key named "details" was missing
            """.trimIndent())
    }

    @Test
    fun `should not complain about missing optional keys when all patterns mandatory is false`(@TempDir tempDir: File) {
        System.setProperty(ALL_PATTERNS_MANDATORY, "false")
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleFile = tempDir.resolve("example.json")
        val example = ScenarioStub(
            request = HttpRequest(path = "/products", method = "POST", body = JSONObjectValue(mapOf("name" to StringValue("iPhone")))),
            response = HttpResponse(status = 200, body = JSONArrayValue(
                List(2) { JSONObjectValue(mapOf("id" to NumberValue(1), "name" to StringValue("iPhone")))}
            ))
        )
        exampleFile.writeText(example.toJSON().toStringLiteral())

        val result = feature.matchResultFlagBased(example, DefaultMismatchMessages).toResultIfAny()
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(result.reportString()).isEmpty()
    }
}