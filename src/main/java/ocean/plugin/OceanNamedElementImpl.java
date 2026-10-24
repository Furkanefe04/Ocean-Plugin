package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class OceanNamedElementImpl extends OceanPsiNode implements OceanNamedElement {
    public OceanNamedElementImpl(@NotNull ASTNode node) {
        super(node);
    }

    @Nullable
    @Override
    public PsiElement getNameIdentifier() {
        // If the element itself is an anyId rule node, return it
        String selfType = getNode().getElementType().toString();
        if (selfType.contains("anyId")) return this;

        if (getNode().getElementType() instanceof RuleIElementType ri) {
            int rule = ri.getRuleIndex();

            if (rule == OceanParser.RULE_classDeclaration ||
                    rule == OceanParser.RULE_interfaceDeclaration ||
                    rule == OceanParser.RULE_enumDeclaration ||
                    rule == OceanParser.RULE_annotationDeclaration ||
                    rule == OceanParser.RULE_enumConstant ||
                    rule == OceanParser.RULE_typeParameter) {
                return firstDirectAnyId();
            }

            if (rule == OceanParser.RULE_fieldDeclaration ||
                    rule == OceanParser.RULE_variableDeclaration) {
                for (PsiElement child : getChildren()) {
                    if (isRule(child, OceanParser.RULE_variableDeclarator)) {
                        for (PsiElement vChild : child.getChildren()) {
                            if (isAnyId(vChild)) return vChild;
                        }
                    }
                }
            }

            if (rule == OceanParser.RULE_variableDeclarator) {
                return firstDirectAnyId();
            }

            if (rule == OceanParser.RULE_methodDeclaration ||
                    rule == OceanParser.RULE_constructorDeclaration ||
                    rule == OceanParser.RULE_annotationMemberDeclaration ||
                    rule == OceanParser.RULE_resource ||
                    rule == OceanParser.RULE_parameter) {
                return lastDirectAnyId();
            }
        }

        // Broad search for any node that looks like an identifier
        for (PsiElement child : getChildren()) {
            String type = child.getNode().getElementType().toString();
            // Match anyId rule or IDENTIFIER token
            if (type.contains("anyId") || type.contains("IDENTIFIER")) {
                if (!OceanKeywords.isKeyword(child.getText())) {
                    return child;
                }
            }
        }
        // Deep search if not found in direct children
        PsiElement deep = PsiTreeUtil.findChildOfType(this, OceanPsiNode.class);
        if (deep != null && deep.getNode().getElementType().toString().contains("anyId")) {
            if (!OceanKeywords.isKeyword(deep.getText())) {
                return deep;
            }
        }
        // Last resort: first child that matches identifier pattern and isn't a keyword
        for (PsiElement child : getChildren()) {
            String text = child.getText();
            if (text != null && text.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                if (!OceanKeywords.isKeyword(text)) {
                    return child;
                }
            }
        }
        return null;
    }

    protected boolean isRule(PsiElement element, int ruleIndex) {
        return element != null &&
                element.getNode() != null &&
                element.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == ruleIndex;
    }

    private PsiElement firstDirectAnyId() {
        for (PsiElement child : getChildren()) {
            if (isAnyId(child)) return child;
        }
        return null;
    }

    private PsiElement lastDirectAnyId() {
        PsiElement result = null;
        for (PsiElement child : getChildren()) {
            if (isAnyId(child)) result = child;
        }
        return result;
    }

    private boolean isAnyId(PsiElement element) {
        return element != null &&
                element.getNode() != null &&
                element.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == OceanParser.RULE_anyId &&
                !OceanKeywords.isKeyword(element.getText());
    }

    @Override
    public String getName() {
        PsiElement id = getNameIdentifier();
        if (id == null) return null;
        String text = id.getText();
        return (text != null && !OceanKeywords.isKeyword(text)) ? text : null;
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        PsiElement identifier = getNameIdentifier();
        if (identifier != null) {
            PsiElement newIdentifier = OceanElementFactory.createIdentifier(getProject(), name);
            if (newIdentifier != null) identifier.replace(newIdentifier);
        }
        return this;
    }
}
