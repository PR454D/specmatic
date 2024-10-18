package io.specmatic.core.filters

data class ScenarioMetadataFilter(
    val methods: Set<String> = emptySet(),
    val paths: Set<String> = emptySet(),
    val statusCodes: Set<String> = emptySet(),
    val headers: Set<String> = emptySet(),
    val queryParams: Set<String> = emptySet(),
    val exampleNames: Set<String> = emptySet()
) {
    fun isSatisfiedByAll(metadata: ScenarioMetadata): Boolean {
        return methods.contains(metadata.method, false) &&
                paths.contains(metadata.path, false) &&
                statusCodes.contains(metadata.statusCode.toString(), false) &&
                (metadata.exampleName.isNotBlank()
                        && exampleNames.contains(metadata.exampleName, false)) &&
                (headers.isEmpty() || metadata.header.any { headers.contains(it, false) }) &&
                (queryParams.isEmpty() || metadata.query.any { queryParams.contains(it, false) })
    }

    fun isSatisfiedByAtLeastOne(metadata: ScenarioMetadata): Boolean {
        return methods.contains(metadata.method, true) ||
                paths.contains(metadata.path, true) ||
                statusCodes.contains(metadata.statusCode.toString(), true) ||
                (metadata.exampleName.isNotBlank()
                        && exampleNames.contains(metadata.exampleName, true)) ||
                (headers.isNotEmpty() && metadata.header.any { headers.contains(it, true) }) ||
                (queryParams.isNotEmpty() && metadata.query.any { queryParams.contains(it, true) })
    }

    private fun Set<String>.contains(element: String, strict: Boolean): Boolean {
        if(strict) return (this.isNotEmpty() && element in this)
        return (this.isEmpty() || element in this)
    }

    companion object {
        private const val FILTER_SEPARATOR = ";"

        fun from(filter: String): ScenarioMetadataFilter {
            if(filter.split(FILTER_SEPARATOR).isEmpty()) {
                return ScenarioMetadataFilter()
            }
            val filters = filter.split(FILTER_SEPARATOR)
            return ScenarioMetadataFilter(
                methods = filters.getFiltersWithTag(ScenarioFilterTags.METHOD),
                paths = filters.getFiltersWithTag(ScenarioFilterTags.PATH),
                statusCodes = filters.getFiltersWithTag(ScenarioFilterTags.STATUS_CODE),
                headers = filters.getFiltersWithTag(ScenarioFilterTags.HEADER),
                queryParams = filters.getFiltersWithTag(ScenarioFilterTags.QUERY),
                exampleNames = filters.getFiltersWithTag(ScenarioFilterTags.EXAMPLE_NAME)
            )
        }

        private fun List<String>.getFiltersWithTag(tag: ScenarioFilterTags): Set<String> {
            return this.asSequence().map {
                it.trim().split("=")
            }.filter { it.size == 2 }.filter {
                val key = it[0]
                key == tag.key
            }.flatMap {
                val values = it[1]
                values.trim().split(",").map { value ->
                    value.trim()
                }
            }.toSet()
        }
    }
}











