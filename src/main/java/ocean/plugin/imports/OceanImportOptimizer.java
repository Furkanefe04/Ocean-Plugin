package ocean.plugin.imports;

import com.intellij.lang.ImportOptimizer;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import ocean.plugin.OceanFile;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles 'Optimize Imports' (Ctrl + Alt + O) for Ocean source files.
 * - Removes unused imports.
 * - Deduplicates identical imports.
 * - Orders remaining imports: java.* / javax.* first, third-party libraries second, ocean.* third.
 */
public class OceanImportOptimizer implements ImportOptimizer {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^[ \t]*import\\s+(?:(static)\\s+)?([a-zA-Z0-9_.*]+)\\s*;[ \t]*\r?\n?"
    );

    @Override
    public boolean supports(@NotNull PsiFile file) {
        return file instanceof OceanFile;
    }

    @NotNull
    @Override
    public Runnable processFile(@NotNull PsiFile file) {
        if (!(file instanceof OceanFile)) {
            return () -> {};
        }

        Project project = file.getProject();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return () -> {};
        }

        String text = document.getText();
        Matcher matcher = IMPORT_PATTERN.matcher(text);

        List<ImportEntry> entries = new ArrayList<>();

        while (matcher.find()) {
            boolean isStatic = matcher.group(1) != null;
            String fullPath = matcher.group(2).trim();
            entries.add(new ImportEntry(isStatic, fullPath, matcher.start(), matcher.end()));
        }

        if (entries.isEmpty()) {
            return () -> {};
        }

        // Mask out existing import statements and comments/strings when checking usages
        StringBuilder bodyBuilder = new StringBuilder(text);
        for (ImportEntry entry : entries) {
            for (int k = entry.start(); k < entry.end() && k < bodyBuilder.length(); k++) {
                bodyBuilder.setCharAt(k, ' ');
            }
        }
        String cleanedBody = maskCommentsAndStrings(bodyBuilder.toString());

        // Deduplicate and filter out unused imports
        Set<String> seen = new HashSet<>();
        List<ImportEntry> keptImports = new ArrayList<>();

        for (ImportEntry entry : entries) {
            String key = (entry.isStatic() ? "static " : "") + entry.fullPath();
            if (!seen.add(key)) {
                // Duplicate import, drop
                continue;
            }

            if (isWildcard(entry.fullPath())) {
                keptImports.add(entry);
                continue;
            }

            String simpleName = getSimpleName(entry.fullPath());
            if (isUsedInText(cleanedBody, simpleName)) {
                keptImports.add(entry);
            }
        }

        // Sort imports into logical groups
        keptImports.sort(OceanImportOptimizer::compareEntries);

        // Determine safe insertion point:
        int firstImportStart = entries.get(0).start();
        Matcher declMatcher = Pattern.compile("(?m)^[ \t]*(?:public|private|protected)?\\s*(?:class|interface|enum|annotation|function|variable|value)\\b").matcher(text);
        int firstDeclStart = declMatcher.find() ? declMatcher.start() : -1;

        Matcher pkgMatcher = Pattern.compile("(?m)^[ \t]*package\\s+[^;]+;[ \t]*\r?\n?").matcher(text);
        int pkgEnd = pkgMatcher.find() ? pkgMatcher.end() : -1;

        int insertPoint;
        if (firstDeclStart != -1 && firstImportStart > firstDeclStart) {
            insertPoint = (pkgEnd != -1) ? pkgEnd : 0;
        } else {
            insertPoint = firstImportStart;
        }

        return () -> {
            RangeMarker insertMarker = document.createRangeMarker(insertPoint, insertPoint);
            insertMarker.setGreedyToRight(true);

            // Delete each existing import statement individually from end to start
            for (int i = entries.size() - 1; i >= 0; i--) {
                ImportEntry entry = entries.get(i);
                document.deleteString(entry.start(), entry.end());
            }

            if (!keptImports.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                String prevGroup = null;

                for (ImportEntry imp : keptImports) {
                    String currentGroup = getImportGroup(imp);

                    if (prevGroup != null && !prevGroup.equals(currentGroup) && sb.length() > 0) {
                        sb.append("\n");
                    }
                    prevGroup = currentGroup;

                    sb.append("import ").append(imp.isStatic() ? "static " : "").append(imp.fullPath()).append(";\n");
                }

                int finalInsertOffset = insertMarker.getStartOffset();
                document.insertString(finalInsertOffset, sb.toString());
            }

            insertMarker.dispose();
            PsiDocumentManager.getInstance(project).commitDocument(document);
        };
    }

    private static boolean isWildcard(String path) {
        return path.endsWith(".*");
    }

    public static String getSimpleName(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    public static boolean isUsedInText(String text, String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return false;
        // Check for whole-word occurrence: \bSimpleName\b
        Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
        return wordPattern.matcher(text).find();
    }

    public static String getImportGroup(ImportEntry entry) {
        if (entry.isStatic()) {
            return "0_static";
        }
        String path = entry.fullPath();
        if (path.startsWith("java.") || path.startsWith("javax.")) {
            return "1_java";
        }
        if (path.startsWith("ocean.")) {
            return "3_ocean";
        }
        return "2_other";
    }

    private static int compareEntries(ImportEntry o1, ImportEntry o2) {
        String g1 = getImportGroup(o1);
        String g2 = getImportGroup(o2);
        int cmp = g1.compareTo(g2);
        if (cmp != 0) return cmp;
        return o1.fullPath().compareToIgnoreCase(o2.fullPath());
    }

    public static String maskCommentsAndStrings(String text) {
        // Masking for single-line comments //..., multi-line comments /*...*/,
        // triple-quoted multiline strings """...""", regular strings "...", and char literals '...'
        char[] chars = text.toCharArray();
        int n = chars.length;
        boolean inString = false;
        boolean inInterpolatedString = false;
        int interpBraceDepth = 0;
        boolean inMultilineString = false;
        boolean inChar = false;
        boolean inSingleComment = false;
        boolean inMultiComment = false;

        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (inSingleComment) {
                if (c == '\n' || c == '\r') {
                    inSingleComment = false;
                } else {
                    chars[i] = ' ';
                }
            } else if (inMultiComment) {
                if (c == '*' && i + 1 < n && chars[i + 1] == '/') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                    inMultiComment = false;
                } else {
                    chars[i] = ' ';
                }
            } else if (inMultilineString) {
                if (c == '"' && i + 2 < n && chars[i + 1] == '"' && chars[i + 2] == '"') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    chars[i + 2] = ' ';
                    i += 2;
                    inMultilineString = false;
                } else {
                    chars[i] = ' ';
                }
            } else if (inInterpolatedString) {
                if (c == '{') {
                    interpBraceDepth++;
                    // Do not mask inside { ... } interpolation expression
                } else if (c == '}' && interpBraceDepth > 0) {
                    interpBraceDepth--;
                    // Do not mask inside { ... } interpolation expression
                } else if (interpBraceDepth > 0) {
                    // Inside { ... } expression: preserve code as-is so imports are detected!
                } else if (c == '\\' && i + 1 < n) {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                } else if (c == '"') {
                    chars[i] = ' ';
                    inInterpolatedString = false;
                } else {
                    chars[i] = ' ';
                }
            } else if (inString) {
                if (c == '\\' && i + 1 < n) {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                } else if (c == '"') {
                    chars[i] = ' ';
                    inString = false;
                } else {
                    chars[i] = ' ';
                }
            } else if (inChar) {
                if (c == '\\' && i + 1 < n) {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                } else if (c == '\'') {
                    chars[i] = ' ';
                    inChar = false;
                } else {
                    chars[i] = ' ';
                }
            } else {
                if (c == '/' && i + 1 < n && chars[i + 1] == '/') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                    inSingleComment = true;
                } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                    inMultiComment = true;
                } else if (c == '$' && i + 3 < n && chars[i + 1] == '"' && chars[i + 2] == '"' && chars[i + 3] == '"') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    chars[i + 2] = ' ';
                    chars[i + 3] = ' ';
                    i += 3;
                    inMultilineString = true;
                } else if (c == '$' && i + 1 < n && chars[i + 1] == '"') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i++;
                    inInterpolatedString = true;
                    interpBraceDepth = 0;
                } else if (c == '"' && i + 2 < n && chars[i + 1] == '"' && chars[i + 2] == '"') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    chars[i + 2] = ' ';
                    i += 2;
                    inMultilineString = true;
                } else if (c == '"') {
                    chars[i] = ' ';
                    inString = true;
                } else if (c == '\'') {
                    chars[i] = ' ';
                    inChar = true;
                }
            }
        }
        return new String(chars);
    }

    public record ImportEntry(boolean isStatic, String fullPath, int start, int end) {}
}
