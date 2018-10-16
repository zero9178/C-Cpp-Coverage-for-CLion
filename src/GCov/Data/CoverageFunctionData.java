package GCov.Data;

import java.util.Map;
import java.util.TreeMap;

public class CoverageFunctionData {

    private final Map<Integer, CoverageLineData> m_lines = new TreeMap<>();
    private final int m_startLine;
    private final int m_endLine;
    private int m_executionCount;
    private final String m_functionName;
    private CoverageFileData m_fileData;

    public CoverageFileData getFileData() {
        return m_fileData;
    }

    void setFileData(CoverageFileData fileData) {
        m_fileData = fileData;
    }

    CoverageFunctionData(int startLine, int endLine, int executionCount, String functionName) {
        m_startLine = startLine;
        m_endLine = endLine;
        m_executionCount = executionCount;
        m_functionName = functionName;
    }

    void emplaceLine(int lineNumber, int executionCount, boolean unexecutedBlock) {
        this.emplaceLine(new CoverageLineData(lineNumber, executionCount, unexecutedBlock));
    }

    private void emplaceLine(CoverageLineData lineData) {
        CoverageLineData line = m_lines.get(lineData.getLineNumber());
        if (line == null) {
            m_lines.put(lineData.getLineNumber(), lineData);
            m_fileData.getLineData().put(lineData.getLineNumber(),lineData);
        } else {
            line.setExecutionCount(line.getExecutionCount() + lineData.getExecutionCount());
        }
    }

    boolean lineIsInFunction(int line) {
        return line >= m_startLine && line <= m_endLine;
    }

    public int getStartLine() {
        return m_startLine;
    }

    int getExecutionCount() {
        return m_executionCount;
    }

    void setExecutionCount(int executionCount) {
        m_executionCount = executionCount;
    }

    String getFunctionName() {
        return m_functionName;
    }

    public int getCoverage() {
        int coverage = 0;
        for(Map.Entry<Integer, CoverageLineData> entry : m_lines.entrySet()) {
            if(entry.getValue().getExecutionCount() > 0) {
                coverage++;
            }
        }
        return coverage;
    }

    public Map<Integer, CoverageLineData> getLines() {
        return m_lines;
    }

    @Override
    public String toString() {
        return m_functionName;
    }
}