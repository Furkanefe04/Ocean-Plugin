package ocean.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class OceanOverrideMethodAction extends AnAction {

    public OceanOverrideMethodAction() {
        super("Override Methods...", "Override Superclass Methods", OceanIcons.ICON);
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

        List<OceanImplementMethodsAction.MethodStub> methods = collectOverridableMethods(project, classDec);

        StringBuilder sb = new StringBuilder();
        if (!methods.isEmpty()) {
            for (OceanImplementMethodsAction.MethodStub m : methods) {
                String ret = m.returnType() != null && !m.returnType().isBlank() ? m.returnType() + " " : "void ";
                String argNames = extractArgNames(m.params());
                sb.append("\n\n    @Override\n");
                sb.append("    public ").append(ret).append("function ").append(m.name()).append("(").append(m.params()).append(") {\n");
                if (!"void".equalsIgnoreCase(m.returnType()) && !(m.returnType() != null && m.returnType().isBlank())) {
                    sb.append("        return super.").append(m.name()).append("(").append(argNames).append(");\n");
                } else {
                    sb.append("        super.").append(m.name()).append("(").append(argNames).append(");\n");
                }
                sb.append("    }");
            }
        }

        int insertOffset = calculateSafeInsertionOffset(editor, classDec);

        String codeToInsert = sb.toString();
        WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().insertString(insertOffset, codeToInsert));
    }

    public static int calculateSafeInsertionOffset(Editor editor, PsiElement classDec) {
        int classStart = classDec.getTextRange().getStartOffset();
        int classEnd = classDec.getTextRange().getEndOffset();
        int caretOffset = editor != null ? editor.getCaretModel().getOffset() : classEnd - 1;

        if (caretOffset <= classStart || caretOffset >= classEnd) {
            return Math.max(0, classEnd - 1);
        }

        PsiFile file = classDec.getContainingFile();
        if (file != null) {
            PsiElement cur = file.findElementAt(caretOffset);
            while (cur != null && cur != classDec) {
                if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                    int r = ri.getRuleIndex();
                    // If caret is inside a method, constructor, or field, insert after this member!
                    if (r == OceanParser.RULE_methodDeclaration ||
                        r == OceanParser.RULE_constructorDeclaration ||
                        r == OceanParser.RULE_fieldDeclaration ||
                        r == OceanParser.RULE_block) {
                        return Math.min(classEnd - 1, cur.getTextRange().getEndOffset());
                    }
                }
                cur = cur.getParent();
            }
        }

        return caretOffset;
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
        boolean insideClass = false;
        PsiElement curr = element;
        while (curr != null) {
            if (curr.getNode() != null && curr.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_classDeclaration) {
                    insideClass = true;
                    break;
                }
            }
            curr = curr.getParent();
        }

        e.getPresentation().setEnabledAndVisible(insideClass);
    }

    public static List<OceanImplementMethodsAction.MethodStub> collectOverridableMethods(Project project, PsiElement classDec) {
        List<OceanImplementMethodsAction.MethodStub> result = new ArrayList<>();
        Set<String> seenSigs = new HashSet<>();

        // 1. Collect methods already declared directly in this class
        Set<String> declaredInClass = collectDeclaredInClass(classDec);

        // 2. Collect missing abstract methods first
        OceanImplementMethodsAction action = new OceanImplementMethodsAction();
        List<OceanImplementMethodsAction.MethodStub> missing = action.collectMissingMethods(project, classDec);
        if (missing != null) {
            for (OceanImplementMethodsAction.MethodStub s : missing) {
                if (seenSigs.add(s.name() + "/" + countParams(s.params()))) {
                    result.add(s);
                }
            }
        }

        // 3. Resolve superclass overridable methods
        String superName = OceanScopeDiscovery.extractExtendsName(classDec);
        if (superName != null && !superName.isBlank()) {
            // A. Ocean superclass
            PsiElement oceanSuper = OceanScopeDiscovery.findOceanClass(project, superName, classDec.getContainingFile());
            if (oceanSuper != null) {
                collectOceanSuperMethods(oceanSuper, declaredInClass, seenSigs, result, project, new HashSet<>());
            } else {
                // B. Java superclass
                PsiClass javaSuper = OceanScopeDiscovery.resolveJavaClass(project, superName);
                if (javaSuper != null) {
                    collectJavaSuperMethods(javaSuper, declaredInClass, seenSigs, result);
                }
            }
        }

        // 4. If result is still empty, provide standard Object overridables
        if (result.isEmpty()) {
            if (!declaredInClass.contains("toString")) {
                result.add(new OceanImplementMethodsAction.MethodStub("toString", "String", ""));
            }
            if (!declaredInClass.contains("equals")) {
                result.add(new OceanImplementMethodsAction.MethodStub("equals", "bool", "Object other"));
            }
            if (!declaredInClass.contains("hashCode")) {
                result.add(new OceanImplementMethodsAction.MethodStub("hashCode", "int", ""));
            }
        }

        return result;
    }

    private static @NotNull Set<String> collectDeclaredInClass(PsiElement classDec) {
        Set<String> declaredInClass = new HashSet<>();
        for (PsiElement child : classDec.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_memberDeclaration) {
                for (PsiElement mChild : child.getChildren()) {
                    if (mChild instanceof OceanMethodDeclaration method) {
                        String mName = method.getName();
                        if (mName != null) declaredInClass.add(mName);
                    }
                }
            }
        }
        return declaredInClass;
    }

    private static void collectOceanSuperMethods(PsiElement cls, Set<String> declaredInClass, Set<String> seenSigs, List<OceanImplementMethodsAction.MethodStub> result, Project project, Set<PsiElement> visited) {
        if (cls == null || !visited.add(cls)) return;
        for (PsiElement child : cls.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_memberDeclaration) {
                for (PsiElement mChild : child.getChildren()) {
                    if (mChild instanceof OceanMethodDeclaration method) {
                        String name = method.getName();
                        if (name == null || declaredInClass.contains(name)) continue;
                        String text = method.getText();
                        if (text.contains("private") || text.contains("final") || text.contains("static")) continue;

                        String retType = extractReturnType(method);
                        String params = extractParamsString(method);
                        String sig = name + "/" + countParams(params);
                        if (seenSigs.add(sig)) {
                            result.add(new OceanImplementMethodsAction.MethodStub(name, retType, params));
                        }
                    }
                }
            }
        }
        String superName = OceanScopeDiscovery.extractExtendsName(cls);
        if (superName != null) {
            PsiElement nextSuper = OceanScopeDiscovery.findOceanClass(project, superName, cls.getContainingFile());
            if (nextSuper != null) {
                collectOceanSuperMethods(nextSuper, declaredInClass, seenSigs, result, project, visited);
            }
        }
    }

    private static void collectJavaSuperMethods(PsiClass javaCls, Set<String> declaredInClass, Set<String> seenSigs, List<OceanImplementMethodsAction.MethodStub> result) {
        if (javaCls == null) return;
        for (PsiMethod m : javaCls.getAllMethods()) {
            if (m.isConstructor()) continue;
            if (m.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (m.hasModifierProperty(PsiModifier.PRIVATE)) continue;
            if (m.hasModifierProperty(PsiModifier.FINAL)) continue;

            String name = m.getName();
            if (declaredInClass.contains(name)) continue;

            PsiType retType = m.getReturnType();
            String retStr = retType != null ? retType.getPresentableText() : "void";
            StringBuilder params = new StringBuilder();
            PsiParameter[] pList = m.getParameterList().getParameters();
            for (int i = 0; i < pList.length; i++) {
                if (i > 0) params.append(", ");
                params.append(pList[i].getType().getPresentableText()).append(" ").append(pList[i].getName());
            }
            String pStr = params.toString();
            String sig = name + "/" + pList.length;
            if (seenSigs.add(sig)) {
                result.add(new OceanImplementMethodsAction.MethodStub(name, retStr, pStr));
            }
        }
    }

    private static String extractReturnType(OceanMethodDeclaration method) {
        return OceanScopeDiscovery.getMethodReturnType(method);
    }

    private static String extractParamsString(OceanMethodDeclaration method) {
        for (PsiElement child : method.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_parameterList) {
                    return child.getText().trim();
                }
            }
        }
        return "";
    }

    public static int countParams(String paramsStr) {
        if (paramsStr == null || paramsStr.isBlank()) return 0;
        String argNames = extractArgNames(paramsStr);
        if (argNames.isBlank()) return 0;
        return argNames.split(",").length;
    }

    public static String extractArgNames(String paramsStr) {
        if (paramsStr == null || paramsStr.isBlank()) return "";
        List<String> names = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : paramsStr.toCharArray()) {
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                String p = current.toString().trim();
                if (!p.isEmpty()) {
                    names.add(extractParamName(p));
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String p = current.toString().trim();
        if (!p.isEmpty()) {
            names.add(extractParamName(p));
        }
        return String.join(", ", names);
    }

    private static String extractParamName(String paramChunk) {
        String trimmed = paramChunk.trim();
        int eq = trimmed.indexOf('=');
        if (eq > 0) trimmed = trimmed.substring(0, eq).trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace >= 0) {
            return trimmed.substring(lastSpace + 1).trim();
        }
        return trimmed;
    }
}
