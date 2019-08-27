package net.zero9178.cov.data

import net.zero9178.cov.util.ComparablePair

//All line and column numbers in the file start with 1 and 1
data class CoverageData(val files: Map<String, CoverageFileData>)

data class CoverageFileData(val filePath: String, val functions: Map<String, CoverageFunctionData>)

sealed class FunctionCoverageData {
    abstract val data: Any
}

class FunctionLineData(override val data: Map<Int, Long>) : FunctionCoverageData()

class FunctionRegionData(override val data: List<Region>) : FunctionCoverageData() {
    data class Region(
        val startPos: ComparablePair<Int, Int>,
        val endPos: ComparablePair<Int, Int>,
        val executionCount: Long,
        val kind: Kind
    ) {
        enum class Kind {
            Code, Gap, Skipped, Expanded
        }
    }
}

data class CoverageFunctionData(
    val startLine: Int,
    val endLine: Int,
    val functionName: String,
    val coverage: FunctionCoverageData,
    val branches: List<CoverageBranchData>
)

data class CoverageBranchData(
    val startPos: ComparablePair<Int, Int>,
    val steppedInCount: Int,
    val skippedCount: Int
)