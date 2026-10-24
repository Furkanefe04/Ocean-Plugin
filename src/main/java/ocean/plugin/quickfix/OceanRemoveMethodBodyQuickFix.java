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
 * Quick-fix to remove method body '{ ... }' from an abstract or interface method and replace it with ';'.
 */
public class OceanRemoveMethodBodyQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final PsiElement blockNode;

    public OceanRemoveMethodBodyQuickFix(PsiElement blockNode) {
        this.blockNode = blockNode;
    }

    @NotNull
    @Override
    public String getText() {
        return "Remove method body";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Remove Method Body";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return blockNode != null && blockNode.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        applyFix(project, blockNode);
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

        PsiElement blockToReplace = target;
        if (target.getNode() != null && target.getText().contains("{")) {
            for (PsiElement child : target.getChildren()) {
                if (child.getNode() != null && child.getText().trim().startsWith("{")) {
                    blockToReplace = child;
                    break;
                }
            }
        }

        int start = blockToReplace.getTextRange().getStartOffset();
        int end = blockToReplace.getTextRange().getEndOffset();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.replaceString(start, end, ";");
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
