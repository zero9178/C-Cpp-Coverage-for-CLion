package net.zero9178.cov.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsUtil
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.IconUtil
import com.jetbrains.rd.util.first
import net.zero9178.cov.data.CoverageData
import net.zero9178.cov.data.FunctionLineData
import net.zero9178.cov.data.FunctionRegionData
import net.zero9178.cov.util.ComparablePair
import net.zero9178.cov.util.toCP
import java.awt.*
import java.nio.file.InvalidPathException
import java.nio.file.Paths

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
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
                clear()
                EditorFactory.getInstance().allEditors.forEach(::applyOnEditor)
            })
    }

    private class MyEditorCustomElementRenderer(val editor: Editor, val fullCoverage: Boolean) :
        EditorCustomElementRenderer {
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

    private fun applyOnHighlightFunction(editor: Editor, functionGroup: HighlightFunctionGroup) {
        val rangesInFunction = myActiveHighlighting.getOrPut(editor) { mutableMapOf() }
        val ranges = rangesInFunction.getOrPut(functionGroup) { mutableListOf() }
        val value = functionGroup.functions[functionGroup.active] ?: return
        val colorScheme = EditorColorsUtil.getGlobalOrDefaultColorScheme()
        val markupModel = editor.markupModel as? MarkupModelEx ?: return
        for ((start, end, covered) in value.highlighted) {
            val colour =
                colorScheme.getAttributes(
                    if (covered)
                        CodeInsightColors.LINE_FULL_COVERAGE
                    else CodeInsightColors.LINE_NONE_COVERAGE
                ).foregroundColor
            ranges += markupModel.addRangeHighlighter(
                editor.logicalPositionToOffset(start),
                editor.logicalPositionToOffset(end),
                HighlighterLayer.CARET_ROW + 1,
                TextAttributes(null, colour, null, EffectType.SEARCH_MATCH, Font.PLAIN),
                HighlighterTargetArea.EXACT_RANGE
            )
        }

        myActiveInlays.getOrPut(editor) { mutableMapOf() }.getOrPut(
            functionGroup
        ) { mutableListOf() } += value.branchInfo.map { (startPos, steppedIn, skipped) ->
            editor.inlayModel.addInlineElement(
                editor.logicalPositionToOffset(startPos),
                MyEditorCustomElementRenderer(editor, steppedIn && skipped)
            )
        }
    }

    private fun applyOnEditor(editor: Editor) {
        val vs = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val info = myHighlighting[vs] ?: return

        info.values.forEach {
            applyOnHighlightFunction(editor, it)
        }
    }

    private fun removeFromEditor(editor: Editor) {
        myActiveInlays.remove(editor)?.apply {
            this.values.flatten().filterNotNull().forEach {
                Disposer.dispose(it)
            }
        }
        val markupModel = editor.markupModel as? MarkupModelEx ?: return
        myActiveHighlighting.remove(editor)?.apply {
            this.values.flatten().filter {
                markupModel.containsHighlighter(it)
            }.forEach {
                markupModel.removeHighlighter(it)
            }
        }
    }

    data class HighlightFunctionGroup(
        val region: FunctionRegion,
        val functions: Map<String, HighlightFunction>,
        var active: String
    ) : Disposable {
        var isDisposed = false
            private set

        override fun dispose() {
            isDisposed = true
        }
    }

    data class HighlightFunction(
        val highlighted: List<Region>,
        val branchInfo: List<Triple<LogicalPosition, Boolean, Boolean>>
    )

    private val myActiveInlays: MutableMap<Editor, MutableMap<HighlightFunctionGroup, MutableList<Inlay<*>?>>> =
        mutableMapOf()
    private val myActiveHighlighting: MutableMap<Editor, MutableMap<HighlightFunctionGroup, MutableList<RangeHighlighter>>> =
        mutableMapOf()

    private var myHighlighting: Map<VirtualFile, Map<ComparablePair<Int, Int>, HighlightFunctionGroup>> = mapOf()

    val highlighting
        get() = synchronized(this) {
            myHighlighting
        }

    fun changeActive(group: HighlightFunctionGroup, active: String) {
        assert(group.functions.contains(active))
        fun changeActiveImpl(editor: Editor?, group: HighlightFunctionGroup, active: String?) {

            val openEditor = editor ?: myActiveHighlighting.entries.find {
                it.value.contains(group)
            }?.key
            if (openEditor != null) {
                myActiveInlays[openEditor]?.remove(group)?.filterNotNull()?.forEach {
                    Disposer.dispose(it)
                }
                val markupModel = openEditor.markupModel as? MarkupModelEx ?: return
                myActiveHighlighting[openEditor]?.remove(group)?.filter {
                    markupModel.containsHighlighter(it)
                }?.forEach {
                    markupModel.removeHighlighter(it)
                }
            }

            if (active != null) {
                synchronized(this) {
                    group.active = active
                }
            }
            if (openEditor != null) {
                applyOnHighlightFunction(openEditor, group)
                if (active != null) {
                    val vs = FileDocumentManager.getInstance().getFile(openEditor.document) ?: return
                    myHighlighting[vs]?.values?.filter {
                        group.region.first < it.region.first && group.region.second > it.region.second
                    }?.forEach {
                        changeActiveImpl(openEditor, it, null)
                    }
                }
            }
        }
        changeActiveImpl(null, group, active)
    }

    fun setCoverageData(coverageData: CoverageData?) {
        synchronized(this) {
            myHighlighting.values.forEach {
                it.values.forEach { functionGroup ->
                    Disposer.dispose(functionGroup)
                }
            }
            myHighlighting = mapOf()
            clear()
            if (coverageData == null) {
                myProject.putUserData(DUPLICATE_FUNCTION_GROUP, mutableSetOf())
                DaemonCodeAnalyzer.getInstance(myProject).restart()
                return
            }
            myHighlighting = coverageData.files.mapValues { (_, file) ->
                file.functions.values.groupBy {
                    it.startPos toCP it.endPos
                }.map { entry ->
                    val functions = entry.value.associate { functionData ->
                        val highlighting = when (functionData.coverage) {
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
                        val branches = functionData.branches.map {
                            Triple(
                                LogicalPosition(it.startPos.first - 1, it.startPos.second - 1),
                                it.steppedInCount != 0,
                                it.skippedCount != 0
                            )
                        }
                        functionData.functionName to HighlightFunction(highlighting, branches)
                    }
                    entry.key.first to HighlightFunctionGroup(entry.key, functions, functions.first().key)
                }.toMap()
            }.mapNotNull {
                try {
                    LocalFileSystem.getInstance().findFileByNioFile(Paths.get(it.key))?.let { vs ->
                        vs to it.value
                    }
                } catch (e: InvalidPathException) {
                    null
                }
            }.toMap()
            EditorFactory.getInstance().allEditors.forEach(::applyOnEditor)
            myProject.putUserData(DUPLICATE_FUNCTION_GROUP, mutableSetOf())
            DaemonCodeAnalyzer.getInstance(myProject).restart()
        }
    }

    private fun clear() {
        myActiveHighlighting.keys.union(myActiveInlays.keys).forEach(::removeFromEditor)
        myActiveInlays.clear()
        myActiveHighlighting.clear()
    }

    override fun dispose() {}
}

typealias Region = Triple<LogicalPosition, LogicalPosition, Boolean>

typealias FunctionRegion = ComparablePair<ComparablePair<Int, Int>, ComparablePair<Int, Int>>