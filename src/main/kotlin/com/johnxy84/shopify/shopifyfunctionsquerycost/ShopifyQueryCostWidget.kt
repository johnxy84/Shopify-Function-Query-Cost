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
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.ide.DataManager
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Color
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import java.awt.Point

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
    private val debounceTimer = Timer(300) { updateNow() }.apply { isRepeats = false }
    private val inputQueryIndex = project.getService(ShopifyInputQueryIndexService::class.java)
    private val connection: MessageBusConnection = project.messageBus.connect(this)
    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    showBreakdownPopup(e)
                }
            }
        })

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
        if (debounceTimer.isRunning) {
            debounceTimer.restart()
        } else {
            debounceTimer.start()
        }
    }

    private fun updateNow() {
        if (project.isDisposed) return
        val analysis = computeAnalysis()

        if (analysis == null) {
            label.text = "No Shopify input query active"
            label.toolTipText = null
            label.foreground = JBColor.foreground()
            notifyStatusBar()
            return
        }

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

    private fun computeAnalysis(): QueryAnalysis? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
        if (editor == null || file == null || !inputQueryIndex.isInputQueryFile(file)) {
            return null
        }

        return ShopifyQueryAnalyzer.analyze(editor.document.text)
    }

    private fun showBreakdownPopup(e: MouseEvent?) {
        val analysis = computeAnalysis()
        val popupFactory = JBPopupFactory.getInstance()
        val popup = if (analysis == null) {
            createMessagePopup(popupFactory, "No Shopify input query active", false)
        } else if (analysis.cost == null) {
            createMessagePopup(
                popupFactory,
                "Parse error: ${analysis.parseError ?: "Unknown error"}",
                true
            )
        } else {
            val root = analysis.breakdown ?: QueryBreakdownNode("Query", 0, emptyList())
            val treeRoot = buildTreeNode(root) ?: DefaultMutableTreeNode("Query")
            val treeModel = DefaultTreeModel(treeRoot)
            val tree = SimpleTree(treeModel).apply {
                isRootVisible = true
            }
            TreeUtil.expandAll(tree)

            val header = JBLabel("Total cost: ${analysis.cost}").apply {
                border = JBUI.Borders.empty(6, 8)
            }
            val panel = JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(JBScrollPane(tree), BorderLayout.CENTER)
                border = JBUI.Borders.empty(4)
                preferredSize = JBUI.size(320, 240)
            }

            popupFactory
                .createComponentPopupBuilder(panel, tree)
                .setResizable(true)
                .setMovable(true)
                .createPopup()
        }

        if (label.isShowing) {
            val screenPoint = label.locationOnScreen
            val popupSize = popup.content.preferredSize
            var x = screenPoint.x
            var y = screenPoint.y - popupSize.height
            if (y < 0) {
                y = screenPoint.y + label.height
            }
            popup.showInScreenCoordinates(label, Point(x, y))
        } else if (e != null) {
            popup.show(RelativePoint(e))
        } else {
            val dataContext = DataManager.getInstance().getDataContext(label)
            popup.showInBestPositionFor(dataContext)
        }
    }

    private fun createMessagePopup(factory: JBPopupFactory, message: String, resizable: Boolean): JBPopup {
        return factory
            .createComponentPopupBuilder(
                JBLabel(message).apply {
                    border = JBUI.Borders.empty(8)
                },
                null
            )
            .setResizable(resizable)
            .createPopup()
    }

    private fun buildTreeNode(node: QueryBreakdownNode): DefaultMutableTreeNode? {
        val childNodes = node.children.mapNotNull { buildTreeNode(it) }
        val shouldInclude = node.cost > 0 || childNodes.isNotEmpty()
        if (!shouldInclude) return null

        val labelText = if (node.cost > 0) {
            "${node.label} (cost: ${node.cost})"
        } else {
            node.label
        }
        val treeNode = DefaultMutableTreeNode(labelText)
        childNodes.forEach { treeNode.add(it) }
        return treeNode
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
