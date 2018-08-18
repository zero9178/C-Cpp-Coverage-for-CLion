package icons;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

public interface CoverageIcons {
    Icon COVERAGE_RUNNER = IconLoader.getIcon("/icons/toolWindowCoverage.png");
    Icon LINE_COVERED = IconLoader.getIcon("/icons/Covered.png");
    Icon LINE_NOT_COVERED = IconLoader.getIcon("/icons/NotCovered.png");
}
