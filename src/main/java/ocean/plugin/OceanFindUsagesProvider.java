package ocean.plugin;

import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanFindUsagesProvider implements FindUsagesProvider {
    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(
                new OceanLexerAdapter(new OceanLexer(null)),
                PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.IDENTIFIER),
                PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.LINE_COMMENT, OceanLexer.BLOCK_COMMENT),
                PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.STRING_LITERAL, OceanLexer.CHAR_LITERAL, OceanLexer.MULTILINE_STRING, OceanLexer.INTERPOLATED_STRING, OceanLexer.MULTILINE_INTERPOLATED_STRING));
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement psiElement) {
        String text = psiElement.getText();
        if (OceanKeywords.isKeyword(text)) return false;
        if (psiElement instanceof OceanNamedElement named) {
            String name = named.getName();
            return name != null && !name.isEmpty() && !OceanKeywords.isKeyword(name);
        }
        return false;
    }

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement psiElement) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        String text = element.getText();
        if (element instanceof OceanClassDeclaration || (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_classDeclaration)) {
            if (text != null) {
                if (text.startsWith("data class") || text.contains("data class ")) return "data class";
                if (text.startsWith("sealed class") || text.contains("sealed class ")) return "sealed class";
            }
            return "class";
        }
        if (element instanceof OceanInterfaceDeclaration || (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_interfaceDeclaration)) {
            if (text != null && (text.startsWith("sealed interface") || text.contains("sealed interface "))) {
                return "sealed interface";
            }
            return "interface";
        }
        if (element instanceof OceanEnumDeclaration) return "enum";
        if (element instanceof OceanEnumConstant) return "enum constant";
        if (element instanceof OceanAnnotationDeclaration) return "annotation";
        if (element instanceof OceanAnnotationMemberDeclaration) return "annotation member";
        if (element instanceof OceanMethodDeclaration || (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration)) {
            if (text != null && (text.startsWith("async ") || text.contains("async function ") || text.contains("async def "))) {
                return "async function";
            }
            return "function";
        }
        if (element instanceof OceanFieldDeclaration) return "field";
        if (element instanceof OceanVariableDeclarator d) return d.isField() ? "field" : "variable";
        if (element instanceof OceanParameter) return "parameter";
        if (element instanceof OceanPatternVariable) return "pattern variable";
        if (element instanceof OceanCatchVariable) return "catch variable";
        if (element instanceof OceanLoopVariable) return "loop variable";
        if (element instanceof OceanLabelDeclaration) return "label";
        if (element instanceof OceanVariableDeclaration) return "variable";
        if (element instanceof OceanTypeParameter) return "type parameter";
        return "symbol";
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        if (element instanceof PsiNamedElement named && named.getName() != null) {
            return named.getName();
        }
        return element.getText();
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        String name = getDescriptiveName(element);
        if (useFullName) {
            PsiElement parent = element.getParent();
            while (parent != null) {
                if (parent instanceof OceanClassDeclaration || parent instanceof OceanInterfaceDeclaration || parent instanceof OceanEnumDeclaration) {
                    PsiNamedElement namedClass = (PsiNamedElement) parent;
                    if (namedClass.getName() != null) {
                        return namedClass.getName() + "." + name;
                    }
                    break;
                }
                parent = parent.getParent();
            }
        }
        return name;
    }
}
