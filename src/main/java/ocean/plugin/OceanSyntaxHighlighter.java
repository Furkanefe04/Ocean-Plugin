package ocean.plugin;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public class OceanSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey KEYWORD = createTextAttributesKey("OCEAN_KEYWORD",
            DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey MODIFIER = createTextAttributesKey("OCEAN_MODIFIER",
            DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey STRING = createTextAttributesKey("OCEAN_STRING",
            DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = createTextAttributesKey("OCEAN_NUMBER",
            DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey COMMENT = createTextAttributesKey("OCEAN_COMMENT",
            DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey IDENTIFIER = createTextAttributesKey("OCEAN_IDENTIFIER",
            DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey METHOD_DECLARATION = createTextAttributesKey("OCEAN_METHOD_DECLARATION",
            DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey METHOD_CALL = createTextAttributesKey("OCEAN_METHOD_CALL",
            DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey PARAMETER = createTextAttributesKey("OCEAN_PARAMETER",
            DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey CLASS_NAME = createTextAttributesKey("OCEAN_CLASS_NAME",
            DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey PRIMITIVE_TYPE = createTextAttributesKey("OCEAN_PRIMITIVE_TYPE",
            DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey STDLIB_FUNCTION = createTextAttributesKey("OCEAN_STDLIB_FUNCTION",
            DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);
    public static final TextAttributesKey STRING_ESCAPE = createTextAttributesKey("OCEAN_STRING_ESCAPE",
            DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE);
    public static final TextAttributesKey BRACES = createTextAttributesKey("OCEAN_BRACES",
            DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey ANNOTATION = createTextAttributesKey("OCEAN_ANNOTATION",
            DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey INSTANCE_FIELD = createTextAttributesKey("OCEAN_INSTANCE_FIELD",
            DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey STATIC_FIELD = createTextAttributesKey("OCEAN_STATIC_FIELD",
            DefaultLanguageHighlighterColors.STATIC_FIELD);
    public static final TextAttributesKey INTERFACE_NAME = createTextAttributesKey("OCEAN_INTERFACE_NAME",
            DefaultLanguageHighlighterColors.INTERFACE_NAME);
    public static final TextAttributesKey STATIC_METHOD = createTextAttributesKey("OCEAN_STATIC_METHOD",
            DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey OPERATOR = createTextAttributesKey("OCEAN_OPERATOR",
            DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey PARENTHESES = createTextAttributesKey("OCEAN_PARENTHESES",
            DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey BRACKETS = createTextAttributesKey("OCEAN_BRACKETS",
            DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey SEMICOLON = createTextAttributesKey("OCEAN_SEMICOLON",
            DefaultLanguageHighlighterColors.SEMICOLON);
    public static final TextAttributesKey COMMA = createTextAttributesKey("OCEAN_COMMA",
            DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey DOT = createTextAttributesKey("OCEAN_DOT",
            DefaultLanguageHighlighterColors.DOT);
    public static final TextAttributesKey LOCAL_VARIABLE = createTextAttributesKey("OCEAN_LOCAL_VARIABLE",
            DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey TYPE_PARAMETER = createTextAttributesKey("OCEAN_TYPE_PARAMETER",
            DefaultLanguageHighlighterColors.CLASS_NAME);


    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

    @NotNull
    @Override
    public Lexer getHighlightingLexer() {
        OceanLexer lexer = new OceanLexer(null);
        return new OceanLexerAdapter(lexer);
    }

    @NotNull
    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (!(tokenType instanceof TokenIElementType))
            return EMPTY_KEYS;
        int type = ((TokenIElementType) tokenType).getANTLRTokenType();

        if (type == OceanLexer.STRING_LITERAL || type == OceanLexer.CHAR_LITERAL || type == OceanLexer.MULTILINE_STRING ||
                type == OceanLexer.INTERPOLATED_STRING || type == OceanLexer.MULTILINE_INTERPOLATED_STRING) {
            return new TextAttributesKey[] { STRING };
        } else if (type == OceanLexer.NUMBER) {
            return new TextAttributesKey[] { NUMBER };
        } else if (type == OceanLexer.LINE_COMMENT || type == OceanLexer.BLOCK_COMMENT) {
            return new TextAttributesKey[] { COMMENT };
        } else if (type == OceanLexer.AT) {
            return new TextAttributesKey[] { ANNOTATION };
        } else if (type == OceanLexer.LBRACE || type == OceanLexer.RBRACE) {
            return new TextAttributesKey[] { BRACES };
        } else if (type == OceanLexer.LPAREN || type == OceanLexer.RPAREN) {
            return new TextAttributesKey[] { PARENTHESES };
        } else if (type == OceanLexer.LBRACK || type == OceanLexer.RBRACK) {
            return new TextAttributesKey[] { BRACKETS };
        } else if (type == OceanLexer.SEMI) {
            return new TextAttributesKey[] { SEMICOLON };
        } else if (type == OceanLexer.COMMA) {
            return new TextAttributesKey[] { COMMA };
        } else if (type == OceanLexer.DOT || type == OceanLexer.SAFE_DOT) {
            return new TextAttributesKey[] { DOT };
        } else if (type == OceanLexer.OCEAN_INPUT || type == OceanLexer.OCEAN_OUTPUT) {
            return new TextAttributesKey[] { STDLIB_FUNCTION };
        } else if (isPrimitiveType(type)) {
            return new TextAttributesKey[] { PRIMITIVE_TYPE };
        } else if (isKeyword(type)) {
            return new TextAttributesKey[] { KEYWORD };
        } else if (isOperator(type)) {
            return new TextAttributesKey[] { OPERATOR };
        } else if (type == OceanLexer.IDENTIFIER) {
            return new TextAttributesKey[] { IDENTIFIER };
        }
        return EMPTY_KEYS;
    }

    private boolean isPrimitiveType(int type) {
        return type == OceanLexer.BOOL_TYPE ||
                type == OceanLexer.INT_TYPE ||
                type == OceanLexer.FLOAT_TYPE ||
                type == OceanLexer.DOUBLE_TYPE ||
                type == OceanLexer.CHAR_TYPE ||
                type == OceanLexer.LONG_TYPE ||
                type == OceanLexer.SHORT_TYPE ||
                type == OceanLexer.BYTE_TYPE ||
                type == OceanLexer.STRING_TYPE ||
                type == OceanLexer.VOID;
    }

    private boolean isKeyword(int type) {
        return type == OceanLexer.ABSTRACT ||
                type == OceanLexer.CASE ||
                type == OceanLexer.CATCH ||
                type == OceanLexer.CLASS ||
                type == OceanLexer.DECREASING ||
                type == OceanLexer.DEFAULT ||
                type == OceanLexer.ELSE ||
                type == OceanLexer.ENUM ||
                type == OceanLexer.EXTENDS ||
                type == OceanLexer.FALSE ||
                type == OceanLexer.FINAL ||
                type == OceanLexer.FINALLY ||
                type == OceanLexer.FOR ||
                type == OceanLexer.FROM ||
                type == OceanLexer.FUNCTION ||
                type == OceanLexer.IF ||
                type == OceanLexer.IMPLEMENTS ||
                type == OceanLexer.IMPORT ||
                type == OceanLexer.IN ||
                type == OceanLexer.INCREASING ||
                type == OceanLexer.INTERFACE ||
                type == OceanLexer.LOCK ||
                type == OceanLexer.NEW ||
                type == OceanLexer.NULL_KW ||
                type == OceanLexer.RETURN ||
                type == OceanLexer.SKIP_KW ||
                type == OceanLexer.STATIC ||
                type == OceanLexer.STOP_KW ||
                type == OceanLexer.SUPER ||
                type == OceanLexer.SWITCH ||
                type == OceanLexer.SYNC ||
                type == OceanLexer.THIS ||
                type == OceanLexer.TO ||
                type == OceanLexer.TRUE ||
                type == OceanLexer.TRYING ||
                type == OceanLexer.VALUE ||
                type == OceanLexer.VARIABLE ||
                type == OceanLexer.VOID ||
                type == OceanLexer.WHILE ||
                type == OceanLexer.WITH ||
                type == OceanLexer.PACKAGE ||
                type == OceanLexer.THROW ||
                type == OceanLexer.THROWS ||
                type == OceanLexer.INSTANCEOF ||
                type == OceanLexer.DO ||
                type == OceanLexer.PUBLIC ||
                type == OceanLexer.PRIVATE ||
                type == OceanLexer.PROTECTED ||
                type == OceanLexer.DATA ||
                type == OceanLexer.ANNOTATION_KW ||
                type == OceanLexer.SEALED ||
                type == OceanLexer.NON_SEALED ||
                type == OceanLexer.RESTRICTS ||
                type == OceanLexer.ASYNC ||
                type == OceanLexer.AWAIT ||
                type == OceanLexer.NATIVE ||
                type == OceanLexer.WHEN ||
                type == OceanLexer.VERIFY ||
                type == OceanLexer.RESULT_KW;
    }

    private boolean isOperator(int type) {
        return type == OceanLexer.ASSIGN ||
                type == OceanLexer.PLUS ||
                type == OceanLexer.MINUS ||
                type == OceanLexer.STAR ||
                type == OceanLexer.SLASH ||
                type == OceanLexer.PERCENT ||
                type == OceanLexer.PLUS_PLUS ||
                type == OceanLexer.MINUS_MINUS ||
                type == OceanLexer.PLUS_ASSIGN ||
                type == OceanLexer.MINUS_ASSIGN ||
                type == OceanLexer.STAR_ASSIGN ||
                type == OceanLexer.SLASH_ASSIGN ||
                type == OceanLexer.PERCENT_ASSIGN ||
                type == OceanLexer.AMP_ASSIGN ||
                type == OceanLexer.PIPE_ASSIGN ||
                type == OceanLexer.CARET_ASSIGN ||
                type == OceanLexer.LSHIFT_ASSIGN ||
                type == OceanLexer.RSHIFT_ASSIGN ||
                type == OceanLexer.URSHIFT_ASSIGN ||
                type == OceanLexer.EQUALS ||
                type == OceanLexer.NOT_EQUALS ||
                type == OceanLexer.LT ||
                type == OceanLexer.GT ||
                type == OceanLexer.LESS_EQUAL ||
                type == OceanLexer.GREATER_EQUAL ||
                type == OceanLexer.LOGICAL_AND ||
                type == OceanLexer.LOGICAL_OR ||
                type == OceanLexer.AMP ||
                type == OceanLexer.PIPE ||
                type == OceanLexer.CARET ||
                type == OceanLexer.TILDE ||
                type == OceanLexer.BANG ||
                type == OceanLexer.QUESTION ||
                type == OceanLexer.COLON ||
                type == OceanLexer.DOUBLE_COLON ||
                type == OceanLexer.ARROW ||
                type == OceanLexer.RANGE ||
                type == OceanLexer.LSHIFT ||
                type == OceanLexer.RSHIFT ||
                type == OceanLexer.URSHIFT ||
                type == OceanLexer.NULL_COALESCE;
    }
}
