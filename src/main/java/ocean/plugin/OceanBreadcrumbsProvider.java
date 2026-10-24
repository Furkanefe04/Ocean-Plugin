package ocean.plugin;

import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanBreadcrumbsProvider implements BreadcrumbsProvider {
    private static final Language[] LANGUAGES = new Language[]{OceanLanguage.INSTANCE};

    @Override
    public Language[] getLanguages() {
        return LANGUAGES;
    }

    @Override
    public boolean acceptElement(@NotNull PsiElement element) {
        if (element.getNode() == null) return false;
        if (element.getNode().getElementType() instanceof RuleIElementType ri) {
            int idx = ri.getRuleIndex();
            return idx == OceanParser.RULE_classDeclaration ||
                   idx == OceanParser.RULE_interfaceDeclaration ||
                   idx == OceanParser.RULE_enumDeclaration ||
                   idx == OceanParser.RULE_annotationDeclaration ||
                   idx == OceanParser.RULE_constructorDeclaration ||
                   idx == OceanParser.RULE_methodDeclaration ||
                   idx == OceanParser.RULE_forStatement ||
                   idx == OceanParser.RULE_whileStatement ||
                   idx == OceanParser.RULE_doWhileStatement ||
                   idx == OceanParser.RULE_ifStatement ||
                   idx == OceanParser.RULE_tryStatement ||
                   idx == OceanParser.RULE_catchClause ||
                   idx == OceanParser.RULE_switchStatement ||
                   idx == OceanParser.RULE_switchCase ||
                   idx == OceanParser.RULE_defaultCase ||
                   idx == OceanParser.RULE_switchExpressionCase ||
                   idx == OceanParser.RULE_defaultExpressionCase ||
                   idx == OceanParser.RULE_lockBlockStatement ||
                   idx == OceanParser.RULE_verifyStatement;
        }
        return false;
    }

    @NotNull
    @Override
    public String getElementInfo(@NotNull PsiElement element) {
        if (element.getNode() == null) return "";
        RuleIElementType ri = (RuleIElementType) element.getNode().getElementType();
        int idx = ri.getRuleIndex();
        
        if (idx == OceanParser.RULE_classDeclaration) {
            String name = findNameForElement(element);
            String text = element.getText();
            boolean isData = text.contains("data class");
            boolean isSealed = text.contains("sealed ");
            String prefix = isSealed ? (isData ? "sealed data class " : "sealed class ") : (isData ? "data class " : "class ");
            return name != null ? prefix + name : prefix.trim();
        }
        if (idx == OceanParser.RULE_interfaceDeclaration) {
            String name = findNameForElement(element);
            boolean isSealed = element.getText().contains("sealed ");
            String prefix = isSealed ? "sealed interface " : "interface ";
            return name != null ? prefix + name : prefix.trim();
        }
        if (idx == OceanParser.RULE_enumDeclaration) {
            String name = findNameForElement(element);
            return name != null ? "enum " + name : "enum";
        }
        if (idx == OceanParser.RULE_annotationDeclaration) {
            String name = findNameForElement(element);
            return name != null ? "annotation " + name : "annotation";
        }
        if (idx == OceanParser.RULE_constructorDeclaration) {
            String name = findNameForElement(element);
            return name != null ? name + "()" : "constructor()";
        }
        if (idx == OceanParser.RULE_methodDeclaration) {
            String name = findNameForElement(element);
            boolean isAsync = element.getText().contains("async ");
            String prefix = isAsync ? "async " : "";
            return name != null ? prefix + name + "()" : prefix + "function()";
        }
        if (idx == OceanParser.RULE_forStatement) {
            return "for";
        }
        if (idx == OceanParser.RULE_whileStatement) {
            return "while";
        }
        if (idx == OceanParser.RULE_doWhileStatement) {
            return "do-while";
        }
        if (idx == OceanParser.RULE_ifStatement) {
            return "if";
        }
        if (idx == OceanParser.RULE_tryStatement) {
            return "trying";
        }
        if (idx == OceanParser.RULE_catchClause) {
            String txt = element.getText();
            int lparen = txt.indexOf('(');
            int rparen = txt.indexOf(')');
            if (lparen >= 0 && rparen > lparen) {
                String paramText = txt.substring(lparen + 1, rparen).trim();
                return "catch (" + paramText + ")";
            }
            return "catch";
        }
        if (idx == OceanParser.RULE_switchStatement) {
            return "switch";
        }
        if (idx == OceanParser.RULE_switchCase || idx == OceanParser.RULE_switchExpressionCase) {
            String txt = element.getText();
            int colonOrArrow = txt.indexOf("->");
            if (colonOrArrow < 0) colonOrArrow = txt.indexOf(':');
            if (colonOrArrow > 0) {
                String label = txt.substring(0, colonOrArrow).trim();
                return label.length() > 25 ? label.substring(0, 22) + "..." : label;
            }
            return "case";
        }
        if (idx == OceanParser.RULE_defaultCase || idx == OceanParser.RULE_defaultExpressionCase) {
            return "default";
        }
        if (idx == OceanParser.RULE_lockBlockStatement) {
            return "lock";
        }
        if (idx == OceanParser.RULE_verifyStatement) {
            return "verify";
        }
        return "";
    }

    @Nullable
    @Override
    public String getElementTooltip(@NotNull PsiElement element) {
        String info = getElementInfo(element);
        if (element.getNode() == null) return info;
        RuleIElementType ri = (RuleIElementType) element.getNode().getElementType();
        int idx = ri.getRuleIndex();

        if (idx == OceanParser.RULE_classDeclaration || idx == OceanParser.RULE_interfaceDeclaration) {
            String firstLine = element.getText().split("\\{", 2)[0].trim();
            return firstLine.replaceAll("\\s+", " ");
        }
        if (idx == OceanParser.RULE_methodDeclaration || idx == OceanParser.RULE_constructorDeclaration) {
            String header = element.getText().split("\\{", 2)[0].trim();
            return header.replaceAll("\\s+", " ");
        }
        return info;
    }

    private String findNameForElement(PsiElement element) {
        if (element instanceof PsiNamedElement named) {
            String name = named.getName();
            if (name != null) return name;
        }
        for (PsiElement child : element.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_anyId) {
                String text = child.getText();
                int angle = text.indexOf('<');
                return (angle > 0) ? text.substring(0, angle).trim() : text.trim();
            }
        }
        return null;
    }
}
