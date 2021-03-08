package net.zero9178.cov.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.parents
import com.intellij.ui.awt.RelativePoint
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.OCFunctionDefinition
import com.jetbrains.cidr.lang.psi.OCLambdaExpression
import com.jetbrains.cidr.lang.psi.OCMethod
import icons.CPPCoverageIcons
import net.zero9178.cov.util.toCP
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import kotlin.math.min

private const val MAX_COMBO_BOX_WIDTH = 200

class CoverageTemplateLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is LeafPsiElement || element.elementType !== OCTokenTypes.LBRACE) {
            return null
        }
        val project = element.project
        val highlighter = CoverageHighlighter.getInstance(project)
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val file = psiFile.virtualFile ?: return null
        val info = highlighter.highlighting[file] ?: return null
        val line = document.getLineNumber(element.startOffset)
        val column = element.startOffset - document.getLineStartOffset(line)
        val region = (line + 1) toCP (column + 1)
        val group = info[region] ?: return null
        if (group.functions.size <= 1) {
            return null
        }
        val callable = element.parents.find {
            when (it) {
                is OCFunctionDefinition -> true
                is OCMethod -> true
                is OCLambdaExpression -> true
                else -> false
            }
        }
        val attach = when (callable) {
            is OCFunctionDefinition -> callable.nameIdentifier
            is OCMethod -> callable.nameIdentifier
            is OCLambdaExpression -> callable.lambdaIntroducer.firstChild
            else -> null
        } ?: return null

        return object : LineMarkerInfo<PsiElement>(attach, attach.textRange, CPPCoverageIcons.TEMPLATE_LINE_MARKER, {
            "Change displayed template instantiation coverage"
        }, null, GutterIconRenderer.Alignment.RIGHT, {
            "Change displayed template instantiation coverage"
        }) {
            override fun createGutterRenderer(): GutterIconRenderer {
                return object : LineMarkerGutterIconRenderer<PsiElement>(this) {
                    override fun getClickAction(): AnAction =
                        TemplateInstantiationsChoosePopup(group, highlighter, this)

                    override fun isNavigateAction(): Boolean {
                        return true
                    }
                }
            }
        }
    }

    private class TemplateInstantiationsChoosePopup(
        val group: CoverageHighlighter.HighlightFunctionGroup,
        val highlighter: CoverageHighlighter,
        val renderer: GutterIconRenderer
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val editor = e.getData(CommonDataKeys.EDITOR) as? EditorEx ?: return
            val gutterComponent = editor.gutterComponentEx
            var point = gutterComponent.getCenterPoint(renderer)
            if (point == null) { // disabled gutter icons for example
                point = Point(
                    gutterComponent.width,
                    editor.visualPositionToXY(editor.caretModel.visualPosition).y + editor.lineHeight / 2
                )
            }
            val comboBox = ComboBox(group.functions.keys.toTypedArray())
            val maxLength = group.functions.keys.maxOf { it.length }
            comboBox.prototypeDisplayValue = "".padStart(min(maxLength, MAX_COMBO_BOX_WIDTH), '0')
            comboBox.addActionListener {
                if (Disposer.isDisposed(group)) {
                    return@addActionListener
                }
                highlighter.changeActive(group, comboBox.item)
            }
            val balloon = JBPopupFactory.getInstance().createDialogBalloonBuilder(
                comboBox,
                null
            ).apply {
                setHideOnClickOutside(true)
                setCloseButtonEnabled(false)
                setAnimationCycle(0)
                setBlockClicksThroughBalloon(true)
            }.createBalloon()
            val moveListener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent?) {
                    balloon.hide()
                }
            }
            gutterComponent.addComponentListener(moveListener)
            Disposer.register(balloon) {
                gutterComponent.removeComponentListener(moveListener)
            }
            val hierarchyListener = object : HierarchyBoundsAdapter() {
                override fun ancestorMoved(e: HierarchyEvent?) {
                    balloon.hide()
                }
            }
            gutterComponent.addHierarchyBoundsListener(hierarchyListener)
            Disposer.register(balloon) {
                gutterComponent.removeHierarchyBoundsListener(hierarchyListener)
            }
            balloon.show(RelativePoint(gutterComponent, point), Balloon.Position.above)
        }
    }


}