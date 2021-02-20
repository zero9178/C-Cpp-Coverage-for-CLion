package net.zero9178.cov.data

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
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
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

class GCCJSONCoverageGenerator(private val myGcov: String) : CoverageGenerator {

    companion object {
        val log = Logger.getInstance(GCCJSONCoverageGenerator::class.java)
    }

    private fun findStatementsForBranches(
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
            lines.filter { it.branches.isNotEmpty() }.flatMap { line ->
                try {
                    val startOffset = document.getLineStartOffset(line.lineNumber.toInt() - 1)
                    val lineEndOffset = document.getLineEndOffset(line.lineNumber.toInt() - 1)
                    val result = mutableListOf<OCElement>()
                    val leftOutStmts = mutableListOf<OCElement>()
                    object : OCRecursiveVisitor(
                        TextRange(
                            startOffset,
                            lineEndOffset
                        )
                    ) {
                        override fun visitLoopStatement(loop: OCLoopStatement?) {
                            loop ?: return super.visitLoopStatement(loop)
                            matchBranch(loop)
                            super.visitLoopStatement(loop)
                        }

                        override fun visitIfStatement(stmt: OCIfStatement?) {
                            stmt ?: return super.visitIfStatement(stmt)
                            matchBranch(stmt)
                            super.visitIfStatement(stmt)
                        }

                        override fun visitConditionalExpression(expression: OCConditionalExpression?) {
                            expression ?: return super.visitConditionalExpression(expression)
                            matchBranch(expression)
                            super.visitConditionalExpression(expression)
                        }

                        private fun matchBranch(element: OCElement) {
                            //When an statement that'd usually have branch coverage contains an boolean expression as its condition
                            //no branch coverage is generated for the coverage. Instead we put that statement into leftOutStmts
                            //and later down below evaluate the branch coverage of the whole boolean expression to figure out
                            //the branch coverage of the whole condition
                            val isShortCicuit = { statement: OCElement, condition: PsiElement ->
                                val list = PsiTreeUtil.getChildrenOfTypeAsList(
                                    condition,
                                    OCBinaryExpression::class.java
                                )
                                if (list.any { listOf("||", "or", "&&", "and").contains(it.operationSignNode.text) }) {
                                    leftOutStmts += statement
                                    true
                                } else {
                                    false
                                }
                            }

                            when (element) {
                                is OCIfStatement -> {
                                    if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                        return
                                    }
                                    if (element.condition?.let { isShortCicuit(element, it) } == true) {
                                        return
                                    }
                                }
                                is OCConditionalExpression -> {
                                    if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                        return
                                    }
                                    if (isShortCicuit(element, element.condition)) {
                                        return
                                    }
                                }
                                is OCDoWhileStatement -> {
                                    if (element.lastChild.textOffset !in startOffset..lineEndOffset) {
                                        return
                                    }
                                }
                                else -> {
                                    if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                        return
                                    }
                                }
                            }

                            if (element is OCLoopStatement) {
                                if (element.condition?.let { isShortCicuit(element, it) } == true) {
                                    return
                                }
                            }

                            result += element
                        }

                        override fun visitBinaryExpression(expression: OCBinaryExpression?) {
                            super.visitBinaryExpression(expression)
                            expression ?: return
                            when (expression.operationSignNode.text) {
                                "||", "or", "&&", "and" -> {
                                    //Calling with the operands here as it creates a branch for each operand
                                    val left = expression.left
                                    if (left != null) {
                                        if (PsiTreeUtil.findChildrenOfType(left, OCBinaryExpression::class.java).none {
                                                listOf("||", "or", "&&", "and").contains(it.operationSignNode.text)
                                            }) {
                                            matchBranch(left)
                                        }
                                    }
                                    val right = expression.right
                                    if (right != null) {
                                        if (PsiTreeUtil.findChildrenOfType(right, OCBinaryExpression::class.java).none {
                                                listOf("||", "or", "&&", "and").contains(it.operationSignNode.text)
                                            }) {
                                            matchBranch(right)
                                        }
                                    }
                                }
                            }
                        }
                    }.visitElement(psiFile)

                    val zip =
                        line.branches.chunked(2).filter { it.none { branch -> branch.throwing } && it.size == 2 }
                            .map { it[0] to it[1] }
                            .zip(result)

                    fun OCElement.getCondition() = when (this) {
                        is OCIfStatement -> this.condition
                        is OCLoopStatement -> this.condition
                        is OCConditionalExpression -> this.condition
                        else -> null
                    }

                    fun OCElement.getBranchMarkOffset(): Int? {
                        return when (this) {
                            is OCIfStatement -> this.lParenth?.startOffset
                            is OCLoopStatement -> this.lParenth?.startOffset
                            is OCConditionalExpression -> PsiTreeUtil.findSiblingForward(
                                this.condition,
                                OCTokenTypes.QUEST,
                                null
                            )?.node?.textRange?.endOffset
                            is OCExpression -> {
                                val parent = this.parent as? OCBinaryExpression ?: return null
                                PsiTreeUtil.findSiblingForward(
                                    this,
                                    parent.operationSign,
                                    null
                                )?.node?.textRange?.endOffset
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
                                val parentExpression =
                                    element.parent as? OCBinaryExpression ?: return@foldIndexed current
                                val isLast = index == filter.lastIndex
                                when (parentExpression.operationSignNode.text) {
                                    "or", "||" -> if (current == null) {
                                        skipped.count.toInt() to steppedIn.count.toInt()
                                    } else {
                                        if (current.second != 0) {
                                            if (isLast) {
                                                current.first + skipped.count.toInt() to steppedIn.count.toInt()
                                            } else {
                                                current.first + steppedIn.count.toInt() to skipped.count.toInt()
                                            }
                                        } else {
                                            current
                                        }
                                    }
                                    "and", "&&" -> if (current == null) {
                                        steppedIn.count.toInt() to skipped.count.toInt()
                                    } else {
                                        if (current.first != 0) {
                                            steppedIn.count.toInt() to current.second + skipped.count.toInt()
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

                    var lastOCElement: OCElement? = null
                    zip.filter {
                        when (it.second) {
                            is OCLoopStatement -> CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
                            is OCIfStatement -> CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
                            is OCConditionalExpression -> CoverageGeneratorSettings.getInstance().conditionalExpCoverageEnabled
                            is OCExpression -> CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
                            else -> true
                        }
                    }.fold(stmts) { list, (branches, element) ->
                        val parent = element.parent
                        if (parent != null && lastOCElement?.parent === parent) {
                            lastOCElement = element
                            return@fold list
                        }
                        lastOCElement = element
                        val steppedIn = if (branches.first.fallthrough) branches.first else branches.second
                        val skipped = if (branches.first.fallthrough) branches.second else branches.first
                        list + run {
                            val startLine =
                                document.getLineNumber(
                                    element.getBranchMarkOffset() ?: element.textOffset
                                ) + 1
                            val startColumn =
                                (element.getBranchMarkOffset()
                                    ?: element.textOffset) - document.getLineStartOffset(
                                    startLine - 1
                                ) + 1
                            CoverageBranchData(
                                startLine toCP startColumn, steppedIn.count.toInt(), skipped.count.toInt()
                            )
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e)
                    emptyList()
                }
            }
        }
    }

    @Suppress("ConvertCallChainIntoSequence")
    private fun rootToCoverageData(root: Root, env: CPPEnvironment, project: Project): CoverageData {
        val sources = CMakeWorkspace.getInstance(project).module?.let { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap {
                it.sourceFolderFiles.toList()
            }.map {
                it.path
            }.toHashSet()
        } ?: emptySet()

        val filesToProcess = if (CoverageGeneratorSettings.getInstance().calculateExternalSources)
            root.files
        else root.files.filter {
            sources.contains(
                env.toLocalPath(
                    it.file
                ).replace('\\', '/')
            )
        }

        val files = filesToProcess.chunked(ceil(filesToProcess.size / Thread.activeCount().toDouble()).toInt()).map {

            CompletableFuture.supplyAsync {
                it.filter { it.lines.isNotEmpty() || it.functions.isNotEmpty() }.map { file ->
                    val linesOfFunction = file.lines.groupBy { it.functionName }
                    val functions = file.functions.map { function ->
                        val lines = linesOfFunction[function.name] ?: emptyList()
                        CoverageFunctionData(
                            function.startLine.toInt(),
                            function.endLine.toInt(),
                            function.demangledName,
                            FunctionLineData(lines.associate { it.lineNumber.toInt() to it.count.toLong() }),
                            if (CoverageGeneratorSettings.getInstance().branchCoverageEnabled)
                                findStatementsForBranches(
                                    lines,
                                    env.toLocalPath(file.file),
                                    project
                                ) else emptyList()
                        )
                    }.associateBy { it.functionName }
                    CoverageFileData(env.toLocalPath(file.file).replace('\\', '/'), functions)
                }
            }

        }.flatMap { it.get() }.associateBy { it.filePath }

        return CoverageData(
            files,
            CoverageGeneratorSettings.getInstance().branchCoverageEnabled,
            CoverageGeneratorSettings.getInstance().calculateExternalSources
        )
    }

    private data class Root(
        val currentWorkingDirectory: String,
        val files: List<File>
    )

    private data class File(val file: String, val functions: List<Function>, val lines: List<Line>)

    private data class Function(
        val demangledName: String,
        val endLine: Number,
        val name: String,
        val startLine: Number
    )

    private data class Line(
        val branches: List<Branch>,
        val count: Number,
        val lineNumber: Number,
        val functionName: String
    )

    private data class Branch(val count: Number, val fallthrough: Boolean, val throwing: Boolean)

    private fun processJson(
        jsonContents: List<String>,
        env: CPPEnvironment,
        project: Project
    ): CoverageData {

        val jsonStart = System.nanoTime()
        val files = jsonContents.flatMap { json ->
            val jsonObject = Parser.jackson().parse(StringReader(json)) as JsonObject
            val currentWorkingDirectory = jsonObject.string("current_working_directory")?.replace(
                '\n',
                '\\'
            ) ?: return@flatMap emptyList() //For some reason gcov uses \n instead of \\ on Windows?!
            val files = jsonObject.array<JsonObject>("files")?.mapNotNull fileMap@{
                val file = it.string("file") ?: return@fileMap null
                val functions = it.array<JsonObject>("functions")?.mapNotNull { function ->
                    val demangledName = function.string("demangled_name") ?: return@mapNotNull null
                    val endColumn = function["end_column"] as? Number ?: return@mapNotNull null
                    val name = function.string("name") ?: return@mapNotNull null
                    val startLine = function["start_line"] as? Number ?: return@mapNotNull null
                    Function(
                        demangledName,
                        endColumn,
                        name,
                        startLine
                    )
                } ?: return@fileMap null
                val lines = it.array<JsonObject>("lines")?.mapNotNull { line ->
                    val branches = line.array<JsonObject>("branches")?.mapNotNull branchMap@{ branch ->
                        val count = branch["count"] as? Number ?: return@branchMap null
                        val fallthrough = branch.boolean("fallthrough") ?: return@branchMap null
                        val throwing = branch.boolean("throw") ?: return@branchMap null
                        Branch(count, fallthrough, throwing)
                    } ?: return@mapNotNull null
                    val count = line["count"] as? Number ?: return@mapNotNull null
                    val lineNumber = line["line_number"] as? Number ?: return@mapNotNull null
                    val functionName = line.string("function_name") ?: ""
                    Line(branches, count, lineNumber, functionName)
                } ?: return@fileMap null
                File(file, functions, lines)
            } ?: return@flatMap emptyList()
            val root = Root(currentWorkingDirectory, files)

            root.files.map { file ->
                File(
                    if (Paths.get(env.toLocalPath(file.file)).isAbsolute) file.file else Paths.get(
                        env.toLocalPath(
                            currentWorkingDirectory
                        )
                    )
                        .resolve(env.toLocalPath(file.file))
                        .toRealPath(
                            LinkOption.NOFOLLOW_LINKS
                        ).toString().run {
                            env.toEnvPath(this)
                        },
                    file.functions,
                    file.lines
                )
            }
        }
        log.info("JSON processing took ${System.nanoTime() - jsonStart} ns")

        return rootToCoverageData(
            Root(files = files, currentWorkingDirectory = ""),
            env,
            project
        )
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
            NotificationGroupManager.getInstance().getNotificationGroup("C/C++ Coverage Notification")
                .createNotification(
                    "gcov returned error code $retCode",
                    "Invocation and error output:",
                    "Invocation: ${command.joinToString(" ")}\n Stderr: ${lines.joinToString("\n")}",
                    NotificationType.ERROR
                ).notify(configuration.project)
            return null
        }

        files.forEach { Files.deleteIfExists(Paths.get(environment.toLocalPath(it))) }

        return processJson(lines, environment, configuration.project)
    }
}