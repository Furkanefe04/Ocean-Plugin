package ocean.plugin.inspections;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public class OceanRemoveImportQuickFix implements LocalQuickFix {
    private final String importPath;

    public OceanRemoveImportQuickFix(String importPath) {
        this.importPath = importPath;
    }

    @NotNull
    @Override
    public String getName() {
        return "Remove unused import '" + importPath + "'";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Remove unused import";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement el = descriptor.getPsiElement();
        if (el == null) return;
        PsiFile file = el.getContainingFile();
        if (file == null) return;
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;

        int startOffset = el.getTextRange().getStartOffset();
        if (startOffset >= document.getTextLength()) return;

        int lineNum = document.getLineNumber(startOffset);
        int lineStart = document.getLineStartOffset(lineNum);
        int deleteEnd = document.getLineEndOffset(lineNum);
        if (deleteEnd < document.getTextLength() && document.getText().charAt(deleteEnd) == '\r') {
            deleteEnd++;
        }
        if (deleteEnd < document.getTextLength() && document.getText().charAt(deleteEnd) == '\n') {
            deleteEnd++;
        }

        document.deleteString(lineStart, deleteEnd);
        PsiDocumentManager.getInstance(project).commitDocument(document);
    }
}
