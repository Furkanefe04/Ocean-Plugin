package ocean.plugin.format;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import ocean.plugin.OceanLanguage;
import org.jetbrains.annotations.NotNull;

/**
 * Provides Code Style settings and live preview sample for Ocean language
 * under Settings -> Editor -> Code Style -> Ocean.
 */
public class OceanLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    @NotNull
    @Override
    public Language getLanguage() {
        return OceanLanguage.INSTANCE;
    }

    @Override
    public String getCodeSample(@NotNull SettingsType settingsType) {
        return """
            package com.example.demo;

            import java.util.List;
            import ocean.stdlib.OceanList;

            class Calculator {
                variable baseValue = 10;

                public int function add(int a, int b) {
                    variable sum = a + b + this.baseValue;
                    if (sum > 100) {
                        return 100;
                    }
                    return sum;
                }

                main() {
                    value calc = new Calculator();
                    for (int i from 0 to 5 with increasing 1) {
                        OceanOutput($"Result: ${calc.add(i, 2)}");
                    }
                }
            }
            """;
    }

    @Override
    protected void customizeDefaults(@NotNull CommonCodeStyleSettings commonSettings,
                                     @NotNull CommonCodeStyleSettings.IndentOptions indentOptions) {
        indentOptions.INDENT_SIZE = 4;
        indentOptions.TAB_SIZE = 4;
        indentOptions.CONTINUATION_INDENT_SIZE = 4;
    }
}
