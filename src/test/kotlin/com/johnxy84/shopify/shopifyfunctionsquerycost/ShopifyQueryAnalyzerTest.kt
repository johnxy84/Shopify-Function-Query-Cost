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
                metafield(namespace: "x", key: "y") {
                  value
                }
              }
            }
        """.trimIndent()

        val analysis = ShopifyQueryAnalyzer.analyze(query)
        assertEquals(3, analysis.cost)
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
}
