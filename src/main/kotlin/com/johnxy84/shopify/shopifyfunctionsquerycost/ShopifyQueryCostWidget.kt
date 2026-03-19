package com.johnxy84.shopify.shopifyfunctionsquerycost

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JLabel
import com.intellij.ui.JBColor

class ShopifyQueryCostWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ShopifyQueryCostWidget.ID

    override fun getDisplayName(): String = "Shopify Query Cost"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = ShopifyQueryCostWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class ShopifyQueryCostWidget(private val project: Project) : StatusBarWidget, CustomStatusBarWidget, Disposable {
    private var statusBar: StatusBar? = null
    private val label = JLabel("Shopify Query Cost: —")
    private val updateQueue = MergingUpdateQueue("ShopifyQueryCostWidget", 300, true, null, this, null, false)
    private val inputQueryIndex = project.getService(ShopifyInputQueryIndexService::class.java)
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    init {
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                scheduleUpdate()
            }
        })

        connection.subscribe(SHOPIFY_INPUT_QUERY_INDEX_TOPIC, object : ShopifyInputQueryIndexListener {
            override fun inputQueryIndexChanged() {
                scheduleUpdate()
            }
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document)
                val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                if (file != null && file == selectedFile) {
                    scheduleUpdate()
                }
            }
        }, this)
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        scheduleUpdate()
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getComponent(): JComponent = label

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    private fun scheduleUpdate() {
        updateQueue.queue(Update.create(ID) {
            updateNow()
        })
    }

    private fun updateNow() {
        if (project.isDisposed) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }

        if (file == null || !inputQueryIndex.isInputQueryFile(file)) {
            label.text = "No Shopify input query active"
            label.toolTipText = null
            label.foreground = JBColor.foreground()
            notifyStatusBar()
            return
        }

        val analysis = ShopifyQueryAnalyzer.analyze(editor.document.text)
        if (analysis.cost == null) {
            label.text = "Shopify Query Cost: — (Parse error)"
            label.toolTipText = analysis.parseError?.let { "<html>$it</html>" }
            label.foreground = JBColor(Color(0xD32F2F), Color(0xFF6B6B))
            notifyStatusBar()
            return
        }

        label.text = "Shopify Query Cost: ${analysis.cost}/30"
        val rangeWarning = if (analysis.cost in 25..30) {
            "Cost is in warning range (25–30)."
        } else {
            null
        }
        val allWarnings = analysis.warnings + listOfNotNull(rangeWarning)
        label.toolTipText = if (allWarnings.isEmpty()) {
            "Shopify input query within limits."
        } else {
            "<html>Warnings:<br>${allWarnings.joinToString("<br>")}</html>"
        }
        label.foreground = statusColor(analysis, rangeWarning != null)
        notifyStatusBar()
    }

    private fun statusColor(analysis: QueryAnalysis, rangeWarning: Boolean): Color {
        if (analysis.cost == null) {
            return JBColor(Color(0xD32F2F), Color(0xFF6B6B))
        }
        val isBad = (analysis.cost > 30)
        if (isBad) {
            return JBColor(Color(0xD32F2F), Color(0xFF6B6B))
        }
        val isWarn = rangeWarning || analysis.warnings.isNotEmpty()
        if (isWarn) {
            return JBColor(Color(0xF57C00), Color(0xF2C94C))
        }
        return JBColor(Color(0x2E7D32), Color(0x6FCF97))
    }

    private fun notifyStatusBar() {
        UIUtil.invokeLaterIfNeeded {
            statusBar?.updateWidget(ID)
        }
    }

    companion object {
        const val ID = "ShopifyQueryCostWidget"
    }
}
