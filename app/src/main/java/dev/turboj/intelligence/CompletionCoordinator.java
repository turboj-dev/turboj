package dev.turboj.intelligence;

import dev.turboj.ui.EditorComponent;

import java.util.Objects;

/** The only owner of completion presentation. All methods run on the EDT. */
public final class CompletionCoordinator {
    private final EditorComponent editor;
    private long generation;
    private boolean semantic, explicit;

    public CompletionCoordinator(EditorComponent editor) {
        this.editor = Objects.requireNonNull(editor);
    }

    public long generation() {
        return generation;
    }

    public boolean hasSemanticChoice() {
        return semantic || explicit;
    }

    public void clear() {
        generation++;
        semantic = explicit = false;
        editor.clearInlineCompletion();
    }

    public boolean semantic(EditorComponent.InlineCompletion proposal, boolean selected) {
        if (explicit && !selected) return false;
        var previous = editor.isAIInlineCompletion() ? editor.inlineCompletion() : null;
        boolean shown = editor.setInlineCompletion(proposal);
        semantic = shown;
        explicit = selected;
        if (!shown && !selected && previous != null) editor.setAIInlineCompletion(previous);
        return shown;
    }

    public void alternatives(boolean visible) {
        explicit = visible;
    }

    public boolean ai(long ticket, EditorComponent.InlineCompletion proposal) {
        if (ticket != generation || hasSemanticChoice()) return false;
        return editor.setAIInlineCompletion(proposal);
    }

    public void clearAI() {
        if (editor.isAIInlineCompletion()) editor.clearInlineCompletion();
    }
}
