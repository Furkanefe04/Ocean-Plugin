package ocean.plugin.quickfix;

import com.intellij.codeInsight.intention.HighPriorityAction;
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

/**
 * Quick-fix to remove '@Override' annotation from static methods or invalid targets.
 */
public class OceanRemoveOverrideQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final PsiElement annotationNode;

    public OceanRemoveOverrideQuickFix(PsiElement annotationNode) {
        this.annotationNode = annotationNode;
    }

    @NotNull
    @Override
    public String getText() {
        return "Remove '@Override' annotation";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Remove Override Annotation";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return annotationNode != null && annotationNode.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        applyFix(project, annotationNode);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        applyFix(project, descriptor.getPsiElement());
    }

    public static void applyFix(Project project, PsiElement target) {
        if (target == null) return;
        PsiFile file = target.getContainingFile();
        if (file == null) return;
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        int start = target.getTextRange().getStartOffset();
        int end = target.getTextRange().getEndOffset();

        // Also consume trailing whitespace if present
        String text = doc.getText();
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t' || text.charAt(end) == '\r' || text.charAt(end) == '\n')) {
            end++;
        }

        int finalEnd = end;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.deleteString(start, finalEnd);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
