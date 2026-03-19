package com.johnxy84.shopify.shopifyfunctionsquerycost

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import javax.swing.Icon

class ShopifyExtensionTomlFileType : FileType {
    override fun getName(): String = "ShopifyExtensionToml"

    override fun getDescription(): @NlsContexts.Label String =
        "Shopify extension configuration (shopify.extension.toml)"

    override fun getDefaultExtension(): String = "toml"

    override fun getIcon(): Icon? = null

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = false
}
