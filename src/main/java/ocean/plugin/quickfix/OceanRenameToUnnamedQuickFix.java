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
import ocean.plugin.OceanNamedElement;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix to rename an unused variable to '_' (unnamed variable in Ocean V3).
 */
public class OceanRenameToUnnamedQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final PsiElement element;

    public OceanRenameToUnnamedQuickFix(PsiElement element) {
        this.element = element;
    }

    @NotNull
    @Override
    public String getText() {
        return "Rename to '_' (unnamed variable)";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Rename to Unnamed Variable";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return element != null && element.isValid() && !"_".equals(element.getText());
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        applyFix(project, element);
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

        PsiElement nameNode = target;
        if (target instanceof OceanNamedElement named && named.getNameIdentifier() != null) {
            nameNode = named.getNameIdentifier();
        }

        int start = nameNode.getTextRange().getStartOffset();
        int end = nameNode.getTextRange().getEndOffset();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.replaceString(start, end, "_");
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
