package ocean.plugin.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Line breakpoint type for Ocean source files (.ocean).
 * Enables placing breakpoints in the editor gutter for lines with executable code.
 */
public class OceanLineBreakpointType extends XLineBreakpointType<XBreakpointProperties<?>> {

    public OceanLineBreakpointType() {
        super("ocean-line", "Ocean Line Breakpoints");
    }

    @Nullable
    @Override
    public XBreakpointProperties<?> createBreakpointProperties(@NotNull VirtualFile file, int line) {
        return null;
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        if (!"ocean".equalsIgnoreCase(file.getExtension())) {
            return false;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return false;
        }

        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null || line < 0 || line >= document.getLineCount()) {
            return false;
        }

        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        String lineText = document.getText(new TextRange(start, end)).trim();

        return isLineBreakable(lineText);
    }

    public static boolean isLineBreakable(String lineText) {
        if (lineText == null) return false;
        String trimmed = lineText.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return false;
        if (trimmed.equals("{") || trimmed.equals("}") || trimmed.equals(";")) return false;
        if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) return false;
        // Type declaration headers are non-executable
        return !trimmed.matches("^(?:public|private|protected|internal|abstract|sealed|final)?\\s*(?:class|interface|enum|annotation)\\b.*");
    }
}
