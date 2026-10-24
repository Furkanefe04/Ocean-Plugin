package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanFoldingBuilder extends FoldingBuilderEx implements DumbAware {
    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
        List<FoldingDescriptor> descriptors = new ArrayList<>();
        foldImports(root, descriptors);
        foldCustomRegions(root, document, descriptors);
        collect(root, descriptors);
        return descriptors.toArray(new FoldingDescriptor[0]);
    }

    private void foldCustomRegions(PsiElement root, Document document, List<FoldingDescriptor> descriptors) {
        String text = document.getText();
        Pattern regionPattern = Pattern.compile("//\\s*region\\b(.*)");
        Pattern endRegionPattern = Pattern.compile("//\\s*endregion\\b");

        Matcher rm = regionPattern.matcher(text);
        Stack<int[]> stack = new Stack<>(); // [startOffset, nameStart, nameEnd]
        Map<Integer, String> regionNames = new HashMap<>();

        // Collect regions line by line
        int lines = document.getLineCount();
        for (int i = 0; i < lines; i++) {
            int lineStart = document.getLineStartOffset(i);
            int lineEnd = document.getLineEndOffset(i);
            String line = text.substring(lineStart, lineEnd).trim();

            if (line.startsWith("//region") || line.startsWith("// region")) {
                String name = line.replaceFirst("//\\s*region\\s*", "").trim();
                if (name.isEmpty()) name = "region";
                stack.push(new int[]{lineStart, lineEnd});
                regionNames.put(lineStart, name);
            } else if (line.startsWith("//endregion") || line.startsWith("// endregion")) {
                if (!stack.isEmpty()) {
                    int[] startInfo = stack.pop();
                    int startOffset = startInfo[0];
                    String desc = regionNames.getOrDefault(startOffset, "region");
                    TextRange range = new TextRange(startOffset, lineEnd);
                    descriptors.add(new FoldingDescriptor(root.getNode(), range) {
                        @Override
                        public String getPlaceholderText() {
                            return desc;
                        }
                    });
                }
            }
        }
    }

    private void foldImports(PsiElement root, List<FoldingDescriptor> descriptors) {
        PsiElement[] compUnits = PsiTreeUtil.collectElements(root, e ->
                e.getNode() != null &&
                e.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == OceanParser.RULE_compilationUnit
        );
        for (PsiElement cu : compUnits) {
            List<PsiElement> currentGroup = new ArrayList<>();
            for (PsiElement child = cu.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNode() != null &&
                        child.getNode().getElementType() instanceof RuleIElementType ri &&
                        ri.getRuleIndex() == OceanParser.RULE_importStatement) {
                    currentGroup.add(child);
                } else if (!child.getText().trim().isEmpty() && !(child instanceof PsiWhiteSpace)) {
                    addImportsGroupDescriptor(currentGroup, descriptors);
                    currentGroup.clear();
                }
            }
            addImportsGroupDescriptor(currentGroup, descriptors);
        }
    }

    private void addImportsGroupDescriptor(List<PsiElement> group, List<FoldingDescriptor> descriptors) {
        if (group.size() > 1) {
            PsiElement first = group.get(0);
            PsiElement last = group.get(group.size() - 1);
            TextRange range = new TextRange(first.getTextRange().getStartOffset(), last.getTextRange().getEndOffset());
            descriptors.add(new FoldingDescriptor(first.getNode(), range) {
                @Override
                public String getPlaceholderText() {
                    return "import ...";
                }
            });
        }
    }

    private void collect(PsiElement element, List<FoldingDescriptor> descriptors) {
        if (shouldFold(element)) {
            TextRange range = getFoldingRange(element);
            if (range != null && range.getLength() > 2 && element.getText().contains("\n")) {
                descriptors.add(new FoldingDescriptor(element.getNode(), range));
            }
        }
        for (PsiElement child : element.getChildren()) {
            collect(child, descriptors);
        }
    }

    private TextRange getFoldingRange(PsiElement element) {
        if (element.getNode() == null) return null;
        if (element.getNode().getElementType() instanceof RuleIElementType ri) {
            int rule = ri.getRuleIndex();
            if (rule == OceanParser.RULE_classDeclaration ||
                rule == OceanParser.RULE_interfaceDeclaration ||
                rule == OceanParser.RULE_enumDeclaration ||
                rule == OceanParser.RULE_annotationDeclaration ||
                rule == OceanParser.RULE_switchStatement) {
                String text = element.getText();
                int lbrace = text.indexOf('{');
                int rbrace = text.lastIndexOf('}');
                if (lbrace != -1 && rbrace > lbrace) {
                    int base = element.getTextRange().getStartOffset();
                    return new TextRange(base + lbrace, base + rbrace + 1);
                }
            }
            if (rule == OceanParser.RULE_primary || rule == OceanParser.RULE_expression) {
                String text = element.getText();
                if (text.startsWith("[") && text.endsWith("]")) {
                    return element.getTextRange();
                }
                if (text.startsWith("{") && text.endsWith("}") && !text.contains("function") && !text.contains("class")) {
                    return element.getTextRange();
                }
            }
        }
        return element.getTextRange();
    }

    private boolean shouldFold(PsiElement element) {
        if (element == null || element instanceof PsiFile || element.getNode() == null) return false;
        if (element.getNode().getElementType() instanceof RuleIElementType ri) {
            int rule = ri.getRuleIndex();
            if (rule == OceanParser.RULE_classDeclaration ||
                    rule == OceanParser.RULE_interfaceDeclaration ||
                    rule == OceanParser.RULE_enumDeclaration ||
                    rule == OceanParser.RULE_annotationDeclaration ||
                    rule == OceanParser.RULE_switchStatement ||
                    rule == OceanParser.RULE_block) {
                return true;
            }
            // Multi-line collection literals [ ... ] and { ... }
            if (rule == OceanParser.RULE_primary || rule == OceanParser.RULE_expression) {
                String text = element.getText();
                return (text.startsWith("[") && text.endsWith("]") && text.contains("\n")) ||
                       (text.startsWith("{") && text.endsWith("}") && text.contains("\n") && !text.contains("function"));
            }
        }
        if (element.getNode().getElementType() instanceof TokenIElementType ti) {
            int tt = ti.getANTLRTokenType();
            return tt == OceanLexer.BLOCK_COMMENT || tt == OceanLexer.MULTILINE_STRING || tt == OceanLexer.MULTILINE_INTERPOLATED_STRING;
        }
        return false;
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        if (node.getElementType() instanceof TokenIElementType ti) {
            int tt = ti.getANTLRTokenType();
            if (tt == OceanLexer.BLOCK_COMMENT) return "/*...*/";
            if (tt == OceanLexer.MULTILINE_STRING) return "\"\"\"...\"\"\"";
            if (tt == OceanLexer.MULTILINE_INTERPOLATED_STRING) return "$ \"\"\"...\"\"\"";
        }
        String text = node.getText();
        if (text.startsWith("[") && text.endsWith("]")) {
            return "[...]";
        }
        return "{...}";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
