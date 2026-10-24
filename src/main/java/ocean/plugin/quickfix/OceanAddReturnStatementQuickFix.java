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
import ocean.plugin.OceanParser;
import org.jetbrains.annotations.NotNull;

import static ocean.plugin.OceanCompletionContributor.isRuleIndex;

/**
 * Quick-fix to append a default 'return <val>;' statement to a non-void method missing a return.
 */
public class OceanAddReturnStatementQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final PsiElement methodElement;
    private final String returnType;

    public OceanAddReturnStatementQuickFix(PsiElement methodElement, String returnType) {
        this.methodElement = methodElement;
        this.returnType = returnType != null ? returnType.trim() : "void";
    }

    @NotNull
    @Override
    public String getText() {
        String defaultVal = getDefaultReturnValue(returnType);
        return "Add 'return " + defaultVal + ";' statement";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Add Return Statement";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return methodElement != null && methodElement.isValid() && !"void".equalsIgnoreCase(returnType);
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        applyFix(project, methodElement, returnType);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        applyFix(project, descriptor.getPsiElement(), returnType);
    }

    public static String getDefaultReturnValue(String returnType) {
        if (returnType == null) return "null";
        return switch (returnType.trim()) {
            case "int", "long", "short", "byte" -> "0";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            case "bool", "boolean" -> "false";
            case "char" -> "'\\0'";
            default -> "null";
        };
    }

    public static void applyFix(Project project, PsiElement element, String returnType) {
        if (element == null) return;
        PsiFile file = element.getContainingFile();
        if (file == null) return;
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        // Climb to enclosing methodDeclaration if element is a child token/identifier
        PsiElement methodDecl = element;
        while (methodDecl != null && !isRuleIndex(methodDecl, OceanParser.RULE_methodDeclaration)) {
            if (methodDecl instanceof PsiFile) {
                methodDecl = null;
                break;
            }
            methodDecl = methodDecl.getParent();
        }
        if (methodDecl == null) methodDecl = element;

        // Find the block node
        PsiElement blockNode = null;
        for (PsiElement child : methodDecl.getChildren()) {
            if (isRuleIndex(child, OceanParser.RULE_block)) {
                blockNode = child;
                break;
            }
        }
        if (blockNode == null) return;

        int closeBraceOffset = blockNode.getTextRange().getEndOffset() - 1;
        if (closeBraceOffset < 0) return;

        String defaultVal = getDefaultReturnValue(returnType);
        String returnStmt = "\n        return " + defaultVal + ";\n    ";

        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.insertString(closeBraceOffset, returnStmt);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
