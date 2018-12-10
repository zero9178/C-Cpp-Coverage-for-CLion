package gcov.lineMarker

import gcov.Data.CoverageData
import gcov.State.EditorState
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import icons.CoverageIcons

class CoverageLineMarker : LineMarkerProvider {

    override fun getLineMarkerInfo(psiElement: PsiElement): LineMarkerInfo<*>? {
        if (!EditorState.getInstance(psiElement.project).showInEditor || psiElement.firstChild != null || !psiElement.text.contains("\n")) {
            return null
        }

        val gatherer = CoverageData.getInstance(psiElement.project) ?: return null

        val containingFile = psiElement.containingFile
        val path = containingFile.virtualFile.canonicalPath
        if (path == null || psiElement.project.basePath == null || !path.startsWith(psiElement.project.basePath!!)) {
            return null
        }
        val fileData = gatherer.getCoverageFromPath(path) ?: return null

        val fileViewProvider = containingFile.viewProvider
        val document = fileViewProvider.document ?: return null
        val textOffset = psiElement.textOffset
        val lineNumber = document.getLineNumber(textOffset) + 1
        val lineData = fileData.getLineDataAt(lineNumber) ?: return null

        return LineMarkerInfo(psiElement, TextRange(textOffset, textOffset), if (lineData.isCovered) CoverageIcons.LINE_COVERED else CoverageIcons.LINE_NOT_COVERED,
                0, { "Executed " + lineData.executionCount + " times" }, null, GutterIconRenderer.Alignment.LEFT)
    }

    override fun collectSlowLineMarkers(list: List<PsiElement>, collection: Collection<LineMarkerInfo<*>>) {}
}
