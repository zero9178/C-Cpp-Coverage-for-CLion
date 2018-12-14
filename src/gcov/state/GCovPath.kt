package gcov.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GCoveragePath")
class GCovPath : PersistentStateComponent<GCovPath> {

    var gcovPath = ""

    override fun getState(): GCovPath = this

    override fun loadState(path: GCovPath) = XmlSerializerUtil.copyBean(path,this)

    companion object {

        fun getInstance(project: Project):GCovPath = ServiceManager.getService(project,GCovPath::class.java)!!

    }

}