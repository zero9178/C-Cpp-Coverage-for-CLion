package net.zero9178.cov.data

data class CoverageData(var data: Map<String, CoverageFileData>)

data class CoverageFileData(val filePath: String, var functionData: Map<String, CoverageFunctionData>)

data class CoverageFunctionData(
    var startLine: Int,
    var endLine: Int,
    var functionName: String,
    var lines: Map<Int, CoverageLineData>
)

data class CoverageLineData(val lineNumber: Int, var executionCount: Int)