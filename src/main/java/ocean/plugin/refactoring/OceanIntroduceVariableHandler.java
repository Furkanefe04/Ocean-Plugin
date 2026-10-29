package ocean.plugin.refactoring;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.RefactoringActionHandler;
import ocean.plugin.OceanFile;
import ocean.plugin.OceanKeywords;
import ocean.plugin.OceanParser;
import ocean.plugin.OceanTypeHelper;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles 'Extract Variable' refactoring (Ctrl + Alt + V) for Ocean files.
 * - Identifies selected expression (or expression under caret).
 * - Infers type via OceanTypeHelper.
 * - Proposes meaningful variable name.
 * - Inserts declaration before current statement and replaces expression with variable name.
 */
public class OceanIntroduceVariableHandler implements RefactoringActionHandler {

    private static final Pattern METHOD_CALL_PATTERN = Pattern.compile("([a-zA-Z0-9_]+)\\s*\\(");

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        if (editor == null || !(file instanceof OceanFile)) {
            return;
        }

        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return;
        }

        SelectionModel selectionModel = editor.getSelectionModel();
        int exprStart;
        int exprEnd;
        String exprText;

        if (selectionModel.hasSelection()) {
            exprStart = selectionModel.getSelectionStart();
            exprEnd = selectionModel.getSelectionEnd();
            exprText = selectionModel.getSelectedText();
        } else {
            int offset = editor.getCaretModel().getOffset();
            PsiElement el = file.findElementAt(offset);
            if (el == null && offset > 0) el = file.findElementAt(offset - 1);
            if (el == null) return;

            // Expand to enclosing non-whitespace element
            while (el != null && (el.getText().isBlank() || el instanceof PsiFile)) {
                el = el.getParent();
            }
            if (el == null) return;
            TextRange tr = el.getTextRange();
            exprStart = tr.getStartOffset();
            exprEnd = tr.getEndOffset();
            exprText = el.getText();
        }

        if (exprText == null || exprText.isBlank()) {
            return;
        }

        // Clean up boundary whitespace from selection
        while (exprStart < exprEnd && Character.isWhitespace(document.getText().charAt(exprStart))) {
            exprStart++;
        }
        while (exprEnd > exprStart && Character.isWhitespace(document.getText().charAt(exprEnd - 1))) {
            exprEnd--;
        }
        exprText = document.getText().substring(exprStart, exprEnd);

        PsiElement exprPsi = file.findElementAt(exprStart);
        String inferredType = inferType(exprText, exprPsi);
        String suggestedName = suggestVarName(exprText, inferredType);

        int lineStart = findLineStart(exprStart, exprPsi, document);

        // Calculate leading indentation of the statement line
        int indentEnd = lineStart;
        String docText = document.getText();
        while (indentEnd < docText.length() && (docText.charAt(indentEnd) == ' ' || docText.charAt(indentEnd) == '\t')) {
            indentEnd++;
        }
        String indent = docText.substring(lineStart, indentEnd);
        String typePrefix = !inferredType.isBlank() && !inferredType.equals("Object")
                ? inferredType
                : "variable";
        String newDecl = indent + typePrefix + " " + suggestedName + " = " + exprText + ";\n";

        int finalExprStart = exprStart;
        int finalExprEnd = exprEnd;

        WriteCommandAction.runWriteCommandAction(project, "Extract Variable", null, () -> {
            // 1. Insert new declaration at beginning of statement line
            document.insertString(lineStart, newDecl);

            // 2. Adjust offset of expression and replace with variable name
            int shiftedStart = finalExprStart + newDecl.length();
            int shiftedEnd = finalExprEnd + newDecl.length();
            document.replaceString(shiftedStart, shiftedEnd, suggestedName);

            PsiDocumentManager.getInstance(project).commitDocument(document);

            // 3. Move caret to newly inserted variable reference
            editor.getCaretModel().moveToOffset(shiftedStart + suggestedName.length());
        });
    }

    private static int findLineStart(int exprStart, PsiElement exprPsi, Document document) {
        int targetAnchorOffset = exprStart;
        if (exprPsi != null) {
            PsiElement cur = exprPsi;
            while (cur != null && !(cur instanceof PsiFile)) {
                if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                    if (ri.getRuleIndex() == OceanParser.RULE_statement) {
                        targetAnchorOffset = cur.getTextRange().getStartOffset();
                        break;
                    }
                }
                cur = cur.getParent();
            }
        }
        int lineNum = document.getLineNumber(targetAnchorOffset);
        return document.getLineStartOffset(lineNum);
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull PsiElement @NotNull [] elements, DataContext dataContext) {
        if (elements.length == 1 && elements[0].getContainingFile() != null) {
            PsiFile file = elements[0].getContainingFile();
            Project p = file.getProject();
            Document doc = PsiDocumentManager.getInstance(p).getDocument(file);
            if (doc != null) {
                // Delegate to single element
                int start = elements[0].getTextRange().getStartOffset();
                int end = elements[0].getTextRange().getEndOffset();
                String text = elements[0].getText();
                String type = inferType(text, elements[0]);
                String name = suggestVarName(text, type);

                int lineNum = doc.getLineNumber(start);
                int lineStart = doc.getLineStartOffset(lineNum);
                String docText = doc.getText();
                int indentEnd = lineStart;
                while (indentEnd < docText.length() && (docText.charAt(indentEnd) == ' ' || docText.charAt(indentEnd) == '\t')) {
                    indentEnd++;
                }
                String indent = docText.substring(lineStart, indentEnd);
                String typePrefix = !type.isBlank() && !type.equals("Object") ? type : "variable";
                String newDecl = indent + typePrefix + " " + name + " = " + text + ";\n";

                WriteCommandAction.runWriteCommandAction(project, "Extract Variable", null, () -> {
                    doc.insertString(lineStart, newDecl);
                    doc.replaceString(start + newDecl.length(), end + newDecl.length(), name);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                });
            }
        }
    }

    public static String inferType(String exprText, PsiElement context) {
        String guessed = OceanTypeHelper.guessTypeFromTextOrElement(exprText, context);
        if (guessed != null && !guessed.isBlank() && !guessed.equals("java.lang.Object")) {
            return cleanTypeName(guessed);
        }

        // Literal and syntax heuristics
        if (exprText.matches("^-?\\d+$")) return "int";
        if (exprText.matches("^-?\\d+\\.\\d+([fFdD])?$")) return "double";
        if (exprText.startsWith("\"") || exprText.startsWith("$\"")) return "String";
        if ("true".equals(exprText) || "false".equals(exprText)) return "bool";

        return "variable";
    }

    public static String cleanTypeName(String type) {
        if (type == null) return "variable";
        if (type.startsWith("java.lang.")) {
            type = type.substring("java.lang.".length());
        }
        if (type.startsWith("ocean.stdlib.")) {
            type = type.substring("ocean.stdlib.".length());
        }
        if ("boolean".equals(type)) return "bool";
        if ("var".equals(type)) return "variable";
        if ("val".equals(type)) return "value";
        return type;
    }

    public static String suggestVarName(String exprText, String type) {
        String name = "item";
        Matcher m = METHOD_CALL_PATTERN.matcher(exprText);
        if (m.find()) {
            String methodName = m.group(1);
            if (methodName.startsWith("get") && methodName.length() > 3) {
                name = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                name = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            } else {
                name = methodName;
            }
        } else if ("String".equalsIgnoreCase(type)) {
            name = "str";
        } else if ("int".equalsIgnoreCase(type) || "long".equalsIgnoreCase(type)) {
            name = "num";
        } else if ("double".equalsIgnoreCase(type) || "float".equalsIgnoreCase(type)) {
            name = "valNum";
        } else if ("bool".equalsIgnoreCase(type) || "boolean".equalsIgnoreCase(type)) {
            name = "flag";
        } else if (type != null && !type.isBlank() && !type.equals("variable") && !type.equals("value")) {
            int genericIdx = type.indexOf('<');
            String base = genericIdx > 0 ? type.substring(0, genericIdx) : type;
            name = Character.toLowerCase(base.charAt(0)) + base.substring(1);
        }

        // Ensure suggested variable name does NOT collide with Ocean keywords (e.g. variable, value, class, etc.)
        if (OceanKeywords.isKeyword(name)) {
            name = name + "Var";
        }

        return name;
    }
}
