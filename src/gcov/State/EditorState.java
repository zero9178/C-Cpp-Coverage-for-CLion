package gcov.State;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "GCoverageShowInEditor")
public class EditorState implements PersistentStateComponent<EditorState> {

    public boolean showInEditor = true;

    public static EditorState getInstance(@NotNull Project project) {
        return ServiceManager.getService(project,EditorState.class);
    }

    @Nullable
    @Override
    public EditorState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull EditorState editorState) {
        XmlSerializerUtil.copyBean(editorState,this);
    }
}
