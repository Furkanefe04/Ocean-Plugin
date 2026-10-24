package ocean.plugin.inspections;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import ocean.plugin.OceanParser;
import ocean.plugin.OceanVariableDeclaration;
import ocean.plugin.OceanVariableDeclarator;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;

public class OceanRemoveUnusedVariableQuickFix implements LocalQuickFix {
    private final String varName;

    public OceanRemoveUnusedVariableQuickFix(String varName) {
        this.varName = varName;
    }

    @NotNull
    @Override
    public String getName() {
        return "Remove unused variable '" + varName + "'";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Remove unused variable";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (element == null) return;

        OceanVariableDeclarator declarator = element instanceof OceanVariableDeclarator vd ? vd :
                PsiTreeUtil.getParentOfType(element, OceanVariableDeclarator.class);
        if (declarator == null) return;

        PsiElement parent = declarator.getParent();
        PsiFile file = declarator.getContainingFile();
        if (file == null) return;
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);

        if (parent instanceof OceanVariableDeclaration varDecl && document != null) {
            OceanVariableDeclarator[] declarators = PsiTreeUtil.getChildrenOfType(varDecl, OceanVariableDeclarator.class);
            if (declarators == null || declarators.length <= 1) {
                int start = varDecl.getTextRange().getStartOffset();
                int end = varDecl.getTextRange().getEndOffset();
                int lineStart = document.getLineStartOffset(document.getLineNumber(start));
                int lineEnd = document.getLineEndOffset(document.getLineNumber(end));

                PsiElement initExpr = null;
                for (PsiElement c : declarator.getChildren()) {
                    if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_expression) {
                        initExpr = c;
                        break;
                    }
                }
                boolean hasSideEffect = false;
                String initText = "";
                if (initExpr != null) {
                    initText = initExpr.getText().trim();
                    if (initText.contains("(") && initText.contains(")")) {
                        hasSideEffect = true;
                    }
                }

                if (hasSideEffect) {
                    document.replaceString(start, end, initText + ";");
                } else {
                    String lineText = document.getText().substring(lineStart, lineEnd);
                    if (lineText.trim().equals(varDecl.getText().trim()) || lineText.trim().equals(varDecl.getText().trim() + ";")) {
                        int deleteEnd = Math.min(lineEnd + 1, document.getTextLength());
                        document.deleteString(lineStart, deleteEnd);
                    } else {
                        document.deleteString(start, end);
                    }
                }
                PsiDocumentManager.getInstance(project).commitDocument(document);
                return;
            }
        }

        try {
            if (parent instanceof OceanVariableDeclaration varDecl) {
                varDecl.delete();
            } else {
                declarator.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
