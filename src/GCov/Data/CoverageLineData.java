package GCov.Data;

public class CoverageLineData {
    private final int m_lineNumber;
    private int m_executionCount;

    CoverageLineData(int lineNumber, int executionCount, boolean unexecutedBlock) {
        m_lineNumber = lineNumber;
        m_executionCount = executionCount;
    }

    int getLineNumber() {
        return m_lineNumber;
    }

    public int getExecutionCount() {
        return m_executionCount;
    }

    void setExecutionCount(int executionCount) {
        m_executionCount = executionCount;
    }

    public boolean isCovered() {
        return m_executionCount != 0;
    }
}