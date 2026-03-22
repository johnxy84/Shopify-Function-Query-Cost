package com.johnxy84.shopify.shopifyfunctionsquerycost

import graphql.language.*
import graphql.parser.Parser

data class QueryAnalysis(
    val cost: Int?,
    val breakdown: QueryBreakdownNode?,
    val warnings: List<String>,
    val parseError: String? = null
)

data class QueryBreakdownNode(
    val label: String,
    val cost: Int,
    val children: List<QueryBreakdownNode> = emptyList()
)

object ShopifyQueryAnalyzer {
    private val specialCostFields = setOf("metafield", "hasTags", "hasAnyTag", "inAnyCollection", "inCollections")
    private val attributeFields = setOf("attribute", "attributes")

    fun analyze(query: String): QueryAnalysis {
        return try {
            val document = Parser().parseDocument(query)
            val fragments = document.definitions.filterIsInstance<FragmentDefinition>()
                .associateBy { it.name }
            val breakdownResult = buildBreakdown(document, fragments)
            val cost = breakdownResult.totalCost
            val warnings = mutableListOf<String>()

            if (cost > 30) {
                warnings.add("Cost exceeds 30 (actual: $cost).")
            }
            if (hasOversizedList(document, fragments)) {
                warnings.add("List literal exceeds 100 elements.")
            }

            QueryAnalysis(cost = cost, breakdown = breakdownResult.root, warnings = warnings)
        } catch (e: Exception) {
            QueryAnalysis(
                cost = null,
                breakdown = null,
                warnings = emptyList(),
                parseError = e.message ?: "Parse error"
            )
        }
    }

    private data class BreakdownResult(
        val root: QueryBreakdownNode,
        val totalCost: Int
    )

    private fun buildBreakdown(document: Document, fragments: Map<String, FragmentDefinition>): BreakdownResult {
        val children = mutableListOf<QueryBreakdownNode>()
        var totalCost = 0

        document.definitions.forEach { definition ->
            when (definition) {
                is OperationDefinition -> {
                    val operationLabel = definition.name ?: "operation"
                    val selectionResult = breakdownSelectionSet(
                        definition.selectionSet,
                        fragments,
                        parentIsSpecialObject = false
                    )
                    children.add(
                        QueryBreakdownNode(
                            label = operationLabel,
                            cost = 0,
                            children = selectionResult.nodes
                        )
                    )
                    totalCost += selectionResult.cost
                }
                else -> Unit
            }
        }

        val root = QueryBreakdownNode(label = "Query", cost = 0, children = children)
        return BreakdownResult(root = root, totalCost = totalCost)
    }

    private data class SelectionBreakdown(
        val nodes: List<QueryBreakdownNode>,
        val cost: Int
    )

    private fun breakdownSelectionSet(
        selectionSet: SelectionSet?,
        fragments: Map<String, FragmentDefinition>,
        parentIsSpecialObject: Boolean
    ): SelectionBreakdown {
        if (selectionSet == null) return SelectionBreakdown(emptyList(), 0)
        val nodes = mutableListOf<QueryBreakdownNode>()
        var totalCost = 0

        for (selection in selectionSet.selections) {
            when (selection) {
                is Field -> {
                    val fieldName = selection.name
                    val isSpecialField = specialCostFields.contains(fieldName)
                    val isTypename = fieldName == "__typename"
                    val label = buildFieldLabel(selection, fieldName)

                    val fieldCost = when {
                        isTypename -> 0
                        isSpecialField -> 3
                        selection.selectionSet == null -> if (parentIsSpecialObject) 0 else 1
                        parentIsSpecialObject -> 0
                        else -> 0
                    }

                    val childParentIsSpecial = isSpecialField
                    val childBreakdown = breakdownSelectionSet(
                        selection.selectionSet,
                        fragments,
                        childParentIsSpecial
                    )

                    nodes.add(
                        QueryBreakdownNode(
                            label = label,
                            cost = fieldCost,
                            children = childBreakdown.nodes
                        )
                    )
                    totalCost += fieldCost + childBreakdown.cost
                }
                is InlineFragment -> {
                    val typeLabel = selection.typeCondition?.name?.let { "InlineFragment on $it" } ?: "InlineFragment"
                    val childBreakdown = breakdownSelectionSet(
                        selection.selectionSet,
                        fragments,
                        parentIsSpecialObject
                    )
                    nodes.add(
                        QueryBreakdownNode(
                            label = typeLabel,
                            cost = 0,
                            children = childBreakdown.nodes
                        )
                    )
                    totalCost += childBreakdown.cost
                }
                is FragmentSpread -> {
                    val fragment = fragments[selection.name]
                    if (fragment != null) {
                        val childBreakdown = breakdownSelectionSet(
                            fragment.selectionSet,
                            fragments,
                            parentIsSpecialObject
                        )
                        nodes.add(
                            QueryBreakdownNode(
                                label = "Fragment: ${selection.name}",
                                cost = 0,
                                children = childBreakdown.nodes
                            )
                        )
                        totalCost += childBreakdown.cost
                    } else {
                        nodes.add(
                            QueryBreakdownNode(
                                label = "Fragment: ${selection.name}",
                                cost = 0,
                                children = emptyList()
                            )
                        )
                    }
                }
            }
        }

        return SelectionBreakdown(nodes = nodes, cost = totalCost)
    }

    private fun buildFieldLabel(field: Field, fieldName: String): String {
        val baseName = field.alias ?: fieldName

        if (fieldName == "metafield") {
            val namespace = argumentString(field.arguments, "namespace")
            val key = argumentString(field.arguments, "key")
            val identifier = argumentObjectPair(field.arguments, "identifier", "namespace", "key")
            val resolvedNamespace = namespace ?: identifier?.first
            val resolvedKey = key ?: identifier?.second
            val labelFromPair = if (resolvedNamespace != null || resolvedKey != null) {
                when {
                    resolvedNamespace != null && resolvedKey != null -> "$resolvedNamespace:$resolvedKey"
                    resolvedNamespace != null -> "namespace=$resolvedNamespace"
                    else -> "key=$resolvedKey"
                }
            } else {
                null
            }

            if (labelFromPair != null) {
                return "$baseName ($labelFromPair)"
            }

        }

        if (attributeFields.contains(fieldName)) {
            val key = argumentString(field.arguments, "key")
            val name = argumentString(field.arguments, "name")
            val labelValue = key ?: name
            if (labelValue != null) {
                return "$baseName ($labelValue)"
            }
        }

        return baseName
    }

    private fun argumentString(arguments: List<Argument>?, name: String): String? {
        if (arguments == null) return null
        val arg = arguments.firstOrNull { it.name == name } ?: return null
        return valueToLabel(arg.value)
    }

    private fun argumentObjectPair(
        arguments: List<Argument>?,
        name: String,
        firstKey: String,
        secondKey: String
    ): Pair<String?, String?>? {
        if (arguments == null) return null
        val arg = arguments.firstOrNull { it.name == name } ?: return null
        val value = arg.value
        if (value !is ObjectValue) return null
        val first = value.objectFields.firstOrNull { it.name == firstKey }?.value?.let { valueToLabel(it) }
        val second = value.objectFields.firstOrNull { it.name == secondKey }?.value?.let { valueToLabel(it) }
        return first to second
    }

    private fun valueToLabel(value: Value<*>): String? {
        return when (value) {
            is StringValue -> value.value
            is EnumValue -> value.name
            is IntValue -> value.value.toString()
            is FloatValue -> value.value.toString()
            is BooleanValue -> value.isValue.toString()
            is VariableReference -> "\$${value.name}"
            else -> null
        }
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
