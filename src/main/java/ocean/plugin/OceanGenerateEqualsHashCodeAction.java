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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OceanGenerateEqualsHashCodeAction extends AnAction {

    public OceanGenerateEqualsHashCodeAction() {
        super("equals() and hashCode()...", "Generate equals and hashCode methods", OceanIcons.ICON);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (psiFile == null || editor == null || project == null) return;

        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        if (element == null) return;

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

        // Collect existing methods
        Set<String> declaredMethods = new HashSet<>();
        List<FieldInfo> instanceFields = new ArrayList<>();

        for (PsiElement child : classDec.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_memberDeclaration) {
                    for (PsiElement mChild : child.getChildren()) {
                        if (mChild instanceof OceanMethodDeclaration method) {
                            String mName = method.getName();
                            if (mName != null) declaredMethods.add(mName);
                        } else if (mChild.getNode() != null && mChild.getNode().getElementType() instanceof RuleIElementType mri) {
                            if (mri.getRuleIndex() == OceanParser.RULE_fieldDeclaration) {
                                for (FieldInfo f : OceanGenerateConstructorAction.parseFields(mChild)) {
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

        String codeToInsert = generateEqualsHashCodeCode(className, instanceFields, declaredMethods.contains("equals"), declaredMethods.contains("hashCode"));
        if (codeToInsert.isBlank()) return;

        int caretOffset = editor.getCaretModel().getOffset();
        int classStart = classDec.getTextRange().getStartOffset();
        int classEnd = classDec.getTextRange().getEndOffset();
        int insertOffset = (caretOffset > classStart && caretOffset < classEnd) ? caretOffset : Math.max(0, classEnd - 1);

        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().insertString(insertOffset, codeToInsert));
    }

    public static String generateEqualsHashCodeCode(String className, List<FieldInfo> fields, boolean hasEquals, boolean hasHashCode) {
        StringBuilder sb = new StringBuilder();

        if (!hasEquals) {
            sb.append("\n    @Override\n");
            sb.append("    public bool function equals(Object other) {\n");
            sb.append("        if (this == other) return true;\n");
            sb.append("        if (other == null || getClass() != other.getClass()) return false;\n");
            sb.append("        ").append(className).append(" that = (").append(className).append(") other;\n");

            if (fields == null || fields.isEmpty()) {
                sb.append("        return super.equals(other);\n");
            } else {
                sb.append("        return ");
                for (int i = 0; i < fields.size(); i++) {
                    FieldInfo f = fields.get(i);
                    if (i > 0) sb.append(" &&\n               ");
                    if (isPrimitive(f.type())) {
                        sb.append("this.").append(f.name()).append(" == that.").append(f.name());
                    } else {
                        sb.append("Objects.equals(this.").append(f.name()).append(", that.").append(f.name()).append(")");
                    }
                }
                sb.append(";\n");
            }
            sb.append("    }\n");
        }

        if (!hasHashCode) {
            sb.append("\n    @Override\n");
            sb.append("    public int function hashCode() {\n");
            if (fields == null || fields.isEmpty()) {
                sb.append("        return super.hashCode();\n");
            } else {
                sb.append("        return Objects.hash(");
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(fields.get(i).name());
                }
                sb.append(");\n");
            }
            sb.append("    }\n");
        }

        return sb.toString();
    }

    private static boolean isPrimitive(String type) {
        if (type == null) return false;
        return switch (type) {
            case "int", "long", "short", "byte", "float", "double", "bool", "boolean", "char" -> true;
            default -> false;
        };
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
}
