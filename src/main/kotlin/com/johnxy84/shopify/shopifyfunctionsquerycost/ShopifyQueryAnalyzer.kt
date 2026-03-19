package com.johnxy84.shopify.shopifyfunctionsquerycost

import graphql.language.*
import graphql.parser.Parser

data class QueryAnalysis(
    val cost: Int?,
    val warnings: List<String>,
    val parseError: String? = null
)

object ShopifyQueryAnalyzer {
    private val specialCostFields = setOf("metafield", "hasTags", "hasAnyTag", "inAnyCollection", "inCollections")

    fun analyze(query: String): QueryAnalysis {
        return try {
            val document = Parser().parseDocument(query)
            val fragments = document.definitions.filterIsInstance<FragmentDefinition>()
                .associateBy { it.name }
            val cost = calculateCost(document, fragments)
            val warnings = mutableListOf<String>()

            if (cost > 30) {
                warnings.add("Cost exceeds 30 (actual: $cost).")
            }
            if (hasOversizedList(document, fragments)) {
                warnings.add("List literal exceeds 100 elements.")
            }

            QueryAnalysis(cost = cost, warnings = warnings)
        } catch (e: Exception) {
            QueryAnalysis(
                cost = null,
                warnings = emptyList(),
                parseError = e.message ?: "Parse error"
            )
        }
    }

    private fun calculateCost(document: Document, fragments: Map<String, FragmentDefinition>): Int {
        var cost = 0
        document.definitions.forEach { definition ->
            when (definition) {
                is OperationDefinition -> {
                    cost += costSelectionSet(definition.selectionSet, fragments, parentIsSpecialObject = false)
                }
                else -> Unit
            }
        }
        return cost
    }

    private fun costSelectionSet(
        selectionSet: SelectionSet?,
        fragments: Map<String, FragmentDefinition>,
        parentIsSpecialObject: Boolean
    ): Int {
        if (selectionSet == null) return 0
        var cost = 0
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> {
                    val fieldName = selection.name
                    val isSpecialField = specialCostFields.contains(fieldName)
                    val isTypename = fieldName == "__typename"

                    val fieldCost = when {
                        isTypename -> 0
                        isSpecialField -> 3
                        selection.selectionSet == null -> if (parentIsSpecialObject) 0 else 1
                        parentIsSpecialObject -> 0
                        else -> 0
                    }
                    cost += fieldCost

                    val childParentIsSpecial = isSpecialField
                    cost += costSelectionSet(selection.selectionSet, fragments, childParentIsSpecial)
                }
                is InlineFragment -> {
                    cost += costSelectionSet(selection.selectionSet, fragments, parentIsSpecialObject)
                }
                is FragmentSpread -> {
                    val fragment = fragments[selection.name]
                    if (fragment != null) {
                        cost += costSelectionSet(fragment.selectionSet, fragments, parentIsSpecialObject)
                    }
                }
            }
        }
        return cost
    }

    private fun hasOversizedList(document: Document, fragments: Map<String, FragmentDefinition>): Boolean {
        val variableDefaults = mutableMapOf<String, Value<*>>()
        document.definitions.filterIsInstance<OperationDefinition>().forEach { op ->
            op.variableDefinitions?.forEach { def ->
                def.defaultValue?.let { variableDefaults[def.name] = it }
            }
        }

        document.definitions.forEach { definition ->
            when (definition) {
                is OperationDefinition -> {
                    if (selectionSetHasOversizedList(definition.selectionSet, fragments, variableDefaults)) return true
                    definition.variableDefinitions?.forEach { def ->
                        val defaultValue = def.defaultValue
                        if (defaultValue != null && valueHasOversizedList(defaultValue, variableDefaults)) return true
                    }
                }
                is FragmentDefinition -> {
                    if (selectionSetHasOversizedList(definition.selectionSet, fragments, variableDefaults)) return true
                }
            }
        }
        return false
    }

    private fun selectionSetHasOversizedList(
        selectionSet: SelectionSet?,
        fragments: Map<String, FragmentDefinition>,
        variableDefaults: Map<String, Value<*>>
    ): Boolean {
        if (selectionSet == null) return false
        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> {
                    if (selection.arguments.any { argumentHasOversizedList(it, variableDefaults) }) return true
                    if (selectionSetHasOversizedList(selection.selectionSet, fragments, variableDefaults)) return true
                }
                is InlineFragment -> {
                    if (selectionSetHasOversizedList(selection.selectionSet, fragments, variableDefaults)) return true
                }
                is FragmentSpread -> {
                    val fragment = fragments[selection.name]
                    if (fragment != null &&
                        selectionSetHasOversizedList(fragment.selectionSet, fragments, variableDefaults)
                    ) return true
                }
            }
        }
        return false
    }

    private fun argumentHasOversizedList(argument: Argument, variableDefaults: Map<String, Value<*>>): Boolean {
        return valueHasOversizedList(argument.value, variableDefaults)
    }

    private fun valueHasOversizedList(value: Value<*>, variableDefaults: Map<String, Value<*>>): Boolean {
        return when (value) {
            is ArrayValue -> value.values.size > 100 ||
                value.values.any { valueHasOversizedList(it, variableDefaults) }
            is ObjectValue -> value.objectFields.any { valueHasOversizedList(it.value, variableDefaults) }
            is VariableReference -> {
                val defaultValue = variableDefaults[value.name]
                defaultValue != null && valueHasOversizedList(defaultValue, variableDefaults)
            }
            else -> false
        }
    }

}
