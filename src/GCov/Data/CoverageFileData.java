package GCov.Data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class CoverageFileData {

    private final String m_filePath;
    private final Map<String, CoverageFunctionData> m_data = new TreeMap<>();
    private final Map<Integer, CoverageLineData> m_lineDataMap = new HashMap<>();

    CoverageFileData(String filePath,boolean projectSource) {
        m_filePath = filePath;
    }

    void emplaceFunction(int startLine, int endLine, int executionCount, String functionName) {
        this.emplaceFunction(new CoverageFunctionData(startLine, endLine, executionCount, functionName));
    }

    private void emplaceFunction(@NotNull CoverageFunctionData functionData) {
        CoverageFunctionData data = m_data.get(functionData.getFunctionName());
        if (data == null) {
            functionData.setFileData(this);
            m_data.put(functionData.getFunctionName(), functionData);
        } else {
            data.setExecutionCount(data.getExecutionCount() + functionData.getExecutionCount());
        }
    }

    @NotNull CoverageFunctionData functionFromLine(int line) {
        for (Map.Entry<String, CoverageFunctionData> entry : m_data.entrySet()) {
            if (entry.getValue().lineIsInFunction(line)) {
                return entry.getValue();
            }
        }
        CoverageFunctionData unknown = m_data.get("<unknown function>");
        if (unknown == null) {
            unknown = new CoverageFunctionData(-1, -1, 0, "<unknown function>");
            unknown.setFileData(this);
            m_data.put("<unkown function>", unknown);
        }
        return unknown;
    }

    public Map<String, CoverageFunctionData> getFunctionData() {
        return m_data;
    }

    Map<Integer,CoverageLineData> getLineData() {
        return m_lineDataMap;
    }

    public String getFilePath() {
        return m_filePath;
    }

    @Nullable
    public CoverageLineData getLineDataAt(int line) {
        return m_lineDataMap.get(line);
    }
}