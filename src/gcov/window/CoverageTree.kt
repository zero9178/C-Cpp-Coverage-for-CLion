package gcov.window

import gcov.data.CoverageFileData
import gcov.data.CoverageFunctionData
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dualView.TreeTableView
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import org.jetbrains.annotations.Contract

import javax.swing.*
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.io.File

/**
 * Class acting as the model for the treeTable inside the GCovWindowFactory tool window
 */
class CoverageTree(root: DefaultMutableTreeNode) : TreeTableView(ListTreeTableModelOnColumns(root, columnInfo)) {

    /**
     * Resets the model to an empty state
     */
    fun resetModel() {
        setModel(ListTreeTableModelOnColumns(DefaultMutableTreeNode("empty-root"), columnInfo))
        setRootVisible(false)
        emptyText.text = "Nothing to show"
    }

    /**
     * Handles mouse input to jump to functions and files
     */
    class TreeMouseHandler internal constructor(private val m_project: Project, private val m_tree: TreeTable) : MouseListener {

        override fun mouseClicked(e: MouseEvent) {
            if (m_project.isDisposed) {
                m_tree.removeMouseListener(this)
                return
            }

            if (e.clickCount != 2) {
                return
            }

            val selRow = m_tree.rowAtPoint(e.point)
            val selColumn = m_tree.columnAtPoint(e.point)
            if (selRow < 0 || selColumn != 0) {
                return
            }
            val data = (m_tree.getValueAt(selRow, selColumn) as DefaultMutableTreeNode).userObject

            if (data is CoverageFileData) {
                val file = VfsUtil.findFileByIoFile(File(data.filePath), true)
                if (file == null || file.fileType.isBinary) {
                    return
                }

                FileEditorManager.getInstance(m_project).openEditor(OpenFileDescriptor(m_project, file), true)
            } else if (data is CoverageFunctionData) {
                val fileData = data.fileData ?: return
                val file = VfsUtil.findFileByIoFile(File(fileData.filePath), true)
                if (file == null || file.fileType.isBinary) {
                    return
                }

                FileEditorManager.getInstance(m_project)
                        .openEditor(OpenFileDescriptor(m_project, file,
                                data.startLine - 1, 0), true)
            }
        }

        override fun mousePressed(e: MouseEvent) {

        }

        override fun mouseReleased(e: MouseEvent) {

        }

        override fun mouseEntered(e: MouseEvent) {

        }

        override fun mouseExited(e: MouseEvent) {

        }
    }

    companion object {

        val columnInfo: Array<ColumnInfo<*, *>>
            @Contract(pure = true)
            get() = arrayOf(TreeColumnInfo("Function/File"), object : ColumnInfo<Any, Component>("Coverage") {
                override fun valueOf(o: Any): Component {
                    if(o !is DefaultMutableTreeNode) {
                        throw NotImplementedError()
                    }
                    val bar = JProgressBar()
                    bar.isStringPainted = true
                    bar.isOpaque = true
                    val userObject = o.userObject
                    if (userObject is CoverageFileData) {
                        bar.minimum = 0
                        bar.maximum = 100
                        val (coverage,lineCount,coveredLineCount) = userObject.functionData.values.fold(Triple(0.0,0,0)) {
                            acc: Triple<Double, Int, Int>, functionData: CoverageFunctionData ->
                            if(functionData.lines.isEmpty()) {
                                acc
                            } else {
                                Triple(acc.first + functionData.coverage / functionData.lines.size.toDouble(),
                                        acc.second + functionData.lines.size,acc.third + functionData.coverage)
                            }
                        }

                        bar.value = (100 * coverage / userObject.functionData.size).toInt()
                        bar.toolTipText = coveredLineCount.toString() + "/" + lineCount + " covered"
                    } else if (userObject is CoverageFunctionData) {
                        bar.minimum = 0
                        bar.maximum = if (userObject.lines.isEmpty()) 1 else userObject.lines.size
                        bar.value = userObject.coverage
                        bar.toolTipText = bar.value.toString() + "/" + userObject.lines.size + " covered"
                    }
                    return bar
                }

                override fun getRenderer(o: Any?): TableCellRenderer {
                    return TableCellRenderer { _, value, _, _, _, _ -> value as JProgressBar }
                }
            })
    }

}
