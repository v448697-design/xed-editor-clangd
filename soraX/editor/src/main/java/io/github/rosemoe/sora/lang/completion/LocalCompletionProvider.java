package io.github.rosemoe.sora.lang.completion;

import androidx.annotation.NonNull;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Supplies completions that do not require a language server. Called after the active editor
 * language has contributed its items, so both sources appear in the same popup.
 */
public interface LocalCompletionProvider {

    void provideCompletions(
            @NonNull CodeEditor editor,
            @NonNull ContentReference content,
            @NonNull CharPosition position,
            @NonNull CompletionPublisher publisher);

    /**
     * Called when the user commits a completion item, including items contributed by the language
     * server. Local providers can use this as acceptance feedback for their ranking model.
     */
    default void onItemAccepted(@NonNull CodeEditor editor, @NonNull CharSequence label) {}
}
