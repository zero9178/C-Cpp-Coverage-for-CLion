package gcov.data

class CoverageLineData(val lineNumber: Int, executionCount: Int) {
    var executionCount: Int = executionCount
        internal set

    val isCovered: Boolean
        get() = executionCount != 0

}