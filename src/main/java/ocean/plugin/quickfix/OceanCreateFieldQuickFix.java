package ocean.plugin.quickfix;

import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import ocean.plugin.*;
import ocean.plugin.refactoring.OceanIntroduceVariableHandler;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Quick-fix to create missing fields or variables in Ocean V3 syntax:
 *   public [static] <fieldType> <fieldName> = <defaultValue>;
 */
public class OceanCreateFieldQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final String fieldName;
    private final String fieldType;
    private final boolean isStatic;
    private final String targetClassName;
    private final PsiElement element;

    public OceanCreateFieldQuickFix() {
        this("", "Object", false, null, null);
    }

    public OceanCreateFieldQuickFix(String fieldName) {
        this(fieldName, "variable", false, null, null);
    }

    public OceanCreateFieldQuickFix(String fieldName, String fieldType, boolean isStatic) {
        this(fieldName, fieldType, isStatic, null, null);
    }

    public OceanCreateFieldQuickFix(String fieldName, String fieldType, boolean isStatic, String targetClassName, PsiElement element) {
        this.fieldName = fieldName != null ? fieldName : "";
        this.fieldType = fieldType != null && !fieldType.isBlank() ? fieldType : "variable";
        this.isStatic = isStatic;
        this.targetClassName = targetClassName;
        this.element = element;
    }

    public OceanCreateFieldQuickFix(PsiElement element) {
        this(
                element != null ? element.getText() : "",
                inferExpectedFieldType(element),
                isFieldStatic(element),
                inferTargetClassName(element),
                element
        );
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public String getTargetClassName() {
        return targetClassName;
    }

    public PsiElement getElement() {
        return element;
    }

    @NotNull
    @Override
    public String getText() {
        return "Create variable '" + fieldName + "'";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Create Field/Variable";
    }

    public static boolean isValidName(String name) {
        return name != null && !name.isBlank() && !OceanKeywords.isKeyword(name);
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file instanceof OceanFile && isValidName(fieldName);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        if (!(file instanceof OceanFile)) return;
        PsiElement context = null;
        if (element != null) {
            context = element;
        } else if (editor != null) {
            context = file.findElementAt(editor.getCaretModel().getOffset());
        }
        applyFixInternal(project, file, context);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement el = descriptor.getPsiElement();
        if (el == null) el = element;
        if (el == null || el.getContainingFile() == null) return;
        applyFixInternal(project, el.getContainingFile(), el);
    }

    public String generateFieldStub() {
        StringBuilder sb = new StringBuilder();
        sb.append("    public ");
        if (isStatic) {
            sb.append("static ");
        }
        String type = (fieldType != null && !fieldType.isBlank()) ? fieldType : "variable";
        sb.append(type).append(" ").append(fieldName);
        String defaultVal = getDefaultFieldValue(type);
        sb.append(" = ").append(defaultVal).append(";\n");
        return sb.toString();
    }

    public static String getDefaultFieldValue(String type) {
        if (type == null) return "0";
        String clean = OceanIntroduceVariableHandler.cleanTypeName(type).trim();
        return switch (clean) {
            case "int", "long", "short", "byte", "variable", "value" -> "0";
            case "char" -> "' '";
            case "double", "float" -> "0.0";
            case "bool", "boolean" -> "false";
            case "String" -> "\"\"";
            default -> "null";
        };
    }

    private void applyFixInternal(Project project, PsiFile file, PsiElement context) {
        if (context == null && element != null) {
            context = element;
        }
        if (context != null && context.getContainingFile() != null) {
            file = context.getContainingFile();
        }
        if (file == null) return;

        PsiFile targetFile = file;
        OceanClassDeclaration targetClass = null;
        if (targetClassName != null) {
            for (OceanClassDeclaration cd : PsiTreeUtil.findChildrenOfType(file, OceanClassDeclaration.class)) {
                if (targetClassName.equals(cd.getName())) {
                    targetClass = cd;
                    break;
                }
            }
            if (targetClass == null) {
                PsiElement found = OceanScopeDiscovery.findOceanClass(project, targetClassName, file);
                if (found instanceof OceanClassDeclaration cd) {
                    targetClass = cd;
                    targetFile = cd.getContainingFile();
                }
            }
        }
        if (targetClass == null && context != null) {
            targetClass = PsiTreeUtil.getParentOfType(context, OceanClassDeclaration.class);
        }
        if (targetClass == null) {
            targetClass = PsiTreeUtil.findChildOfType(file, OceanClassDeclaration.class);
        }

        Document document = PsiDocumentManager.getInstance(project).getDocument(targetFile);
        if (document == null) return;

        String decl = generateFieldStub();
        OceanClassDeclaration finalTargetClass = targetClass;

        WriteCommandAction.runWriteCommandAction(project, "Create Field", null, () -> {
            if (finalTargetClass != null) {
                // Insert after opening '{' of class
                String docText = document.getText();
                int classStart = finalTargetClass.getTextRange().getStartOffset();
                int openBrace = docText.indexOf('{', classStart);
                if (openBrace > 0) {
                    document.insertString(openBrace + 1, "\n" + decl);
                } else {
                    document.insertString(classStart, decl);
                }
            } else {
                document.insertString(0, decl.trim() + "\n");
            }
            PsiDocumentManager.getInstance(project).commitDocument(document);
        });
    }

    public static String inferExpectedFieldType(PsiElement element) {
        if (element == null) return "variable";
        PsiElement cur = element;
        while (cur != null && !(cur instanceof PsiFile)) {
            // 1. Inside variable or field declaration: int x = missingVar;
            if (isRule(cur, OceanParser.RULE_variableDeclaration) || isRule(cur, OceanParser.RULE_fieldDeclaration)) {
                PsiElement typeNode = findChildByRule(cur, OceanParser.RULE_type);
                if (typeNode != null) {
                    return OceanIntroduceVariableHandler.cleanTypeName(typeNode.getText().trim());
                }
                return "variable";
            }

            // 2. Assignment: x = missingVar; or missingVar = "hello";
            if (isRule(cur, OceanParser.RULE_assignment)) {
                PsiElement eq = null;
                PsiElement lhs = null;
                PsiElement rhs = null;
                for (PsiElement c = cur.getFirstChild(); c != null; c = c.getNextSibling()) {
                    String txt = c.getText().trim();
                    if ("=".equals(txt) || "+=".equals(txt) || "-=".equals(txt) || "*=".equals(txt) || "/=".equals(txt)) {
                        eq = c;
                        continue;
                    }
                    if (eq == null && !txt.isEmpty()) {
                        if (lhs == null) lhs = c;
                    } else if (eq != null && !txt.isEmpty()) {
                        if (rhs == null) rhs = c;
                    }
                }

                // If element is on LHS (e.g. missingVar = 42;), infer from RHS
                if (eq != null && element.getTextOffset() < eq.getTextOffset() && rhs != null) {
                    String rhsType = OceanTypeHelper.guessTypeFromTextOrElement(rhs.getText(), rhs);
                    if (rhsType != null && !rhsType.equals("java.lang.Object")) {
                        return OceanIntroduceVariableHandler.cleanTypeName(rhsType);
                    }
                }

                // If element is on RHS (e.g. x = missingVar;), infer from LHS
                if (eq != null && element.getTextOffset() > eq.getTextOffset() && lhs != null) {
                    String lhsType = OceanTypeHelper.guessTypeFromTextOrElement(lhs.getText(), lhs);
                    if (lhsType != null && !lhsType.equals("java.lang.Object")) {
                        return OceanIntroduceVariableHandler.cleanTypeName(lhsType);
                    }
                }
            }

            // 3. Condition: if (missingVar)
            if (isRule(cur, OceanParser.RULE_ifStatement) || isRule(cur, OceanParser.RULE_whileStatement) || isRule(cur, OceanParser.RULE_doWhileStatement)) {
                PsiElement block = findChildByRule(cur, OceanParser.RULE_block);
                if (block == null || element.getTextOffset() < block.getTextOffset()) {
                    return "bool";
                }
            }

            // 4. Return statement: return missingVar;
            if (isRule(cur, OceanParser.RULE_returnStatement)) {
                PsiElement method = cur.getParent();
                while (method != null && !isRule(method, OceanParser.RULE_methodDeclaration) && !(method instanceof OceanMethodDeclaration)) {
                    if (method instanceof PsiFile || isRule(method, OceanParser.RULE_classDeclaration)) break;
                    method = method.getParent();
                }
                if (method != null) {
                    PsiElement typeNode = findChildByRule(method, OceanParser.RULE_type);
                    if (typeNode != null) {
                        return OceanIntroduceVariableHandler.cleanTypeName(typeNode.getText().trim());
                    }
                }
            }

            cur = cur.getParent();
        }
        return "variable";
    }

    public static boolean isFieldStatic(PsiElement element) {
        if (element == null) return false;
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(element);
        if (prev != null && (".".equals(prev.getText()) || "?.".equals(prev.getText()) || "::".equals(prev.getText()))) {
            PsiElement recLeaf = PsiTreeUtil.prevVisibleLeaf(prev);
            if (recLeaf != null && OceanCreateMethodQuickFix.isKnownClassName(recLeaf.getText().trim(), element)) {
                return true;
            }
        }
        return OceanCreateMethodQuickFix.isInsideStaticMethod(element);
    }

    public static String inferTargetClassName(PsiElement element) {
        return OceanCreateMethodQuickFix.inferTargetClassName(element);
    }

    private static boolean isRule(PsiElement e, int ruleIdx) {
        return e != null && e.getNode() != null && e.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIdx;
    }

    private static PsiElement findChildByRule(PsiElement parent, int ruleIdx) {
        if (parent == null) return null;
        for (PsiElement child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isRule(child, ruleIdx)) return child;
        }
        return null;
    }
}
