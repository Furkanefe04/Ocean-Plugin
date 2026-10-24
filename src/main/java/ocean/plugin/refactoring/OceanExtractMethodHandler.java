package ocean.plugin.refactoring;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.RefactoringActionHandler;
import ocean.plugin.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles 'Extract Method / Function' refactoring (Ctrl + Alt + M) for Ocean files.
 * Generates functions in Ocean V3 syntax:
 *   public [returnType] function methodName([params]) { ... }
 */
public class OceanExtractMethodHandler implements RefactoringActionHandler {

    private static final Pattern RETURN_PATTERN = Pattern.compile("(?m)^[ \t]*return\\s+([^;]+);");

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        if (editor == null || !(file instanceof OceanFile)) {
            return;
        }

        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return;
        }

        SelectionModel sm = editor.getSelectionModel();
        if (!sm.hasSelection()) {
            return;
        }

        int selStart = sm.getSelectionStart();
        int selEnd = sm.getSelectionEnd();
        String selectedCode = sm.getSelectedText();
        if (selectedCode == null || selectedCode.isBlank()) {
            return;
        }

        OceanClassDeclaration enclosingClass = PsiTreeUtil.findElementOfClassAtOffset(file, selStart, OceanClassDeclaration.class, false);
        if (enclosingClass == null) {
            enclosingClass = PsiTreeUtil.findChildOfType(file, OceanClassDeclaration.class);
        }

        // 1. Analyze return statement
        Matcher returnMatcher = RETURN_PATTERN.matcher(selectedCode);
        String returnType = "void";
        boolean hasReturn = false;
        boolean isPureExpression = false;
        if (returnMatcher.find()) {
            hasReturn = true;
            String returnExpr = returnMatcher.group(1).trim();
            PsiElement exprContext = file.findElementAt(selStart);
            returnType = OceanIntroduceVariableHandler.cleanTypeName(
                    OceanIntroduceVariableHandler.inferType(returnExpr, exprContext)
            );
        } else if (!selectedCode.contains(";") && !selectedCode.contains("\n")) {
            PsiElement exprContext = file.findElementAt(selStart);
            String inferred = OceanIntroduceVariableHandler.cleanTypeName(
                    OceanIntroduceVariableHandler.inferType(selectedCode.trim(), exprContext)
            );
            if (!inferred.isBlank()) {
                returnType = inferred;
                hasReturn = true;
                isPureExpression = true;
            }
        }

        // 2. Discover parameters from prior local variables used in selection
        List<String> paramDecls = new ArrayList<>();
        List<String> argNames = new ArrayList<>();

        Set<String> seenIdentifiers = new LinkedHashSet<>();
        Matcher idMatcher = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b").matcher(selectedCode);
        while (idMatcher.find()) {
            String word = idMatcher.group(1);
            if (!OceanKeywords.isKeyword(word)) {
                seenIdentifiers.add(word);
            }
        }

        // Check if any identifier was declared before selStart
        PsiElement method = PsiTreeUtil.findElementOfClassAtOffset(file, selStart, OceanMethodDeclaration.class, false);
        boolean isStatic = false;
        if (method != null) {
            String mText = method.getText();
            int funcIdx = mText.indexOf("function");
            if (funcIdx > 0 && mText.substring(0, funcIdx).contains("static")) {
                isStatic = true;
            }
            for (String varName : seenIdentifiers) {
                boolean matched = false;
                for (OceanParameter param : PsiTreeUtil.findChildrenOfType(method, OceanParameter.class)) {
                    if (varName.equals(param.getName()) && param.getTextRange().getStartOffset() < selStart) {
                        String type = OceanIntroduceVariableHandler.cleanTypeName(
                                OceanTypeHelper.guessType(param)
                        );
                        paramDecls.add(type + " " + varName);
                        argNames.add(varName);
                        matched = true;
                        break;
                    }
                }
                if (matched) continue;

                for (OceanVariableDeclarator decl : PsiTreeUtil.findChildrenOfType(method, OceanVariableDeclarator.class)) {
                    if (varName.equals(decl.getName())
                            && decl.getTextRange().getStartOffset() < selStart) {
                        String type = OceanIntroduceVariableHandler.cleanTypeName(
                                OceanTypeHelper.guessType(decl)
                        );
                        paramDecls.add(type + " " + varName);
                        argNames.add(varName);
                        break;
                    }
                }
            }
        }

        String baseName = "extractedFunction";
        String methodName = baseName;
        if (enclosingClass != null) {
            Set<String> existingMethods = new HashSet<>();
            for (OceanMethodDeclaration md : PsiTreeUtil.findChildrenOfType(enclosingClass, OceanMethodDeclaration.class)) {
                if (md.getName() != null) {
                    existingMethods.add(md.getName());
                }
            }
            int counter = 1;
            while (existingMethods.contains(methodName)) {
                methodName = baseName + counter++;
            }
        }

        String bodyText = isPureExpression ? ("return " + selectedCode.trim() + ";") : selectedCode;
        String methodStub = buildMethodStub(isStatic, methodName, returnType, paramDecls, bodyText);

        // 3. Build replacement call with preserved indentation
        int actualSelStart = selStart;
        int actualSelEnd = selEnd;

        int lineNum = document.getLineNumber(selStart);
        int lineStart = document.getLineStartOffset(lineNum);
        String docText = document.getText();
        int indentEnd = lineStart;
        while (indentEnd < docText.length() && (docText.charAt(indentEnd) == ' ' || docText.charAt(indentEnd) == '\t')) {
            indentEnd++;
        }
        String indent = docText.substring(lineStart, indentEnd);

        String callStatement;
        String args = String.join(", ", argNames);
        boolean isFullLine = (selStart <= indentEnd);

        if (isFullLine) {
            actualSelStart = lineStart;
            if (hasReturn && !returnType.equals("void")) {
                callStatement = indent + "return " + methodName + "(" + args + ");\n";
            } else {
                callStatement = indent + methodName + "(" + args + ");\n";
            }
            if (actualSelEnd < docText.length() && (docText.charAt(actualSelEnd) == '\n' || docText.charAt(actualSelEnd) == '\r')) {
                if (docText.charAt(actualSelEnd) == '\r' && actualSelEnd + 1 < docText.length() && docText.charAt(actualSelEnd + 1) == '\n') {
                    actualSelEnd += 2;
                } else {
                    actualSelEnd += 1;
                }
            }
        } else {
            if (isPureExpression) {
                callStatement = methodName + "(" + args + ")";
            } else if (hasReturn && !returnType.equals("void")) {
                callStatement = "return " + methodName + "(" + args + ");";
            } else {
                callStatement = methodName + "(" + args + ");";
            }
        }

        OceanClassDeclaration finalEnclosingClass = enclosingClass;
        final int replaceStart = actualSelStart;
        final int replaceEnd = actualSelEnd;
        final String finalCall = callStatement;

        WriteCommandAction.runWriteCommandAction(project, "Extract Method", null, () -> {
            RangeMarker classEndMarker = null;
            if (finalEnclosingClass != null) {
                int classEnd = finalEnclosingClass.getTextRange().getEndOffset();
                int insertOffset = classEnd - 1;
                String currentText = document.getText();
                while (insertOffset > 0 && currentText.charAt(insertOffset) != '}') {
                    insertOffset--;
                }
                int targetPos = (insertOffset > 0) ? insertOffset : classEnd;
                classEndMarker = document.createRangeMarker(targetPos, targetPos);
                classEndMarker.setGreedyToRight(true);
            }

            // Replace selection with method call
            document.replaceString(replaceStart, replaceEnd, finalCall);

            // Insert new method in class or at bottom of file
            if (classEndMarker != null && classEndMarker.isValid()) {
                int insertOffset = classEndMarker.getStartOffset();
                document.insertString(insertOffset, "\n" + methodStub + "\n");
                classEndMarker.dispose();
            } else {
                document.insertString(document.getTextLength(), "\n" + methodStub + "\n");
            }

            PsiDocumentManager.getInstance(project).commitDocument(document);
        });
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull PsiElement @NotNull [] elements, DataContext dataContext) {
        // Elements array invocation
    }

    public static String buildMethodStub(String name, String returnType, List<String> params, String body) {
        return buildMethodStub(false, name, returnType, params, body);
    }

    public static String buildMethodStub(boolean isStatic, String name, String returnType, List<String> params, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public ");
        if (isStatic) {
            sb.append("static ");
        }
        sb.append(returnType).append(" function ").append(name).append("(");
        sb.append(String.join(", ", params));
        sb.append(") {\n");

        String[] lines = body.split("\r?\n");
        for (String line : lines) {
            if (line.isBlank()) {
                sb.append("\n");
            } else {
                sb.append("        ").append(line.trim()).append("\n");
            }
        }
        sb.append("    }");
        return sb.toString();
    }
}
