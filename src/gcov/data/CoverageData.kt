package gcov.data

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import gcov.state.ShowNonProjectSourcesState
import gcov.window.CoverageTree
import org.jetbrains.annotations.Contract
import java.nio.file.Paths
import java.util.*
import javax.swing.tree.DefaultMutableTreeNode

class CoverageData(val project: Project) {

    private val myData = TreeMap<String, CoverageFileData>()

    val coverageData: Map<String, CoverageFileData>
        @Contract(pure = true)
        get() = myData

    fun setCoverageForPath(path: String, fileData: CoverageFileData) {
        myData[path] = fileData
    }

    fun getCoverageFromPath(path: String): CoverageFileData? = myData[path]

    private fun restartDaemonForFile(file: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(Paths.get(file).toFile()) ?: return

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
    }

    fun clearCoverage() {
        val files = ArrayList(myData.keys)
        myData.clear()

        val basePath = project.basePath
        for (file in files) {
            if (basePath != null && !file.startsWith(basePath)) {
                continue
            }
            restartDaemonForFile(file)
        }
    }

    fun updateEditor() {
        val basePath = project.basePath
        for ((key, _) in myData) {
            if (basePath != null && !key.startsWith(basePath)) {
                continue
            }
            restartDaemonForFile(key)
        }
    }

    fun display(tree: CoverageTree) {
        if (myData.isEmpty()) {
            tree.emptyText.text = "No coverage data found. Did you compile with \"--coverage\"?"
            return
        }

        val root = DefaultMutableTreeNode("invisibile-root")
        val basePath = this.project.basePath
        for ((key, value) in myData) {
            if (!ShowNonProjectSourcesState.getInstance(this.project).showNonProjectSources && (basePath == null || !key.startsWith(basePath))) {
                continue
            }

            val fileNode = object : DefaultMutableTreeNode(value) {
                override fun toString(): String {
                    val uObject = userObject ?: return ""
                    return if (uObject !is CoverageFileData) {
                        uObject.toString()
                    } else run {
                        if (basePath == null || ShowNonProjectSourcesState.getInstance(project).showNonProjectSources) {
                            uObject.filePath
                        } else {
                            uObject.filePath.substring(basePath.length + 1)
                        }
                    }
                }
            }

            root.add(fileNode)
            for (functionValue in value.functionData.values) {
                fileNode.add(DefaultMutableTreeNode(functionValue))
            }
        }

        tree.setModel(ListTreeTableModelOnColumns(root, CoverageTree.columnInfo))
        tree.setRootVisible(false)
        updateEditor()
    }

    companion object {

        fun getInstance(project: Project) = project.getComponent<CoverageData>(CoverageData::class.java)!!
    }
}
