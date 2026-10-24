package ocean.plugin.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import ocean.plugin.*;
import ocean.plugin.quickfix.OceanRenameToUnnamedQuickFix;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Inspection that flags declared local variables that are never read/referenced in their scope.
 * Highlights with ProblemHighlightType.LIKE_UNUSED_SYMBOL (grayed-out text) and offers
 * a quick-fix to remove them.
 */
public class OceanUnusedSymbolInspection extends LocalInspectionTool {

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        Set<PsiElement> visited = new HashSet<>();
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof OceanVariableDeclarator declarator) {
                    if (visited.add(declarator)) {
                        checkVariableDeclarator(declarator, holder);
                    }
                } else if (element instanceof OceanParameter parameter) {
                    if (visited.add(parameter)) {
                        checkParameter(parameter, holder);
                    }
                } else if (element instanceof OceanLoopVariable loopVar) {
                    if (visited.add(loopVar)) {
                        checkLoopVariable(loopVar, holder);
                    }
                } else if (element instanceof OceanCatchVariable catchVar) {
                    if (visited.add(catchVar)) {
                        checkCatchVariable(catchVar, holder);
                    }
                } else if (element instanceof OceanPatternVariable patternVar) {
                    if (visited.add(patternVar)) {
                        checkPatternVariable(patternVar, holder);
                    }
                }
                super.visitElement(element);
            }
        };
    }

    private boolean isMatchingReference(PsiElement el, String varName) {
        if (OceanTypeHelper.isInterpolatedString(el)) {
            return OceanTypeHelper.interpolatedStringContainsVariable(el.getText(), varName);
        }
        if (!varName.equals(el.getText())) {
            return false;
        }
        // If el is preceded by '.' (dot), it is a member access (obj.varName), not this variable!
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(el);
        return prev == null || !".".equals(prev.getText());
    }

    private void checkVariableDeclarator(OceanVariableDeclarator declarator, ProblemsHolder holder) {
        if (declarator.isField()) {
            return; // Fields can be accessed externally, skip
        }

        String varName = declarator.getName();
        if (varName == null || varName.isBlank() || varName.equals("_")) {
            return;
        }

        // Find enclosing scope (method, loop, or file)
        PsiElement scope = PsiTreeUtil.getParentOfType(declarator, OceanMethodDeclaration.class);
        if (scope == null) {
            scope = declarator.getContainingFile();
        }
        if (scope == null) return;

        final PsiElement finalNameId = declarator.getNameIdentifier() != null ? declarator.getNameIdentifier() : declarator;

        int declStart = declarator.getTextRange().getStartOffset();
        int declEnd = declarator.getTextRange().getEndOffset();

        PsiElement initializer = declarator.getInitializer();
        int initStart = initializer != null ? initializer.getTextRange().getStartOffset() : -1;
        int initEnd = initializer != null ? initializer.getTextRange().getEndOffset() : -1;

        // Check if there are any references to this variable in scope
        boolean isUsed = false;
        for (PsiElement child : PsiTreeUtil.collectElements(scope, el -> {
            // Only consider leaf tokens (with no children)
            if (el.getFirstChild() != null) return false;

            int elStart = el.getTextRange().getStartOffset();
            int elEnd = el.getTextRange().getEndOffset();

            // Exclude the declarator itself and its name (anything inside declarator, except initializer)
            if (elStart >= declStart && elEnd <= declEnd) {
                if (initStart >= 0 && elStart >= initStart && elEnd <= initEnd) {
                    return isMatchingReference(el, varName);
                }
                return false;
            }

            return isMatchingReference(el, varName);
        })) {
            isUsed = true;
            break;
        }

        if (!isUsed) {
            holder.registerProblem(
                    finalNameId,
                    "Variable '" + varName + "' is never used",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    new OceanRemoveUnusedVariableQuickFix(varName),
                    new OceanRenameToUnnamedQuickFix(declarator)
            );
        }
    }

    private void checkParameter(OceanParameter parameter, ProblemsHolder holder) {
        String paramName = parameter.getName();
        if (paramName == null || paramName.isBlank() || paramName.equals("_")) {
            return;
        }

        OceanMethodDeclaration method = PsiTreeUtil.getParentOfType(parameter, OceanMethodDeclaration.class);
        if (method == null) return;
        if (method.getText().contains("@Override")) return;

        if (!method.getText().contains("{")) return;

        int paramStart = parameter.getTextRange().getStartOffset();
        int paramEnd = parameter.getTextRange().getEndOffset();

        boolean isUsed = false;
        for (PsiElement child : PsiTreeUtil.collectElements(method, el -> {
            if (el.getFirstChild() != null) return false;
            int elStart = el.getTextRange().getStartOffset();
            int elEnd = el.getTextRange().getEndOffset();
            if (elStart >= paramStart && elEnd <= paramEnd) return false;
            return isMatchingReference(el, paramName);
        })) {
            isUsed = true;
            break;
        }

        if (!isUsed) {
            PsiElement nameId = parameter.getNameIdentifier() != null ? parameter.getNameIdentifier() : parameter;
            holder.registerProblem(
                    nameId,
                    "Parameter '" + paramName + "' is never used",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    new OceanRenameToUnnamedQuickFix(parameter)
            );
        }
    }

    private void checkLoopVariable(OceanLoopVariable loopVar, ProblemsHolder holder) {
        String varName = loopVar.getName();
        if (varName == null || varName.isBlank() || varName.equals("_")) return;

        // Loop scope: the enclosing for statement
        PsiElement loopScope = loopVar.getParent();
        while (loopScope != null && !(loopScope instanceof OceanMethodDeclaration)) {
            String txt = loopScope.getText();
            if (txt != null && txt.startsWith("for")) break;
            loopScope = loopScope.getParent();
        }
        if (loopScope == null) return;

        int varStart = loopVar.getTextRange().getStartOffset();
        int varEnd = loopVar.getTextRange().getEndOffset();

        boolean isUsed = false;
        for (PsiElement child : PsiTreeUtil.collectElements(loopScope, el -> {
            if (el.getFirstChild() != null) return false;
            int elStart = el.getTextRange().getStartOffset();
            int elEnd = el.getTextRange().getEndOffset();
            if (elStart >= varStart && elEnd <= varEnd) return false;
            return isMatchingReference(el, varName);
        })) {
            isUsed = true;
            break;
        }

        if (!isUsed) {
            PsiElement nameId = loopVar.getNameIdentifier() != null ? loopVar.getNameIdentifier() : loopVar;
            holder.registerProblem(
                    nameId,
                    "Loop variable '" + varName + "' is never used",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    new OceanRenameToUnnamedQuickFix(loopVar)
            );
        }
    }

    private void checkCatchVariable(OceanCatchVariable catchVar, ProblemsHolder holder) {
        String varName = catchVar.getName();
        if (varName == null || varName.isBlank() || varName.equals("_")) return;

        // Catch scope: the enclosing catch clause
        PsiElement catchScope = catchVar.getParent();
        while (catchScope != null && !(catchScope instanceof OceanMethodDeclaration)) {
            String txt = catchScope.getText();
            if (txt != null && txt.startsWith("catch")) break;
            catchScope = catchScope.getParent();
        }
        if (catchScope == null) return;

        int varStart = catchVar.getTextRange().getStartOffset();
        int varEnd = catchVar.getTextRange().getEndOffset();

        boolean isUsed = false;
        for (PsiElement child : PsiTreeUtil.collectElements(catchScope, el -> {
            if (el.getFirstChild() != null) return false;
            int elStart = el.getTextRange().getStartOffset();
            int elEnd = el.getTextRange().getEndOffset();
            if (elStart >= varStart && elEnd <= varEnd) return false;
            return isMatchingReference(el, varName);
        })) {
            isUsed = true;
            break;
        }

        if (!isUsed) {
            PsiElement nameId = catchVar.getNameIdentifier() != null ? catchVar.getNameIdentifier() : catchVar;
            holder.registerProblem(
                    nameId,
                    "Catch variable '" + varName + "' is never used",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    new OceanRenameToUnnamedQuickFix(catchVar)
            );
        }
    }

    private void checkPatternVariable(OceanPatternVariable patternVar, ProblemsHolder holder) {
        String varName = patternVar.getName();
        if (varName == null || varName.isBlank() || varName.equals("_")) return;

        PsiElement scope = patternVar.getParent();
        while (scope != null && !(scope instanceof OceanMethodDeclaration)) {
            String txt = scope.getText();
            if (txt != null && (txt.startsWith("case") || txt.startsWith("if"))) break;
            scope = scope.getParent();
        }
        if (scope == null) return;

        int varStart = patternVar.getTextRange().getStartOffset();
        int varEnd = patternVar.getTextRange().getEndOffset();

        boolean isUsed = false;
        for (PsiElement child : PsiTreeUtil.collectElements(scope, el -> {
            if (el.getFirstChild() != null) return false;
            int elStart = el.getTextRange().getStartOffset();
            int elEnd = el.getTextRange().getEndOffset();
            if (elStart >= varStart && elEnd <= varEnd) return false;
            return isMatchingReference(el, varName);
        })) {
            isUsed = true;
            break;
        }

        if (!isUsed) {
            PsiElement nameId = patternVar.getNameIdentifier() != null ? patternVar.getNameIdentifier() : patternVar;
            holder.registerProblem(
                    nameId,
                    "Pattern variable '" + varName + "' is never used",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    new OceanRenameToUnnamedQuickFix(patternVar)
            );
        }
    }
}
