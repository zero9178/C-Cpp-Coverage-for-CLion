package net.zero9178.cov.util

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId

fun isCTestInstalled() =
    PluginManager.getInstance().findEnabledPlugin(PluginId.getId("org.jetbrains.plugins.clion.ctest")) != null