package ocean.plugin;

import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class OceanNamesValidator implements NamesValidator {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "bool", "case", "catch", "class", "decreasing", "default",
            "else", "enum", "extends", "false", "final", "finally", "for", "from",
            "function", "if", "implements", "import", "in", "increasing", "interface",
            "lock", "new", "null", "package", "private", "protected", "public",
            "return", "skip", "static", "stop", "super", "switch", "sync", "this",
            "throw", "throws", "to", "true", "trying", "value", "variable", "void", "while",
            "with", "int", "long", "float", "double", "char", "byte", "short",
            "String", "data", "result", "annotation", "sealed", "non-sealed", "restricts",
            "async", "await", "native", "when", "instanceof", "do", "verify");

    @Override
    public boolean isKeyword(@NotNull String name, Project project) {
        return OceanKeywords.isKeyword(name);
    }

    @Override
    public boolean isIdentifier(@NotNull String name, Project project) {
        return name.matches("[A-Za-z_\\u00c7\\u00e7\\u011e\\u011f\\u0130\\u0131\\u00d6\\u00f6\\u015e\\u015f\\u00dc\\u00fc][A-Za-z0-9_\\u00c7\\u00e7\\u011e\\u011f\\u0130\\u0131\\u00d6\\u00f6\\u015e\\u015f\\u00dc\\u00fc]*")
                && !isKeyword(name, project);
    }
}
