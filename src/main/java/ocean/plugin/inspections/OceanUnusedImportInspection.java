package ocean.plugin.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import ocean.plugin.OceanFile;
import ocean.plugin.OceanParser;
import ocean.plugin.imports.OceanImportOptimizer;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspection that flags unused import statements in Ocean files as grayed-out text
 * (ProblemHighlightType.LIKE_UNUSED_SYMBOL) and offers a quick fix to remove them.
 */
public class OceanUnusedImportInspection extends LocalInspectionTool {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^[ \t]*import\\s+(?:(static)\\s+)?([a-zA-Z0-9_.*]+)\\s*;[ \t]*"
    );

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof OceanFile)) return;
                checkImports((OceanFile) file, holder);
                super.visitFile(file);
            }
        };
    }

    private void checkImports(OceanFile file, ProblemsHolder holder) {
        String text = file.getText();
        Matcher matcher = IMPORT_PATTERN.matcher(text);

        // Body without imports for usage lookup, masked for comments and strings
        String bodyWithoutImports = text.replaceAll("(?m)^[ \t]*import\\s+[^;]+;[ \t]*\r?\n?", " ");
        bodyWithoutImports = OceanImportOptimizer.maskCommentsAndStrings(bodyWithoutImports);

        while (matcher.find()) {
            boolean isStatic = matcher.group(1) != null;
            String fullPath = matcher.group(2).trim();
            if (fullPath.endsWith(".*")) {
                continue; // Keep wildcards
            }

            String simpleName = OceanImportOptimizer.getSimpleName(fullPath);
            if (!OceanImportOptimizer.isUsedInText(bodyWithoutImports, simpleName)) {
                PsiElement el = file.findElementAt(matcher.start());
                if (el != null) {
                    PsiElement targetNode = null;
                    PsiElement curr = el;
                    while (curr != null && !(curr instanceof OceanFile)) {
                        if (curr.getNode() != null && curr.getNode().getElementType() instanceof RuleIElementType ri
                                && ri.getRuleIndex() == OceanParser.RULE_importStatement) {
                            targetNode = curr;
                            break;
                        }
                        curr = curr.getParent();
                    }
                    if (targetNode == null) {
                        targetNode = el;
                    }
                    String displayPath = (isStatic ? "static " : "") + fullPath;
                    holder.registerProblem(
                            targetNode,
                            "Unused import '" + displayPath + "'",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            new OceanRemoveImportQuickFix(displayPath)
                    );
                }
            }
        }
    }
}
