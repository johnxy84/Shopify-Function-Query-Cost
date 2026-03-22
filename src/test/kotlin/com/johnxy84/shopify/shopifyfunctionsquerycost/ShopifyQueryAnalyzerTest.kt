package com.johnxy84.shopify.shopifyfunctionsquerycost

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopifyQueryAnalyzerTest {
    @Test
    fun `metafield subtree cost is 3`() {
        val query = """
            query Input {
              product {
                vat: metafield(namespace: "x", key: "y") {
                  value
                }
              }
            }
        """.trimIndent()

        val analysis = ShopifyQueryAnalyzer.analyze(query)
        assertEquals(3, analysis.cost)
        val node = findNode(analysis.breakdown) { it.label == "vat (x:y)" }
        assertEquals(3, node?.cost)
        val valueNode = findNode(node) { it.label == "value" }
        assertEquals(0, valueNode?.cost)
    }

    @Test
    fun `typename has zero cost`() {
        val query = """
            query Input {
              cart {
                __typename
                id
              }
            }
        """.trimIndent()

        val analysis = ShopifyQueryAnalyzer.analyze(query)
        assertEquals(1, analysis.cost)
        val typenameNode = findNode(analysis.breakdown) { it.label == "__typename" }
        assertEquals(0, typenameNode?.cost)
    }

    @Test
    fun `fragments are included in cost`() {
        val query = """
            query Input {
              cart {
                ...Fields
              }
            }
            fragment Fields on Cart {
              id
            }
        """.trimIndent()

        val analysis = ShopifyQueryAnalyzer.analyze(query)
        assertEquals(1, analysis.cost)
        val fragmentNode = findNode(analysis.breakdown) { it.label == "Fragment: Fields" }
        assertTrue(fragmentNode != null)
        val idNode = findNode(fragmentNode) { it.label == "id" }
        assertTrue(idNode != null)
    }

    @Test
    fun `list literal warning triggers`() {
        val items = (1..101).joinToString(",")
        val query = """
            query Input {
              cart {
                hasTags(tags: [$items]) {
                  hasTag
                }
              }
            }
        """.trimIndent()

        val analysis = ShopifyQueryAnalyzer.analyze(query)
        assertTrue(analysis.warnings.any { it.contains("List literal") })
    }

    private fun findNode(
        root: QueryBreakdownNode?,
        predicate: (QueryBreakdownNode) -> Boolean
    ): QueryBreakdownNode? {
        if (root == null) return null
        val queue = ArrayDeque<QueryBreakdownNode>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            node.children.forEach { queue.add(it) }
        }
        return null
    }
}
