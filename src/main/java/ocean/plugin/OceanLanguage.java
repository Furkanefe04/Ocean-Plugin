package ocean.plugin;

import com.intellij.lang.Language;
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory;

public class OceanLanguage extends Language {
    public static final OceanLanguage INSTANCE = new OceanLanguage();

    private OceanLanguage() {
        super("Ocean");
        // Ensure element types are defined for the language early on
        PSIElementTypeFactory.defineLanguageIElementTypes(this, getTokenNames(), OceanParser.ruleNames);
    }

    private static String[] getTokenNames() {
        String[] names = new String[OceanParser.VOCABULARY.getMaxTokenType() + 1];
        for (int i = 0; i < names.length; i++) {
            names[i] = OceanParser.VOCABULARY.getLiteralName(i);
            if (names[i] == null) names[i] = OceanParser.VOCABULARY.getSymbolicName(i);
            if (names[i] == null) names[i] = "<INVALID>";
        }
        return names;
    }
}
