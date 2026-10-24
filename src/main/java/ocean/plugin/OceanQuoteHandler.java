package ocean.plugin;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;

public class OceanQuoteHandler extends SimpleTokenSetQuoteHandler {
    public OceanQuoteHandler() {
        super(
                PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.STRING_LITERAL),
                PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.CHAR_LITERAL),
                PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.MULTILINE_STRING),
                PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.INTERPOLATED_STRING),
                PSIElementTypeFactory.getTokenIElementTypes(OceanLanguage.INSTANCE).get(OceanLexer.MULTILINE_INTERPOLATED_STRING)
        );
    }
}
