package ocean.plugin.editor;

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import ocean.plugin.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles structural selection expansion (Ctrl + W) and shrinking (Ctrl + Shift + W)
 * for Ocean source files according to language syntax hierarchy.
 */
public class OceanExtendWordSelectionHandler extends ExtendWordSelectionHandlerBase {

    @Override
    public boolean canSelect(@NotNull PsiElement e) {
        return e.getLanguage().isKindOf(OceanLanguage.INSTANCE);
    }

    @Override
    public List<TextRange> select(@NotNull PsiElement e, @NotNull CharSequence editorText, int cursorOffset, @NotNull Editor editor) {
        List<TextRange> ranges = new ArrayList<>();

        // 1. String content without and with quotes
        TextRange elementRange = e.getTextRange();
        String text = e.getText();
        if (text != null && ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("$\"") && text.endsWith("\"")))) {
            int prefixLen = text.startsWith("$\"") ? 2 : 1;
            int suffixLen = 1;
            if (text.length() > prefixLen + suffixLen) {
                ranges.add(new TextRange(elementRange.getStartOffset() + prefixLen, elementRange.getEndOffset() - suffixLen));
            }
            ranges.add(elementRange);
        }

        // 2. Walk up structural hierarchy
        PsiElement current = e;
        while (current != null && !(current instanceof OceanFile)) {
            TextRange range = current.getTextRange();
            if (range != null && range.getLength() > 0 && !ranges.contains(range)) {
                // Check if element has braces { ... } or parens ( ... )
                String curText = current.getText();
                if (curText != null){
                    if (curText.startsWith("{") && curText.endsWith("}") && curText.length() > 2) {
                        TextRange inner = new TextRange(range.getStartOffset() + 1, range.getEndOffset() - 1);
                        if (!ranges.contains(inner)) {
                            ranges.add(inner);
                        }
                    } else if (curText.startsWith("(") && curText.endsWith(")") && curText.length() > 2) {
                        TextRange inner = new TextRange(range.getStartOffset() + 1, range.getEndOffset() - 1);
                        if (!ranges.contains(inner)) {
                            ranges.add(inner);
                        }
                    }
                }
                ranges.add(range);
            }

            // Also check for method, class or statement boundaries
            if (current instanceof OceanMethodDeclaration || current instanceof OceanClassDeclaration) {
                // Parent already handles further expansion
            }

            current = current.getParent();
        }

        return ranges;
    }
}
