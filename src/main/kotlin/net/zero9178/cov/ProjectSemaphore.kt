package net.zero9178.cov

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.Semaphore

class ProjectSemaphore {
    val semaphore = Semaphore(1, true)

    companion object {
        fun getInstance(project: Project) = project.service<ProjectSemaphore>()
    }
}