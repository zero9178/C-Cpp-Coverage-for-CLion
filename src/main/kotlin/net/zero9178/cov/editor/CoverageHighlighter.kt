package net.zero9178.cov.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsUtil
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.IconUtil
import net.zero9178.cov.data.CoverageData
import net.zero9178.cov.data.FunctionLineData
import net.zero9178.cov.data.FunctionRegionData
import java.awt.*

class CoverageHighlighter(private val myProject: Project) : Disposable {

    companion object {
        fun getInstance(project: Project) = project.service<CoverageHighlighter>()
    }

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project !== myProject) {
                    return
                }
                applyOnEditor(editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project !== myProject) {
                    return
                }
                removeFromEditor(editor)
            }
        }, this)
    }

    private fun applyOnEditor(editor: Editor) {
        val markupModel = editor.markupModel
        val colorScheme = EditorColorsUtil.getGlobalOrDefaultColorScheme()
        val vs = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = vs.path
        val info = myHighlighting[path] ?: return
        val ranges = myActiveHighlighting.getOrPut(editor) { mutableListOf() }
        for ((start, end, covered) in info.highLightedLines) {
            val colour =
                colorScheme.getAttributes(
                    if (covered)
                        CodeInsightColors.LINE_FULL_COVERAGE
                    else CodeInsightColors.LINE_NONE_COVERAGE
                ).foregroundColor
            ranges += markupModel.addRangeHighlighter(
                editor.logicalPositionToOffset(start),
                editor.logicalPositionToOffset(end),
                HighlighterLayer.SELECTION - 1,
                TextAttributes(null, colour, null, EffectType.SEARCH_MATCH, Font.PLAIN),
                HighlighterTargetArea.EXACT_RANGE
            )
        }

        class MyEditorCustomElementRenderer(val fullCoverage: Boolean) : EditorCustomElementRenderer {
            override fun paint(
                inlay: Inlay<*>,
                g: Graphics,
                targetRegion: Rectangle,
                textAttributes: TextAttributes
            ) {
                val margin = 1
                val icon = IconUtil.toSize(
                    if (fullCoverage) AllIcons.Actions.Commit else AllIcons.General.Error,
                    targetRegion.height - 2 * margin,
                    targetRegion.height - 2 * margin
                )

                val graphics = g.create() as Graphics2D
                graphics.composite = AlphaComposite.SrcAtop.derive(1.0f)
                icon.paintIcon(editor.component, graphics, targetRegion.x + margin, targetRegion.y + margin)
                graphics.dispose()
            }

            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                return calcHeightInPixels(inlay)
            }
        }

        myActiveInlays.getOrPut(editor) { mutableListOf() } += info.branchInfo.map { (startPos, steppedIn, skipped) ->
            editor.inlayModel.addInlineElement(
                editor.logicalPositionToOffset(startPos),
                MyEditorCustomElementRenderer(steppedIn && skipped)
            )
        }
    }

    private fun removeFromEditor(editor: Editor) {
        myActiveInlays.remove(editor)?.apply {
            this.filterNotNull().forEach {
                Disposer.dispose(it)
            }
        }
        val markupModel = editor.markupModel as? MarkupModelEx ?: return
        myActiveHighlighting.remove(editor)?.apply {
            this.filter {
                markupModel.containsHighlighter(it)
            }.forEach {
                markupModel.removeHighlighter(it)
            }
        }
    }

    private data class HighLightInfo(
        val highLightedLines: List<Triple<LogicalPosition, LogicalPosition, Boolean>>,
        val branchInfo: List<Triple<LogicalPosition, Boolean, Boolean>>
    )

    private val myActiveInlays: MutableMap<Editor, MutableList<Inlay<*>?>> = mutableMapOf()
    private val myActiveHighlighting: MutableMap<Editor, MutableList<RangeHighlighter>> = mutableMapOf()
    private var myHighlighting: Map<String, HighLightInfo> = mapOf()

    fun setCoverageData(coverageData: CoverageData?) {
        myHighlighting = mapOf()
        myActiveHighlighting.keys.union(myActiveInlays.keys).forEach(::removeFromEditor)
        myActiveInlays.clear()
        myActiveHighlighting.clear()
        if (coverageData == null) {
            return
        }
        myHighlighting = coverageData.files.mapValues { (_, file) ->
            val lines = file.functions.values.flatMap { functionData ->
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
            }
            val branches = file.functions.values.flatMap { functionData ->
                functionData.branches.map {
                    Triple(
                        LogicalPosition(it.startPos.first - 1, it.startPos.second - 1),
                        it.steppedInCount != 0,
                        it.skippedCount != 0
                    )
                }
            }.distinctBy { it.first }
            HighLightInfo(lines, branches)
        }
        EditorFactory.getInstance().allEditors.forEach {
            applyOnEditor(it)
        }
    }

    override fun dispose() {}
}