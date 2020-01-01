package net.zero9178.cov.window

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CoverageWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val coverageView = CoverageView.getInstance(project)
        toolWindow.contentManager.addContent(
            ContentFactory.SERVICE.getInstance().createContent(
                coverageView.panel,
                "",
                false
            )
        )
    }
}