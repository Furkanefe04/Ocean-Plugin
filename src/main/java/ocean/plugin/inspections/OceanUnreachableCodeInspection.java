package ocean.plugin.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import ocean.plugin.OceanAnnotator;
import ocean.plugin.OceanParser;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Inspection that flags unreachable statements after terminal control flow jumps
 * (return, throw, stop, skip, terminating conditionals) as grayed-out dead code.
 */
public class OceanUnreachableCodeInspection extends LocalInspectionTool {

    private static final OceanAnnotator SHARED_ANNOTATOR = new OceanAnnotator();

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (isBlockElement(element)) {
                    checkBlockStatements(element, holder);
                }
                super.visitElement(element);
            }
        };
    }

    private static boolean isBlockElement(PsiElement element) {
        if (element.getNode() == null) return false;
        if (element.getNode().getElementType() instanceof RuleIElementType ri) {
            return ri.getRuleIndex() == OceanParser.RULE_block;
        }
        return false;
    }

    public static void checkBlockStatements(PsiElement block, ProblemsHolder holder) {
        if (block == null) return;
        PsiElement[] children = block.getChildren();
        boolean unreachable = false;

        for (PsiElement child : children) {
            String text = child.getText().trim();
            if (text.isEmpty() || text.equals("}") || text.equals("{") || text.startsWith("//") || text.startsWith("/*")) {
                continue;
            }

            if (unreachable) {
                holder.registerProblem(
                        child,
                        "Unreachable statement",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        new OceanDeleteDeadCodeQuickFix(child)
                );
            } else if (isTerminalStatement(text) || SHARED_ANNOTATOR.isStatementTerminating(child)) {
                unreachable = true;
            }
        }
    }

    public static boolean isTerminalStatement(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.matches("^(?:return|throw)(?:[\\s(;].*)?$") ||
               trimmed.matches("^(?:stop|skip)(?:[\\s;].*)?$");
    }
}
