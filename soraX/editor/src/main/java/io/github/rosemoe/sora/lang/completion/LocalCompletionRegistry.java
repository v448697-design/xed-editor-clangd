package io.github.rosemoe.sora.lang.completion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.CodeEditor;

public final class LocalCompletionRegistry {

    private static volatile LocalCompletionProvider provider;

    private LocalCompletionRegistry() {}

    public static void setProvider(@Nullable LocalCompletionProvider newProvider) {
        provider = newProvider;
    }

    public static void provide(
            @NonNull CodeEditor editor,
            @NonNull ContentReference content,
            @NonNull CharPosition position,
            @NonNull CompletionPublisher publisher) {
        final LocalCompletionProvider current = provider;
        if (current == null) {
            return;
        }
        try {
            current.provideCompletions(editor, content, position, publisher);
        } catch (CompletionCancelledException ignored) {
            // A newer completion request has superseded this one.
        } catch (RuntimeException ignored) {
            // Local hints must never break completion coming from the language server.
        }
    }

    public static void onItemAccepted(@NonNull CodeEditor editor, @NonNull CharSequence label) {
        final LocalCompletionProvider current = provider;
        if (current == null) {
            return;
        }
        try {
            current.onItemAccepted(editor, label);
        } catch (RuntimeException ignored) {
            // Feedback must not interfere with committing the selected completion.
        }
    }
}
