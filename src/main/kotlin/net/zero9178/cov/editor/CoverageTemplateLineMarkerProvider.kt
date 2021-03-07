package net.zero9178.cov.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.parents
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.SelectionAwareListCellRenderer
import com.intellij.util.ui.JBUI
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.OCFunctionDefinition
import com.jetbrains.cidr.lang.psi.OCLambdaExpression
import com.jetbrains.cidr.lang.psi.OCMethod
import icons.CPPCoverageIcons
import net.zero9178.cov.util.toCP
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.SwingConstants

class CoverageTemplateLineMarkerProvider : LineMarkerProvider {

    init {
        print("test")
    }

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
            "Change displayed template type"
        }, null, GutterIconRenderer.Alignment.RIGHT, {
            "Change displayed template type"
        }) {
            override fun createGutterRenderer(): GutterIconRenderer {
                return object : LineMarkerGutterIconRenderer<PsiElement>(this) {
                    override fun getClickAction(): AnAction = TemplateInstantiationsChoosePopup(group, highlighter)

                    override fun isNavigateAction(): Boolean {
                        return true
                    }
                }
            }
        }
    }

    private class TemplateInstantiationsChoosePopup(
        val group: CoverageHighlighter.HighlightFunctionGroup,
        val highlighter: CoverageHighlighter
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val popUp = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(group.functions.keys.toList()).apply {
                    setRenderer(SelectionAwareListCellRenderer {
                        val text = StringUtil.first(it, 100, true)
                        JBLabel(text, SwingConstants.LEFT).apply {
                            border = JBUI.Borders.empty(2)
                        }
                    })
                    setItemChosenCallback {
                        highlighter.changeActive(group, it)
                    }
                    setTitle("Template Instantiation")
                }.createPopup()
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val location = MouseInfo.getPointerInfo().location
            popUp.show(
                RelativePoint(
                    Point(
                        location.x - popUp.content.preferredSize.width / 2,
                        location.y - popUp.content.preferredSize.height / 2
                    )
                ).getPointOn(editor.contentComponent)
            )
        }
    }


}