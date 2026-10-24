package ocean.plugin;

import com.intellij.lang.Language;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;

public class OceanLanguage extends Language {
    public static final OceanLanguage INSTANCE = new OceanLanguage();

    private OceanLanguage() {
        super("Ocean");
        // Ensure element types are defined for the language early on
        PSIElementTypeFactory.defineLanguageIElementTypes(this, OceanParser.tokenNames, OceanParser.ruleNames);
    }
}