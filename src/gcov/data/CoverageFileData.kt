package gcov.data

import java.util.HashMap
import java.util.TreeMap

class CoverageFileData(val filePath: String) {
    private val myData = TreeMap<String, CoverageFunctionData>()
    private val myLineData: MutableMap<Int, CoverageLineData> = HashMap()

    val functionData: Map<String, CoverageFunctionData>
        get() = myData

    val lineData: MutableMap<Int,CoverageLineData>
        get() = myLineData

    fun emplaceFunction(startLine: Int, endLine: Int, executionCount: Int, functionName: String) {
        this.emplaceFunction(CoverageFunctionData(startLine, endLine, functionName,executionCount))
    }

    fun emplaceFunction(functionData: CoverageFunctionData) {
        val data = myData[functionData.functionName]
        if (data == null) {
            functionData.fileData = this
            myData[functionData.functionName] = functionData
        } else {
            data.executionCount += functionData.executionCount
        }
    }

    fun functionFromLine(line: Int): CoverageFunctionData =
            myData.values.find{ it.lineIsInFunction(line) } ?: myData.getOrDefault("<unknown function>", CoverageFunctionData(this))

    fun getLineDataAt(line: Int): CoverageLineData? = myLineData[line]
}