package GCov.State;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "GCoverageShowNonProjectSources")
public class ShowNonProjectSourcesState implements PersistentStateComponent<ShowNonProjectSourcesState> {

    public boolean showNonProjectSources = false;

    public static ShowNonProjectSourcesState getInstance(@NotNull Project project) {
        return ServiceManager.getService(project,ShowNonProjectSourcesState.class);
    }

    @Nullable
    @Override
    public ShowNonProjectSourcesState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull ShowNonProjectSourcesState showNonProjectSourcesState) {
        XmlSerializerUtil.copyBean(showNonProjectSourcesState,this);
    }

}
