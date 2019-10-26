package net.zero9178.cov.data

import com.beust.klaxon.*
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.impl.OCTemplateParameterListImpl
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import com.jetbrains.cidr.lang.symbols.OCResolveContext
import com.jetbrains.cidr.lang.types.OCIntType
import com.jetbrains.cidr.lang.types.OCRealType
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.math.min

private fun OCExpression.isBooleanExpression() =
    this is OCBinaryExpression && (this.operationSign === OCTokenTypes.ANDAND || this.operationSign === OCTokenTypes.OROR)


private fun addBranchCoverageForExpression(
    expression: OCExpression,
    coverage: Pair<Int, Int>,
    booleanMap: MutableMap<OCElement, Pair<Int, Int>>,
    leftOutStmts: MutableList<OCElement>
) {
    booleanMap[expression] = coverage
    var prev = expression
    var currentCoverage = coverage
    var parent = expression.parent as? OCExpression ?: return
    while (parent is OCParenthesizedExpression || parent.isBooleanExpression()) {
        if (parent is OCParenthesizedExpression) {
            booleanMap[parent] = currentCoverage
        } else {
            parent as OCBinaryExpression
            if (parent.right !== prev) {
                return
            }
            val lhs = parent.left?.let { booleanMap[it] } ?: return
            currentCoverage = when (parent.operationSign) {
                OCTokenTypes.OROR -> {
                    lhs.first + currentCoverage.first to currentCoverage.second
                }
                OCTokenTypes.ANDAND -> {
                    currentCoverage.first to lhs.second + currentCoverage.second
                }
                else -> return
            }
            booleanMap[parent] = currentCoverage
        }
        prev = parent
        parent = parent.parent as? OCExpression ?: break
    }

    val stmt = leftOutStmts.find {
        when (it) {
            is OCIfStatement -> it.condition?.expression === prev
            is OCConditionalExpression -> it.condition === prev
            else -> false
        }
    }
    if (stmt != null) {
        booleanMap[stmt] = currentCoverage
    }
}

/**
 * expression has side effects according to gcc/gimplify.c recalculate_side_effects
 */
private fun hasSideEffects(condition: OCExpression?): Boolean {
    condition ?: return true
    var result = false
    object : OCRecursiveVisitor() {
        override fun visitElement(element: PsiElement?) {
            if (!result) {
                super.visitElement(element)
            }
        }

        override fun visitCallExpression(expression: OCCallExpression?) {
            result = true
        }

        override fun visitExpression(expr: OCExpression?) {
            expr ?: return super.visitExpression(expr)
            if (expr.resolvedType.isVolatile) {
                result = true
            } else {
                super.visitExpression(expr)
            }
        }

        override fun visitUnaryExpression(expression: OCUnaryExpression?) {
            expression ?: return super.visitUnaryExpression(expression)
            when (expression.operationSign) {
                OCTokenTypes.PLUSPLUS, OCTokenTypes.MINUSMINUS, OCTokenTypes.DEREF, OCTokenTypes.DEREF_MUL -> result =
                    true
                else -> super.visitUnaryExpression(expression)
            }
        }

        override fun visitSizeofExpression(expression: OCSizeofExpression?) {}

        override fun visitAssignmentExpression(expression: OCAssignmentExpression?) {
            result = true
        }

        override fun visitLambdaExpression(lambdaExpression: OCLambdaExpression?) {}
    }.visitElement(condition)
    return result
}

private fun generatesCode(condition: OCExpression): Pair<Boolean, Int> {
    return when (condition) {
        is OCReferenceExpression -> false to 0
        is OCBinaryExpression -> {
            if (condition.isBooleanExpression()) {
                condition.right?.let { generatesCode(it) } ?: false to 0
            } else {
                !listOf(
                    OCTokenTypes.LT,
                    OCTokenTypes.LTEQ,
                    OCTokenTypes.GT,
                    OCTokenTypes.GTEQ, OCTokenTypes.EQEQ, OCTokenTypes.EXCLEQ
                ).contains(condition.operationSign) to condition.operationSignNode.startOffset
            }
        }
        is OCUnaryExpression -> {
            when {
                condition.operationSign === OCTokenTypes.EXCL -> {
                    val resolvedType = condition.resolvedType
                    if (resolvedType is OCIntType) {
                        OCIntType.isBool(resolvedType, OCResolveContext.forPsi(condition))
                    } else {
                        true
                    }
                }
                condition.operationSign === OCTokenTypes.PLUS -> {
                    val resolvedType = condition.resolvedType
                    if (resolvedType is OCIntType) {
                        resolvedType.getBits(null, null, condition.project) < OCIntType.INT.getBits(
                            null,
                            null,
                            condition.project
                        )
                    } else resolvedType !is OCRealType
                }
                else -> true
            } to condition.operationSignNode.startOffset
        }
        is OCParenthesizedExpression -> condition.operand?.let { generatesCode(it) } ?: false to 0
        else -> true to condition.textOffset
    }
}

private fun findBranches(
    ocFile: OCFile,
    range: TextRange,
    linesWithBranches: List<Line>,
    index: Int,
    branches: MutableList<Pair<Int, Int>>,
    booleanCondIfMap: MutableMap<OCElement, Pair<Int, Int>>,
    patchHandled: MutableSet<OCBinaryExpression>
): List<OCElement> {
    val statements = mutableListOf<OCElement>()
    val leftOutStmts = mutableListOf<OCElement>()
    object : OCRecursiveVisitor(range) {

        private val myLogger = Logger.getInstance(GCCJSONCoverageGenerator::class.java)

        private var myFirstBoolean: OCBinaryExpression? = null
        private var myDeepest: OCExpression? = null

        override fun visitLoopStatement(loop: OCLoopStatement?) {
            loop ?: return super.visitLoopStatement(loop)
            var cond = loop.condition ?: return super.visitLoopStatement(loop)
            while (cond is OCParenthesizedExpression) {
                cond = cond.operand ?: break
            }
            val offset = when (cond) {
                is OCBinaryExpression -> {
                    cond.operationSignNode.startOffset
                }
                is OCCallExpression -> {
                    cond.argumentList.leftPar?.textOffset ?: cond.argumentList.textOffset
                }
                else -> cond.textOffset
            }
            if (range.contains(offset)) {
                statements += loop
            }
            super.visitLoopStatement(loop)
        }

        override fun visitIfStatement(stmt: OCIfStatement?) {
            stmt ?: return super.visitIfStatement(stmt)
            var currentCond: OCExpression? = stmt.condition?.expression ?: return super.visitIfStatement(stmt)
            while (currentCond is OCParenthesizedExpression) {
                currentCond = currentCond.operand
            }
            if (currentCond != null && currentCond.isBooleanExpression() && (!ocFile.isCpp || !hasSideEffects(
                    currentCond
                ))
            ) {
                leftOutStmts += stmt
            } else {
                val offset = {
                    if (currentCond != null) {
                        val (generatesCode, textOffset) = generatesCode(currentCond)
                        if (generatesCode) {
                            textOffset
                        } else {
                            stmt.textOffset + 1
                        }
                    } else {
                        stmt.textOffset + 1
                    }
                }()
                visitElement(stmt.condition)
                if (range.contains(offset) && !((stmt.thenBranch as? OCBlockStatement)?.statements.isNullOrEmpty()
                            && (stmt.elseBranch as? OCBlockStatement)?.statements.isNullOrEmpty())
                ) {
                    statements += stmt
                }
            }
        }

        override fun visitConditionalExpression(expression: OCConditionalExpression?) {
            expression ?: return super.visitConditionalExpression(expression)
            val element = PsiTreeUtil.findSiblingForward(expression.condition, OCTokenTypes.QUEST, null)
                ?: return super.visitConditionalExpression(expression)
            if (range.contains(element.textOffset)) {
                statements += expression
            }
            super.visitConditionalExpression(expression)
        }

        override fun visitBinaryExpression(expression: OCBinaryExpression?) {
            expression ?: return super.visitBinaryExpression(expression)

            /*
            Looking at gimple one can see that for each operand of a boolean expression gcc generates
            an if. The problem is that we get a branch for each of those if and the source location of this
            if is at first glance inconsistent. Following code snippets:
            3| return 5
            5| &&
            6| 6
            7| &&
            8| 7;

            Will generate 6 branches in gcov (2 per if. Where one says how often the if was true and the other
            how often it was false). The source location of the first of the first branch, the one
            corresponding to the 5 at line 3, is actually at the && operator on line 7. Meanwhile the 6 at
            line 6 corresponds to the && at line 5. Lastly the 7 at line 8 corresponds to again the && on line 7.

            In summary gcov reports 4 branches at line 7 and one at line 5 where 2 of the ones at line 7
            correspond to the 5 at line 3 and the second pair to the 7 at line 8. Yikes

            This is due to how source location is generated within the c_parser_binary_expression function in c-parser.c
            (corresponding C++ parser does the same). GCC uses a operator precedence parser which has a stack
            that pushes if the operator afterwards and pops top and combining it with the expression
            underneath. Each pop results in the resulting expression having the source location of the
            operand in between the expressions. As pops occur earlier when precedence is not ascending
            we get different source locations.

            For E1 || E2 && E3 we get first get E1 pushed, then || with E2 and last && with E3. At this point
            the parser finished and must now pop the whole stack to return an expression. First it will combine
            E2 with the top of the stack which is && and E3. Therefore the stack is now [0] = E1, [1] = || (E2 && E3).
            [1] has the source location of the && operator. Next step they get popped off again and merged with
            E1. Therefore we get [0] = E1 || (E2 && E3). With the source location being at ||. This means
            that the whole expression has that source location and the very first boolean expression that
            is going to be executed is going to have its branches associated with the line of the ||.

            The problematic case is E1 &&(1) E2 &&(2) E3. First we get E1 pushed again. After that &&(1) and E2.
            After that it will see that the about to be pushed &&(2) has the same or lower precedence (same
            in this case) and before pushing it pops the top off and merges it with the one below. Therefore
            we now have [0] = E1 &&(1) E2, and after the push also [1] &&(2) E3. [0] has its branch source
            location associated with &&(1). Now we are done with the expression and need to pop until we have
            a single expression again. Therefore we get E1 &&(1) E2 &&(2) E3. The source location of the whole
            expression is now the one of &&(2) which means the branch of E1 is located at the line where
            &&(2) is located. Yikes. E2 branches are located at &&(1) and E3 at &&(2).


            Therefore in general operators branches have source locations of its operator to the left except
            for the very first operand executed in the whole expression which is at the source location of the whole expression. The source
            location of the whole expression is the one of the operator of the highest boolean expression in
            the AST.

             */

            if (!expression.isBooleanExpression()) {
                return super.visitBinaryExpression(expression)
            }

            var isFirst = false
            var hadToPatch = false
            if (myFirstBoolean == null) {
                myFirstBoolean = expression
                isFirst = true
                // I am the top boolean expression in the AST. I got two extra branches which
                // actually belong to the deepest (aka first to execute) expression which could even be Me!
                //Lets find this deepest branch

                if (!patchHandled.contains(expression)) {
                    var iterations = 0
                    var deepest: OCBinaryExpression = expression
                    var current: OCExpression = deepest
                    loop@ while (true) {
                        when (current) {
                            is OCBinaryExpression -> {
                                val left = current.left ?: break@loop
                                current = left
                                if (left.isBooleanExpression()) {
                                    iterations++
                                    deepest = left as OCBinaryExpression
                                }
                            }
                            is OCUnaryExpression -> {
                                current = current.operand ?: break@loop
                            }
                            is OCParenthesizedExpression -> {
                                current = current.operand ?: break@loop
                            }
                            is OCPostfixExpression -> {
                                current = current.operand
                            }
                            is OCConditionalExpression -> {
                                current = current.condition
                            }
                            is OCCommaExpression -> {
                                current = current.headExpression
                            }
                            is OCAssignmentExpression -> {
                                current = current.sourceExpression ?: break@loop
                            }
                            is OCCallExpression -> {
                                current = current.functionReferenceExpression
                            }
                            else -> break@loop
                        }
                    }
                    myDeepest = deepest
                    if (range.contains(deepest.operationSignNode.startOffset) && range.contains(expression.operationSignNode.startOffset)) {
                        patchHandled.add(expression)
                    } else {
                        //We can safely assume that we are the beginning of the boolean expression. Even if we may
                        //not be on the line of expression yet. We will just record how many branches we had to move
                        //from top to deepest and walk that distance back with the branches instead. Since there's
                        //always two branches per statement we need to walk two at the time. If we run out of
                        //branches in this line we will go a line further

                        var currentLineIndex = index
                        var currentBranchIndex = statements.size
                        while (iterations != 0 && currentLineIndex < linesWithBranches.size) {
                            val min = min(
                                linesWithBranches[currentLineIndex].branches.size - currentBranchIndex,
                                iterations
                            )
                            iterations -= min
                            currentBranchIndex += min
                            if (currentBranchIndex > linesWithBranches[currentLineIndex].branches.lastIndex) {
                                currentBranchIndex = 0
                                currentLineIndex++
                            }
                        }
                        val left = deepest.left
                        if (iterations == 0 && currentLineIndex < linesWithBranches.size && left != null && range.contains(
                                deepest.operationSignNode.textRange
                            )
                        ) {
                            patchHandled.add(expression)
                            hadToPatch = true
                            addBranchCoverageForExpression(
                                left,
                                linesWithBranches[currentLineIndex].branches[currentBranchIndex],
                                booleanCondIfMap, leftOutStmts
                            )
                        }
                    }
                }
            }

            expression.left?.accept(this)

            if (range.contains(expression.operationSignNode.textRange)) {
                if (hadToPatch) {
                    if (statements.size <= branches.lastIndex) {
                        branches.removeAt(statements.size)
                    } else {
                        myLogger.warn("More branches found than gcov in expression " + expression.text)
                    }
                }
                if (myDeepest === expression && range.contains(expression.operationSignNode.startOffset) &&
                    (myFirstBoolean?.let { range.contains(it.operationSignNode.startOffset) } == true)
                ) {
                    val left = expression.left
                    if (left != null) {
                        if (statements.size <= branches.lastIndex) {
                            addBranchCoverageForExpression(
                                left,
                                branches[statements.size],
                                booleanCondIfMap,
                                leftOutStmts
                            )
                            branches.removeAt(statements.size)
                        } else {
                            myLogger.warn("More branches found than gcov in expression " + expression.text)
                        }
                    }
                }
                val right = expression.right
                if (right != null) {
                    if (statements.size <= branches.lastIndex) {

                        addBranchCoverageForExpression(
                            right,
                            branches[statements.size],
                            booleanCondIfMap,
                            leftOutStmts
                        )
                        branches.removeAt(statements.size)
                    } else {
                        myLogger.warn("More branches found than gcov in expression " + expression.text)
                    }
                }
            }

            expression.right?.accept(this)

            if (isFirst) {
                myFirstBoolean = null
            }
        }

        override fun visitLambdaExpression(lambdaExpression: OCLambdaExpression?) {
            lambdaExpression?.lambdaIntroducer?.accept(this)
            return
        }

        override fun visitSizeofExpression(expression: OCSizeofExpression?) {
            return
        }

        override fun visitTemplateParameterList(list: OCTemplateParameterListImpl?) {
            return
        }
    }.visitElement(ocFile)
    return statements
}

private fun getBranchData(
    lines: List<Line>,
    file: String,
    project: Project
): List<CoverageBranchData> {
    if (lines.isEmpty()) {
        return emptyList()
    }
    return DumbService.getInstance(project).runReadActionInSmartMode<List<CoverageBranchData>> {
        val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadActionInSmartMode emptyList()
        val psiFile =
            PsiManager.getInstance(project).findFile(vfs) as? OCFile ?: return@runReadActionInSmartMode emptyList()
        val document =
            PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadActionInSmartMode emptyList()
        val handled = mutableSetOf<OCBinaryExpression>()
        val booleanCondIfMap = mutableMapOf<OCElement, Pair<Int, Int>>()
        val linesWithBranches = lines.filter { it.branches.isNotEmpty() }
        linesWithBranches.mapIndexed { index, line ->

            val branches = line.branches.toMutableList()
            val result = findBranches(
                psiFile,
                TextRange(
                    document.getLineStartOffset(line.lineNumber - 1),
                    document.getLineEndOffset(line.lineNumber - 1)
                ),
                linesWithBranches, index, branches,
                booleanCondIfMap,
                handled
            )

            val zip = branches.zip(result)

            fun PsiElement.getBranchMarkOffset(): Int? {
                return when (this) {
                    is OCIfStatement -> this.lParenth?.startOffset
                    is OCLoopStatement -> this.lParenth?.startOffset
                    is OCConditionalExpression -> PsiTreeUtil.findSiblingForward(
                        this.condition,
                        OCTokenTypes.QUEST,
                        null
                    )?.node?.textRange?.endOffset
                    else -> null
                }
            }

            zip.filter {
                when (it.second) {
                    is OCLoopStatement -> CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
                    is OCIfStatement -> CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
                    is OCConditionalExpression -> CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled
                    is OCExpression -> CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
                    else -> true
                }
            }.map { (branches, element) ->
                CoverageBranchData(
                    {
                        val startLine =
                            document.getLineNumber(
                                element.getBranchMarkOffset() ?: element.textOffset
                            ) + 1
                        val startColumn =
                            (element.getBranchMarkOffset()
                                ?: element.textOffset) - document.getLineStartOffset(
                                startLine - 1
                            ) + 1
                        startLine toCP startColumn
                    }(), branches.first, branches.second
                )
            }
        }.flatten() + booleanCondIfMap.toList().fold(emptyList<CoverageBranchData>()) { list, (element, coverage) ->
            when (element) {
                is OCIfStatement -> {
                    val offset = element.lParenth?.startOffset ?: element.textOffset
                    val line = document.getLineNumber(offset) + 1
                    list + CoverageBranchData(
                        line toCP offset - document.getLineStartOffset(line - 1) + 1,
                        coverage.first,
                        coverage.second
                    )
                }
                is OCConditionalExpression -> {
                    val quest = PsiTreeUtil.findSiblingForward(element.condition, OCTokenTypes.QUEST, null)
                    val offset = quest?.textRange?.endOffset ?: element.condition.textRange.endOffset
                    val line = document.getLineNumber(offset) + 1
                    list + CoverageBranchData(
                        line toCP offset - document.getLineStartOffset(line - 1) + 1,
                        coverage.first,
                        coverage.second
                    )
                }
                else -> {
                    val parent = element.parent
                    if (parent !is OCBinaryExpression || !parent.isBooleanExpression() || element !== parent.left) {
                        list
                    } else {
                        val offset = parent.operationSignNode.textRange.endOffset
                        val line = document.getLineNumber(offset) + 1
                        list + CoverageBranchData(
                            line toCP offset - document.getLineStartOffset(line - 1) + 1,
                            coverage.first,
                            coverage.second
                        )
                    }
                }
            }
        }
    }
}

@Suppress("ConvertCallChainIntoSequence")
private fun rootToCoverageData(root: Root, env: CPPEnvironment, project: Project): CoverageData {
    val activeCount = 1//Thread.activeCount()
    return CoverageData(root.files.chunked(ceil(root.files.size / activeCount.toDouble()).toInt()).map {
        ApplicationManager.getApplication().executeOnPooledThread<List<CoverageFileData>> {
            it.filter { it.lines.isNotEmpty() || it.functions.isNotEmpty() }.map { file ->
                CoverageFileData(env.toLocalPath(file.file).replace('\\', '/'), file.functions.map { function ->
                    val lines = file.lines.filter {
                        it.functionName == function.name
                    }
                    CoverageFunctionData(
                        function.startLine,
                        function.endLine,
                        function.demangledName,
                        FunctionLineData(lines.associate { it.lineNumber to it.count }),
                        getBranchData(
                            lines,
                            env.toLocalPath(file.file),
                            project
                        )
                    )
                }.associateBy { it.functionName })
            }
        }
    }.flatMap { it.get() }.associateBy { it.filePath })
}

private data class Root(
    @Json(name = "current_working_directory") val currentWorkingDirectory: String,
    @Json(name = "data_file") val dataFile: String,
    @Json(name = "gcc_version") val gccVersion: String,
    val files: List<File>
)

private data class File(val file: String, val functions: List<Function>, val lines: List<Line>)

private data class Function(
    val blocks: Int, @Json(name = "blocks_executed") val blocksExecuted: Long, @Json(name = "demangled_name") val demangledName: String, @Json(
        name = "end_column"
    ) val endColumn: Int, @Json(name = "end_line") val endLine: Int, @Json(name = "execution_count") val executionCount: Long,
    val name: String, @Json(name = "start_column") val startColumn: Int, @Json(name = "start_line") val startLine: Int
)

@Target(AnnotationTarget.FIELD)
annotation class BranchFilter

private data class Line @JvmOverloads constructor(
    @BranchFilter val branches: List<Pair<Int, Int>>,
    val count: Long, @Json(name = "line_number") val lineNumber: Int, @Json(name = "unexecuted_block") val unexecutedBlock: Boolean, @Json(
        name = "function_name"
    ) val functionName: String = ""
)

private data class Branch(val count: Int, val fallthrough: Boolean, @Json(name = "throw") val throwing: Boolean)

private fun processJson(
    jsonContents: List<String>,
    env: CPPEnvironment,
    project: Project
): CoverageData {

    val filter = object : Converter {
        override fun canConvert(cls: Class<*>) = cls == List::class.java

        override fun fromJson(jv: JsonValue): Any? {
            val array = jv.array ?: return null
            val list = Klaxon().parseFromJsonArray<Branch>(array) ?: return null
            return list.chunked(2).filter { branch -> !branch.any { it.throwing } }.filter { it.size == 2 }
                .map {
                    if (it[0].fallthrough) {
                        it[0].count to it[1].count
                    } else {
                        it[1].count to it[0].count
                    }
                }
        }

        override fun toJson(value: Any) = ""
    }

    val root = jsonContents.map {
        ApplicationManager.getApplication().executeOnPooledThread<List<File>> {
            Klaxon().fieldConverter(BranchFilter::class, filter)
                .maybeParse<Root>(Parser.jackson().parse(StringReader(it)) as JsonObject)?.files
        }
    }.flatMap {
        it.get()
    }

    return rootToCoverageData(Root("", "", "", root), env, project)
}

class GCCJSONCoverageGenerator(private val myGcov: String) : CoverageGenerator {

    override fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return null
        val files =
            config.configurationGenerationDir.walkTopDown().filter {
                it.isFile && it.name.endsWith(".gcda")
            }.map { environment.toEnvPath(it.absolutePath) }.filterNotNull().toList()

        val command = listOf(
            myGcov,
            "-i",
            "-m",
            "-t"
        ) + if (CoverageGeneratorSettings.getInstance().branchCoverageEnabled) {
            listOf("-b")
        } else {
            emptyList()
        } + files
        val p = environment.hostMachine.runProcess(GeneralCommandLine(command), null, -1)
        val lines = p.stdoutLines
        val retCode = p.exitCode
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "gcov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${command.joinToString(" ")}\n Stderr: ${p.stderrLines.joinToString("\n")}",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        files.forEach { Files.deleteIfExists(Paths.get(environment.toLocalPath(it))) }

        return processJson(lines, environment, configuration.project)
    }
}