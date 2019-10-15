package net.zero9178.cov.data

import com.beust.klaxon.Json
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.parser.OCTokenTypes
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.math.min

private fun OCExpression.isBooleanExpression() =
    this is OCBinaryExpression && listOf("||", "or", "&&", "and").any {
        it == this.operationSignNode.text
    }

class GCCJSONCoverageGenerator(private val myGcov: String) : CoverageGenerator {

    private fun findBranches(
        psiFile: PsiFile,
        range: TextRange,
        line: Line,
        linesWithBranches: List<Line>,
        index: Int,
        handled: MutableSet<OCBinaryExpression>
    ): Pair<List<OCElement>, List<OCElement>> {
        val result = mutableListOf<OCElement>()
        val leftOutStmts = mutableListOf<OCElement>()
        object : OCRecursiveVisitor(
            range
        ) {

            private var myFirstBoolean: OCBinaryExpression? = null

            override fun visitLoopStatement(loop: OCLoopStatement?) {
                loop ?: return super.visitLoopStatement(loop)
                result += loop
                super.visitLoopStatement(loop)
            }

            override fun visitIfStatement(stmt: OCIfStatement?) {
                stmt ?: return super.visitIfStatement(stmt)
                result += stmt
                super.visitIfStatement(stmt)
            }

            override fun visitConditionalExpression(expression: OCConditionalExpression?) {
                expression ?: return super.visitConditionalExpression(expression)
                result += expression
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
                for the very first operand which is at the source location of the whole expression. The source
                location of the whole expression is the one of the operator of the highest boolean expression in
                the AST.

                 */

                if (!expression.isBooleanExpression()) {
                    return super.visitBinaryExpression(expression)
                }

                var isFirst = false
                if (myFirstBoolean == null) {
                    myFirstBoolean = expression
                    isFirst = true
                    // I am the top boolean expression in the AST. I got two extra branches which
                    // actually belong to the deepest (aka first to execute) expression which could even be Me!
                    //Lets find this deepest branch

                    if (!handled.contains(expression)) {
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
                        val left = deepest.left
                        if (left != null && range.contains(deepest.operationSignNode.textRange)
                        ) {
                            //We can safely assume that we are the beginning of the boolean expression. Even if we may
                            //not be on the line of expression yet. We will just record how many branches we had to move
                            //from top to deepest and walk that distance back with the branches instead. Since there's
                            //always two branches per statement we need to walk two at the time. If we run out of
                            //branches in this line we will go a line further

                            handled.add(expression)
                            var currentLineIndex = index
                            var currentBranchIndex = result.size * 2
                            while (iterations != 0 && currentLineIndex < linesWithBranches.size) {
                                val min = min(
                                    (linesWithBranches[currentLineIndex].branches.size - currentBranchIndex) / 2,
                                    iterations
                                )
                                iterations -= min
                                currentBranchIndex += min * 2
                                if (currentBranchIndex > linesWithBranches[currentLineIndex].branches.lastIndex) {
                                    currentBranchIndex = 0
                                    currentLineIndex++
                                }
                            }
                            if (iterations == 0 && currentLineIndex < linesWithBranches.size) {
                                line.branches.addAll(
                                    result.size * 2,
                                    linesWithBranches[currentLineIndex].branches.slice(currentBranchIndex..currentBranchIndex + 1)
                                )
                                linesWithBranches[currentLineIndex].branches.removeAt(currentBranchIndex)
                                linesWithBranches[currentLineIndex].branches.removeAt(currentBranchIndex)
                                result += left
                            }
                        }
                    }
                }

                expression.left?.accept(this)

                if (range.contains(expression.operationSignNode.textRange)) {
                    val right = expression.right
                    if (right != null) {
                        result += right
                    }
                }

                expression.right?.accept(this)

                if (isFirst) {
                    myFirstBoolean = null
                }
            }

            override fun visitLambdaExpression(lambdaExpression: OCLambdaExpression?) {
                return
            }
        }.visitElement(psiFile)
        return result to leftOutStmts
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
            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadActionInSmartMode emptyList()
            val document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadActionInSmartMode emptyList()
            val handled = mutableSetOf<OCBinaryExpression>()
            val linesWithBranches = lines.filter { it.branches.isNotEmpty() }
            linesWithBranches.mapIndexed { index, line ->
                val startOffset = document.getLineStartOffset(line.lineNumber - 1)
                val lineEndOffset = document.getLineEndOffset(line.lineNumber - 1)

                val (result, leftOutStmts) = findBranches(
                    psiFile,
                    TextRange(startOffset, lineEndOffset),
                    line,
                    linesWithBranches,
                    index,
                    handled
                )

                val zip =
                    line.branches.chunked(2).filter { it.size == 2 }
                        .map { it[0] to it[1] }
                        .zip(result)

                fun OCElement.getCondition() = when (this) {
                    is OCIfStatement -> this.condition
                    is OCLoopStatement -> this.condition
                    is OCConditionalExpression -> this.condition
                    else -> null
                }

                fun PsiElement.getBranchMarkOffset(): Int? {
                    return when {
                        this is OCIfStatement -> this.lParenth?.startOffset
                        this is OCLoopStatement -> this.lParenth?.startOffset
                        this is OCConditionalExpression -> PsiTreeUtil.findSiblingForward(
                            this.condition,
                            OCTokenTypes.QUEST,
                            null
                        )?.node?.textRange?.endOffset
                        this is OCBinaryExpression && this.isBooleanExpression() -> {
                            this.operationSignNode.textRange.endOffset
                        }
                        this is OCExpression -> {
                            this.parent.getBranchMarkOffset()
                        }
                        else -> null
                    }
                }

                /**
                 * Here we evaluate the boolean expressions inside of a condition to figure out the branch coverage
                 * of the condition
                 *
                 * OR Test code:
                 * for(; E0 || ... || En;) {
                 *      ...
                 * }
                 *
                 * How gcov sees it:
                 * {
                 *  int i = 0;
                 * check:
                 *  if(E0)//Branch 1
                 *  {
                 *      goto body;
                 *  }
                 *  else if(E1)
                 *  {
                 *
                 *  }
                 *  ...
                 *  else if(!(En))//Branch 2
                 *  {
                 *      goto end;
                 *  }
                 *  else
                 *  {
                 *      goto body;
                 *  }
                 *  body:
                 *  ...
                 *  goto check;
                 *  end:
                 *
                 *  AND Test code:
                 * for(int i = 0; i < 5 && i % 2 == 0; i++) {
                 *      ...
                 * }
                 *
                 * How gcov sees it:
                 * {
                 *  int i = 0;
                 * check:
                 *  if(i < 5)//Branch 1
                 *  {
                 *      if(i % 2 == 0)//Branch 2
                 *      {
                 *          goto body;
                 *      }
                 *      else
                 *      {
                 *          goto end;
                 *      }
                 *  }
                 *  else
                 *  {
                 *      goto end;
                 *  }
                 *  body:
                 *  ...
                 *  goto check;
                 *  end:
                 *
                 *  To figure out the branch probability of a condition that has boolean operators
                 *  we need to check how many times the else was NOT reached in case of OR or check how many times
                 *  all branches reached the deepest body
                 */
                val stmts = leftOutStmts.map { thisStmt ->
                    val filter =
                        zip.filter { thisStmt.getCondition()?.textRange?.contains(it.second.textRange) ?: false }
                    thisStmt to filter
                        .foldIndexed<Pair<Pair<Branch, Branch>, OCElement>, Pair<Int, Int>?>(null) { index, current, (branches, element) ->
                            val skipped = if (branches.first.fallthrough) branches.second else branches.first
                            val steppedIn = if (branches.first.fallthrough) branches.first else branches.second

                            //if current != null than operand is always the one in the second branch so to say
                            val parentExpression = element.parent as? OCBinaryExpression ?: return@foldIndexed current
                            val isLast = index == filter.lastIndex
                            when (parentExpression.operationSignNode.text) {
                                "or", "||" -> if (current == null) {
                                    skipped.count to steppedIn.count
                                } else {
                                    if (current.second != 0) {
                                        if (isLast) {
                                            current.first + skipped.count to steppedIn.count
                                        } else {
                                            current.first + steppedIn.count to skipped.count
                                        }
                                    } else {
                                        current
                                    }
                                }
                                "and", "&&" -> if (current == null) {
                                    steppedIn.count to skipped.count
                                } else {
                                    if (current.first != 0) {
                                        steppedIn.count to current.second + skipped.count
                                    } else {
                                        current
                                    }
                                }
                                else -> current
                            }
                        }
                }.filter { it.second != null }.map { it.first to it.second!! }.map { (thisIf, pair) ->
                    val startLine =
                        document.getLineNumber(thisIf.getBranchMarkOffset() ?: thisIf.textOffset) + 1
                    val startColumn =
                        (thisIf.getBranchMarkOffset() ?: thisIf.textOffset) - document.getLineStartOffset(
                            startLine - 1
                        ) + 1
                    CoverageBranchData(
                        startLine toCP startColumn,
                        pair.first, pair.second
                    )
                }

                zip.filter {
                    when (it.second) {
                        is OCLoopStatement -> CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
                        is OCIfStatement -> CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
                        is OCConditionalExpression -> CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled
                        is OCExpression -> CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
                        else -> true
                    }
                }.fold(stmts) { list, (branches, element) ->
                    if (element is OCExpression && element !is OCConditionalExpression) {
                        val parent = element.parent
                        if (parent is OCBinaryExpression && parent.left !== element) {
                            //return@fold list
                        }
                    }
                    val steppedIn = if (branches.first.fallthrough) branches.first else branches.second
                    val skipped = if (branches.first.fallthrough) branches.second else branches.first
                    list + CoverageBranchData(
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
                        }(), steppedIn.count, skipped.count
                    )
                }
            }.flatten()
        }
    }

    @Suppress("ConvertCallChainIntoSequence")
    private fun rooToCoverageData(root: Root, env: CPPEnvironment, project: Project) =
        CoverageData(root.files.chunked(ceil(root.files.size / Thread.activeCount().toDouble()).toInt()).map {
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

    private data class Line(
        val branches: MutableList<Branch>,
        val count: Long, @Json(name = "line_number") val lineNumber: Int, @Json(name = "unexecuted_block") val unexecutedBlock: Boolean, @Json(
            name = "function_name"
        ) val functionName: String = ""
    ) {
        init {
            branches.removeIf { it.throwing }
        }
    }

    private data class Branch(val count: Int, val fallthrough: Boolean, @Json(name = "throw") val throwing: Boolean)

    private fun processJson(
        jsonContents: List<String>,
        env: CPPEnvironment,
        project: Project
    ): CoverageData {

        val root = jsonContents.map {
            ApplicationManager.getApplication().executeOnPooledThread<List<File>> {
                Klaxon().maybeParse<Root>(Parser.jackson().parse(StringReader(it)) as JsonObject)?.files
            }
        }.flatMap {
            it.get()
        }

        return rooToCoverageData(Root("", "", "", root), env, project)
    }

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