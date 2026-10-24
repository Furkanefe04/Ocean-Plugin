package ocean.plugin.inspections;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

public class OceanDeleteDeadCodeQuickFix implements LocalQuickFix, IntentionAction {
    private final PsiElement element;

    public OceanDeleteDeadCodeQuickFix() {
        this(null);
    }

    public OceanDeleteDeadCodeQuickFix(PsiElement element) {
        this.element = element;
    }

    @NotNull
    @Override
    public String getText() {
        return "Delete unreachable code";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Delete unreachable code";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return element == null || element.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        if (element != null && element.isValid()) {
            applyFix(project, element);
        } else if (editor != null && file != null) {
            PsiElement el = file.findElementAt(editor.getCaretModel().getOffset());
            if (el != null) {
                applyFix(project, el);
            }
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        applyFix(project, descriptor.getPsiElement());
    }

    public static void applyFix(Project project, PsiElement el) {
        if (el == null) return;
        PsiFile file = el.getContainingFile();
        if (file == null) return;
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        int start = el.getTextRange().getStartOffset();
        int end = el.getTextRange().getEndOffset();
        int lineNum = doc.getLineNumber(start);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText().substring(lineStart, lineEnd).trim();

        WriteCommandAction.runWriteCommandAction(project, "Delete Unreachable Code", null, () -> {
            if (lineText.equals(el.getText().trim()) || lineText.equals(el.getText().trim() + ";")) {
                int deleteEnd = Math.min(lineEnd + 1, doc.getTextLength());
                doc.deleteString(lineStart, deleteEnd);
            } else {
                doc.deleteString(start, end);
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }
}
