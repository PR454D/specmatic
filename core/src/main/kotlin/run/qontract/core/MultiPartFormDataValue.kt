package run.qontract.core

import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.utils.io.streams.*
import run.qontract.core.pattern.Pattern
import run.qontract.core.pattern.isPatternToken
import run.qontract.core.pattern.parsedPattern
import run.qontract.core.value.JSONObjectValue
import run.qontract.core.value.StringValue
import run.qontract.core.value.Value

const val CONTENT_DISPOSITION = "Content-Disposition"

sealed class MultiPartFormDataValue(open val name: String) {
    abstract fun inferType(): MultiPartFormDataPattern
    abstract fun toDisplayableValue(): String
    abstract fun toJSONObject(): JSONObjectValue
    abstract fun addTo(formBuilder: FormBuilder)
    abstract fun toClauseData(
        clauses: List<GherkinClause>,
        newTypes: Map<String, Pattern>,
        examples: ExampleDeclarations
    ): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations>
}

data class MultiPartContentValue(override val name: String, val content: Value, val boundary: String = "#####") : MultiPartFormDataValue(name) {
    override fun inferType(): MultiPartFormDataPattern {
        return MultiPartContentPattern(name, content.exactMatchElseType())
    }

    override fun toDisplayableValue(): String = """
--$boundary
$CONTENT_DISPOSITION: form-data; name="$name"
Content-Type: ${content.httpContentType}

$content
""".trim()

    override fun toJSONObject(): JSONObjectValue =
            JSONObjectValue(mapOf("name" to StringValue(name), "content" to StringValue(content.toStringValue()), "contentType" to StringValue(content.httpContentType)))

    override fun addTo(formBuilder: FormBuilder) {
        formBuilder.append(name, content.toStringValue(), Headers.build {
            append(HttpHeaders.ContentType, ContentType.parse(content.httpContentType))
            append(CONTENT_DISPOSITION, "form-data; name=${name}")
        })
    }

    override fun toClauseData(
        clauses: List<GherkinClause>,
        newTypes: Map<String, Pattern>,
        examples: ExampleDeclarations
    ): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
        val (typeDeclaration, newExamples) = this.content.typeDeclarationWithKey(this.name, newTypes, examples)

        val newGherkinClause = GherkinClause(
            "request-part ${this.name} ${typeDeclaration.typeValue}",
            GherkinSection.When
        )
        return Triple(clauses.plus(newGherkinClause), typeDeclaration.types, examples.plus(newExamples))
    }
}

data class MultiPartFileValue(override val name: String, val filename: String, val contentType: String? = null, val contentEncoding: String? = null, val content: String? = null, val boundary: String = "#####") : MultiPartFormDataValue(name) {
    override fun inferType(): MultiPartFormDataPattern {
        return MultiPartFilePattern(name, parsedPattern(filename.removePrefix("@")), contentType, contentEncoding)
    }

    override fun toDisplayableValue(): String {
        val headers = mapOf (
                CONTENT_DISPOSITION to """form-data; name="$name"; filename="$filename"""",
                "Content-Type" to (contentType ?: ""),
                "Content-Encoding" to (contentEncoding ?: "")
        ).filter { it.value.isNotBlank() }

        val headerString = headers.entries.joinToString {
            "${it.key}: ${it.value}"
        }

        return """
--$boundary
$headerString

(File content not shown here.  Please examine the file ${filename.removePrefix("@")})
""".trim()
    }

    override fun toJSONObject() =
            JSONObjectValue(mapOf("name" to StringValue(name), "filename" to StringValue("@${filename}")).let { map ->
                when (contentType) {
                    null -> map
                    else -> map.plus("contentType" to StringValue(contentType))
                }
            }.let { map ->
                when (contentEncoding) {
                    null -> map
                    else -> map.plus("contentEncoding" to StringValue(contentEncoding))
                }
            })

    override fun addTo(formBuilder: FormBuilder) {
        formBuilder.appendInput(name, Headers.build {
            if(contentType != null)
                append(HttpHeaders.ContentType, ContentType.parse(contentType))
            contentEncoding?.let {
                append(HttpHeaders.ContentEncoding, contentEncoding)
            }
            append(CONTENT_DISPOSITION, "form-data; name=${name}; filename=${filename.removePrefix("@")}")
        }) {
            (content ?: "").byteInputStream().asInput()
        }
    }

    override fun toClauseData(
        clauses: List<GherkinClause>,
        newTypes: Map<String, Pattern>,
        examples: ExampleDeclarations
    ): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
        val contentType = this.contentType
        val contentEncoding = contentType?.let { this.contentEncoding }

        return Triple(
            clauses.plus(
                GherkinClause(
                    "request-part ${this.name} @${this.filename} $contentType $contentEncoding".trim(),
                    GherkinSection.When
                )
            ), newTypes, examples
        )
    }
}
