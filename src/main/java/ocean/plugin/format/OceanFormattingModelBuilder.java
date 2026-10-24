package ocean.plugin.format;

import com.intellij.formatting.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import ocean.plugin.OceanLanguage;
import ocean.plugin.OceanLexer;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;
import org.jetbrains.annotations.NotNull;

public class OceanFormattingModelBuilder implements FormattingModelBuilder {
    @NotNull
    @Override
    public FormattingModel createModel(@NotNull FormattingContext formattingContext) {
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.getContainingFile(),
                new OceanFormattingBlock(formattingContext.getNode(), Wrap.createWrap(WrapType.NONE, false), null, createSpaceBuilder(formattingContext.getCodeStyleSettings())),
                formattingContext.getCodeStyleSettings());
    }

    private static SpacingBuilder createSpaceBuilder(CodeStyleSettings settings) {
        OceanLanguage language = OceanLanguage.INSTANCE;
        TokenSet operators = PSIElementTypeFactory.createTokenSet(language,
                OceanLexer.ASSIGN, OceanLexer.PLUS, OceanLexer.MINUS, OceanLexer.STAR, OceanLexer.SLASH,
                OceanLexer.PERCENT, OceanLexer.PLUS_ASSIGN, OceanLexer.MINUS_ASSIGN, OceanLexer.STAR_ASSIGN,
                OceanLexer.SLASH_ASSIGN, OceanLexer.PERCENT_ASSIGN, OceanLexer.AMP, OceanLexer.PIPE,
                OceanLexer.CARET, OceanLexer.LSHIFT, OceanLexer.RSHIFT, OceanLexer.URSHIFT,
                OceanLexer.LSHIFT_ASSIGN, OceanLexer.RSHIFT_ASSIGN, OceanLexer.URSHIFT_ASSIGN,
                OceanLexer.EQUALS, OceanLexer.NOT_EQUALS, OceanLexer.LT, OceanLexer.GT, OceanLexer.LESS_EQUAL, OceanLexer.GREATER_EQUAL,
                OceanLexer.LOGICAL_AND, OceanLexer.LOGICAL_OR, OceanLexer.NULL_COALESCE, OceanLexer.QUESTION);
        
        TokenSet braces = PSIElementTypeFactory.createTokenSet(language, OceanLexer.LBRACE, OceanLexer.RBRACE);
        TokenSet lbrace = PSIElementTypeFactory.createTokenSet(language, OceanLexer.LBRACE);
        TokenSet rbrace = PSIElementTypeFactory.createTokenSet(language, OceanLexer.RBRACE);
        TokenSet comma = PSIElementTypeFactory.createTokenSet(language, OceanLexer.COMMA);
        TokenSet colon = PSIElementTypeFactory.createTokenSet(language, OceanLexer.COLON);
        TokenSet arrow = PSIElementTypeFactory.createTokenSet(language, OceanLexer.ARROW);
        TokenSet keywordsSameLine = PSIElementTypeFactory.createTokenSet(language, OceanLexer.ELSE, OceanLexer.CATCH, OceanLexer.FINALLY);
        IElementType lparen = PSIElementTypeFactory.createTokenSet(language, OceanLexer.LPAREN).getTypes()[0];
        IElementType rparen = PSIElementTypeFactory.createTokenSet(language, OceanLexer.RPAREN).getTypes()[0];
        IElementType lbrack = PSIElementTypeFactory.createTokenSet(language, OceanLexer.LBRACK).getTypes()[0];
        IElementType rbrack = PSIElementTypeFactory.createTokenSet(language, OceanLexer.RBRACK).getTypes()[0];

        TokenSet controlKeywords = PSIElementTypeFactory.createTokenSet(language,
                OceanLexer.IF, OceanLexer.WHILE, OceanLexer.FOR, OceanLexer.SWITCH,
                OceanLexer.TRYING, OceanLexer.CATCH, OceanLexer.SYNC, OceanLexer.LOCK, OceanLexer.WHEN);
        TokenSet lparenSet = PSIElementTypeFactory.createTokenSet(language, OceanLexer.LPAREN);
        TokenSet parensBracks = PSIElementTypeFactory.createTokenSet(language, OceanLexer.LPAREN, OceanLexer.LBRACK);

        return new SpacingBuilder(settings, language)
                .around(operators).spaces(1)
                .after(comma).spaces(1)
                .before(comma).spaces(0)
                .after(colon).spaces(1)
                .before(colon).spaces(0)
                .before(lbrace).spaces(1)
                .after(lbrace).spacing(0, 0, 1, false, 0)
                .before(rbrace).spacing(0, 0, 1, false, 0)
                .between(rbrace, keywordsSameLine).spaces(1)
                .after(rbrace).spacing(0, 0, 1, false, 0)
                .after(PSIElementTypeFactory.createTokenSet(language, OceanLexer.SEMI)).spacing(0, 0, 1, false, 0)
                .before(PSIElementTypeFactory.createTokenSet(language, OceanLexer.SEMI)).spaces(0)
                .around(PSIElementTypeFactory.createTokenSet(language, OceanLexer.DOT, OceanLexer.SAFE_DOT, OceanLexer.DOUBLE_COLON, OceanLexer.RANGE)).spaces(0)
                .after(PSIElementTypeFactory.createTokenSet(language, OceanLexer.AT, OceanLexer.HASH)).spaces(0)
                .between(controlKeywords, lparenSet).spaces(1)
                .before(parensBracks).spaces(0)
                .withinPair(lparen, rparen).none()
                .withinPair(lbrack, rbrack).none()
                .around(arrow).spaces(1);
    }
}
