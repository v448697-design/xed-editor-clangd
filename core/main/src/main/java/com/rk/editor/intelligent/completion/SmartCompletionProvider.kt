package com.rk.editor.intelligent.completion

import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.LocalCompletionProvider
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor

class SmartCompletionProvider(private val engine: SmartCompletionEngine) : LocalCompletionProvider {

    override fun provideCompletions(
        editor: CodeEditor,
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher
    ) {
        if (!SmartCompletion.enabled) return
        val context = SmartCompletion.contextOf(editor) ?: return
        val line = runCatching { editor.text.getLineString(position.line) }.getOrNull() ?: return
        val column = position.column.coerceIn(0, line.length)
        var start = column
        while (start > 0 && isIdentifierPart(line[start - 1])) start--
        if (column - start > 40) return
        val prefix = line.substring(start, column)
        val suggestions = engine.suggest(context.projectKey, context.filePath, prefix)
        if (suggestions.isEmpty()) return
        val items = suggestions
            .filterNot { publisher.hasLabel(it.text) }
            .map { suggestion -> LearnedCompletionItem(suggestion, prefix.length) }
        if (items.isEmpty()) return
        publisher.addItems(items)
    }

    override fun onItemAccepted(editor: CodeEditor, label: CharSequence) {
        SmartCompletion.onAccepted(editor, label.toString())
    }

    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()
}

private class LearnedCompletionItem(
    suggestion: SmartSuggestion,
    prefixLength: Int
) : SimpleCompletionItem(suggestion.text, prefixLength, suggestion.text) {

    init {
        desc(suggestion.source)
        localCompletion = true
    }
}
