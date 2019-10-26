package icons;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

public interface CoverageIcons {
    Icon BRANCH_COVERED = IconLoader.getIcon("/icons/branchCovered.png");

    Icon BRANCH_NOT_COVERED = IconLoader.getIcon("/icons/branchNotCovered.png");
}
