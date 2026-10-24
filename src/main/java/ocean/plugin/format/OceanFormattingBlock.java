package ocean.plugin.format;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import ocean.plugin.OceanLexer;
import ocean.plugin.OceanParser;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class OceanFormattingBlock extends AbstractBlock {
    private final SpacingBuilder spacingBuilder;

    protected OceanFormattingBlock(@NotNull ASTNode node, @Nullable Wrap wrap, @Nullable Alignment alignment, SpacingBuilder spacingBuilder) {
        super(node, wrap, alignment);
        this.spacingBuilder = spacingBuilder;
    }

    @Override
    protected List<Block> buildChildren() {
        List<Block> blocks = new ArrayList<>();
        for (ASTNode child = myNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType().toString().equals("WHITE_SPACE")) continue;
            blocks.add(new OceanFormattingBlock(child, Wrap.createWrap(WrapType.NONE, false), null, spacingBuilder));
        }
        return blocks;
    }

    @Override
    public Indent getIndent() {
        IElementType type = myNode.getElementType();
        ASTNode parent = myNode.getTreeParent();
        if (parent == null) return Indent.getNoneIndent();

        if (isBrace(type)) {
            return Indent.getNoneIndent();
        }

        // If this child is a block itself, it should align with its parent header, not be indented.
        if (type instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_block) {
            return Indent.getNoneIndent();
        }

        // If this child is a statement wrapping a block, it should align with its parent header, not be indented.
        if (type instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_statement) {
            ASTNode firstChild = myNode.getFirstChildNode();
            if (firstChild != null && firstChild.getElementType() instanceof RuleIElementType sri && sri.getRuleIndex() == OceanParser.RULE_block) {
                return Indent.getNoneIndent();
            }
        }

        if (isMemberParent(parent)) {
            // Only indent members (inside { ... }), not class headers (before {) or braces
            if (isClassHeaderElement(myNode, parent)) {
                return Indent.getNoneIndent();
            }
            return Indent.getNormalIndent();
        }

        if (isIndentedParent(parent)) {
            return Indent.getNormalIndent();
        }

        if (isControlParent(parent)) {
            if (type instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_statement) {
                return Indent.getNormalIndent();
            }
            return Indent.getNoneIndent();
        }

        return Indent.getNoneIndent();
    }

    private boolean isControlParent(ASTNode node) {
        if (node == null || !(node.getElementType() instanceof RuleIElementType ri)) return false;
        int rule = ri.getRuleIndex();
        return rule == OceanParser.RULE_ifStatement ||
                rule == OceanParser.RULE_forStatement ||
                rule == OceanParser.RULE_whileStatement ||
                rule == OceanParser.RULE_doWhileStatement ||
                rule == OceanParser.RULE_catchClause ||
                rule == OceanParser.RULE_tryStatement ||
                rule == OceanParser.RULE_lockBlockStatement;
    }

    private boolean isMemberParent(ASTNode node) {
        if (node == null || !(node.getElementType() instanceof RuleIElementType ri)) return false;
        int rule = ri.getRuleIndex();
        return rule == OceanParser.RULE_classDeclaration ||
                rule == OceanParser.RULE_interfaceDeclaration ||
                rule == OceanParser.RULE_enumDeclaration ||
                rule == OceanParser.RULE_annotationDeclaration;
    }

    private boolean isClassHeaderElement(ASTNode node, ASTNode parent) {
        if (isBrace(node.getElementType())) return true;
        for (ASTNode child = parent.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (isLBrace(child.getElementType())) {
                return false;
            }
            if (child == node) {
                return true;
            }
        }
        return false;
    }

    private boolean isLBrace(IElementType type) {
        if (type instanceof TokenIElementType ti) {
            return ti.getANTLRTokenType() == OceanLexer.LBRACE;
        }
        return false;
    }

    @Nullable
    @Override
    public Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    @Override
    public boolean isLeaf() {
        return myNode.getFirstChildNode() == null;
    }

    @Override
    public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
        IElementType type = myNode.getElementType();
        if (type instanceof RuleIElementType ri) {
            int rule = ri.getRuleIndex();

            ASTNode prevNode = null;
            if (newChildIndex > 0 && newChildIndex - 1 < getSubBlocks().size()) {
                Block prevBlock = getSubBlocks().get(newChildIndex - 1);
                if (prevBlock instanceof OceanFormattingBlock ofBlock) {
                    prevNode = ofBlock.getNode();
                }
            }

            IElementType prevType = prevNode != null ? prevNode.getElementType() : null;

            boolean isPrevStatement = false;
            boolean isPrevBlock = false;
            boolean isPrevCatchClause = false;
            if (prevType instanceof RuleIElementType pri) {
                int pRule = pri.getRuleIndex();
                isPrevStatement = pRule == OceanParser.RULE_statement;
                isPrevBlock = pRule == OceanParser.RULE_block;
                isPrevCatchClause = pRule == OceanParser.RULE_catchClause;
            }

            boolean isPrevRBrace = false;
            boolean isPrevSemi = false;
            if (prevType instanceof TokenIElementType pti) {
                int token = pti.getANTLRTokenType();
                isPrevRBrace = token == OceanLexer.RBRACE;
                isPrevSemi = token == OceanLexer.SEMI;
            }

            if (rule == OceanParser.RULE_block || 
                rule == OceanParser.RULE_classDeclaration || 
                rule == OceanParser.RULE_interfaceDeclaration || 
                rule == OceanParser.RULE_enumDeclaration ||
                rule == OceanParser.RULE_annotationDeclaration ||
                rule == OceanParser.RULE_switchStatement) {
                if (isPrevRBrace) {
                    return new ChildAttributes(Indent.getNoneIndent(), null);
                }
                return new ChildAttributes(Indent.getNormalIndent(), null);
            }

            if (rule == OceanParser.RULE_ifStatement ||
                rule == OceanParser.RULE_forStatement ||
                rule == OceanParser.RULE_whileStatement ||
                rule == OceanParser.RULE_doWhileStatement) {
                if (isPrevStatement || isPrevSemi) {
                    return new ChildAttributes(Indent.getNoneIndent(), null);
                }
                return new ChildAttributes(Indent.getNormalIndent(), null);
            }

            if (rule == OceanParser.RULE_tryStatement) {
                if (isPrevBlock || isPrevCatchClause) {
                    return new ChildAttributes(Indent.getNoneIndent(), null);
                }
                return new ChildAttributes(Indent.getNormalIndent(), null);
            }

            if (rule == OceanParser.RULE_lockBlockStatement ||
                rule == OceanParser.RULE_catchClause) {
                if (isPrevBlock) {
                    return new ChildAttributes(Indent.getNoneIndent(), null);
                }
                return new ChildAttributes(Indent.getNormalIndent(), null);
            }

            if (rule == OceanParser.RULE_switchCase ||
                rule == OceanParser.RULE_defaultCase) {
                return new ChildAttributes(Indent.getNormalIndent(), null);
            }
        }
        return new ChildAttributes(Indent.getNoneIndent(), null);
    }

    private boolean isIndentedParent(ASTNode node) {
        if (node == null || !(node.getElementType() instanceof RuleIElementType ri)) return false;
        int rule = ri.getRuleIndex();
        return rule == OceanParser.RULE_block ||
                rule == OceanParser.RULE_switchStatement ||
                rule == OceanParser.RULE_switchCase ||
                rule == OceanParser.RULE_defaultCase;
    }

    private boolean isBrace(IElementType type) {
        if (type instanceof TokenIElementType ti) {
            int token = ti.getANTLRTokenType();
            return token == OceanLexer.LBRACE || token == OceanLexer.RBRACE;
        }
        return false;
    }
}
