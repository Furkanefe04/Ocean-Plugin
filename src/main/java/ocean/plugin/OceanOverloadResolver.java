package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import ocean.plugin.imports.OceanImportOptimizer;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;

import java.util.*;

public class OceanOverloadResolver {

    public static class CallInfo {
        public final boolean isCall;
        public final List<String> argumentTypes;

        private CallInfo(boolean isCall, List<String> argumentTypes) {
            this.isCall = isCall;
            this.argumentTypes = argumentTypes != null ? argumentTypes : Collections.emptyList();
        }

        public static CallInfo notACall() {
            return new CallInfo(false, null);
        }

        public static CallInfo call(List<String> argumentTypes) {
            return new CallInfo(true, argumentTypes);
        }
    }

    public record MethodCandidate(PsiElement element, List<String> paramTypes, boolean isVarArgs) {
    }

    /**
     * Determines whether the reference element is part of a call expression,
     * and extracts the guessed types of all passed arguments.
     */
    public static CallInfo findCallInfo(PsiElement refElement) {
        if (refElement == null) return CallInfo.notACall();

        PsiElement parent = refElement.getParent();
        while (parent != null) {
            if (isExpressionRule(parent)) {
                // Case 1: Member call (receiver.member(...) or receiver?.member(...))
                PsiElement dot = null;
                for (PsiElement child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                    String ct = child.getText();
                    if (".".equals(ct) || "?.".equals(ct) || "::".equals(ct)) {
                        dot = child;
                        break;
                    }
                }
                if (dot != null) {
                    PsiElement member = nextVisibleSibling(dot);
                    if (member == refElement || PsiTreeUtil.isAncestor(member, refElement, false)) {
                        PsiElement lParen = findNextLParen(member);
                        if (lParen != null) {
                            return CallInfo.call(extractArgumentTypes(parent, lParen));
                        } else {
                            // Member access without parenthesis is a property / field access
                            return CallInfo.notACall();
                        }
                    }
                }

                // Case 2: Direct function call foo(...)
                PsiElement first = getFirstVisibleChild(parent);
                if (first != null && (first == refElement || PsiTreeUtil.isAncestor(first, refElement, false))) {
                    PsiElement lParen = findNextLParen(first);
                    if (lParen != null) {
                        return CallInfo.call(extractArgumentTypes(parent, lParen));
                    }
                }

                // Case 3: Constructor call new Foo(...)
                PsiElement firstChild = getFirstVisibleChild(parent);
                if (firstChild != null && "new".equals(firstChild.getText().trim())) {
                    PsiElement lParen = findNextLParen(refElement);
                    if (lParen != null) {
                        return CallInfo.call(extractArgumentTypes(parent, lParen));
                    }
                }
            }
            parent = parent.getParent();
        }

        return CallInfo.notACall();
    }

    private static PsiElement findNextLParen(PsiElement element) {
        if (element == null) return null;
        for (PsiElement sib = element.getNextSibling(); sib != null; sib = sib.getNextSibling()) {
            String text = sib.getText().trim();
            if (text.isEmpty()) continue;
            if ("(".equals(text)) return sib;
            if (isRule(sib, OceanParser.RULE_typeArguments)) continue;
            break;
        }
        return null;
    }

    private static List<String> extractArgumentTypes(PsiElement callExpr, PsiElement lParen) {
        List<String> types = new ArrayList<>();
        PsiElement argList = findChildByRule(callExpr, OceanParser.RULE_argumentList);
        if (argList == null && lParen != null) {
            for (PsiElement child = lParen.getNextSibling(); child != null; child = child.getNextSibling()) {
                if (isRule(child, OceanParser.RULE_argumentList)) {
                    argList = child;
                    break;
                }
                if (")".equals(child.getText().trim())) {
                    break;
                }
            }
        }
        if (argList == null) {
            return types; // 0 arguments
        }

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
                types.add(guessArgumentType(argExpr));
            }
        }
        return types;
    }

    public static String guessArgumentType(PsiElement argExpr) {
        if (argExpr == null) return "java.lang.Object";
        String text = argExpr.getText().trim();
        if (text.isEmpty()) return "java.lang.Object";

        // 0. Unwrap outer parentheses: (expr)
        while (text.startsWith("(") && text.endsWith(")") && isMatchedParentheses(text)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty()) return "java.lang.Object";

        // 1. String and Interpolated String checks
        if ((text.startsWith("\"") && text.endsWith("\"")) ||
            text.startsWith("\"\"\"") ||
            text.startsWith("$\"") ||
            text.startsWith("$\"\"\"")) {
            return "java.lang.String";
        }

        // 2. Boolean Literals & Logical / Comparison Operations
        if ("true".equals(text) || "false".equals(text)) {
            return "boolean";
        }
        if (text.startsWith("!") && text.length() > 1) {
            return "boolean";
        }
        if (text.contains("==") || text.contains("!=") || text.contains("&&") || text.contains("||") ||
            text.contains("<=") || text.contains(">=") || text.contains(" instanceof ") ||
            isComparisonExpression(text)) {
            return "boolean";
        }

        // 3. Null Literal
        if ("null".equals(text)) {
            return "null";
        }

        // 4. Character Literals (e.g. 'a', '\n', '\t', '\'', '\\')
        if (text.startsWith("'") && text.endsWith("'") && text.length() >= 3) {
            return "char";
        }

        // 5. String Concatenation check: e.g. "foo" + bar or $"..." + num
        if (text.contains("+")) {
            int plusIdx = text.indexOf('+');
            String leftPart = text.substring(0, plusIdx).trim();
            String rightPart = text.substring(plusIdx + 1).trim();
            if (isStringLike(leftPart) || isStringLike(rightPart)) {
                return "java.lang.String";
            }
        }

        // 6. Numeric Literals (clean underscores: 1_000_000 -> 1000000)
        String cleanNum = text.replace("_", "");
        // Hexadecimal: 0x... and Binary: 0b...
        if (cleanNum.matches("^-?0[xX][0-9a-fA-F]+[lL]?$") || cleanNum.matches("^-?0[bB][01]+[lL]?$")) {
            return cleanNum.endsWith("L") || cleanNum.endsWith("l") ? "long" : "int";
        }

        // Long suffix
        if (cleanNum.matches("^-?\\d+[lL]$")) {
            return "long";
        }
        // Float suffix
        if (cleanNum.matches("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fF]$")) {
            return "float";
        }
        // Double (suffix d/D, or dot, or scientific notation)
        if (cleanNum.matches("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?[dD]$") ||
            cleanNum.matches("^-?\\d+\\.\\d+([eE][+-]?\\d+)?$") ||
            cleanNum.matches("^-?\\d+[eE][+-]?\\d+$")) {
            return "double";
        }
        // Decimal Integer
        if (cleanNum.matches("^-?\\d+$")) {
            return "int";
        }

        // 7. Collection Literals
        if (text.startsWith("[") && text.endsWith("]")) {
            return "ocean.stdlib.OceanList";
        }
        if (text.startsWith("#{") && text.endsWith("}")) {
            return "ocean.stdlib.OceanSet";
        }
        if (text.startsWith("{") && text.endsWith("}") && text.contains(":")) {
            return "ocean.stdlib.OceanMap";
        }

        // 8. OceanTypeHelper resolution
        try {
            if (argExpr.isValid()) {
                String type = OceanTypeHelper.guessTypeFromTextOrElement(text, argExpr);
                if (type == null) {
                    type = OceanTypeHelper.guessType(argExpr);
                }
                if (type != null) {
                    return type;
                }
            }
        } catch (Exception ignored) {}

        return "java.lang.Object";
    }

    private static boolean isMatchedParentheses(String s) {
        if (!s.startsWith("(") || !s.endsWith(")")) return false;
        int depth = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0) return false;
        }
        return depth == 1;
    }

    private static boolean isStringLike(String s) {
        return (s.startsWith("\"") && s.endsWith("\"")) ||
               s.startsWith("\"\"\"") ||
               s.startsWith("$\"") ||
               s.startsWith("$\"\"\"");
    }

    private static boolean isComparisonExpression(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains("->")) return false; // Lambda arrow
        if (text.startsWith("new ")) return false; // Instantiation with generic
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '>') {
                // Ignore if it's inside quotes or preceded/followed by '=' (handled separately)
                if (i + 1 < len && text.charAt(i + 1) == '=') continue;
                if (i > 0 && text.charAt(i - 1) == '-') continue; // part of ->
                if (i + 1 < len && text.charAt(i + 1) == '>') continue; // >>
                if (i > 0 && text.charAt(i - 1) == '<') continue; // <<
                // Check if it's generic type argument like List<String> or Map<K, V>
                int close = text.indexOf('>', i);
                if (c == '<' && close > i) {
                    String inside = text.substring(i + 1, close);
                    if (inside.matches("^[a-zA-Z0-9_.,?\\s\\[\\]]+$") && !inside.contains("+") && !inside.contains("-") && !inside.contains("*") && !inside.contains("/")) {
                        continue; // likely generic type argument
                    }
                }
                return true;
            }
        }
        return false;
    }

    public static MethodCandidate extractCandidate(PsiElement element, Project project) {
        if (element instanceof PsiMethod psiMethod) {
            List<String> paramTypes = new ArrayList<>();
            for (PsiParameter p : psiMethod.getParameterList().getParameters()) {
                paramTypes.add(p.getType().getCanonicalText());
            }
            return new MethodCandidate(element, paramTypes, psiMethod.isVarArgs());
        }

        PsiElement methodDecl = element;
        if (!isRule(methodDecl, OceanParser.RULE_methodDeclaration) && !isRule(methodDecl, OceanParser.RULE_constructorDeclaration)) {
            methodDecl = PsiTreeUtil.getParentOfType(element, OceanMethodDeclaration.class);
        }
        if (methodDecl == null && (isRule(element, OceanParser.RULE_anyId) || element instanceof PsiNamedElement)) {
            PsiElement p = element.getParent();
            while (p != null) {
                if (isRule(p, OceanParser.RULE_methodDeclaration) || isRule(p, OceanParser.RULE_constructorDeclaration)) {
                    methodDecl = p;
                    break;
                }
                p = p.getParent();
            }
        }

        if (methodDecl != null) {
            PsiElement paramList = findChildByRule(methodDecl, OceanParser.RULE_parameterList);
            List<String> paramTypes = new ArrayList<>();
            boolean isVarArgs = false;
            if (paramList != null) {
                for (PsiElement child = paramList.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (isRule(child, OceanParser.RULE_parameter)) {
                        PsiElement typeNode = findChildByRule(child, OceanParser.RULE_type);
                        String tStr = "java.lang.Object";
                        if (typeNode != null) {
                            String resolved = OceanTypeHelper.resolveDeclaredType(typeNode.getText().trim(), project, child);
                            tStr = resolved != null ? resolved : typeNode.getText().trim();
                        }
                        paramTypes.add(tStr);
                        if (child.getText().contains("...")) {
                            isVarArgs = true;
                        }
                    }
                }
            }
            return new MethodCandidate(element, paramTypes, isVarArgs);
        }

        return null;
    }

    public static int scoreCandidate(MethodCandidate candidate, List<String> argTypes, Project project) {
        if (candidate == null) return -1;
        int argCount = argTypes.size();
        int paramCount = candidate.paramTypes.size();

        if (!candidate.isVarArgs) {
            if (argCount != paramCount) return -1;
        } else {
            if (argCount < paramCount - 1) return -1;
        }

        int totalScore = candidate.isVarArgs ? 50 : 100;

        for (int i = 0; i < argCount; i++) {
            String actual = argTypes.get(i);
            String expected;
            if (i < paramCount - 1) {
                expected = candidate.paramTypes.get(i);
            } else if (i == paramCount - 1 && !candidate.isVarArgs) {
                expected = candidate.paramTypes.get(i);
            } else {
                String rawLastParam = candidate.paramTypes.get(paramCount - 1);
                // Case: caller passes an array directly to varargs (e.g. String[] passed to String... or String[])
                if (argCount == paramCount && candidate.isVarArgs && actual != null && actual.endsWith("[]")) {
                    String arrayParam = rawLastParam;
                    if (arrayParam.endsWith("...")) {
                        arrayParam = arrayParam.substring(0, arrayParam.length() - 3).trim() + "[]";
                    }
                    int arrayScore = scoreTypeMatch(actual, arrayParam, project);
                    if (arrayScore >= 0) {
                        totalScore += arrayScore;
                        continue;
                    }
                }

                expected = rawLastParam;
                if (expected.endsWith("...")) {
                    expected = expected.substring(0, expected.length() - 3).trim();
                } else if (expected.endsWith("[]")) {
                    expected = expected.substring(0, expected.length() - 2).trim();
                }
            }

            int pScore = scoreTypeMatch(actual, expected, project);
            if (pScore < 0) return -1;
            totalScore += pScore;
        }

        return totalScore;
    }

    public static int scoreTypeMatch(String actual, String expected, Project project) {
        if (actual == null) actual = "java.lang.Object";
        if (expected == null) expected = "java.lang.Object";

        actual = normalizeType(actual);
        expected = normalizeType(expected);

        // 1. Literal null
        if ("null".equals(actual)) {
            if (isPrimitive(expected)) return -1;
            if ("java.lang.Object".equals(expected)) return 50;
            return 100;
        }

        // 2. Exact match
        if (actual.equals(expected)) return 1000;
        if (getSimpleTypeName(actual).equals(getSimpleTypeName(expected))) return 950;

        // 3. Primitive widening (beats autoboxing per JLS §15.12.2 and Ocean compiler)
        if (isPrimitiveWidening(actual, expected)) return 850;

        // 4. Boxed / Unboxed equivalence
        if (isBoxedEquivalent(actual, expected)) return 750;

        // 4.1 Generic type parameter match (e.g. E, T, K, V, ?)
        if (looksLikeTypeParam(expected)) {
            return isPrimitive(actual) ? 680 : 700;
        }
        if (expected.endsWith("[]") && looksLikeTypeParam(expected.substring(0, expected.length() - 2).trim())) {
            if (actual.endsWith("[]")) return 750;
        }

        // 5. Object fallback
        if ("java.lang.Object".equals(expected) || "Object".equals(expected)) {
            if (isPrimitive(actual)) return 70;
            return 100;
        }

        // 6. Inheritance / Subtyping (Class hierarchy: Ocean and Java)
        if (project != null && !isPrimitive(actual) && !isPrimitive(expected)) {
            if (OceanScopeDiscovery.isSubclassOf(project, actual, expected)) {
                return 500;
            }
        }

        if ("java.lang.Object".equals(actual)) {
            if (isPrimitive(expected)) return -1;
            return 20;
        }

        return -1;
    }

    public static boolean looksLikeTypeParam(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("?")) return true;
        if (name.length() == 1 && Character.isUpperCase(name.charAt(0))) return true;
        if (name.length() <= 4 && name.chars().allMatch(Character::isUpperCase)) {
            // Exclude well-known uppercase class/interface abbreviations
            return switch (name) {
                case "URI", "URL", "UUID", "DTO", "XML", "JSON", "HTTP", "SQL", "HTML", "REST" -> false;
                default -> true;
            };
        }
        return switch (name) {
            case "In", "Out", "Item", "Key", "Value", "Req", "Resp", "Source", "Target", "Element" -> true;
            default -> false;
        };
    }

    public static boolean isPrivateMember(PsiElement element) {
        if (element == null) return false;
        if (element instanceof PsiModifierListOwner mlo) {
            return mlo.hasModifierProperty(PsiModifier.PRIVATE);
        }
        PsiElement p = element;
        while (p != null && !(p instanceof PsiFile)) {
            if (isRule(p, OceanParser.RULE_fieldDeclaration) ||
                isRule(p, OceanParser.RULE_methodDeclaration) ||
                isRule(p, OceanParser.RULE_constructorDeclaration) ||
                isRule(p, OceanParser.RULE_classDeclaration)) {
                for (PsiElement child : p.getChildren()) {
                    if (isRule(child, OceanParser.RULE_modifier)) {
                        if ("private".equals(child.getText().trim())) {
                            return true;
                        }
                        ASTNode node = child.getNode();
                        if (node != null) {
                            for (ASTNode sub = node.getFirstChildNode(); sub != null; sub = sub.getTreeNext()) {
                                if (sub.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.PRIVATE) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                break;
            }
            p = p.getParent();
        }
        return false;
    }

    public static List<PsiElement> filterAndRankCandidates(List<PsiElement> candidates, List<String> argTypes, Project project) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        if (candidates.size() == 1) return candidates;

        class Scored {
            final PsiElement element;
            final int score;
            Scored(PsiElement element, int score) {
                this.element = element;
                this.score = score;
            }
        }

        List<Scored> scoredList = new ArrayList<>();
        boolean hasMethodCandidates = false;
        for (PsiElement cand : candidates) {
            MethodCandidate mc = extractCandidate(cand, project);
            if (mc != null) {
                hasMethodCandidates = true;
                int score = scoreCandidate(mc, argTypes, project);
                if (score >= 0) {
                    scoredList.add(new Scored(cand, score));
                }
            } else {
                scoredList.add(new Scored(cand, -100));
            }
        }

        if (scoredList.isEmpty()) {
            List<PsiElement> copy = new ArrayList<>();
            for (PsiElement c : candidates) {
                if (extractCandidate(c, project) != null) copy.add(c);
            }
            if (copy.isEmpty()) copy.addAll(candidates);
            copy.sort((a, b) -> Boolean.compare(isPrivateMember(a), isPrivateMember(b)));
            return copy;
        }

        scoredList.sort((a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            return Boolean.compare(isPrivateMember(a.element), isPrivateMember(b.element));
        });

        int topScore = scoredList.get(0).score;
        if (topScore <= 0) {
            List<PsiElement> copy = new ArrayList<>(candidates);
            copy.sort((a, b) -> Boolean.compare(isPrivateMember(a), isPrivateMember(b)));
            return copy;
        }

        List<PsiElement> best = new ArrayList<>();
        for (Scored s : scoredList) {
            if (s.score == topScore) {
                best.add(s.element);
            }
        }
        return best;
    }

    public static String normalizeType(String type) {
        if (type == null) return "java.lang.Object";
        type = type.trim();
        int angle = type.indexOf('<');
        if (angle > 0) type = type.substring(0, angle).trim();

        return switch (type) {
            case "bool", "boolean" -> "boolean";
            case "String", "java.lang.String" -> "java.lang.String";
            case "int" -> "int";
            case "long" -> "long";
            case "double" -> "double";
            case "float" -> "float";
            case "char" -> "char";
            case "byte" -> "byte";
            case "short" -> "short";
            case "void" -> "void";
            case "Object", "java.lang.Object" -> "java.lang.Object";
            case "Integer", "java.lang.Integer" -> "java.lang.Integer";
            case "Boolean", "java.lang.Boolean" -> "java.lang.Boolean";
            case "Long", "java.lang.Long" -> "java.lang.Long";
            case "Double", "java.lang.Double" -> "java.lang.Double";
            case "Float", "java.lang.Float" -> "java.lang.Float";
            case "Character", "java.lang.Character" -> "java.lang.Character";
            case "Byte", "java.lang.Byte" -> "java.lang.Byte";
            case "Short", "java.lang.Short" -> "java.lang.Short";
            default -> type;
        };
    }

    public static String getSimpleTypeName(String type) {
        if (type == null) return "";
        return OceanImportOptimizer.getSimpleName(type);
    }

    public static boolean isPrimitive(String type) {
        return "int".equals(type) || "boolean".equals(type) || "long".equals(type) ||
               "double".equals(type) || "float".equals(type) || "char".equals(type) ||
               "byte".equals(type) || "short".equals(type);
    }

    public static boolean isBoxedEquivalent(String t1, String t2) {
        return ("int".equals(t1) && "java.lang.Integer".equals(t2)) || ("java.lang.Integer".equals(t1) && "int".equals(t2)) ||
               ("boolean".equals(t1) && "java.lang.Boolean".equals(t2)) || ("java.lang.Boolean".equals(t1) && "boolean".equals(t2)) ||
               ("long".equals(t1) && "java.lang.Long".equals(t2)) || ("java.lang.Long".equals(t1) && "long".equals(t2)) ||
               ("double".equals(t1) && "java.lang.Double".equals(t2)) || ("java.lang.Double".equals(t1) && "double".equals(t2)) ||
               ("float".equals(t1) && "java.lang.Float".equals(t2)) || ("java.lang.Float".equals(t1) && "float".equals(t2)) ||
               ("char".equals(t1) && "java.lang.Character".equals(t2)) || ("java.lang.Character".equals(t1) && "char".equals(t2)) ||
               ("byte".equals(t1) && "java.lang.Byte".equals(t2)) || ("java.lang.Byte".equals(t1) && "byte".equals(t2)) ||
               ("short".equals(t1) && "java.lang.Short".equals(t2)) || ("java.lang.Short".equals(t1) && "short".equals(t2));
    }

    public static boolean isPrimitiveWidening(String from, String to) {
        if (!isPrimitive(from) || !isPrimitive(to)) return false;
        return switch (from) {
            case "byte" -> Set.of("short", "int", "long", "float", "double").contains(to);
            case "short", "char" -> Set.of("int", "long", "float", "double").contains(to);
            case "int" -> Set.of("long", "float", "double").contains(to);
            case "long" -> Set.of("float", "double").contains(to);
            case "float" -> "double".equals(to);
            default -> false;
        };
    }

    private static boolean isExpressionRule(PsiElement element) {
        return isRule(element, OceanParser.RULE_expression);
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

    private static PsiElement getFirstVisibleChild(PsiElement parent) {
        if (parent == null) return null;
        PsiElement child = parent.getFirstChild();
        while (child != null && child.getText().trim().isEmpty()) {
            child = child.getNextSibling();
        }
        return child;
    }

    private static PsiElement nextVisibleSibling(PsiElement element) {
        if (element == null) return null;
        PsiElement sibling = element.getNextSibling();
        while (sibling != null && sibling.getText().trim().isEmpty()) {
            sibling = sibling.getNextSibling();
        }
        return sibling;
    }
}
