package GCov.LineMarker;

import GCov.Data.GCovCoverageGatherer;
import GCov.State.EditorState;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import icons.CoverageIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class CoverageLineMarker implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo getLineMarkerInfo(@NotNull PsiElement psiElement) {
        if(EditorState.getInstance(psiElement.getProject()).showInEditor && psiElement.getFirstChild() == null && psiElement.getText().contains("\n")) {
            GCovCoverageGatherer gatherer = GCovCoverageGatherer.getInstance(psiElement.getProject());
            PsiFile containingFile = psiElement.getContainingFile();
            String path = containingFile.getVirtualFile().getCanonicalPath();
            if(path == null || psiElement.getProject().getBasePath() == null || !path.startsWith(psiElement.getProject().getBasePath())) {
                return null;
            }
            GCovCoverageGatherer.CoverageFileData fileData = gatherer.getCoverageFromPath(path);
            if(fileData == null) {
                return null;
            }

            FileViewProvider fileViewProvider = containingFile.getViewProvider();
            Document document = fileViewProvider.getDocument();
            if(document == null) {
                return null;
            }
            int textOffset = psiElement.getTextOffset();
            int lineNumber = document.getLineNumber(textOffset)+1;
            GCovCoverageGatherer.CoverageLineData lineData =  fileData.getLineData(lineNumber);
            if(lineData == null) {
                return null;
            }

            return new LineMarkerInfo<>(psiElement, new TextRange(textOffset, textOffset), lineData.isCovered() ? CoverageIcons.LINE_COVERED : CoverageIcons.LINE_NOT_COVERED,
                    0, element -> "Executed "  + String.valueOf(lineData.getExecutionCount()) + " times", null, GutterIconRenderer.Alignment.LEFT);
        }
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<PsiElement> list, @NotNull Collection<LineMarkerInfo> collection) { }
}
