package ocean.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OceanGenerateGettersSettersAction extends AnAction {
    public OceanGenerateGettersSettersAction() {
        super("Getters and Setters...", "Generate Getters and Setters", OceanIcons.ICON);
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
        if (OceanGenerateConstructorAction.isDataClass(classDec)) return;

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

        // Collect existing method names in class to prevent duplicates
        Set<String> existingMethods = new HashSet<>();
        for (PsiElement m : PsiTreeUtil.collectElements(classDec, el ->
                el.getNode() != null && el.getNode().getElementType() instanceof RuleIElementType mri &&
                mri.getRuleIndex() == OceanParser.RULE_methodDeclaration)) {
            for (PsiElement c : m.getChildren()) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType mri &&
                        mri.getRuleIndex() == OceanParser.RULE_anyId) {
                    String mName = c.getText().trim();
                    int angle = mName.indexOf('<');
                    existingMethods.add(angle > 0 ? mName.substring(0, angle).trim() : mName);
                    break;
                }
            }
        }

        // Build getters and setters code
        StringBuilder sb = new StringBuilder();
        for (FieldInfo f : instanceFields) {
            if (f.name() == null || f.name().isBlank()) continue;
            String capitalized = Character.toUpperCase(f.name().charAt(0)) + (f.name().length() > 1 ? f.name().substring(1) : "");
            String fieldType = f.type();
            if (fieldType == null || "variable".equals(fieldType) || "value".equals(fieldType)) {
                fieldType = "Object";
            }
            
            // Getter
            String getterName = "get" + capitalized;
            if (!existingMethods.contains(getterName)) {
                sb.append("\n    public ").append(fieldType).append(" function ").append(getterName).append("() {\n");
                sb.append("        return this.").append(f.name()).append(";\n");
                sb.append("    }\n");
                existingMethods.add(getterName);
            }

            // Setter (only for mutable non-final fields)
            String setterName = "set" + capitalized;
            if (!f.isFinal() && !existingMethods.contains(setterName)) {
                sb.append("\n    public void function ").append(setterName).append("(").append(fieldType).append(" ").append(f.name()).append(") {\n");
                sb.append("        this.").append(f.name()).append(" = ").append(f.name()).append(";\n");
                sb.append("    }\n");
                existingMethods.add(setterName);
            }
        }

        int caretOffset = editor.getCaretModel().getOffset();
        int classStart = classDec.getTextRange().getStartOffset();
        int classEnd = classDec.getTextRange().getEndOffset();
        int insertOffset = (caretOffset > classStart && caretOffset < classEnd) ? caretOffset : Math.max(0, classEnd - 1);

        String codeToInsert = sb.toString();
        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().insertString(insertOffset, codeToInsert));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
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
        boolean isData = OceanGenerateConstructorAction.isDataClass(classDec);
        e.getPresentation().setEnabledAndVisible(insideClass && !isData);
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
