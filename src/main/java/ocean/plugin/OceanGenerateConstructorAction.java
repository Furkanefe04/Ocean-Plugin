package ocean.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class OceanGenerateConstructorAction extends AnAction {

    public OceanGenerateConstructorAction() {
        super("Constructor...", "Generate Constructor", OceanIcons.ICON);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (psiFile == null || editor == null || project == null) return;

        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        if (element == null) return;

        // Traverse up to find enclosing classDeclaration
        PsiElement classDec = null;
        PsiElement curr = element;
        while (curr != null) {
            if (curr.getNode() != null && curr.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_classDeclaration) {
                    classDec = curr;
                    break;
                }
            }
            curr = curr.getParent();
        }

        if (classDec == null) return;
        if (isDataClass(classDec)) return;

        // Find all field declarations
        List<FieldInfo> instanceFields = new ArrayList<>();
        for (PsiElement child : classDec.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_memberDeclaration) {
                    for (PsiElement mChild : child.getChildren()) {
                        if (mChild.getNode() != null && mChild.getNode().getElementType() instanceof RuleIElementType mri) {
                            if (mri.getRuleIndex() == OceanParser.RULE_fieldDeclaration) {
                                for (FieldInfo f : parseFields(mChild)) {
                                    if (!f.isStatic()) {
                                        instanceFields.add(f);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Build constructor code
        StringBuilder sb = new StringBuilder();
        String className = findCtorClassName(classDec);

        sb.append("\n    public function ").append(className).append("(");
        for (int i = 0; i < instanceFields.size(); i++) {
            FieldInfo f = instanceFields.get(i);
            String fieldType = f.type();
            if (fieldType == null || "variable".equals(fieldType) || "value".equals(fieldType)) {
                fieldType = "Object";
            }
            sb.append(fieldType).append(" ").append(f.name());
            if (i < instanceFields.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(") {\n");
        for (FieldInfo f : instanceFields) {
            sb.append("        this.").append(f.name()).append(" = ").append(f.name()).append(";\n");
        }
        sb.append("    }\n");

        int caretOffset = editor.getCaretModel().getOffset();
        int classStart = classDec.getTextRange().getStartOffset();
        int classEnd = classDec.getTextRange().getEndOffset();
        int insertOffset = (caretOffset > classStart && caretOffset < classEnd) ? caretOffset : Math.max(0, classEnd - 1);

        String codeToInsert = sb.toString();
        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().insertString(insertOffset, codeToInsert));
    }

    private static @NotNull String findCtorClassName(PsiElement classDec) {
        String className = null;
        if (classDec instanceof OceanClassDeclaration ocd) {
            className = ocd.getName();
        }
        if (className == null || className.isBlank()) {
            for (PsiElement child : classDec.getChildren()) {
                if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri) {
                    if (ri.getRuleIndex() == OceanParser.RULE_anyId) {
                        String idText = child.getText().trim();
                        int angle = idText.indexOf('<');
                        className = (angle > 0) ? idText.substring(0, angle).trim() : idText;
                        break;
                    }
                }
            }
        }
        if (className == null || className.isBlank()) {
            className = "MyClass";
        }
        return className;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Only enable if we are inside an Ocean file and inside a class
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (psiFile == null || editor == null || !psiFile.getName().endsWith(".ocean")) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiElement classDec = null;
        PsiElement curr = element;
        while (curr != null) {
            if (curr.getNode() != null && curr.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_classDeclaration) {
                    classDec = curr;
                    break;
                }
            }
            curr = curr.getParent();
        }

        boolean insideClass = classDec != null;
        boolean isData = isDataClass(classDec);
        e.getPresentation().setEnabledAndVisible(insideClass && !isData);
    }

    public static boolean isDataClass(PsiElement classDec) {
        if (classDec == null) return false;
        String text = classDec.getText();
        int braceIdx = text.indexOf('{');
        String header = braceIdx > 0 ? text.substring(0, braceIdx) : text;
        return header.matches("(?s).*\\bdata\\s+class\\b.*");
    }

    static List<FieldInfo> parseFields(PsiElement field) {
        List<FieldInfo> result = new ArrayList<>();
        String type = "variable";
        boolean isStatic = false;
        boolean isFinal = false;

        for (PsiElement sub : field.getChildren()) {
            if (sub.getNode() != null) {
                if (sub.getNode().getElementType() instanceof RuleIElementType sri) {
                    if (sri.getRuleIndex() == OceanParser.RULE_modifier) {
                        String mod = sub.getText().trim();
                        if ("static".equals(mod)) isStatic = true;
                        if ("final".equals(mod)) isFinal = true;
                    } else if (sri.getRuleIndex() == OceanParser.RULE_type) {
                        type = sub.getText().trim();
                    } else if (sri.getRuleIndex() == OceanParser.RULE_variableDeclarator) {
                        String name = extractDeclaratorName(sub);
                        if (name != null && !name.isEmpty()) {
                            result.add(new FieldInfo(name, type, isStatic, isFinal));
                        }
                    }
                } else if (sub.getNode().getElementType() instanceof TokenIElementType ti) {
                    int tt = ti.getANTLRTokenType();
                    if (tt == OceanLexer.STATIC) isStatic = true;
                    if (tt == OceanLexer.FINAL) isFinal = true;
                }
            }
        }

        if (result.isEmpty()) {
            for (PsiElement sub : field.getChildren()) {
                if (sub.getNode() != null && sub.getNode().getElementType() instanceof RuleIElementType sri) {
                    if (sri.getRuleIndex() == OceanParser.RULE_anyId) {
                        result.add(new FieldInfo(sub.getText().trim(), type, isStatic, isFinal));
                    }
                }
            }
        }

        return result;
    }

    private static String extractDeclaratorName(PsiElement declarator) {
        for (PsiElement c : declarator.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_anyId) {
                    return c.getText().trim();
                }
            }
        }
        String text = declarator.getText().trim();
        int eq = text.indexOf('=');
        if (eq > 0) text = text.substring(0, eq).trim();
        return text;
    }
}
