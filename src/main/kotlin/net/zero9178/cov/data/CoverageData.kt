package net.zero9178.cov.data

//All line and column numbers in the file start with 1 and 1
data class CoverageData(val files: Map<String, CoverageFileData>)

data class CoverageFileData(val filePath: String, val functions: Map<String, CoverageFunctionData>)

sealed class FunctionCoverageData {
    abstract val data: Any
}

class FunctionLineData(override val data: Map<Int, Long>) : FunctionCoverageData()

class FunctionRegionData(override val data: List<Region>) : FunctionCoverageData() {
    data class Region(val startPos: Pair<Int, Int>, val endPos: Pair<Int, Int>, val executionCount: Long)
}

data class CoverageFunctionData(
    val startLine: Int,
    val endLine: Int,
    val functionName: String,
    val coverage: FunctionCoverageData,
    val branches: List<CoverageBranchData>
)

data class CoverageBranchData(
    val startPos: Pair<Int, Int>,
    val steppedInCount: Int,
    val skippedCount: Int
)