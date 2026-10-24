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
import ocean.plugin.OceanCompletionContributor;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix to provide a default initializer for 'variable' or 'value' declaration lacking one.
 */
public class OceanInitializeVariableQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final PsiElement declaratorNode;

    public OceanInitializeVariableQuickFix(PsiElement declaratorNode) {
        this.declaratorNode = declaratorNode;
    }

    @NotNull
    @Override
    public String getText() {
        return "Initialize variable with default value";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Initialize Variable";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return declaratorNode != null && declaratorNode.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        applyFix(project, declaratorNode);
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

        String defaultVal = "null";
        PsiElement parent = target.getParent();
        if (parent != null) {
            for (PsiElement child : parent.getChildren()) {
                if (OceanCompletionContributor.isRule(child, "type")) {
                    String typeText = child.getText().trim();
                    if (typeText.endsWith("?")) {
                        defaultVal = "null";
                    } else {
                        defaultVal = switch (typeText) {
                            case "int", "long", "short", "byte" -> "0";
                            case "float" -> "0.0f";
                            case "double" -> "0.0";
                            case "bool", "boolean" -> "false";
                            case "char" -> "'\\0'";
                            case "string", "String" -> "\"\"";
                            default -> {
                                if (typeText.endsWith("[]") || typeText.startsWith("List") || typeText.startsWith("OceanList")) {
                                    yield "[]";
                                }
                                if (typeText.startsWith("Map") || typeText.startsWith("OceanMap")) {
                                    yield "{}";
                                }
                                yield "new " + typeText + "()";
                            }
                        };
                    }
                    break;
                }
            }
        }

        String finalInit = " = " + defaultVal;
        int end = target.getTextRange().getEndOffset();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.insertString(end, finalInit);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
