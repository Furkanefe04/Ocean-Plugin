package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

public class OceanTypeParameter extends OceanNamedElementImpl {
    public OceanTypeParameter(@NotNull ASTNode node) {
        super(node);
    }

    /**
     * Variance: + (covariant), - (contravariant), or null (invariant)
     */
    public String getVariance() {
        ASTNode plus = getNode().findChildByType(
                PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.PLUS).getTypes()[0]);
        if (plus != null) return "+";
        ASTNode minus = getNode().findChildByType(
                PSIElementTypeFactory.createTokenSet(OceanLanguage.INSTANCE, OceanLexer.MINUS).getTypes()[0]);
        if (minus != null) return "-";
        return null;
    }

    /**
     * Upper bound type text: T <: Number -> "Number", or null
     */
    public String getUpperBound() {
        return getBoundText(OceanLexer.UPPER_BOUND);
    }

    /**
     * Lower bound type text: T >: Integer -> "Integer", or null
     */
    public String getLowerBound() {
        return getBoundText(OceanLexer.LOWER_BOUND);
    }

    private String getBoundText(int boundTokenType) {
        boolean foundBound = false;
        for (ASTNode child : getNode().getChildren(null)) {
            if (child.getElementType() instanceof TokenIElementType ti) {
                if (ti.getANTLRTokenType() == boundTokenType) {
                    foundBound = true;
                }
            }
            if (foundBound && child.getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_type) {
                    return child.getText();
                }
            }
        }
        return null;
    }

    /**
     * Full display text: +T <: Number
     */
    public String getDisplayText() {
        StringBuilder sb = new StringBuilder();
        String v = getVariance();
        if (v != null) sb.append(v);
        sb.append(getName());
        String ub = getUpperBound();
        if (ub != null) sb.append(" <: ").append(ub);
        String lb = getLowerBound();
        if (lb != null) sb.append(" >: ").append(lb);
        return sb.toString();
    }
}
