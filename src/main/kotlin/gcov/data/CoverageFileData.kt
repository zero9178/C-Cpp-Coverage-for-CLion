package gcov.data

import java.util.*

class CoverageFileData(val filePath: String) {
    private val myLineData: MutableMap<Int, CoverageLineData> = HashMap()

    var functionData: MutableMap<String, CoverageFunctionData> = TreeMap()
        internal set

    val lineData: MutableMap<Int,CoverageLineData>
        get() = myLineData

    fun emplaceFunction(startLine: Int, endLine: Int, functionName: String) {
        this.emplaceFunction(CoverageFunctionData(startLine, endLine, functionName))
    }

    fun emplaceFunction(functionData: CoverageFunctionData) {
        val data = this.functionData[functionData.functionName]
        if (data == null) {
            functionData.fileData = this
            this.functionData[functionData.functionName] = functionData
        }
    }

    fun functionFromLine(line: Int): CoverageFunctionData =
            functionData.values.find{ it.lineIsInFunction(line) } ?: functionData.getOrDefault("<unknown function>", CoverageFunctionData(this))

    fun getLineDataAt(line: Int): CoverageLineData? = myLineData[line]
}