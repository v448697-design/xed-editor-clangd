package com.rk.commands.editor

import android.view.KeyEvent
import com.rk.DefaultScope
import com.rk.commands.EditorActionContext
import com.rk.commands.EditorCommand
import com.rk.commands.EditorNonActionContext
import com.rk.commands.KeyCombination
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.runner.RunnerManager
import com.rk.settings.Settings
import com.rk.tabs.editor.EditorTab
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(DelicateCoroutinesApi::class)
class RunCommand : EditorCommand() {
    override val id: String = "editor.run"

    override fun getLabel(): String = strings.run.getString()

    override fun action(editorActionContext: EditorActionContext) {
        val editorTab = editorActionContext.editorTab
        val activity = editorActionContext.currentActivity
        DefaultScope.launch {
            withContext(Dispatchers.IO) {
                editorActionContext.mainViewModel.tabs.forEach { tab ->
                    if (tab is EditorTab) {
                        runCatching { tab.save() }
                    }
                }
            }
            Settings.runs += 1
            RunnerManager.run(
                activity = activity,
                fileObject = editorTab.file,
                onMultipleRunners = {
                    editorTab.editorState.showRunnerDialog = true
                    editorTab.editorState.runnersToShow = it
                },
            )
        }
    }

    override fun isSupported(editorNonActionContext: EditorNonActionContext): Boolean {
        return RunnerManager.isRunnable(editorNonActionContext.editorTab.file)
    }

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.run)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_F5)
}
