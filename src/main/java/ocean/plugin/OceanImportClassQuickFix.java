package ocean.plugin;

import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanImportClassQuickFix implements IntentionAction, HighPriorityAction {
    private final String qualifiedName;

    public OceanImportClassQuickFix(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    @NotNull
    @Override
    public String getText() {
        return "Import class '" + qualifiedName + "'";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Import Class";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file != null && file.isValid() && !file.getText().contains("import " + qualifiedName + ";");
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document d = PsiDocumentManager.getInstance(project).getDocument(file);
            if (d != null) {
                insertImport(d, file, qualifiedName, project);
            }
        });
    }

    public static void insertImport(Document doc, PsiFile file, String qualifiedName, Project project) {
        if (doc == null || qualifiedName == null || qualifiedName.isBlank()
                || qualifiedName.startsWith("java.lang.") || qualifiedName.startsWith("ocean.stdlib.")) {
            return;
        }

        String text = doc.getText();
        if (text.contains("import " + qualifiedName + ";")) {
            return;
        }

        int insertOffset = -1;
        String importStatement = "import " + qualifiedName + ";\n";

        // 1. Try PSI: Find last import statement
        if (file != null) {
            PsiElement[] importStatements = PsiTreeUtil.collectElements(file, e -> {
                if (e.getNode() == null) return false;
                return e.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_importStatement;
            });
            if (importStatements.length > 0) {
                PsiElement lastImp = importStatements[importStatements.length - 1];
                insertOffset = lastImp.getTextRange().getEndOffset();
                if (insertOffset < text.length() && text.charAt(insertOffset) == '\r') insertOffset++;
                if (insertOffset < text.length() && text.charAt(insertOffset) == '\n') insertOffset++;
                importStatement = "import " + qualifiedName + ";\n";
            } else {
                // 2. Try PSI: Find package declaration
                PsiElement[] pkgDecls = PsiTreeUtil.collectElements(file, e -> {
                    if (e.getNode() == null) return false;
                    return e.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_packageDeclaration;
                });
                if (pkgDecls.length > 0) {
                    PsiElement pkg = pkgDecls[0];
                    insertOffset = pkg.getTextRange().getEndOffset();
                    if (insertOffset < text.length() && text.charAt(insertOffset) == '\r') insertOffset++;
                    if (insertOffset < text.length() && text.charAt(insertOffset) == '\n') insertOffset++;
                    importStatement = "\nimport " + qualifiedName + ";\n";
                }
            }
        }

        // 3. Fallback to Text Analysis if PSI didn't locate them
        if (insertOffset == -1) {
            int lastImpIdx = text.lastIndexOf("import ");
            if (lastImpIdx >= 0) {
                int semiAfter = text.indexOf(';', lastImpIdx);
                if (semiAfter > 0) {
                    insertOffset = semiAfter + 1;
                    if (insertOffset < text.length() && text.charAt(insertOffset) == '\r') insertOffset++;
                    if (insertOffset < text.length() && text.charAt(insertOffset) == '\n') insertOffset++;
                    importStatement = "import " + qualifiedName + ";\n";
                }
            } else {
                int pkgIdx = text.indexOf("package ");
                if (pkgIdx >= 0) {
                    int semiAfter = text.indexOf(';', pkgIdx);
                    if (semiAfter > 0) {
                        insertOffset = semiAfter + 1;
                        if (insertOffset < text.length() && text.charAt(insertOffset) == '\r') insertOffset++;
                        if (insertOffset < text.length() && text.charAt(insertOffset) == '\n') insertOffset++;
                        importStatement = "\nimport " + qualifiedName + ";\n";
                    }
                }
            }
        }

        // 4. If still not found, place above class/interface/enum/annotation declaration
        if (insertOffset == -1) {
            Matcher m = Pattern.compile("(?m)^\\s*(public\\s+|abstract\\s+|final\\s+)*(class|interface|enum|annotation)\\b").matcher(text);
            if (m.find()) {
                insertOffset = m.start();
                importStatement = "import " + qualifiedName + ";\n\n";
            } else {
                insertOffset = 0;
                importStatement = "import " + qualifiedName + ";\n";
            }
        }

        doc.insertString(insertOffset, importStatement);
        if (project != null) {
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
