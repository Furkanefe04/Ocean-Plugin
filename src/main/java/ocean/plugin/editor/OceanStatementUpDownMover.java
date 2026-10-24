package ocean.plugin.editor;

import com.intellij.codeInsight.editorActions.moveUpDown.LineRange;
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import ocean.plugin.OceanFile;
import org.jetbrains.annotations.NotNull;

/**
 * Handles moving statements, blocks, and methods up and down (Ctrl + Shift + Up / Down)
 * while respecting block boundaries in Ocean source files.
 */
public class OceanStatementUpDownMover extends StatementUpDownMover {

    @Override
    public boolean checkAvailable(@NotNull Editor editor, @NotNull PsiFile file, @NotNull MoveInfo info, boolean down) {
        if (!(file instanceof OceanFile)) {
            return false;
        }

        LineRange selectionRange = getLineRangeFromSelection(editor);

        Document document = editor.getDocument();
        int totalLines = document.getLineCount();
        int startLine = selectionRange.startLine;
        int endLine = selectionRange.endLine;

        // Check if selected range is a member declaration (function/class)
        String selectedSnippet = document.getText().substring(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine - 1)).trim();
        boolean isMovingMember = selectedSnippet.contains("function ") ||
                selectedSnippet.startsWith("class ") ||
                selectedSnippet.startsWith("interface ") ||
                selectedSnippet.startsWith("enum ");

        if (down) {
            if (endLine >= totalLines) {
                return false;
            }

            int targetLine = endLine;
            // Scan next non-blank line
            while (targetLine < totalLines && document.getText().substring(document.getLineStartOffset(targetLine), document.getLineEndOffset(targetLine)).trim().isEmpty()) {
                targetLine++;
            }
            if (targetLine >= totalLines) {
                return false;
            }

            // Boundary protection: do not move beyond closing brace '}' of enclosing block
            String targetLineText = document.getText().substring(document.getLineStartOffset(targetLine), document.getLineEndOffset(targetLine)).trim();
            if (targetLineText.startsWith("}")) {
                if (!isMovingMember) {
                    return false;
                }
            }

            info.toMove = selectionRange;
            info.toMove2 = new LineRange(targetLine, targetLine + 1);
        } else {
            if (startLine <= 0) {
                return false;
            }

            int targetLine = startLine - 1;
            while (targetLine >= 0 && document.getText().substring(document.getLineStartOffset(targetLine), document.getLineEndOffset(targetLine)).trim().isEmpty()) {
                targetLine--;
            }
            if (targetLine < 0) {
                return false;
            }

            // Boundary protection: do not move above class header
            String targetLineText = document.getText().substring(document.getLineStartOffset(targetLine), document.getLineEndOffset(targetLine)).trim();
            if (targetLineText.startsWith("class ") || targetLineText.startsWith("interface ") || targetLineText.startsWith("enum ")) {
                return false;
            }

            if (!isMovingMember && (targetLineText.endsWith("{") || targetLineText.contains("function "))) {
                return false;
            }

            info.toMove = selectionRange;
            info.toMove2 = new LineRange(targetLine, targetLine + 1);
        }
        return true;
    }
}
