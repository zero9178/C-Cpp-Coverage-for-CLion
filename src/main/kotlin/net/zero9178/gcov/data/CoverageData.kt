package net.zero9178.gcov.data

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import org.jetbrains.annotations.Contract
import java.nio.file.Paths
import java.util.*
import javax.swing.SwingUtilities

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

        if(!SwingUtilities.isEventDispatchThread()) {
            return
        }
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

    companion object {

        fun getInstance(project: Project) = project.getComponent<CoverageData>(CoverageData::class.java)!!
    }
}
