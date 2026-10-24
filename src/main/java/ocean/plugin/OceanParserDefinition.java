package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.antlr.intellij.adaptor.parser.ANTLRParserAdaptor;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.tree.ParseTree;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import com.intellij.lexer.Lexer;

import java.util.Set;

public class OceanParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(OceanLanguage.INSTANCE);

    @NotNull
    @Override
    public Lexer createLexer(Project project) {
        OceanLexer lexer = new OceanLexer(null);
        return new OceanLexerAdapter(lexer);
    }

    @NotNull
    @Override
    public TokenSet getWhitespaceTokens() {
        return PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.WS);
    }

    @NotNull
    @Override
    public TokenSet getCommentTokens() {
        return PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.LINE_COMMENT, OceanLexer.BLOCK_COMMENT);
    }

    @NotNull
    @Override
    public TokenSet getStringLiteralElements() {
        return PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.STRING_LITERAL, OceanLexer.MULTILINE_STRING, OceanLexer.CHAR_LITERAL, OceanLexer.INTERPOLATED_STRING, OceanLexer.MULTILINE_INTERPOLATED_STRING);
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new ANTLRParserAdaptor(OceanLanguage.INSTANCE, new OceanParser(null)) {
            @Override
            protected ParseTree parse(Parser parser, IElementType root) {
                return ((OceanParser) parser).program();
            }
        };
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) { return new OceanFile(viewProvider); }
    @Override
    public @NotNull SpaceRequirements spaceExistenceTypeBetweenTokens(ASTNode left, ASTNode right) { return SpaceRequirements.MAY; }

    @NotNull
    @Override
    public PsiElement createElement(ASTNode node) {
        IElementType type = node.getElementType();
        if (type instanceof RuleIElementType ri) {
            int ruleIndex = ri.getRuleIndex();

            if (ruleIndex == OceanParser.RULE_classDeclaration) return new OceanClassDeclaration(node);
            if (ruleIndex == OceanParser.RULE_interfaceDeclaration) return new OceanInterfaceDeclaration(node);
            if (ruleIndex == OceanParser.RULE_enumDeclaration) return new OceanEnumDeclaration(node);
            if (ruleIndex == OceanParser.RULE_enumConstant) return new OceanEnumConstant(node);
            if (ruleIndex == OceanParser.RULE_annotationDeclaration) return new OceanAnnotationDeclaration(node);
            if (ruleIndex == OceanParser.RULE_annotationMemberDeclaration) return new OceanAnnotationMemberDeclaration(node);
            if (ruleIndex == OceanParser.RULE_methodDeclaration || ruleIndex == OceanParser.RULE_constructorDeclaration) return new OceanMethodDeclaration(node);
            if (ruleIndex == OceanParser.RULE_fieldDeclaration) return new OceanFieldDeclaration(node);
            if (ruleIndex == OceanParser.RULE_variableDeclaration || ruleIndex == OceanParser.RULE_resource) return new OceanVariableDeclaration(node);
            if (ruleIndex == OceanParser.RULE_variableDeclarator) return new OceanVariableDeclarator(node);
            if (ruleIndex == OceanParser.RULE_parameter) return new OceanParameter(node);
            if (ruleIndex == OceanParser.RULE_typeParameter) return new OceanTypeParameter(node);
            
            if (ruleIndex == OceanParser.RULE_anyId) {
                ASTNode parent = node.getTreeParent();
                if (parent != null && parent.getElementType() instanceof RuleIElementType pri) {
                    int pIdx = pri.getRuleIndex();
                    
                    // Labeled statement (e.g. `outer: for(...)`)
                    if (pIdx == OceanParser.RULE_statement) {
                        for (ASTNode c = parent.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                            if (":".equals(c.getText())) {
                                if (node.getStartOffset() < c.getStartOffset()) {
                                    return new OceanLabelDeclaration(node);
                                }
                                break;
                            }
                        }
                    }

                    // 1. Declarations/Definitions
                    if (isDefinitionRule(pIdx)) {
                        if (pIdx == OceanParser.RULE_enumConstant || pIdx == OceanParser.RULE_enumConstants ||
                                pIdx == OceanParser.RULE_resource || pIdx == OceanParser.RULE_variableDeclarator) {
                            return new OceanPsiNode(node);
                        }
                        if (pIdx == OceanParser.RULE_forControl || pIdx == OceanParser.RULE_forStatement) {
                            return new OceanLoopVariable(node);
                        }
                        if (pIdx == OceanParser.RULE_catchClause) {
                            return new OceanCatchVariable(node);
                        }
                        if (pIdx == OceanParser.RULE_switchPattern || pIdx == OceanParser.RULE_instanceofPattern) {
                            return new OceanPatternVariable(node);
                        }
                        return new OceanPsiNode(node);
                    }

                    if (pIdx == OceanParser.RULE_identifierList) {
                        return new OceanParameter(node);
                    }

                    // Named argument label (e.g. `foo(name = 10)`)
                    if (pIdx == OceanParser.RULE_argument) {
                        for (ASTNode c = node.getTreeNext(); c != null; c = c.getTreeNext()) {
                            if ("=".equals(c.getText())) {
                                return new OceanPsiNode(node);
                            }
                        }
                    }

                    if (pIdx == OceanParser.RULE_expression) {
                        for (ASTNode c = node.getTreePrev(); c != null; c = c.getTreePrev()) {
                            if (c.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.INSTANCEOF) {
                                return new OceanPatternVariable(node);
                            }
                        }
                    }
                    
                    // 2. Ignore anyId in packages and imports (fixes hundreds of "unresolved" errors)
                    if (pIdx == OceanParser.RULE_packageDeclaration || pIdx == OceanParser.RULE_importStatement) {
                        return new OceanPsiNode(node);
                    }
                }
                
                // 3. Built-in keywords used in context where anyId is allowed
                if (isBuiltInKeyword(node.getText())) return new OceanPsiNode(node);
                
                // 4. Everything else is a reference
                return new OceanIdReference(node);
            }
        }
        return new OceanPsiNode(node);
    }

    private boolean isDefinitionRule(int pIdx) {
        return pIdx == OceanParser.RULE_classDeclaration || 
               pIdx == OceanParser.RULE_methodDeclaration ||
               pIdx == OceanParser.RULE_variableDeclaration ||
               pIdx == OceanParser.RULE_variableDeclarator ||
               pIdx == OceanParser.RULE_resource ||
               pIdx == OceanParser.RULE_parameter ||
               pIdx == OceanParser.RULE_fieldDeclaration ||
               pIdx == OceanParser.RULE_constructorDeclaration ||
               pIdx == OceanParser.RULE_interfaceDeclaration ||
               pIdx == OceanParser.RULE_enumDeclaration ||
               pIdx == OceanParser.RULE_enumConstant ||
               pIdx == OceanParser.RULE_enumConstants ||
               pIdx == OceanParser.RULE_annotationDeclaration ||
               pIdx == OceanParser.RULE_annotationMemberDeclaration ||
               pIdx == OceanParser.RULE_typeParameter ||
               pIdx == OceanParser.RULE_forStatement ||
               pIdx == OceanParser.RULE_forControl ||
               pIdx == OceanParser.RULE_switchPattern ||
               pIdx == OceanParser.RULE_instanceofPattern ||
               pIdx == OceanParser.RULE_catchClause;
    }

    private static final Set<String> BUILTIN_KEYWORDS = OceanKeywords.KEYWORDS;

    private boolean isBuiltInKeyword(String text) {
        return OceanKeywords.isKeyword(text);
    }
}
