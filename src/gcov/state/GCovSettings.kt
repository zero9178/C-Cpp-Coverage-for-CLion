package gcov.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GCoveragePath")
class GCovSettings : PersistentStateComponent<GCovSettings> {

    var gcovPath = ""

    override fun getState(): GCovSettings = this

    override fun loadState(settings: GCovSettings) = XmlSerializerUtil.copyBean(settings,this)

    companion object {

        fun getInstance(project: Project):GCovSettings = ServiceManager.getService(project,GCovSettings::class.java)!!

    }

}