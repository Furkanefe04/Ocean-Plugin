package ocean.plugin;

import java.util.Set;

public final class OceanKeywords {
    private OceanKeywords() {}

    public static final Set<String> KEYWORDS = Set.of(
            "class", "interface", "enum", "extends", "implements", "package", "import",
            "public", "private", "protected", "static", "final", "abstract", "default",
            "for", "from", "to", "with", "increasing", "decreasing", "in",
            "while", "do", "if", "else", "switch", "case", "return", "skip", "stop",
            "sync", "lock", "trying", "catch", "finally", "variable", "value",
            "void", "function", "native", "async", "await", "when",
            "verify", "data", "sealed", "non-sealed", "restricts", "result", "throw", "throws",
            "annotation", "new", "instanceof", "this", "super", "null", "true", "false",
            "int", "bool", "float", "double", "char", "long", "short", "byte", "String",
            "OceanInput", "OceanOutput", "Output", "main"
    );

    public static boolean isKeyword(String text) {
        return text != null && KEYWORDS.contains(text.trim());
    }
}
