package ocean.plugin.refactoring;

import com.intellij.lang.Language;
import com.intellij.lang.refactoring.InlineActionHandler;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import ocean.plugin.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles 'Inline Variable' refactoring (Ctrl + Alt + N) for Ocean files.
 * Inlines variable initializer value into all usages within scope and removes variable declaration.
 */
public class OceanInlineVariableActionHandler extends InlineActionHandler {

    @Override
    public boolean isEnabledForLanguage(Language l) {
        return l instanceof OceanLanguage;
    }

    @Override
    public boolean canInlineElement(PsiElement element) {
        OceanVariableDeclarator decl = getDeclarator(element);
        if (decl == null || decl.isField() || decl.getInitializer() == null) {
            return false;
        }
        return !isReassigned(decl);
    }

    public static boolean isReassigned(OceanVariableDeclarator decl) {
        String name = decl.getName();
        if (name == null || name.isEmpty()) return true;

        PsiElement scope = PsiTreeUtil.getParentOfType(decl, OceanMethodDeclaration.class);
        if (scope == null) scope = decl.getContainingFile();
        if (scope == null) return false;

        int declEnd = decl.getTextRange().getEndOffset();

        for (PsiElement el : PsiTreeUtil.collectElements(scope, e -> {
            if (e.getTextRange().getStartOffset() <= declEnd) return false;
            if (e.getFirstChild() != null) return false; // leaf only
            return name.equals(e.getText());
        })) {
            PsiElement next = PsiTreeUtil.nextVisibleLeaf(el);
            if (next != null) {
                String op = next.getText();
                if ("=".equals(op) || "+=".equals(op) || "-=".equals(op) || "*=".equals(op) || "/=".equals(op) || "++".equals(op) || "--".equals(op)) {
                    return true;
                }
            }
            PsiElement prev = PsiTreeUtil.prevVisibleLeaf(el);
            if (prev != null) {
                String prevOp = prev.getText();
                if ("++".equals(prevOp) || "--".equals(prevOp)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCompoundExpression(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("+") || text.contains("-") || text.contains("*") || text.contains("/")
                || text.contains("%") || text.contains("&&") || text.contains("||") || text.contains("?")
                || text.contains("==") || text.contains("!=") || text.contains("<") || text.contains(">")
                || text.contains("&") || text.contains("|") || text.contains("^") || text.contains("<<")
                || text.contains(">>") || text.contains("instanceof");
    }

    @Override
    public void inlineElement(Project project, Editor editor, PsiElement element) {
        OceanVariableDeclarator decl = getDeclarator(element);
        if (decl == null || decl.getInitializer() == null) return;
        if (isReassigned(decl)) return;

        String varName = decl.getName();
        PsiElement init = decl.getInitializer();
        String initText = init != null ? init.getText().trim() : "";
        if (varName == null || varName.isEmpty() || initText.isEmpty()) return;

        PsiFile file = decl.getContainingFile();
        if (file == null) return;
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) return;

        // Find enclosing scope (method or file)
        PsiElement scope = PsiTreeUtil.getParentOfType(decl, OceanMethodDeclaration.class);
        if (scope == null) scope = file;

        int declStart = decl.getTextRange().getStartOffset();
        int declEnd = decl.getTextRange().getEndOffset();

        // Collect all leaf occurrences of varName in scope (excluding declaration and member accesses)
        List<TextRange> usages = new ArrayList<>();
        Set<Integer> seenOffsets = new HashSet<>();

        for (PsiElement el : PsiTreeUtil.collectElements(scope, e -> {
            if (e.getFirstChild() != null) return false; // leaf only
            int start = e.getTextRange().getStartOffset();
            int end = e.getTextRange().getEndOffset();
            if (start >= declStart && end <= declEnd) {
                return false;
            }
            if (!varName.equals(e.getText())) {
                return false;
            }
            PsiElement prev = PsiTreeUtil.prevVisibleLeaf(e);
            if (prev != null && (".".equals(prev.getText()) || "?.".equals(prev.getText()))) {
                return false;
            }
            // Don't replace declarations of another variable/parameter/loop var
            if (e.getParent() instanceof OceanNamedElement named && named.getNameIdentifier() == e) {
                return false;
            }
            // Check reference resolution if available
            PsiReference ref = e.getReference();
            if (ref == null && e.getParent() != null) {
                ref = e.getParent().getReference();
            }
            if (ref != null) {
                PsiElement resolved = ref.resolve();
                return resolved == null || resolved == decl || resolved.getTextRange().equals(decl.getTextRange());
            }
            return true;
        })) {
            if (seenOffsets.add(el.getTextRange().getStartOffset())) {
                usages.add(el.getTextRange());
            }
        }

        // Parenthesize if complex or compound expression
        String replacementText = initText;
        if (isCompoundExpression(initText) && (!initText.startsWith("(") || !initText.endsWith(")"))) {
            replacementText = "(" + initText + ")";
        }

        String finalReplacement = replacementText;

        // Sort usages descending by offset to maintain integrity while replacing
        usages.sort((r1, r2) -> Integer.compare(r2.getStartOffset(), r1.getStartOffset()));

        WriteCommandAction.runWriteCommandAction(project, "Inline Variable", null, () -> {
            for (TextRange usage : usages) {
                document.replaceString(usage.getStartOffset(), usage.getEndOffset(), finalReplacement);
            }

            // Remove declaration statement
            PsiElement parent = decl.getParent();
            if (parent instanceof OceanVariableDeclaration varDecl) {
                OceanVariableDeclarator[] allDecls = PsiTreeUtil.getChildrenOfType(varDecl, OceanVariableDeclarator.class);
                if (allDecls != null && allDecls.length > 1) {
                    int dStart = decl.getTextRange().getStartOffset();
                    int dEnd = decl.getTextRange().getEndOffset();
                    String fullText = document.getText();
                    int nextIdx = dEnd;
                    while (nextIdx < fullText.length() && Character.isWhitespace(fullText.charAt(nextIdx))) nextIdx++;
                    if (nextIdx < fullText.length() && fullText.charAt(nextIdx) == ',') {
                        nextIdx++;
                        while (nextIdx < fullText.length() && fullText.charAt(nextIdx) == ' ') nextIdx++;
                        document.deleteString(dStart, nextIdx);
                    } else {
                        int prevIdx = dStart - 1;
                        while (prevIdx > 0 && Character.isWhitespace(fullText.charAt(prevIdx))) prevIdx--;
                        if (prevIdx >= 0 && fullText.charAt(prevIdx) == ',') {
                            document.deleteString(prevIdx, dEnd);
                        } else {
                            document.deleteString(dStart, dEnd);
                        }
                    }
                } else {
                    int lineNum = document.getLineNumber(varDecl.getTextRange().getStartOffset());
                    int lineStart = document.getLineStartOffset(lineNum);
                    int lineEnd = document.getLineEndOffset(lineNum);
                    String lineText = document.getText(new TextRange(lineStart, lineEnd)).trim();

                    if (lineText.equals(varDecl.getText().trim()) || lineText.equals(varDecl.getText().trim() + ";")) {
                        int deleteEnd = Math.min(lineEnd + 1, document.getTextLength());
                        document.deleteString(lineStart, deleteEnd);
                    } else {
                        document.deleteString(varDecl.getTextRange().getStartOffset(), varDecl.getTextRange().getEndOffset());
                    }
                }
            } else {
                document.deleteString(decl.getTextRange().getStartOffset(), decl.getTextRange().getEndOffset());
            }

            PsiDocumentManager.getInstance(project).commitDocument(document);
        });
    }

    private static OceanVariableDeclarator getDeclarator(PsiElement element) {
        if (element instanceof OceanVariableDeclarator vd) return vd;
        return PsiTreeUtil.getParentOfType(element, OceanVariableDeclarator.class);
    }
}
