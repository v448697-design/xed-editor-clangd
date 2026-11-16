package com.rk.commands.editor

import com.rk.DefaultScope
import com.rk.commands.EditorActionContext
import com.rk.commands.EditorCommand
import com.rk.commands.EditorNonActionContext
import com.rk.exec.TerminalCommand
import com.rk.exec.launchTerminal
import com.rk.file.FileWrapper
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.app.InbuiltFeatures
import com.rk.tabs.editor.EditorTab
import com.rk.terminal.setupAssetFile
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(DelicateCoroutinesApi::class)
class CompileCommand : EditorCommand() {
    override val id: String = "editor.compile"

    override fun getLabel(): String = strings.compile.getString()

    override fun action(editorActionContext: EditorActionContext) {
        val editorTab = editorActionContext.editorTab
        val activity = editorActionContext.currentActivity
        val file = editorTab.file
        val tabs = commandContext.mainViewModel.tabs

        DefaultScope.launch {
            withContext(Dispatchers.IO) {
                tabs.forEach { tab -> if (tab is EditorTab) runCatching { tab.save() } }
                setupAssetFile("compile_runner", force = true)
            }

            launchTerminal(
                activity = activity,
                terminalCommand =
                    TerminalCommand(
                        sandbox = true,
                        exe = "/bin/bash",
                        args =
                            arrayOf(
                                localBinDir().child("compile_runner").absolutePath,
                                file.getAbsolutePath(),
                            ),
                        id = strings.compile.getString(),
                        terminatePreviousSession = true,
                        workingDir = file.getParentFile()?.getAbsolutePath() ?: "/",
                    ),
            )
        }
    }

    override fun isSupported(editorNonActionContext: EditorNonActionContext): Boolean {
        val file = editorNonActionContext.editorTab.file
        if (!InbuiltFeatures.terminal.state.value) return false
        if (file !is FileWrapper) return false
        return file.getExtension().lowercase() in SUPPORTED_EXTENSIONS
    }

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.build)

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("c", "cpp", "cc", "cxx", "c++")
    }
}
