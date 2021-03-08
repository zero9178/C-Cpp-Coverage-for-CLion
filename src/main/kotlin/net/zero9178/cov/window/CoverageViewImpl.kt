package net.zero9178.cov.window

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SpeedSearchComparator
import com.intellij.ui.TreeTableSpeedSearch
import com.intellij.ui.dualView.TreeTableView
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import net.zero9178.cov.data.CoverageFileData
import net.zero9178.cov.data.CoverageFunctionData
import net.zero9178.cov.data.FunctionLineData
import net.zero9178.cov.data.FunctionRegionData
import net.zero9178.cov.editor.CoverageHighlighter
import net.zero9178.cov.settings.CoverageGeneratorSettings
import java.awt.Component
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseEvent.BUTTON1
import java.nio.file.Paths
import javax.swing.*
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

class CoverageViewImpl(val project: Project) : CoverageView() {

    private fun sort(
        ascending: Boolean,
        fileComparator: (CoverageFileData, CoverageFileData) -> Int,
        functionComparator: (CoverageFunctionData, CoverageFunctionData) -> Int
    ) =
        TreeUtil.sort(myTreeTableView.tableModel as DefaultTreeModel) { lhs: Any, rhs: Any ->
            lhs as DefaultMutableTreeNode
            rhs as DefaultMutableTreeNode
            val lhsUS = lhs.userObject
            val rhsUS = rhs.userObject
            if (lhsUS is CoverageFileData && rhsUS is CoverageFileData) {
                if (ascending) {
                    fileComparator(lhsUS, rhsUS)
                } else {
                    fileComparator(rhsUS, lhsUS)
                }
            } else if (lhsUS is CoverageFunctionData && rhsUS is CoverageFunctionData) {
                if (ascending) {
                    functionComparator(lhsUS, rhsUS)
                } else {
                    functionComparator(rhsUS, lhsUS)
                }
            } else {
                0
            }
        }

    private class FunctionComparator(private val col: Int) : (CoverageFunctionData, CoverageFunctionData) -> Int {
        override operator fun invoke(lhs: CoverageFunctionData, rhs: CoverageFunctionData) =
            when (col) {
                0 -> lhs.functionName.compareTo(rhs.functionName)
                1 -> getBranchCoverage(lhs).compareTo(getBranchCoverage(rhs))
                else -> (getCurrentLineCoverage(lhs).toDouble() / getMaxLineCoverage(lhs)).compareTo(
                    (getCurrentLineCoverage(
                        rhs
                    ).toDouble() / getMaxLineCoverage(rhs))
                )
            }
    }

    private class FileComparator(private val col: Int) : (CoverageFileData, CoverageFileData) -> Int {
        override fun invoke(lhs: CoverageFileData, rhs: CoverageFileData) = when (col) {
            0 -> lhs.filePath.compareTo(rhs.filePath)
            1 -> getBranchCoverage(lhs).compareTo(getBranchCoverage(rhs))
            else -> (getCurrentLineCoverage(lhs).toDouble() / getMaxLineCoverage(lhs)).compareTo(
                (getCurrentLineCoverage(
                    rhs
                ).toDouble() / getMaxLineCoverage(rhs))
            )
        }
    }

    private var mySorting = MutableList(myTreeTableView.columnCount) {
        SortOrder.UNSORTED
    }

    override fun createUIComponents() {
        myTreeTableView =
            TreeTableView(ListTreeTableModelOnColumns(DefaultMutableTreeNode("empty-root"), getColumnInfo(true)))
        myTreeTableView.rowSelectionAllowed = true
        myTreeTableView.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                e ?: return
                if (e.clickCount != 2 || e.button != BUTTON1) {
                    return
                }

                val selRow = myTreeTableView.rowAtPoint(e.point)
                val selColumn = myTreeTableView.columnAtPoint(e.point)
                if (selRow < 0 || selColumn != 0) {
                    return
                }

                val node = myTreeTableView.getValueAt(selRow, selColumn) as DefaultMutableTreeNode
                when (val data = node.userObject) {
                    is CoverageFileData -> {
                        val file = VfsUtil.findFileByIoFile(Paths.get(data.filePath).toFile(), true) ?: return

                        FileEditorManager.getInstance(project).openEditor(OpenFileDescriptor(project, file), true)
                    }
                    is CoverageFunctionData -> {
                        val fileData = (node.parent as DefaultMutableTreeNode).userObject as CoverageFileData
                        val file = VfsUtil.findFileByIoFile(Paths.get(fileData.filePath).toFile(), true) ?: return

                        FileEditorManager.getInstance(project)
                            .openEditor(
                                OpenFileDescriptor(
                                    project,
                                    file,
                                    data.startPos.first - 1,
                                    data.startPos.second
                                ), true
                            )
                    }
                }
            }
        })
        myTreeTableView.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                e ?: return
                if (e.button != BUTTON1) {
                    return
                }
                myTreeTableView.clearSelection()
                val col = myTreeTableView.columnAtPoint(e.point)
                if (col > mySorting.lastIndex) {
                    return
                }

                mySorting[col] = when (mySorting[col]) {
                    SortOrder.ASCENDING -> {
                        sort(false, FileComparator(col), FunctionComparator(col))
                        SortOrder.DESCENDING
                    }
                    SortOrder.DESCENDING, SortOrder.UNSORTED -> {
                        sort(true, FileComparator(col), FunctionComparator(col))
                        SortOrder.ASCENDING
                    }
                }
                for (i in 0..mySorting.lastIndex) {
                    if (i == col) {
                        continue
                    } else {
                        mySorting[i] = SortOrder.UNSORTED
                    }
                }
                myTreeTableView.updateUI()
            }
        })
        val defaultRenderer = myTreeTableView.tableHeader.defaultRenderer
        myTreeTableView.tableHeader.defaultRenderer =
            TableCellRenderer { table, value, isSelected, hasFocus, row, column ->
                val c = defaultRenderer.getTableCellRendererComponent(
                    table,
                    value,
                    isSelected,
                    hasFocus,
                    row,
                    column
                )
                if (column > mySorting.lastIndex || c !is JLabel) {
                    c
                } else {
                    c.icon = when (mySorting[column]) {
                        SortOrder.ASCENDING -> UIManager.getIcon("Table.ascendingSortIcon")
                        SortOrder.DESCENDING -> UIManager.getIcon("Table.descendingSortIcon")
                        SortOrder.UNSORTED -> null
                    }
                    c
                }
            }
        TreeTableSpeedSearch(myTreeTableView).comparator = SpeedSearchComparator(false)
    }

    init {
        myClear.addActionListener {
            CoverageHighlighter.getInstance(project).setCoverageData(null)
            setRoot(
                null,
                CoverageGeneratorSettings.getInstance().branchCoverageEnabled,
                CoverageGeneratorSettings.getInstance().calculateExternalSources
            )
        }
        myIncludeNonProjectSources.addActionListener {
            (myTreeTableView.tableModel as DefaultTreeModel).reload()
        }
    }

    override fun setRoot(treeNode: DefaultMutableTreeNode?, hasBranchCoverage: Boolean, hasExternalSources: Boolean) {
        val list = TreeUtil.collectExpandedUserObjects(myTreeTableView.tree).filterIsInstance<CoverageFileData>()
        myTreeTableView.setModel(
            object : ListTreeTableModelOnColumns(
                treeNode ?: DefaultMutableTreeNode("empty-root"),
                getColumnInfo(hasBranchCoverage)
            ) {
                override fun getChildCount(parent: Any?): Int {
                    return if (myIncludeNonProjectSources.isSelected || parent == null || parent !is DefaultMutableTreeNode || !parent.isRoot) {
                        super.getChildCount(parent)
                    } else {
                        var count = 0
                        for (i in 0 until parent.childCount) {
                            val child = parent.getChildAt(i)
                            if (isProjectSource(child)) {
                                count++
                            }
                        }
                        count
                    }
                }

                override fun getChild(parent: Any?, index: Int): Any {
                    if (myIncludeNonProjectSources.isSelected || parent == null || parent !is DefaultMutableTreeNode || !parent.isRoot) {
                        return super.getChild(parent, index)
                    } else {
                        var count = 0
                        for (i in 0 until parent.childCount) {
                            val child = parent.getChildAt(i)
                            if (isProjectSource(child)) {
                                if (count == index) {
                                    return child
                                }
                                count++
                            }
                        }
                        return super.getChild(parent, index)
                    }
                }

                private fun isProjectSource(child: TreeNode): Boolean {
                    if (child !is DefaultMutableTreeNode) {
                        return true
                    }
                    val coverageFileData = child.userObject as? CoverageFileData ?: return true
                    val vs = LocalFileSystem.getInstance().findFileByNioFile(Paths.get(coverageFileData.filePath))
                        ?: return true
                    val projectSources = CMakeWorkspace.getInstance(project).module?.let { module ->
                        ModuleRootManager.getInstance(module).contentEntries.map {
                            it.sourceFolderFiles.toSet()
                        }.fold(emptySet()) { result, curr ->
                            result.union(curr)
                        }
                    } ?: emptySet<VirtualFile>()
                    return projectSources.contains(vs)
                }
            }
        )
        fun doFunctionSelection(item: DefaultMutableTreeNode) {
            val coverageFunctionData = item.userObject as? CoverageFunctionData ?: return
            val fileData = (item.parent as? DefaultMutableTreeNode)?.userObject as? CoverageFileData
                ?: return
            val vs = LocalFileSystem.getInstance().findFileByNioFile(Paths.get(fileData.filePath))
                ?: return
            val highlighter = CoverageHighlighter.getInstance(project)
            val file = highlighter.highlighting[vs] ?: return
            val group = file[coverageFunctionData.startPos] ?: return
            if (group.functions.size <= 1) {
                return
            }
            highlighter.changeActive(group, coverageFunctionData.functionName)
        }

        myTreeTableView.selectionModel.addListSelectionListener {
            if (myTreeTableView.selectedRowCount != 1) {
                return@addListSelectionListener
            }
            val item =
                myTreeTableView.model.getValueAt(myTreeTableView.selectedRow, 0) as? DefaultMutableTreeNode
                    ?: return@addListSelectionListener
            doFunctionSelection(item)
        }
        TreeUtil.treeNodeTraverser(treeNode).forEach { node ->
            if (node !is DefaultMutableTreeNode) return@forEach
            val file = node.userObject as? CoverageFileData ?: return@forEach
            if (list.any { it.filePath == file.filePath }) {
                myTreeTableView.tree.expandPath(TreeUtil.getPathFromRoot(node))
            }
        }
        if (mySorting.size != myTreeTableView.columnCount) {
            mySorting = MutableList(myTreeTableView.columnCount) {
                SortOrder.UNSORTED
            }
        }
        myTreeTableView.setRootVisible(false)
        myTreeTableView.setMinRowHeight((myTreeTableView.font.size * 1.75).toInt())
        loop@ for (i in 0 until myTreeTableView.columnCount) {
            myTreeTableView.clearSelection()
            when (mySorting[i]) {
                SortOrder.UNSORTED -> continue@loop
                SortOrder.ASCENDING -> {
                    sort(true, FileComparator(i), FunctionComparator(i))
                }
                SortOrder.DESCENDING -> {
                    sort(false, FileComparator(i), FunctionComparator(i))
                }
            }
            myTreeTableView.updateUI()
        }
        myIncludeNonProjectSources.isVisible = hasExternalSources
    }
}

private class CoverageBar : JPanel() {

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        font = UIUtil.getLabelFont()
    }

    var text: String = ""
        private set

    private fun updateText() {
        if (max != 0L) {
            text = (100.0 * current / max).toInt().toString() + "%"
            toolTipText = "$current/$max"
        } else {
            text = "N/A"
            toolTipText = text
        }
    }

    var max: Long = 100
        set(value) {
            field = value
            updateText()
        }

    var current: Long = 0
        set(value) {
            field = value
            updateText()
        }

    override fun paintComponent(g: Graphics?) {
        super.paintComponent(g)
        g ?: return

        val globalOrDefaultColorScheme = EditorColorsUtil.getGlobalOrDefaultColorScheme()

        g.color =
            if (max > 0) globalOrDefaultColorScheme.getAttributes(CodeInsightColors.LINE_NONE_COVERAGE).foregroundColor
            else JBColor.LIGHT_GRAY
        val insets = border.getBorderInsets(this)
        g.fillRect(insets.left, insets.top, width - insets.right - insets.left, height - insets.bottom - insets.top)

        if (max > 0) {
            g.color = globalOrDefaultColorScheme.getAttributes(CodeInsightColors.LINE_FULL_COVERAGE).foregroundColor
            g.fillRect(
                insets.left,
                insets.top,
                if (max != 0L) ((width - insets.right - insets.left) * current.toDouble() / max).toInt() else width - insets.right - insets.left,
                height - insets.bottom - insets.top
            )
        }

        val textWidth = g.fontMetrics.stringWidth(text)
        val textHeight = g.fontMetrics.height
        g.color = JBColor.BLACK
        g.drawString(
            text,
            insets.left + ((width - insets.right - insets.left) / 2 - textWidth / 2),
            insets.bottom + ((height - insets.top - insets.bottom) / 2 + textHeight / 2)
        )
    }
}

private class ProgressBarColumn(
    title: String,
    private val maxFunc: (DefaultMutableTreeNode) -> Long,
    private val currFunc: (DefaultMutableTreeNode) -> Long
) : ColumnInfo<DefaultMutableTreeNode, Component>(title) {
    override fun valueOf(item: DefaultMutableTreeNode?): Component? {
        item ?: return null
        return CoverageBar().apply {
            max = maxFunc(item)
            current = currFunc(item)
        }
    }

    override fun getRenderer(item: DefaultMutableTreeNode?): TableCellRenderer? {
        return TableCellRenderer { _, value, _, _, _, _ -> value as CoverageBar }
    }

    override fun getColumnClass(): Class<*> {
        return DefaultMutableTreeNode::class.java
    }
}

private fun getColumnInfo(hasBranchCoverage: Boolean): Array<ColumnInfo<*, *>> {

    val fileInfo = TreeColumnInfo("File/Function")
    val branchInfo = ProgressBarColumn("Branch coverage", { treeNode ->
        when (val userObject = treeNode.userObject) {
            is CoverageFunctionData -> if (userObject.branches.isEmpty()) 0 else 100
            is CoverageFileData -> if (userObject.functions.values.all { it.branches.isEmpty() }) 0 else 100
            else -> 100
        }
    }) {
        getBranchCoverage(it.userObject)
    }
    val lineInfo = ProgressBarColumn("Line/Region coverage", {
        getMaxLineCoverage(it.userObject)
    }) {
        getCurrentLineCoverage(it.userObject)
    }

    return if (hasBranchCoverage) {
        arrayOf(fileInfo, branchInfo, lineInfo)
    } else {
        arrayOf(fileInfo, lineInfo)
    }
}

private fun getCurrentLineCoverage(functionOrFileData: Any): Long {
    fun fromFunctionData(it: CoverageFunctionData): Long {
        return when (it.coverage) {
            is FunctionLineData -> it.coverage.data.count { entry -> entry.value > 0 }.toLong()
            is FunctionRegionData -> it.coverage.data.filter { region -> region.kind != FunctionRegionData.Region.Kind.Gap }
                .count { region -> region.executionCount > 0 }.toLong()
        }
    }

    return when (functionOrFileData) {
        is CoverageFunctionData -> fromFunctionData(functionOrFileData)
        is CoverageFileData -> {
            functionOrFileData.functions.values.sumOf(::fromFunctionData)
        }
        else -> 0
    }
}

private fun getMaxLineCoverage(functionOrFileData: Any): Long {
    fun fromFunctionData(it: CoverageFunctionData): Long {
        return when (it.coverage) {
            is FunctionLineData -> it.coverage.data.size.toLong()
            is FunctionRegionData -> it.coverage.data.count { region -> region.kind != FunctionRegionData.Region.Kind.Gap }
                .toLong()
        }
    }

    return when (functionOrFileData) {
        is CoverageFunctionData -> fromFunctionData(functionOrFileData)
        is CoverageFileData -> {
            functionOrFileData.functions.values.sumOf(::fromFunctionData)
        }
        else -> 0L
    }
}

private fun getBranchCoverage(functionOrFileData: Any): Long {
    fun fromFunctionData(it: CoverageFunctionData): Long? {
        if (it.branches.isEmpty()) {
            return null
        }
        return it.branches.map { op ->
            when {
                op.skippedCount != 0 && op.steppedInCount != 0 -> 100
                (op.skippedCount != 0 && op.steppedInCount == 0)
                        || (op.skippedCount == 0 && op.steppedInCount != 0) -> 50
                else -> 0
            }
        }.average().toLong()
    }

    return when (functionOrFileData) {
        is CoverageFunctionData -> fromFunctionData(functionOrFileData) ?: 0
        is CoverageFileData -> {
            if (functionOrFileData.functions.isEmpty()) {
                100L
            } else {
                functionOrFileData.functions.values.mapNotNull(::fromFunctionData).average().toLong()
            }

        }
        else -> 0
    }
}