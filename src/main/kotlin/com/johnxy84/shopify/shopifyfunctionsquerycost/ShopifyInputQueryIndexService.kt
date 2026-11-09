package com.johnxy84.shopify.shopifyfunctionsquerycost

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import com.intellij.util.PathUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TOML_FILE_NAME = "shopify.extension.toml"

interface ShopifyInputQueryIndexListener {
    fun inputQueryIndexChanged()
}

val SHOPIFY_INPUT_QUERY_INDEX_TOPIC: Topic<ShopifyInputQueryIndexListener> =
    Topic.create("Shopify input query index changed", ShopifyInputQueryIndexListener::class.java)

@Service(Service.Level.PROJECT)
class ShopifyInputQueryIndexService(private val project: Project, cs: CoroutineScope) : Disposable {
    private val inputQueryPaths = AtomicReference<Set<String>>(emptySet())
    private val scanningInProgress = AtomicBoolean(true)
    private val rescanRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(TOML_FILE_NAME) }) {
                    rescanRequests.tryEmit(Unit)
                }
            }
        })
        cs.launch { rescanRequests.collectLatest { rescan() } }
        rescanRequests.tryEmit(Unit)
    }

    fun isReady(): Boolean = !scanningInProgress.get()

    fun isInputQueryFile(file: VirtualFile): Boolean {
        val path = PathUtil.toSystemIndependentName(file.path)
        return inputQueryPaths.get().contains(path)
    }

    private suspend fun rescan() {
        // The read action can be cancelled and restarted, so its body must stay side-effect free.
        val found: Set<String> = smartReadAction(project) {
            val result = mutableSetOf<String>()
            for (toml in FilenameIndex.getVirtualFilesByName(TOML_FILE_NAME, GlobalSearchScope.projectScope(project))) {
                val parent = toml.parent
                for (relative in parseInputQueryPaths(toml)) {
                    val resolved = resolvePath(parent, relative)
                    if (resolved != null) {
                        result.add(PathUtil.toSystemIndependentName(resolved.path))
                    }
                }
            }
            result
        }

        val previous = inputQueryPaths.getAndSet(found)
        scanningInProgress.set(false)
        if (previous != found) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(SHOPIFY_INPUT_QUERY_INDEX_TOPIC).inputQueryIndexChanged()
                }
            }
        }
    }

    private fun parseInputQueryPaths(file: VirtualFile): List<String> {
        return try {
            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            parseInputQueryPaths(text)
        } catch (_: IOException) {
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
