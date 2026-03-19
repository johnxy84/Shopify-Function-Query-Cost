package com.johnxy84.shopify.shopifyfunctionsquerycost

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import com.intellij.util.PathUtil
import com.intellij.openapi.vfs.VfsUtilCore
import java.util.concurrent.atomic.AtomicReference

interface ShopifyInputQueryIndexListener {
    fun inputQueryIndexChanged()
}

val SHOPIFY_INPUT_QUERY_INDEX_TOPIC: Topic<ShopifyInputQueryIndexListener> =
    Topic.create("Shopify input query index changed", ShopifyInputQueryIndexListener::class.java)

@Service(Service.Level.PROJECT)
class ShopifyInputQueryIndexService(private val project: Project) : Disposable {
    private val inputQueryPaths = AtomicReference<Set<String>>(emptySet())

    init {
        rescan()
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith("shopify.extension.toml") }) {
                    rescan()
                }
            }
        })
    }

    fun isInputQueryFile(file: VirtualFile): Boolean {
        val path = PathUtil.toSystemIndependentName(file.path)
        return inputQueryPaths.get().contains(path)
    }

    fun rescan() {
        val basePath = project.basePath ?: return
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return
        val found = mutableSetOf<String>()

        VfsUtilCore.iterateChildrenRecursively(
            baseDir,
            { file -> !file.isDirectory || file.name != ".git" },
            { file ->
                if (!file.isDirectory && file.name == "shopify.extension.toml") {
                    val parent = file.parent
                    val paths = parseInputQueryPaths(file)
                    for (relative in paths) {
                        val resolved = resolvePath(parent, relative)
                        if (resolved != null) {
                            found.add(PathUtil.toSystemIndependentName(resolved.path))
                        }
                    }
                }
                true
            }
        )

        val normalized = found.toSet()
        val previous = inputQueryPaths.getAndSet(normalized)
        if (previous != normalized) {
            project.messageBus.syncPublisher(SHOPIFY_INPUT_QUERY_INDEX_TOPIC).inputQueryIndexChanged()
        }
    }

    private fun parseInputQueryPaths(file: VirtualFile): List<String> {
        return try {
            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            parseInputQueryPaths(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseInputQueryPaths(tomlText: String): List<String> {
        val results = mutableListOf<String>()

        val arrayPattern = Regex("""(?m)^\s*input_query\s*=\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
        val arrayMatch = arrayPattern.find(tomlText)
        if (arrayMatch != null) {
            val inner = arrayMatch.groupValues[1]
            val stringPattern = Regex(""""([^"]+)"""")
            stringPattern.findAll(inner).forEach { results.add(it.groupValues[1]) }
            val singleQuotePattern = Regex("'([^']+)'")
            singleQuotePattern.findAll(inner).forEach { results.add(it.groupValues[1]) }
        }

        val singlePattern = Regex("""(?m)^\s*input_query\s*=\s*"([^"]+)"""")
        singlePattern.findAll(tomlText).forEach { results.add(it.groupValues[1]) }
        val singleQuotePattern = Regex("""(?m)^\s*input_query\s*=\s*'([^']+)'""")
        singleQuotePattern.findAll(tomlText).forEach { results.add(it.groupValues[1]) }

        return results.distinct()
    }

    private fun resolvePath(parent: VirtualFile?, relative: String): VirtualFile? {
        if (parent == null) return null
        val cleaned = relative.trim()
        return if (cleaned.startsWith("/")) {
            VirtualFileManager.getInstance().findFileByUrl("file://$cleaned")
        } else {
            parent.findFileByRelativePath(cleaned)
        }
    }

    override fun dispose() {
        // No-op
    }
}
