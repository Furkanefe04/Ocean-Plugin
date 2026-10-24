package ocean.plugin;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanPairedBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = new BracePair[] {
            new BracePair(
                    PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.LBRACE),
                    PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.RBRACE),
                    true),
            new BracePair(
                    PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.LPAREN),
                    PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.RPAREN),
                    false),
            new BracePair(
                    PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.LBRACK),
                    PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.RBRACK),
                    false),
    };

    @NotNull
    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType,
            @Nullable IElementType contextType) {
        return true;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
