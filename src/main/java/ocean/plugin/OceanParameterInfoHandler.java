package ocean.plugin;

import com.intellij.lang.parameterInfo.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class OceanParameterInfoHandler implements ParameterInfoHandler<PsiElement, Object> {

    @Override
    public @Nullable PsiElement findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        PsiElement offsetElement = context.getFile().findElementAt(context.getOffset());
        if (offsetElement == null) return null;

        // Find the enclosing method call expression
        PsiElement parent = offsetElement.getParent();
        while (parent != null) {
            if (isMethodCall(parent)) {
                // If we are inside the parentheses
                int lparen = parent.getText().indexOf('(');
                if (lparen != -1 && context.getOffset() > parent.getTextOffset() + lparen) {
                    // Collect candidates
                    Object[] candidates = collectCandidates(parent);
                    if (candidates != null && candidates.length > 0) {
                        context.setItemsToShow(candidates);
                        return parent;
                    }
                }
            }
            parent = parent.getParent();
        }
        return null;
    }

    @Override
    public void showParameterInfo(@NotNull PsiElement element, @NotNull CreateParameterInfoContext context) {
        context.showHint(element, element.getTextOffset(), this);
    }

    @Override
    public @Nullable PsiElement findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        PsiElement offsetElement = context.getFile().findElementAt(context.getOffset());
        if (offsetElement == null) return null;
        PsiElement parent = offsetElement.getParent();
        while (parent != null) {
            if (isMethodCall(parent)) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    @Override
    public void updateParameterInfo(@NotNull PsiElement element, @NotNull UpdateParameterInfoContext context) {
        int offset = context.getOffset();
        int elementStart = element.getTextOffset();
        String text = element.getText();
        int lparen = text.indexOf('(');
        if (lparen == -1) {
            context.setCurrentParameter(0);
            return;
        }

        int relOffset = offset - elementStart;
        if (relOffset <= lparen) {
            context.setCurrentParameter(0);
            return;
        }

        int paramIndex = 0;
        int depth = 0;
        int maxLen = Math.min(relOffset, text.length());
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = lparen + 1; i < maxLen; i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"' && !inChar) {
                inString = !inString;
                continue;
            }
            if (c == '\'' && !inString) {
                inChar = !inChar;
                continue;
            }
            if (inString || inChar) {
                continue;
            }

            if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                paramIndex++;
            }
        }
        context.setCurrentParameter(paramIndex);
    }

    @Override
    public void updateUI(Object p, @NotNull ParameterInfoUIContext context) {
        String signature;
        if (p instanceof PsiMethod method) {
            signature = getJavaMethodSignature(method);
        } else if (p instanceof String s) {
            signature = s;
        } else {
            return;
        }

        int currentParam = context.getCurrentParameterIndex();
        int lparen = signature.indexOf('(');
        int rparen = signature.lastIndexOf(')');
        if (lparen == -1 || rparen <= lparen) {
            context.setupUIComponentPresentation(signature, 0, 0, false, false, false, context.getDefaultParameterColor());
            return;
        }

        String paramsPart = signature.substring(lparen + 1, rparen);
        if (paramsPart.isBlank()) {
            context.setupUIComponentPresentation(signature, 0, 0, false, false, false, context.getDefaultParameterColor());
            return;
        }

        List<int[]> ranges = new ArrayList<>();
        int pStart = 0;
        int depth = 0;
        for (int i = 0; i < paramsPart.length(); i++) {
            char c = paramsPart.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                ranges.add(new int[]{lparen + 1 + pStart, lparen + 1 + i});
                pStart = i + 1;
                while (pStart < paramsPart.length() && Character.isWhitespace(paramsPart.charAt(pStart))) {
                    pStart++;
                }
            }
        }
        ranges.add(new int[]{lparen + 1 + pStart, lparen + 1 + paramsPart.length()});

        int hlStart = 0;
        int hlEnd = 0;
        if (currentParam >= 0 && currentParam < ranges.size()) {
            hlStart = ranges.get(currentParam)[0];
            hlEnd = ranges.get(currentParam)[1];
        }

        context.setupUIComponentPresentation(signature, hlStart, hlEnd, false, false, false, context.getDefaultParameterColor());
    }

    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "while", "for", "switch", "catch", "sync", "lock", "when", "trying", "verify"
    );

    private boolean isMethodCall(PsiElement e) {
        if (!(e instanceof OceanPsiNode)) return false;
        String text = e.getText();
        if (!text.contains("(")) return false;
        String name = extractMethodName(e);
        return name != null && !name.isEmpty() && !CONTROL_KEYWORDS.contains(name);
    }

    private Object[] collectCandidates(PsiElement call) {
        String methodName = extractMethodName(call);
        if (methodName == null) return null;

        List<Object> candidates = new ArrayList<>();
        
        // Special constructor calls: this(...) and super(...)
        if ("this".equals(methodName) || "super".equals(methodName)) {
            PsiElement enclosingClass = findEnclosingClass(call);
            String targetClassName = null;
            if ("this".equals(methodName)) {
                if (enclosingClass != null) targetClassName = findNameForElement(enclosingClass);
            } else {
                if (enclosingClass != null) {
                    targetClassName = OceanScopeDiscovery.extractExtendsName(enclosingClass);
                }
            }

            if (targetClassName != null) {
                // Ocean class constructors
                PsiElement targetCls = OceanScopeDiscovery.findOceanClass(call.getProject(), targetClassName, call.getContainingFile());
                if (targetCls != null) {
                    PsiElement[] ctors = PsiTreeUtil.collectElements(targetCls, el ->
                            el.getNode() != null && el.getNode().getElementType() instanceof RuleIElementType ri &&
                                    ri.getRuleIndex() == OceanParser.RULE_constructorDeclaration);
                    for (PsiElement ctor : ctors) {
                        candidates.add(buildOceanMethodSignature(ctor));
                    }
                }
                // Java class constructors
                PsiClass jc = OceanScopeDiscovery.resolveJavaClass(call.getProject(), targetClassName, call);
                if (jc != null) {
                    candidates.addAll(Arrays.asList(jc.getConstructors()));
                }
            }
            if (candidates.isEmpty()) {
                candidates.add(methodName + "(...)");
            }
            return candidates.toArray();
        }

        // 1. Resolve receiver type
        PsiElement dot = findChildText(call, ".");
        if (dot == null) dot = findChildText(call, "?.");
        
        String receiverType = null;
        if (dot != null) {
            PsiElement receiver = dot.getPrevSibling();
            while (receiver instanceof PsiWhiteSpace || receiver instanceof PsiComment) {
                receiver = receiver.getPrevSibling();
            }
            if (receiver == null) receiver = PsiTreeUtil.prevVisibleLeaf(dot);
            if (receiver != null) receiverType = OceanTypeHelper.guessType(receiver);
        } else {
            // Local method?
            PsiElement cls = findEnclosingClass(call);
            if (cls != null) receiverType = findNameForElement(cls);
        }

        if (receiverType != null) {
            // 1a. Check Java / Stdlib class
            PsiClass jc = OceanScopeDiscovery.resolveJavaClass(call.getProject(), receiverType, call);
            if (jc != null) {
                candidates.addAll(Arrays.asList(jc.findMethodsByName(methodName, true)));
            }

            // 1b. Project-wide Ocean class / interface / enum / extension methods
            List<PsiElement> oceanMembers = new ArrayList<>();
            OceanScopeDiscovery.resolveMemberChain(receiverType, methodName, oceanMembers, call.getProject(), new HashSet<>(), call);
            for (PsiElement m : oceanMembers) {
                if (m instanceof OceanMethodDeclaration || (m.getNode() != null && m.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration)) {
                    candidates.add(buildOceanMethodSignature(m));
                } else if (m instanceof PsiMethod pm) {
                    candidates.add(pm);
                }
            }
        }
        
        // 2. Local file search fallback
        if (candidates.isEmpty()) {
            PsiElement[] methods = PsiTreeUtil.collectElements(call.getContainingFile(),
                    el -> el.getNode() != null && el.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration);
            for (PsiElement method : methods) {
                String name = findNameForElement(method);
                if (methodName.equals(name)) {
                    candidates.add(buildOceanMethodSignature(method));
                }
            }
        }

        // Add "Unknown method" if empty to at least show something
        if (candidates.isEmpty()) {
            candidates.add(methodName + "(...)");
        }

        return candidates.toArray();
    }

    private String buildOceanMethodSignature(PsiElement method) {
        String name = findNameForElement(method);
        StringBuilder sb = new StringBuilder(name != null ? name : "").append("(");

        PsiElement paramList = null;
        for (PsiElement child : method.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                    ri.getRuleIndex() == OceanParser.RULE_parameterList) {
                paramList = child;
                break;
            }
        }

        if (paramList != null) {
            List<String> params = new ArrayList<>();
            for (PsiElement child : paramList.getChildren()) {
                if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                        ri.getRuleIndex() == OceanParser.RULE_parameter) {
                    String pType = "Object";
                    String pName = "";
                    String defaultVal = null;
                    boolean afterAssign = false;
                    for (PsiElement pSub : child.getChildren()) {
                        if (pSub.getNode() != null && pSub.getNode().getElementType() instanceof RuleIElementType pri) {
                            if (pri.getRuleIndex() == OceanParser.RULE_type) {
                                pType = pSub.getText().trim();
                            } else if (pri.getRuleIndex() == OceanParser.RULE_anyId) {
                                pName = pSub.getText().trim();
                            } else if (afterAssign && pri.getRuleIndex() == OceanParser.RULE_expression) {
                                defaultVal = pSub.getText().trim();
                            }
                        } else if ("=".equals(pSub.getText())) {
                            afterAssign = true;
                        }
                    }
                    String pStr = pType + " " + pName;
                    if (defaultVal != null) {
                        pStr += " = " + defaultVal;
                    }
                    params.add(pStr);
                }
            }
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(params.get(i));
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private String extractMethodName(PsiElement call) {
        String text = call.getText();
        if (text == null || !text.contains("(")) return null;

        int firstParen = text.indexOf('(');
        if (firstParen <= 0) return null;
        int lparen = firstParen;
        // In chained calls like a(1).b(2), if there is a dot after firstParen, take the last '('
        if (text.indexOf('.', firstParen) != -1) {
            int lastParen = text.lastIndexOf('(');
            if (lastParen > firstParen) lparen = lastParen;
        }

        String before = text.substring(0, lparen).trim();
        int lastDot = Math.max(before.lastIndexOf('.'), before.lastIndexOf("?."));
        return (lastDot != -1) ? before.substring(lastDot + 1).trim() : before.trim();
    }

    private String getJavaMethodSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getPresentableText()).append(" ").append(params[i].getName());
        }
        sb.append(")");
        return sb.toString();
    }

    private PsiElement findChildText(PsiElement e, String text) {
        for (PsiElement child : e.getChildren()) if (child.getText().equals(text)) return child;
        return null;
    }

    private PsiElement findEnclosingClass(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == OceanParser.RULE_classDeclaration) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private String findNameForElement(PsiElement element) {
        for (PsiElement child : element.getChildren()) {
            if (child.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == OceanParser.RULE_anyId) return child.getText().trim();
        }
        return null;
    }
}
