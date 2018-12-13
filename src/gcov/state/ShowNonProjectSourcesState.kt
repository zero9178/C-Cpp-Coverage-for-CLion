package gcov.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GCoverageShowNonProjectSources")
class ShowNonProjectSourcesState : PersistentStateComponent<ShowNonProjectSourcesState> {

    var showNonProjectSources = false

    override fun getState(): ShowNonProjectSourcesState = this

    override fun loadState(showNonProjectSourcesState: ShowNonProjectSourcesState) = XmlSerializerUtil.copyBean(showNonProjectSourcesState, this)

    companion object {

        fun getInstance(project: Project): ShowNonProjectSourcesState = ServiceManager.getService(project, ShowNonProjectSourcesState::class.java)!!
    }

}
