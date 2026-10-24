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

import java.util.*;

/**
 * Quick-fix and Intention action to generate missing methods in Ocean V3 syntax:
 *   public [static] <returnType> function <methodName>(<paramType> <paramName>, ...) {
 *       <returnStmt>
 *   }
 */
public class OceanCreateMethodQuickFix implements IntentionAction, HighPriorityAction, LocalQuickFix {
    private final String methodName;
    private final String returnType;
    private final List<String> argTypes;
    private final List<String> argNames;
    private final boolean isStatic;
    private final String targetClassName;
    private final PsiElement callElement;

    public OceanCreateMethodQuickFix() {
        this("", "void", List.of(), List.of(), false, null, null);
    }

    public OceanCreateMethodQuickFix(String methodName, List<String> argTypes) {
        this(methodName, "void", argTypes, defaultArgNames(argTypes), false, null, null);
    }

    public OceanCreateMethodQuickFix(String methodName, String returnType, List<String> argTypes, List<String> argNames, boolean isStatic) {
        this(methodName, returnType, argTypes, argNames, isStatic, null, null);
    }

    public OceanCreateMethodQuickFix(String methodName, String returnType, List<String> argTypes, List<String> argNames, boolean isStatic, String targetClassName, PsiElement callElement) {
        this.methodName = methodName != null ? methodName : "";
        this.returnType = returnType != null && !returnType.isBlank() ? returnType : "void";
        this.argTypes = argTypes != null ? argTypes : List.of();
        this.argNames = argNames != null ? argNames : defaultArgNames(this.argTypes);
        this.isStatic = isStatic;
        this.targetClassName = targetClassName;
        this.callElement = callElement;
    }

    public OceanCreateMethodQuickFix(PsiElement callElement) {
        this.callElement = callElement;
        this.methodName = callElement != null ? callElement.getText() : "";
        this.returnType = inferExpectedReturnType(callElement);
        List<ParamInfo> params = extractParams(callElement);
        this.argTypes = params.stream().map(p -> p.type).toList();
        this.argNames = params.stream().map(p -> p.name).toList();
        this.targetClassName = inferTargetClassName(callElement);
        this.isStatic = isMethodStatic(callElement, this.targetClassName);
    }

    public String getMethodName() {
        return methodName;
    }

    public String getReturnType() {
        return returnType;
    }

    public List<String> getArgTypes() {
        return argTypes;
    }

    public List<String> getArgNames() {
        return argNames;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public String getTargetClassName() {
        return targetClassName;
    }

    public PsiElement getCallElement() {
        return callElement;
    }

    private static List<String> defaultArgNames(List<String> types) {
        if (types == null || types.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            names.add("p" + i);
        }
        return names;
    }

    @NotNull
    @Override
    public String getText() {
        return "Create method '" + methodName + "()'";
    }

    @NotNull
    @Override
    public String getName() {
        return getText();
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "Create Method";
    }

    public static boolean isValidName(String name) {
        return name != null && !name.isBlank() && !OceanKeywords.isKeyword(name);
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file instanceof OceanFile && isValidName(methodName);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        if (!(file instanceof OceanFile)) return;
        PsiElement context = null;
        if (callElement != null) {
            context = callElement;
        } else if (editor != null) {
            context = file.findElementAt(editor.getCaretModel().getOffset());
        }
        applyFixInternal(project, file, context);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement el = descriptor.getPsiElement();
        if (el == null) el = callElement;
        if (el == null || el.getContainingFile() == null) return;
        applyFixInternal(project, el.getContainingFile(), el);
    }

    public String generateMethodStub() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n    public ");
        if (isStatic) {
            sb.append("static ");
        }
        sb.append(returnType).append(" function ").append(methodName).append("(");
        for (int i = 0; i < argTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            String t = OceanIntroduceVariableHandler.cleanTypeName(argTypes.get(i));
            String name = (argNames != null && i < argNames.size()) ? argNames.get(i) : ("p" + i);
            sb.append(t).append(" ").append(name);
        }
        sb.append(") {\n");
        if ("void".equalsIgnoreCase(returnType)) {
            sb.append("        // TODO: implement\n");
        } else {
            String retVal = getDefaultReturnValue(returnType);
            sb.append("        return ").append(retVal).append(";\n");
        }
        sb.append("    }\n");
        return sb.toString();
    }

    public static String getDefaultReturnValue(String type) {
        if (type == null) return "null";
        String clean = OceanIntroduceVariableHandler.cleanTypeName(type).trim();
        return switch (clean) {
            case "void" -> null;
            case "int", "long", "short", "byte", "char" -> "0";
            case "double", "float" -> "0.0";
            case "bool", "boolean" -> "false";
            case "String" -> "\"\"";
            default -> "null";
        };
    }

    private void applyFixInternal(Project project, PsiFile file, PsiElement context) {
        if (context == null && callElement != null) {
            context = callElement;
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

        String methodStub = generateMethodStub();
        OceanClassDeclaration finalTargetClass = targetClass;
        String docText = document.getText();

        WriteCommandAction.runWriteCommandAction(project, "Create Method", null, () -> {
            if (finalTargetClass != null) {
                int classEnd = finalTargetClass.getTextRange().getEndOffset();
                int insertOffset = classEnd;
                // If the class element ends with '}', insert right before it
                if (classEnd > 0 && classEnd <= docText.length() && docText.charAt(classEnd - 1) == '}') {
                    insertOffset = classEnd - 1;
                } else {
                    int p = classEnd - 1;
                    while (p >= finalTargetClass.getTextRange().getStartOffset()) {
                        if (docText.charAt(p) == '}') {
                            insertOffset = p;
                            break;
                        }
                        p--;
                    }
                }
                document.insertString(insertOffset, methodStub);
            } else {
                document.insertString(document.getTextLength(), "\n" + methodStub);
            }
            PsiDocumentManager.getInstance(project).commitDocument(document);
        });
    }

    public record ParamInfo(String type, String name) {}

    public static List<ParamInfo> extractParams(PsiElement callElement) {
        if (callElement == null) return Collections.emptyList();

        PsiElement next = PsiTreeUtil.nextVisibleLeaf(callElement);
        if (next == null || !"(".equals(next.getText())) {
            return Collections.emptyList();
        }

        // 1. Try finding RULE_argumentList via enclosing RULE_expression
        PsiElement argList = null;
        PsiElement parent = callElement.getParent();
        while (parent != null && argList == null) {
            if (isRule(parent, OceanParser.RULE_expression)) {
                argList = findChildByRule(parent, OceanParser.RULE_argumentList);
            }
            if (parent instanceof PsiFile || isRule(parent, OceanParser.RULE_statement)) break;
            parent = parent.getParent();
        }

        if (argList != null) {
            List<ParamInfo> params = new ArrayList<>();
            int index = 0;
            Set<String> usedNames = new HashSet<>();
            for (PsiElement child = argList.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (isRule(child, OceanParser.RULE_argument)) {
                    PsiElement argExpr = findChildByRule(child, OceanParser.RULE_expression);
                    if (argExpr == null) {
                        for (PsiElement ac = child.getLastChild(); ac != null; ac = ac.getPrevSibling()) {
                            String act = ac.getText().trim();
                            if (!act.isEmpty() && !"=".equals(act) && !isRule(ac, OceanParser.RULE_anyId)) {
                                argExpr = ac;
                                break;
                            }
                        }
                    }
                    if (argExpr == null) argExpr = child;

                    String rawType = OceanOverloadResolver.guessArgumentType(argExpr);
                    String cleanType = OceanIntroduceVariableHandler.cleanTypeName(rawType);
                    String paramName = suggestParamName(argExpr, cleanType, index, usedNames);
                    usedNames.add(paramName);
                    params.add(new ParamInfo(cleanType, paramName));
                    index++;
                }
            }
            return params;
        }

        // 2. Fallback: scan between '(' and matching ')'
        List<String> rawArgs = new ArrayList<>();
        List<PsiElement> argNodes = new ArrayList<>();
        PsiElement cur = next.getNextSibling();
        StringBuilder currentArg = new StringBuilder();
        PsiElement firstNode = null;
        int depth = 0;
        while (cur != null) {
            String txt = cur.getText();
            if ("(".equals(txt)) {
                depth++;
            } else if (")".equals(txt)) {
                if (depth == 0) {
                    String a = currentArg.toString().trim();
                    if (!a.isEmpty()) {
                        rawArgs.add(a);
                        argNodes.add(firstNode != null ? firstNode : cur);
                    }
                    break;
                }
                depth--;
            } else if (",".equals(txt) && depth == 0) {
                String a = currentArg.toString().trim();
                if (!a.isEmpty()) {
                    rawArgs.add(a);
                    argNodes.add(firstNode != null ? firstNode : cur);
                }
                currentArg.setLength(0);
                firstNode = null;
                cur = cur.getNextSibling();
                continue;
            }
            if (firstNode == null && !txt.trim().isEmpty()) {
                firstNode = cur;
            }
            currentArg.append(txt);
            cur = cur.getNextSibling();
        }

        List<ParamInfo> params = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < rawArgs.size(); i++) {
            String raw = rawArgs.get(i);
            PsiElement node = argNodes.get(i);
            String type = OceanIntroduceVariableHandler.cleanTypeName(OceanIntroduceVariableHandler.inferType(raw, node));
            String name = suggestParamName(node, type, i, usedNames);
            usedNames.add(name);
            params.add(new ParamInfo(type, name));
        }
        return params;
    }

    public static List<String> extractArgTypes(PsiElement callElement) {
        return extractParams(callElement).stream().map(ParamInfo::type).toList();
    }

    private static String suggestParamName(PsiElement argExpr, String cleanType, int index, Set<String> usedNames) {
        String nameCandidate = null;
        if (argExpr != null) {
            String text = argExpr.getText().trim();
            if (text.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")
                    && !OceanKeywords.isKeyword(text)
                    && !"true".equals(text) && !"false".equals(text) && !"null".equals(text)) {
                nameCandidate = text;
            }
        }
        if (nameCandidate == null) {
            nameCandidate = "p" + index;
        }
        String unique = nameCandidate;
        int counter = 1;
        while (usedNames.contains(unique) || OceanKeywords.isKeyword(unique)) {
            unique = nameCandidate + counter++;
        }
        return unique;
    }

    public static String inferExpectedReturnType(PsiElement element) {
        if (element == null) return "void";
        PsiElement cur = element;
        while (cur != null && !(cur instanceof PsiFile)) {
            // 1. Variable or Field declaration: int x = foo();
            if (isRule(cur, OceanParser.RULE_variableDeclaration) || isRule(cur, OceanParser.RULE_fieldDeclaration)) {
                PsiElement typeNode = findChildByRule(cur, OceanParser.RULE_type);
                if (typeNode != null) {
                    return OceanIntroduceVariableHandler.cleanTypeName(typeNode.getText().trim());
                }
                for (PsiElement c : cur.getChildren()) {
                    String txt = c.getText().trim();
                    if ("variable".equals(txt) || "value".equals(txt)) {
                        return "Object";
                    }
                }
                return "void";
            }

            // 2. Assignment: x = foo();
            if (isRule(cur, OceanParser.RULE_assignment)) {
                PsiElement eq = null;
                PsiElement lhs = null;
                for (PsiElement c = cur.getFirstChild(); c != null; c = c.getNextSibling()) {
                    String txt = c.getText().trim();
                    if ("=".equals(txt) || "+=".equals(txt) || "-=".equals(txt) || "*=".equals(txt) || "/=".equals(txt)) {
                        eq = c;
                        break;
                    }
                    if (lhs == null && !txt.isEmpty()) {
                        lhs = c;
                    }
                }
                if (eq != null && element.getTextOffset() > eq.getTextOffset() && lhs != null) {
                    String lhsType = OceanTypeHelper.guessTypeFromTextOrElement(lhs.getText(), lhs);
                    if (lhsType != null && !lhsType.equals("java.lang.Object")) {
                        return OceanIntroduceVariableHandler.cleanTypeName(lhsType);
                    }
                }
            }

            // 3. Return statement: return foo();
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
                    for (PsiElement c = method.getFirstChild(); c != null; c = c.getNextSibling()) {
                        if ("void".equals(c.getText().trim())) {
                            return "void";
                        }
                    }
                }
                return "void";
            }

            // 4. Condition in if / while / do-while
            if (isRule(cur, OceanParser.RULE_ifStatement) || isRule(cur, OceanParser.RULE_whileStatement) || isRule(cur, OceanParser.RULE_doWhileStatement)) {
                PsiElement block = findChildByRule(cur, OceanParser.RULE_block);
                if (block == null || element.getTextOffset() < block.getTextOffset()) {
                    return "bool";
                }
            }

            // 5. Logical operators in enclosing expression: !foo(), foo() && bar
            if (isRule(cur, OceanParser.RULE_expression)) {
                String exprText = cur.getText();
                if (exprText.startsWith("!") || exprText.contains("&&") || exprText.contains("||") ||
                        exprText.contains("==") || exprText.contains("!=") || exprText.contains("<=") ||
                        exprText.contains(">=") || (exprText.contains("<") && !exprText.contains("->")) ||
                        (exprText.contains(">") && !exprText.contains("->"))) {
                    return "bool";
                }
            }

            // 6. Standalone expression statement: foo();
            if (isRule(cur, OceanParser.RULE_expressionStatement)) {
                return "void";
            }

            cur = cur.getParent();
        }
        return "void";
    }

    public static String inferTargetClassName(PsiElement callElement) {
        if (callElement == null) return null;
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(callElement);
        if (prev != null && (".".equals(prev.getText()) || "?.".equals(prev.getText()) || "::".equals(prev.getText()))) {
            PsiElement recLeaf = PsiTreeUtil.prevVisibleLeaf(prev);
            if (recLeaf != null) {
                String recText = recLeaf.getText().trim();
                if (isKnownClassName(recText, callElement)) {
                    return recText;
                }
                String recType = OceanTypeHelper.guessTypeFromTextOrElement(recText, recLeaf);
                if (recType != null && !recType.equals("java.lang.Object")) {
                    return OceanIntroduceVariableHandler.cleanTypeName(recType);
                }
            }
        }
        return null;
    }

    public static boolean isMethodStatic(PsiElement callElement, String targetClassName) {
        if (callElement == null) return false;
        if (targetClassName != null) {
            PsiElement prev = PsiTreeUtil.prevVisibleLeaf(callElement);
            if (prev != null && (".".equals(prev.getText()) || "?.".equals(prev.getText()) || "::".equals(prev.getText()))) {
                PsiElement recLeaf = PsiTreeUtil.prevVisibleLeaf(prev);
                return recLeaf != null && isKnownClassName(recLeaf.getText().trim(), callElement);
            }
            return false;
        }
        return isInsideStaticMethod(callElement);
    }

    public static boolean isKnownClassName(String name, PsiElement context) {
        if (name == null || name.isBlank() || OceanKeywords.isKeyword(name)) return false;
        if (context != null) {
            PsiFile file = context.getContainingFile();
            if (file != null) {
                for (OceanClassDeclaration cd : PsiTreeUtil.findChildrenOfType(file, OceanClassDeclaration.class)) {
                    if (name.equals(cd.getName())) return true;
                }
            }
            Project project = context.getProject();
            if (OceanScopeDiscovery.getProjectOceanClasses(project).containsKey(name)) return true;
            if (OceanScopeDiscovery.resolveJavaClass(project, name, context) != null) return true;
        }
        return Character.isUpperCase(name.charAt(0));
    }

    public static boolean isInsideStaticMethod(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || cur instanceof OceanMethodDeclaration) {
                for (PsiElement child = cur.getFirstChild(); child != null; child = child.getNextSibling()) {
                    String txt = child.getText().trim();
                    if ("static".equals(txt)) {
                        return true;
                    }
                    if ("function".equals(txt)) {
                        break;
                    }
                }
                return false;
            }
            if (cur instanceof OceanClassDeclaration || isRule(cur, OceanParser.RULE_classDeclaration)) {
                break;
            }
            cur = cur.getParent();
        }
        return false;
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
