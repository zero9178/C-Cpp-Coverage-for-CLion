package net.zero9178.cov.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider

class CoverageFileAccessProtector(val project: Project) : WritingAccessProvider() {

    companion object {
        val currentlyInCoverage = mutableMapOf<Project, Set<VirtualFile>>()
    }

    override fun getReadOnlyMessage(): String {
        return "Files cannot be edited while gathering coverage"
    }

    override fun requestWriting(files: MutableCollection<out VirtualFile>): MutableCollection<VirtualFile> {
        val inCoverage = currentlyInCoverage[project] ?: return mutableListOf()
        return files.filter {
            inCoverage.contains(it)
        }.toMutableList()
    }

}