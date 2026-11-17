package com.rk.editor.intelligent.completion

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.completion.LocalCompletionRegistry
import io.github.rosemoe.sora.lang.styling.StylesUtils
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import java.util.WeakHashMap

data class SmartCompletionContext(val projectKey: String, val filePath: String)

object SmartCompletion {

    private const val TAG = "SmartCompletion"
    private const val MAX_SEED_CHARS = 32_768

    @Volatile private var engine: SmartCompletionEngine? = null
    private val contexts = WeakHashMap<CodeEditor, SmartCompletionContext>()

    @Volatile var enabled: Boolean = true

    fun init(context: Context) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            val created = SmartCompletionEngine(context)
            engine = created
            LocalCompletionRegistry.setProvider(SmartCompletionProvider(created))
            Log.d(TAG, "local completion provider registered")
        }
    }

    fun attach(editor: CodeEditor, projectRoot: String?, filePath: String?) {
        val current = engine ?: return
        if (filePath.isNullOrBlank() || !filePath.startsWith("/")) return
        val root = projectRoot?.takeIf { it.isNotBlank() && it.startsWith("/") }
            ?: File(filePath).parent
            ?: return
        val context = SmartCompletionContext(current.projectKey(root), filePath)
        synchronized(contexts) { contexts[editor] = context }
        val tail = readTail(editor)
        current.attach(context.projectKey, context.filePath, tail)
    }

    fun contextOf(editor: CodeEditor): SmartCompletionContext? =
        synchronized(contexts) { contexts[editor] }

    fun onTextChanged(
        editor: CodeEditor,
        action: Int,
        changedText: CharSequence,
        position: CharPosition
    ) {
        val current = engine ?: return
        val context = contextOf(editor) ?: return
        when (action) {
            ContentChangeEvent.ACTION_INSERT ->
                if (!isCompletionBlocked(editor, position)) {
                    current.onInsert(context.projectKey, context.filePath, changedText)
                }
            ContentChangeEvent.ACTION_DELETE ->
                current.onDelete(context.projectKey, context.filePath, changedText)
            ContentChangeEvent.ACTION_SET_NEW_TEXT ->
                current.attach(context.projectKey, context.filePath, readTail(editor))
        }
    }

    private fun isCompletionBlocked(editor: CodeEditor, position: CharPosition): Boolean {
        val span = StylesUtils.getSpanForPosition(editor.styles, position) ?: return false
        return TextStyle.isNoCompletion(span.style)
    }

    fun onAccepted(editor: CodeEditor, text: String) {
        val current = engine ?: return
        val context = contextOf(editor) ?: return
        current.onAccepted(context.projectKey, context.filePath, text)
    }

    fun flush() {
        engine?.saveAll()
    }

    private fun readTail(editor: CodeEditor): CharSequence {
        val content = editor.text
        val lines = java.util.ArrayDeque<String>()
        var length = 0
        var line = content.lineCount - 1
        while (line >= 0 && length < MAX_SEED_CHARS) {
            val text = content.getLineString(line)
            lines.addFirst(text)
            length += text.length + 1
            line--
        }
        val builder = StringBuilder(minOf(length, MAX_SEED_CHARS + 128))
        for (text in lines) {
            builder.append(text).append('\n')
        }
        if (builder.length > MAX_SEED_CHARS) {
            builder.delete(0, builder.length - MAX_SEED_CHARS)
        }
        return builder
    }
}
