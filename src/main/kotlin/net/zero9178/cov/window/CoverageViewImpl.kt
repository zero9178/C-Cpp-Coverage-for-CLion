package net.zero9178.cov.window

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.dualView.TreeTableView
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.UIUtil
import net.zero9178.cov.data.CoverageFileData
import net.zero9178.cov.data.CoverageFunctionData
import net.zero9178.cov.data.FunctionLineData
import net.zero9178.cov.data.FunctionRegionData
import net.zero9178.cov.editor.CoverageHighlighter
import java.awt.Component
import java.awt.Graphics
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

class CoverageViewImpl(project: Project) : CoverageView() {

    override fun createUIComponents() {
        m_treeTableView =
            TreeTableView(ListTreeTableModelOnColumns(DefaultMutableTreeNode("empty-root"), getColumnInfo()))
    }

    init {
        m_clear.addActionListener {
            CoverageHighlighter.getInstance(project).setCoverageData(null)
            setRoot(null)
        }
    }

    override fun setRoot(treeNode: DefaultMutableTreeNode?) {
        m_treeTableView.setModel(
            ListTreeTableModelOnColumns(
                treeNode ?: DefaultMutableTreeNode("empty-root"),
                getColumnInfo()
            )
        )
        m_treeTableView.setRootVisible(false)
        m_treeTableView.setMinRowHeight((m_treeTableView.font.size * 1.75).toInt())
    }
}

private class CoverageBar : JPanel() {

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        font = UIUtil.getLabelFont()
    }

    private var myText: String = ""

    private fun updateText() {
        myText = if (myMax != 0L) (100.0 * myCurrent / myMax).toInt().toString() + "%" else "100%"
        toolTipText = "$myCurrent/$myMax"
    }

    private var myMax: Long = 100

    var max: Long
        get() = myMax
        set(value) {
            myMax = value
            updateText()
        }

    private var myCurrent: Long = 0

    var current: Long
        get() = myCurrent
        set(value) {
            myCurrent = value
            updateText()
        }

    override fun paintComponent(g: Graphics?) {
        super.paintComponent(g)
        g ?: return

        val globalOrDefaultColorScheme = EditorColorsUtil.getGlobalOrDefaultColorScheme()
        g.color = globalOrDefaultColorScheme.getAttributes(CodeInsightColors.LINE_NONE_COVERAGE).foregroundColor
        val insets = border.getBorderInsets(this)
        g.fillRect(insets.left, insets.top, width - insets.right - insets.left, height - insets.bottom - insets.top)
        g.color = globalOrDefaultColorScheme.getAttributes(CodeInsightColors.LINE_FULL_COVERAGE).foregroundColor
        g.fillRect(
            insets.left,
            insets.top,
            if (max != 0L) ((width - insets.right - insets.left) * current.toDouble() / max).toInt() else width - insets.right - insets.left,
            height - insets.bottom - insets.top
        )
        val textWidth = g.fontMetrics.stringWidth(myText)
        val textHeight = g.fontMetrics.height
        g.color = JBColor.BLACK
        g.drawString(
            myText,
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

    override fun getComparator(): Comparator<DefaultMutableTreeNode>? {
        return Comparator { o1, o2 ->
            o1!!
            o2!!
            (currFunc(o1) / maxFunc(o1).toDouble()).compareTo(
                currFunc(o2) / maxFunc(o2).toDouble()
            )
        }
    }
}

private fun getColumnInfo(): Array<ColumnInfo<*, *>> {

    return arrayOf(
        TreeColumnInfo("File/Function"),
        ProgressBarColumn("Branch coverage", {
            100
        }) {
            fun fromFunctionData(it: CoverageFunctionData): Long {
                if (it.branches.isEmpty()) {
                    return 100
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

            when (val userObject = it.userObject) {
                is CoverageFunctionData -> fromFunctionData(userObject)
                is CoverageFileData -> {
                    if (userObject.functions.isEmpty()) {
                        100L
                    } else {
                        userObject.functions.values.map(::fromFunctionData).average().toLong()
                    }

                }
                else -> 0
            }
        },
        ProgressBarColumn("Line/Region coverage", {
            fun fromFunctionData(it: CoverageFunctionData): Long {
                return when (it.coverage) {
                    is FunctionLineData -> it.coverage.data.size.toLong()
                    is FunctionRegionData -> it.coverage.data.size.toLong()
                }
            }

            when (val userObject = it.userObject) {
                is CoverageFunctionData -> fromFunctionData(userObject)
                is CoverageFileData -> {
                    userObject.functions.values.map(::fromFunctionData).sum()
                }
                else -> 0L
            }
        }) {
            fun fromFunctionData(it: CoverageFunctionData): Long {
                return when (it.coverage) {
                    is FunctionLineData -> it.coverage.data.count { entry -> entry.value > 0 }.toLong()
                    is FunctionRegionData -> it.coverage.data.count { region -> region.executionCount > 0 }.toLong()
                }
            }

            when (val userObject = it.userObject) {
                is CoverageFunctionData -> fromFunctionData(userObject)
                is CoverageFileData -> {
                    userObject.functions.values.map(::fromFunctionData).sum()
                }
                else -> 0
            }
        }
    )
}