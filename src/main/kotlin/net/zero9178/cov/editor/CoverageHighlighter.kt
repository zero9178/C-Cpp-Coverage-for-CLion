package net.zero9178.cov.editor

import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.hints.presentation.IconPresentation
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsUtil
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import net.zero9178.cov.data.CoverageData
import net.zero9178.cov.data.FunctionLineData
import net.zero9178.cov.data.FunctionRegionData
import java.awt.Font

class CoverageHighlighter(private val myProject: Project) {
    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project !== myProject) {
                    return
                }
                applyOnEditor(editor)
            }
        }, myProject)
    }

    private fun applyOnEditor(editor: Editor) {
        val highlightManager = HighlightManager.getInstance(myProject)
        val colorScheme = EditorColorsUtil.getGlobalOrDefaultColorScheme()
        val vs = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = vs.path
        val info = myHighlighting[path] ?: return
        val ranges = myActiveHighlighting.getOrPut(path) { mutableListOf() }
        for ((start, end, covered) in info.highLightedLines) {
            val colour =
                colorScheme.getAttributes(if (covered) CodeInsightColors.LINE_FULL_COVERAGE else CodeInsightColors.LINE_NONE_COVERAGE)
                    .foregroundColor
            highlightManager.addRangeHighlight(
                editor,
                editor.logicalPositionToOffset(start),
                editor.logicalPositionToOffset(end),
                TextAttributes(null, colour, null, EffectType.SEARCH_MATCH, Font.PLAIN),
                false,
                ranges
            )
        }
        myActiveInlays.plusAssign(info.branchInfo.map { (startPos, steppedIn, skipped) ->
            editor.inlayModel.addInlineElement(
                editor.logicalPositionToOffset(startPos),
                PresentationRenderer(
                    IconPresentation(
                        if (steppedIn && skipped) AllIcons.Actions.Commit else AllIcons.General.Error,
                        editor.contentComponent
                    )
                )
            )
        })
    }

    private data class HighLightInfo(
        val highLightedLines: List<Triple<LogicalPosition, LogicalPosition, Boolean>>,
        val branchInfo: List<Triple<LogicalPosition, Boolean, Boolean>>
    )

    private var myActiveInlays: MutableList<Inlay<*>?> = mutableListOf()
    private val myActiveHighlighting: MutableMap<String, MutableList<RangeHighlighter>> = mutableMapOf()
    private val myHighlighting: MutableMap<String, HighLightInfo> =
        mutableMapOf()

    fun setCoverageData(coverageData: CoverageData?) {
        myHighlighting.clear()
        val highlightManager = HighlightManager.getInstance(myProject)
        myActiveHighlighting.forEach { (t, u) ->
            val editor = EditorFactory.getInstance().allEditors.find {
                val vs = FileDocumentManager.getInstance().getFile(it.document) ?: return@find false
                val canonicalPath = vs.canonicalPath ?: return@find false
                canonicalPath == t
            } ?: return@forEach
            ApplicationManager.getApplication().invokeLater {
                u.forEach {
                    highlightManager.removeSegmentHighlighter(editor, it)
                }
            }
        }
        val currentInlays = myActiveInlays
        ApplicationManager.getApplication().invokeLater {
            currentInlays.filterNotNull().forEach { it.dispose() }
        }
        myActiveInlays = mutableListOf()
        myActiveHighlighting.clear()
        if (coverageData == null) {
            return
        }
        for ((name, file) in coverageData.files) {
            myHighlighting[name] = HighLightInfo(file.functions.values.flatMap { functionData ->
                when (functionData.coverage) {
                    is FunctionLineData -> functionData.coverage.data.map {
                        Triple(
                            LogicalPosition(it.key - 1, 0),
                            LogicalPosition(it.key, 0),
                            it.value != 0L
                        )
                    }
                    is FunctionRegionData -> functionData.coverage.data.map {
                        Triple(
                            LogicalPosition(it.startPos.first - 1, it.startPos.second - 1),
                            LogicalPosition(it.endPos.first - 1, it.endPos.second - 1),
                            it.executionCount != 0L
                        )
                    }
                }
            }, file.functions.values.flatMap { functionData ->
                functionData.branches.map {
                    Triple(
                        LogicalPosition(it.startPos.first - 1, it.startPos.second - 1),
                        it.steppedInCount != 0,
                        it.skippedCount != 0
                    )
                }
            }.distinctBy { it.first })
        }
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().allEditors.forEach {
                applyOnEditor(it)
            }
        }
    }

    companion object {
        fun getInstance(project: Project) = project.getComponent(CoverageHighlighter::class.java)!!
    }
}