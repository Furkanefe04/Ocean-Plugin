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
import ocean.plugin.OceanMethodDeclaration;
import org.jetbrains.annotations.NotNull;

import static ocean.plugin.OceanCompletionContributor.isRule;

/**
 * Quick-fix to add 'async' modifier to the enclosing function when 'await' is used.
 */
public class OceanMakeMethodAsyncQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final PsiElement awaitToken;

    public OceanMakeMethodAsyncQuickFix(PsiElement awaitToken) {
        this.awaitToken = awaitToken;
    }

    @NotNull
    @Override
    public String getText() {
        return "Make enclosing function 'async'";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Make Function Async";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return awaitToken != null && awaitToken.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        applyFix(project, awaitToken);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        applyFix(project, descriptor.getPsiElement());
    }

    public static void applyFix(Project project, PsiElement element) {
        if (element == null) return;
        PsiElement cur = element;
        PsiElement methodNode = null;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (cur instanceof OceanMethodDeclaration || isRule(cur, "methodDeclaration")) {
                methodNode = cur;
                break;
            }
            cur = cur.getParent();
        }
        if (methodNode == null) return;
        for (PsiElement child : methodNode.getChildren()) {
            if ("main".equals(child.getText().trim())) {
                return;
            }
        }

        PsiFile file = methodNode.getContainingFile();
        if (file == null) return;
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        // Ocean grammar: modifier* (type | VOID) function name(...)
        int offset = methodNode.getTextRange().getStartOffset();
        for (PsiElement child : methodNode.getChildren()) {
            if (isRule(child, "type") || "void".equals(child.getText().trim())) {
                offset = child.getTextRange().getStartOffset();
                break;
            }
        }
        final int insertOffset = offset;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.insertString(insertOffset, "async ");
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
