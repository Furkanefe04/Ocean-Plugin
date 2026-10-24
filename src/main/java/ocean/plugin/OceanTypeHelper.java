package ocean.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import com.intellij.psi.tree.IElementType;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.*;

public class OceanTypeHelper {
    private static final ThreadLocal<Set<PsiElement>> GUESS_GUARD = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Integer> RECURSION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final Map<String, String> STDLIB_ALIASES = Map.ofEntries(
            Map.entry("OceanList", "ocean.stdlib.OceanList"),
            Map.entry("OceanMap", "ocean.stdlib.OceanMap"),
            Map.entry("OceanSet", "ocean.stdlib.OceanSet"),
            Map.entry("OceanIntList", "ocean.stdlib.OceanIntList"),
            Map.entry("OceanDoubleList", "ocean.stdlib.OceanDoubleList"),
            Map.entry("OceanLongList", "ocean.stdlib.OceanLongList"),
            Map.entry("OceanFloatList", "ocean.stdlib.OceanFloatList"),
            Map.entry("OceanShortList", "ocean.stdlib.OceanShortList"),
            Map.entry("OceanByteList", "ocean.stdlib.OceanByteList"),
            Map.entry("OceanCharList", "ocean.stdlib.OceanCharList"),
            Map.entry("OceanBooleanList", "ocean.stdlib.OceanBooleanList"),
            Map.entry("OceanPair", "ocean.stdlib.OceanPair"),
            Map.entry("OceanTriple", "ocean.stdlib.OceanTriple"),
            Map.entry("OceanResult", "ocean.stdlib.OceanResult"),
            Map.entry("OceanOptional", "ocean.stdlib.OceanOptional"),
            Map.entry("OceanFile", "ocean.stdlib.OceanFile"),
            Map.entry("OceanHttp", "ocean.stdlib.OceanHttp"),
            Map.entry("OceanJson", "ocean.stdlib.OceanJson"),
            Map.entry("OceanMath", "ocean.stdlib.OceanMath"),
            Map.entry("OceanRegex", "ocean.stdlib.OceanRegex"),
            Map.entry("OceanString", "ocean.stdlib.OceanString"),
            Map.entry("OceanStringBuilder", "ocean.stdlib.OceanStringBuilder"),
            Map.entry("OceanCounter", "ocean.stdlib.OceanCounter"),
            Map.entry("OceanStack", "ocean.stdlib.OceanStack"),
            Map.entry("OceanQueue", "ocean.stdlib.OceanQueue"),
            Map.entry("OceanBitSet", "ocean.stdlib.OceanBitSet"),
            Map.entry("OceanDate", "ocean.stdlib.OceanDate"),
            Map.entry("OceanTime", "ocean.stdlib.OceanTime"),
            Map.entry("OceanHttpResponse", "ocean.stdlib.OceanHttpResponse"),
            Map.entry("StrBuilder", "ocean.stdlib.StrBuilder"),
            Map.entry("Task", "ocean.stdlib.Task"),
            Map.entry("ScopedValue", "ocean.stdlib.ScopedValue"),
            Map.entry("OceanInput", "java.util.Scanner")
    );

    public static String getStdlibAlias(String name) {
        if (name == null) return null;
        return STDLIB_ALIASES.get(name);
    }

    public static String resolveDeclaredType(String rawType, Project project) {
        return resolveDeclaredType(rawType, project, null);
    }

    public static String resolveDeclaredType(String rawType, Project project, PsiElement context) {
        if (rawType == null || rawType.isBlank()) return null;
        rawType = rawType.trim();
        if ("variable".equals(rawType) || "value".equals(rawType)) {
            return null;
        }

        String questionSuffix = "";
        if (rawType.endsWith("?")) {
            questionSuffix = "?";
            rawType = rawType.substring(0, rawType.length() - 1).trim();
        }

        StringBuilder arraySuffix = new StringBuilder();
        while (rawType.endsWith("[]")) {
            arraySuffix.append("[]");
            rawType = rawType.substring(0, rawType.length() - 2).trim();
        }
        if (rawType.endsWith("?")) {
            questionSuffix = "?";
            rawType = rawType.substring(0, rawType.length() - 1).trim();
        }

        int lt = rawType.indexOf('<');
        int gt = rawType.lastIndexOf('>');
        String baseType = (lt > 0 && gt > lt) ? rawType.substring(0, lt).trim() : rawType;
        String resolvedGenericArgs = (lt > 0 && gt > lt) ? rawType.substring(lt, gt + 1) : "";
        if (lt > 0 && gt > lt) {
            String inner = rawType.substring(lt + 1, gt).trim();
            List<String> innerArgs = splitGenericArguments(inner);
            if (!innerArgs.isEmpty()) {
                List<String> resolvedInners = new ArrayList<>();
                for (String arg : innerArgs) {
                    // If stdlib alias exists, resolve it (e.g. OceanList -> ocean.stdlib.OceanList)
                    String rArg = resolveDeclaredType(arg, project, context);
                    if (rArg != null && !rArg.equals(arg)) {
                        resolvedInners.add(rArg);
                    } else {
                        resolvedInners.add(arg);
                    }
                }
                resolvedGenericArgs = "<" + String.join(", ", resolvedInners) + ">";
            }
        }

        String resolvedBase = baseType;
        if (STDLIB_ALIASES.containsKey(baseType)) {
            resolvedBase = STDLIB_ALIASES.get(baseType);
        } else if ("String".equals(baseType)) {
            //AIden zeka fışkıran kod
            resolvedBase = "java.lang.String";
        } else if ("int".equals(baseType) || "bool".equals(baseType) || "boolean".equals(baseType) ||
                   "double".equals(baseType) || "float".equals(baseType) || "long".equals(baseType) ||
                   "char".equals(baseType) || "byte".equals(baseType) || "short".equals(baseType)) {
            resolvedBase = baseType;
        } else if (project != null) {
            PsiClass jc = OceanScopeDiscovery.resolveJavaClass(project, baseType, context);
            if (jc != null && jc.getQualifiedName() != null) {
                resolvedBase = jc.getQualifiedName();
            }
        }

        return resolvedBase + resolvedGenericArgs + arraySuffix + questionSuffix;
    }

    public static String boxType(String type) {
        if (type == null) return "java.lang.Object";
        return switch (type) {
            case "int" -> "java.lang.Integer";
            case "double" -> "java.lang.Double";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "boolean", "bool" -> "java.lang.Boolean";
            case "char" -> "java.lang.Character";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "String" -> "java.lang.String";
            default -> type;
        };
    }

    public static List<String> splitGenericArguments(String str) {
        List<String> result = new ArrayList<>();
        if (str == null || str.isBlank()) return result;
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth = Math.max(0, depth - 1);

            if (c == ',' && depth == 0) {
                String s = current.toString().trim();
                if (!s.isEmpty()) result.add(s);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            String s = current.toString().trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    public static List<String> extractTypeArguments(String fullType) {
        if (fullType == null) return Collections.emptyList();
        int lt = fullType.indexOf('<');
        int gt = fullType.lastIndexOf('>');
        if (lt > 0 && gt > lt) {
            return splitGenericArguments(fullType.substring(lt + 1, gt));
        }
        return Collections.emptyList();
    }

    public static String substituteType(String type, Map<String, String> mapping) {
        if (type == null || mapping == null || mapping.isEmpty()) return type;
        type = type.trim();
        if (mapping.containsKey(type)) return mapping.get(type);

        if (type.endsWith("?")) {
            String base = type.substring(0, type.length() - 1).trim();
            String sub = substituteType(base, mapping);
            return sub != null ? (sub.endsWith("?") ? sub : sub + "?") : type;
        }

        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt > 0 && gt > lt) {
            String base = type.substring(0, lt).trim();
            String argsStr = type.substring(lt + 1, gt);
            List<String> args = splitGenericArguments(argsStr);
            List<String> substitutedArgs = new ArrayList<>(args.size());
            for (String arg : args) {
                substitutedArgs.add(substituteType(arg.trim(), mapping));
            }
            return base + "<" + String.join(", ", substitutedArgs) + ">";
        }
        if (type.endsWith("[]")) {
            String elem = type.substring(0, type.length() - 2);
            return substituteType(elem, mapping) + "[]";
        }
        return type;
    }

    private static String substitutePsiType(PsiType type, Map<String, String> typeMapping) {
        if (type == null) return "void";
        String canonical = type.getCanonicalText();
        if (typeMapping == null || typeMapping.isEmpty()) return canonical;
        return substituteType(canonical, typeMapping);
    }

    public static List<String> extractTypeParameterNames(PsiElement oceanCls) {
        List<String> names = new ArrayList<>();
        if (oceanCls == null) return names;
        for (PsiElement child : oceanCls.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeParameter)) {
                PsiElement idChild = findChildByRule(child, OceanParser.RULE_anyId);
                if (idChild != null) {
                    String id = idChild.getText().trim();
                    while (id.startsWith("+") || id.startsWith("-")) id = id.substring(1).trim();
                    names.add(id);
                } else {
                    String t = child.getText().trim();
                    while (t.startsWith("+") || t.startsWith("-")) t = t.substring(1).trim();
                    int space = t.indexOf(' ');
                    names.add(space > 0 ? t.substring(0, space).trim() : t);
                }
            }
        }
        if (names.isEmpty()) {
            String text = oceanCls.getText();
            int lBrace = text.indexOf('{');
            String header = lBrace > 0 ? text.substring(0, lBrace) : text;
            int extIdx = header.indexOf(" extends ");
            int impIdx = header.indexOf(" implements ");
            int clipIdx = header.length();
            if (extIdx > 0) clipIdx = Math.min(clipIdx, extIdx);
            if (impIdx > 0) clipIdx = Math.min(clipIdx, impIdx);
            header = header.substring(0, clipIdx);
            int lt = header.indexOf('<');
            int gt = header.lastIndexOf('>');
            if (lt >= 0 && gt > lt) {
                for (String part : splitGenericArguments(header.substring(lt + 1, gt))) {
                    String p = part.trim();
                    while (p.startsWith("+") || p.startsWith("-")) p = p.substring(1).trim();
                    int sp = p.indexOf(' ');
                    names.add(sp > 0 ? p.substring(0, sp).trim() : p);
                }
            }
        }
        return names;
    }

    public static Map<String, String> buildTypeSubstitutionMap(String fullType, Project project) {
        if (fullType == null) return Collections.emptyMap();
        List<String> typeArgs = extractTypeArguments(fullType);
        if (typeArgs.isEmpty()) return Collections.emptyMap();

        String baseType = cleanGeneric(fullType);
        if (baseType == null) return Collections.emptyMap();
        baseType = OceanScopeDiscovery.normalizeTypeName(baseType);
        if (STDLIB_ALIASES.containsKey(baseType)) baseType = STDLIB_ALIASES.get(baseType);

        Map<String, String> mapping = new HashMap<>();

        if ("ocean.stdlib.OceanList".equals(baseType) || "ocean.stdlib.OceanSet".equals(baseType) || "ocean.stdlib.OceanOptional".equals(baseType) ||
                "java.util.List".equals(baseType) || "java.util.Set".equals(baseType) || "java.util.ArrayList".equals(baseType) || "List".equals(baseType) || "Set".equals(baseType) ||
                "java.util.concurrent.CompletableFuture".equals(baseType) || "CompletableFuture".equals(baseType)) {
            mapping.put("E", typeArgs.get(0));
            mapping.put("T", typeArgs.get(0));
            return mapping;
        }

        if ("ocean.stdlib.OceanMap".equals(baseType) || "java.util.Map".equals(baseType) || "java.util.HashMap".equals(baseType) || "Map".equals(baseType)) {
            if (!typeArgs.isEmpty()) mapping.put("K", typeArgs.get(0));
            if (typeArgs.size() >= 2) mapping.put("V", typeArgs.get(1));
            return mapping;
        }

        if ("ocean.stdlib.OceanPair".equals(baseType)) {
            if (!typeArgs.isEmpty()) mapping.put("A", typeArgs.get(0));
            if (typeArgs.size() >= 2) mapping.put("B", typeArgs.get(1));
            return mapping;
        }

        if ("ocean.stdlib.OceanTriple".equals(baseType)) {
            if (!typeArgs.isEmpty()) mapping.put("A", typeArgs.get(0));
            if (typeArgs.size() >= 2) mapping.put("B", typeArgs.get(1));
            if (typeArgs.size() >= 3) mapping.put("C", typeArgs.get(2));
            return mapping;
        }

        if ("ocean.stdlib.OceanResult".equals(baseType)) {
            if (!typeArgs.isEmpty()) mapping.put("T", typeArgs.get(0));
            if (typeArgs.size() >= 2) mapping.put("E", typeArgs.get(1));
            return mapping;
        }

        if (project != null) {
            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, baseType);
            if (oceanCls != null) {
                List<String> paramNames = extractTypeParameterNames(oceanCls);
                for (int i = 0; i < Math.min(paramNames.size(), typeArgs.size()); i++) {
                    mapping.put(paramNames.get(i), typeArgs.get(i));
                }
            } else {
                PsiClass javaCls = OceanScopeDiscovery.resolveJavaClass(project, baseType);
                if (javaCls != null) {
                    PsiTypeParameter[] params = javaCls.getTypeParameters();
                    for (int i = 0; i < Math.min(params.length, typeArgs.size()); i++) {
                        mapping.put(params[i].getName(), typeArgs.get(i));
                    }
                }
            }
        }

        return mapping;
    }

    public static String resolveIndexAccessType(String receiverType) {
        if (receiverType == null) return null;
        receiverType = receiverType.trim();
        if (receiverType.endsWith("[]")) {
            return receiverType.substring(0, receiverType.length() - 2);
        }
        switch (receiverType) {
            case "ocean.stdlib.OceanIntList", "OceanIntList" -> {
                return "int";
            }
            case "ocean.stdlib.OceanDoubleList", "OceanDoubleList" -> {
                return "double";
            }
            case "ocean.stdlib.OceanLongList", "OceanLongList" -> {
                return "long";
            }
            case "ocean.stdlib.OceanFloatList", "OceanFloatList" -> {
                return "float";
            }
            case "ocean.stdlib.OceanBooleanList", "OceanBooleanList" -> {
                return "boolean";
            }
            case "ocean.stdlib.OceanCharList", "OceanCharList" -> {
                return "char";
            }
            case "ocean.stdlib.OceanByteList", "OceanByteList" -> {
                return "byte";
            }
            case "ocean.stdlib.OceanShortList", "OceanShortList" -> {
                return "short";
            }
        }

        if (receiverType.contains("List") || receiverType.contains("Set")) {
            List<String> typeArgs = extractTypeArguments(receiverType);
            return typeArgs.isEmpty() ? "java.lang.Object" : typeArgs.get(0);
        }
        if (receiverType.contains("Map")) {
            List<String> typeArgs = extractTypeArguments(receiverType);
            return (typeArgs.size() >= 2) ? typeArgs.get(1) : "java.lang.Object";
        }
        return null;
    }

    public static String guessTypeFromTextOrElement(String text, PsiElement context) {
        if (text == null || text.isBlank()) return null;
        text = text.trim();

        // P2-A: PSI-first chain resolution when context is available.
        // This correctly handles: string literals with dots, lambdas, nested calls.
        if (context != null && (text.contains(".") || text.contains("?."))) {
            String psiChainResult = guessChainTypeFromPsi(context, text);
            if (psiChainResult != null) return psiChainResult;
        }

        // If it's a chained call or dotted expression: e.g. "user.getAddress().getCity()" or "a.b"
        // (fallback: string-split based chain, works for simple cases)
        if (text.contains(".") || text.contains("?.")) {
            List<String> parts = splitExpressionChain(text);
            if (!parts.isEmpty()) {
                String currentType = null;
                for (int i = 0; i < parts.size(); i++) {
                    String part = parts.get(i).trim();
                    if (i == 0) {
                        currentType = guessTypeForSimpleTerm(part, context);
                    } else {
                        if (currentType == null) break;
                        String memberName = part;
                        int pIdx = memberName.indexOf('(');
                        if (pIdx >= 0) memberName = memberName.substring(0, pIdx).trim();
                        currentType = findMemberType(currentType, memberName, context != null ? context.getProject() : null, context);
                    }
                }
                if (currentType != null) return currentType;
            }
        }

        return guessTypeForSimpleTerm(text, context);
    }

    /**
     * P2-A: PSI-aware chain type resolution.
     * Walks the PSI tree starting from {@code context} to find an expression
     * node whose text matches {@code chainText}, then resolves each link via
     * {@link #guessType(PsiElement)} which already handles member-call, index
     * access, collection literals, etc.
     * <p>
     * This avoids the string-split approach that breaks on:
     *  - String literals containing dots ("hello.world".length())
     *  - Lambdas: list.stream().filter(x -> x.val > 0).findFirst()
     *  - Parenthesised sub-expressions: (a + b).toString()
     */
    private static String guessChainTypeFromPsi(PsiElement context, String chainText) {
        if (context == null || chainText == null) return null;
        // Walk up to the enclosing expression node that has the same text
        PsiElement candidate = context;
        while (candidate != null) {
            if (isRule(candidate, OceanParser.RULE_expression) || isRule(candidate, OceanParser.RULE_primary)) {
                String t = candidate.getText();
                if (t != null && t.trim().equals(chainText)) {
                    // Found the matching expression node — resolve its type via guessExpressionType
                    String result = guessExpressionType(candidate);
                    if (result != null) return result;
                    break;
                }
                // If the node is longer than the chain text, stop climbing — we have gone past it
                if (t != null && t.length() > chainText.length() + 5) break;
            }
            candidate = candidate.getParent();
        }

        // Also try: look at the context's own enclosing expression if it matches the chainText exactly
        PsiElement exprParent = findEnclosingExpression(context);
        if (exprParent != null && exprParent.getText() != null && exprParent.getText().trim().equals(chainText)) {
            // Walk the DOT chain iteratively
            return resolveExpressionChainPsi(exprParent);
        }
        return null;
    }

    /**
     * Walk up the PSI tree to find the nearest enclosing RULE_expression node.
     */
    private static PsiElement findEnclosingExpression(PsiElement element) {
        PsiElement cur = element != null ? element.getParent() : null;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_expression)) return cur;
            if (cur instanceof PsiFile) break;
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * Recursively resolve a DOT chain by descending through expression children.
     * expr := receiver DOT member
     * Returns the type of the full expression, or null if unresolvable.
     */
    private static String resolveExpressionChainPsi(PsiElement expr) {
        if (expr == null) return null;
        if (!isRule(expr, OceanParser.RULE_expression) && !isRule(expr, OceanParser.RULE_primary)) return null;

        PsiElement dot = findTokenChild(expr, OceanLexer.DOT);
        if (dot == null) dot = findTokenChild(expr, OceanLexer.SAFE_DOT);
        if (dot == null) return null;

        PsiElement receiver = prevVisibleSibling(dot);
        PsiElement memberNode = nextVisibleSibling(dot);
        if (receiver == null || memberNode == null) return null;

        String receiverType = guessType(receiver);
        if (receiverType == null) return null;

        String memberName = memberNode.getText();
        int callParen = memberName.indexOf('(');
        if (callParen >= 0) memberName = memberName.substring(0, callParen).trim();
        if (memberName.isEmpty()) return null;

        return findMemberType(receiverType, memberName, expr.getProject(), expr);
    }

    public static List<String> splitExpressionChain(String expr) {
        List<String> parts = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);

            // Handle escapes within string/char literals
            if ((inDoubleQuote || inSingleQuote) && c == '\\' && i + 1 < expr.length()) {
                current.append(c).append(expr.charAt(i + 1));
                i++;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }

            if (!inDoubleQuote && !inSingleQuote) {
                if (c == '(') depthParen++;
                else if (c == ')') depthParen = Math.max(0, depthParen - 1);
                else if (c == '[') depthBracket++;
                else if (c == ']') depthBracket = Math.max(0, depthBracket - 1);
                else if (c == '{') depthBrace++;
                else if (c == '}') depthBrace = Math.max(0, depthBrace - 1);

                if ((c == '.' || (c == '?' && i + 1 < expr.length() && expr.charAt(i + 1) == '.')) 
                        && depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                    if (!current.isEmpty()) {
                        parts.add(current.toString().trim());
                        current.setLength(0);
                    }
                    if (c == '?') i++; // skip '.'
                    continue;
                }
            }

            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    public static boolean isSingleEnclosed(String text, char open, char close) {
        if (text == null || text.length() < 2) return false;
        if (text.charAt(0) != open || text.charAt(text.length() - 1) != close) return false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i == text.length() - 1;
                }
            }
        }
        return false;
    }

    private static String guessTypeForSimpleTerm(String name, PsiElement context) {
        if (name == null || name.isBlank()) return null;
        name = name.trim();

        if (name.startsWith("#{") && isSingleEnclosed(name.substring(1), '{', '}')) {
            String coll = guessCollectionLiteralType(null, name);
            if (coll != null) return coll;
        } else if (name.startsWith("{") && isSingleEnclosed(name, '{', '}')) {
            String coll = guessCollectionLiteralType(null, name);
            if (coll != null) return coll;
        } else if (name.startsWith("[") && isSingleEnclosed(name, '[', ']')) {
            String coll = guessCollectionLiteralType(null, name);
            if (coll != null) return coll;
        }

        if (name.endsWith("]") && name.contains("[")) {
            int bracketIdx = name.lastIndexOf('[');
            if (bracketIdx > 0) {
                String receiverName = name.substring(0, bracketIdx).trim();
                String receiverType = guessTypeForSimpleTerm(receiverName, context);
                if (receiverType != null) {
                    String elemType = resolveIndexAccessType(receiverType);
                    if (elemType != null) return elemType;
                }
            }
        }

        if (name.startsWith("\"") && name.endsWith("\"")) return "java.lang.String";
        if (name.startsWith("'") && name.endsWith("'")) return "char";
        if ("true".equals(name) || "false".equals(name)) return "boolean";
        if (name.matches("-?\\d+\\.\\d+[dD]?")) return "double";
        if (name.matches("-?\\d+\\.\\d+[fF]")) return "float";
        if (name.matches("-?\\d+[lL]")) return "long";
        if (name.matches("-?\\d+")) return "int";

        if (STDLIB_ALIASES.containsKey(name)) return STDLIB_ALIASES.get(name);
        if (name.startsWith("OceanInput(") || "OceanInput".equals(name)) return "java.util.Scanner";
        switch (name) {
            case "args" -> {
                return "java.lang.String[]";
            }
            case "this" -> {
                return findEnclosingClassName(context);
            }
            case "super" -> {
                return findEnclosingSuperClassName(context);
            }
        }

        if (name.startsWith("new ")) {
            String afterNew = name.substring(4).trim();
            int p = afterNew.indexOf('(');
            if (p > 0) afterNew = afterNew.substring(0, p).trim();
            String cleaned = cleanGeneric(afterNew);
            if (context != null) {
                String resolved = resolveDeclaredType(cleaned, context.getProject(), context);
                if (resolved != null) return resolved;
            }
            return cleaned;
        }

        if (context != null) {
            String lookupName = name;
            int callParen = lookupName.indexOf('(');
            if (callParen >= 0) lookupName = lookupName.substring(0, callParen).trim();

            List<PsiElement> symbols = OceanScopeDiscovery.findVisibleSymbols(context);
            for (PsiElement sym : symbols) {
                if (sym instanceof PsiNamedElement pNamed && (name.equals(pNamed.getName()) || lookupName.equals(pNamed.getName()))) {
                    return determineTypeFromSymbol(sym);
                }
            }

            // Fallback for editing in progress: Scan context file text using regex if AST is recovering
            PsiFile contextFile = context.getContainingFile();
            if (contextFile != null) {
                String fileText = contextFile.getText();
                if (fileText != null && !fileText.isEmpty()) {
                    // Pattern 1: Typed declaration: Type name = ... (e.g. "a ax =" or "User ax =")
                    Pattern pat1 = Pattern.compile("(?<=^|[\\s;{}])([a-zA-Z_][a-zA-Z0-9_<>\\[\\]]*)\\s+" + Pattern.quote(name) + "\\s*=");
                    Matcher m1 = pat1.matcher(fileText);
                    while (m1.find()) {
                        String t = m1.group(1).trim();
                        if (!"value".equals(t) && !"variable".equals(t) && !"final".equals(t) && !"return".equals(t)) {
                            String cleaned = cleanGeneric(t);
                            String resolved = resolveDeclaredType(cleaned, context.getProject(), context);
                            return resolved != null ? resolved : cleaned;
                        }
                    }

                    // Pattern 1b: Varargs parameter: Type... name
                    Pattern patVarargs = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_<>\\[\\]]*)\\s*\\.\\.\\.\\s*" + Pattern.quote(name) + "\\b");
                    Matcher mVarargs = patVarargs.matcher(fileText);
                    if (mVarargs.find()) {
                        String t = mVarargs.group(1).trim();
                        String cleaned = cleanGeneric(t);
                        String resolved = resolveDeclaredType(cleaned, context.getProject(), context);
                        String base = resolved != null ? resolved : cleaned;
                        return base.endsWith("[]") ? base : (base + "[]");
                    }

                    // Pattern 2: (variable/value/any) name = new Type(...) (e.g. "ax = new a()")
                    Pattern pat2 = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*new\\s+([a-zA-Z_][a-zA-Z0-9_<>]*)");
                    Matcher m2 = pat2.matcher(fileText);
                    if (m2.find()) {
                        String cleaned = cleanGeneric(m2.group(1).trim());
                        String resolved = resolveDeclaredType(cleaned, context.getProject(), context);
                        return resolved != null ? resolved : cleaned;
                    }

                    // Pattern 3: (variable/value) name = func(...) (e.g. "variable future = runTests()")
                    Pattern pat3 = Pattern.compile("(?:variable|value)\\s+" + Pattern.quote(name) + "\\s*=\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
                    Matcher m3 = pat3.matcher(fileText);
                    if (m3.find()) {
                        String callee = m3.group(1).trim();
                        for (PsiElement sym : symbols) {
                            if (sym instanceof PsiNamedElement pNamed && callee.equals(pNamed.getName())) {
                                String t = determineTypeFromSymbol(sym);
                                if (t != null && !"java.lang.Object".equals(t)) return t;
                            }
                        }

                        // Also search containing class in contextFile if available
                        PsiElement foundMethod = null;
                        for (PsiElement child : contextFile.getChildren()) {
                            if (child instanceof OceanMethodDeclaration || isRule(child, OceanParser.RULE_methodDeclaration)) {
                                if (child instanceof PsiNamedElement pNamed && callee.equals(pNamed.getName())) {
                                    foundMethod = child;
                                    break;
                                }
                            }
                        }
                        if (foundMethod != null) {
                            String t = determineTypeFromSymbol(foundMethod);
                            if (t != null && !"java.lang.Object".equals(t)) return t;
                        }
                    }
                }
            }

            // Prioritize explicitly imported Java classes before arbitrary project-wide Ocean classes!
            if (contextFile != null) {
                OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(contextFile);
                for (String explicit : fileInfo.explicitImports) {
                    if (explicit.endsWith("." + name) || explicit.equals(name)) {
                        PsiClass jc = OceanScopeDiscovery.resolveJavaClass(context.getProject(), explicit, context);
                        if (jc != null && jc.getQualifiedName() != null) return jc.getQualifiedName();
                    }
                }
            }

            // Local Ocean class in same file
            if (contextFile != null && OceanScopeDiscovery.findClassDeep(contextFile, name) != null) {
                return name;
            }

            // Standard Java class (e.g. Math, System, Thread, Arrays, Collections)
            PsiClass stdJava = OceanScopeDiscovery.resolveJavaClass(context.getProject(), name, context);
            if (stdJava != null && stdJava.getQualifiedName() != null &&
                    (stdJava.getQualifiedName().startsWith("java.") || stdJava.getQualifiedName().startsWith("ocean.stdlib."))) {
                return stdJava.getQualifiedName();
            }

            if (OceanScopeDiscovery.findOceanClass(context.getProject(), name, contextFile) != null) {
                return name;
            }

            if (stdJava != null) return stdJava.getQualifiedName();
        }

        return null;
    }

    private static String cleanGeneric(String t) {
        if (t == null) return null;
        t = t.trim();
        int i = t.indexOf('<');
        return (i > 0) ? t.substring(0, i).trim() : t;
    }

    public static String guessType(PsiElement element) {
        if (element == null) return null;
        if (!GUESS_GUARD.get().add(element)) return "java.lang.Object";
        RECURSION_DEPTH.set(RECURSION_DEPTH.get() + 1);

        try {
            // Check for test mock explicit type
            try {
                Method m = element.getClass().getMethod("getExplicitType");
                Object customType = m.invoke(element);
                if (customType instanceof String s && !s.isEmpty()) return s;
            } catch (Throwable ignored) {}

            String name = element.getText().trim();

            String expressionType = guessExpressionType(element);
            if (expressionType != null) return expressionType;
            
            // 0. Aliases & Specials
            if (STDLIB_ALIASES.containsKey(name)) return STDLIB_ALIASES.get(name);
            if (name.startsWith("OceanInput(") || "OceanInput".equals(name)) return "java.util.Scanner";
            switch (name) {
                case "args" -> {
                    return "java.lang.String[]";
                }
                case "this" -> {
                    return findEnclosingClassName(element);
                }
                case "super" -> {
                    return findEnclosingSuperClassName(element);
                }
            }

            // 1. Literals
            String literal = checkLiteralType(element);
            if (literal != null) return literal;

            // 2. Expression context (Parent)
            PsiElement parent = element.getParent();
            if (isRule(parent, OceanParser.RULE_expression)) {
                // If it's a member access: receiver.member
                PsiElement dot = findTokenChild(parent, OceanLexer.DOT);
                if (dot == null) dot = findTokenChild(parent, OceanLexer.SAFE_DOT);
                if (dot != null && parent.getFirstChild() != element) {
                    if ("length".equals(name)) return "int";
                    String receiverType = guessType(parent.getFirstChild());
                    if (receiverType != null) return findMemberType(receiverType, name, element.getProject(), element);
                }
                
                // If it's a NEW expression
                PsiElement typeChild = findChildByRule(parent, OceanParser.RULE_type);
                if (typeChild != null) {
                    return resolveDeclaredType(typeChild.getText().trim(), parent.getProject(), parent);
                }
            }

            // 2b. Direct Declaration context: if element is itself the name of a parameter or variable
            if (parent != null && (isRule(parent, OceanParser.RULE_parameter) || parent instanceof OceanParameter ||
                                   isRule(parent, OceanParser.RULE_variableDeclarator) || parent instanceof OceanVariableDeclarator)) {
                String declType = determineTypeFromSymbol(element);
                if (declType != null && !"java.lang.Object".equals(declType)) {
                    return declType;
                }
            }

            // 3. Local Scoping
            String lookupName = name;
            int callParen = lookupName.indexOf('(');
            if (callParen >= 0) lookupName = lookupName.substring(0, callParen).trim();

            List<PsiElement> symbols = OceanScopeDiscovery.findVisibleSymbols(element);
            for (PsiElement sym : symbols) {
                if (sym instanceof PsiNamedElement pNamed && (name.equals(pNamed.getName()) || lookupName.equals(pNamed.getName()))) {
                    return determineTypeFromSymbol(sym);
                }
            }

            // 4. Global Classes
            PsiFile containingFile = element.getContainingFile();
            if (containingFile != null) {
                OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(containingFile);
                for (String explicit : fileInfo.explicitImports) {
                    if (explicit.endsWith("." + name) || explicit.equals(name)) {
                        PsiClass jc = OceanScopeDiscovery.resolveJavaClass(element.getProject(), explicit, element);
                        if (jc != null && jc.getQualifiedName() != null) return jc.getQualifiedName();
                    }
                }
                if (OceanScopeDiscovery.findClassDeep(containingFile, name) != null) {
                    return name;
                }
            }

            PsiClass stdJava = OceanScopeDiscovery.resolveJavaClass(element.getProject(), name, element);
            if (stdJava != null && stdJava.getQualifiedName() != null &&
                    (stdJava.getQualifiedName().startsWith("java.") || stdJava.getQualifiedName().startsWith("ocean.stdlib."))) {
                return stdJava.getQualifiedName();
            }

            if (OceanScopeDiscovery.findOceanClass(element.getProject(), name, containingFile) != null) {
                return name;
            }

            if (stdJava != null) return stdJava.getQualifiedName();

            return null;
        } finally {
            GUESS_GUARD.get().remove(element);
            int depth = RECURSION_DEPTH.get() - 1;
            if (depth <= 0) {
                RECURSION_DEPTH.set(0);
                GUESS_GUARD.get().clear();
            } else {
                RECURSION_DEPTH.set(depth);
            }
        }
    }

    private static String determineTypeFromSymbol(PsiElement symbol) {
        if (symbol instanceof PsiField) return ((PsiField)symbol).getType().getCanonicalText();
        if (symbol instanceof PsiMethod) return (((PsiMethod)symbol).getReturnType() != null) ? ((PsiMethod)symbol).getReturnType().getCanonicalText() : "void";
        if (symbol instanceof PsiVariable) return ((PsiVariable)symbol).getType().getCanonicalText();

        if (symbol instanceof OceanMethodDeclaration || isRule(symbol, OceanParser.RULE_methodDeclaration)) {
            return OceanScopeDiscovery.getMethodReturnType(symbol);
        }

        if (symbol instanceof OceanPatternVariable) {
            PsiElement parent = symbol.getParent();
            PsiElement typeNode = null;
            if (parent != null) {
                typeNode = findChildByRule(parent, OceanParser.RULE_type);
                if (typeNode == null) {
                    for (PsiElement sib = symbol.getPrevSibling(); sib != null; sib = sib.getPrevSibling()) {
                        if (isRule(sib, OceanParser.RULE_type)) {
                            typeNode = sib;
                            break;
                        }
                    }
                }
            }
            if (typeNode != null) {
                return resolveDeclaredType(typeNode.getText(), symbol.getProject(), symbol);
            }
        }

        if (symbol instanceof OceanClassDeclaration || symbol instanceof OceanInterfaceDeclaration || symbol instanceof OceanEnumDeclaration || symbol instanceof OceanAnnotationDeclaration) {
            return ((PsiNamedElement) symbol).getName();
        }
        
        PsiElement declaration = symbol;
        if (isRule(declaration, OceanParser.RULE_anyId)) {
            if (declaration.getParent() != null) {
                declaration = declaration.getParent();
            }
        } else if (declaration.getParent() != null &&
            (isRule(declaration.getParent(), OceanParser.RULE_parameter) ||
             isRule(declaration.getParent(), OceanParser.RULE_variableDeclarator) ||
             isRule(declaration.getParent(), OceanParser.RULE_fieldDeclaration))) {
            declaration = declaration.getParent();
        } else if (!(symbol instanceof OceanNamedElement)) {
            declaration = symbol.getParent();
        }
        if (declaration == null) return "java.lang.Object";

        // Direct explicit type node
        PsiElement typeNode = findChildByRule(declaration, OceanParser.RULE_type);
        if (typeNode == null && (isRule(declaration, OceanParser.RULE_variableDeclarator) || declaration instanceof OceanVariableDeclarator)) {
            typeNode = findChildByRule(declaration.getParent(), OceanParser.RULE_type);
        }
        if (typeNode != null) {
            String resolved = resolveDeclaredType(typeNode.getText(), declaration.getProject(), declaration);
            if (resolved != null) {
                // Check if this parameter is a varargs parameter ('...')
                boolean isVarargs = false;
                if (isRule(declaration, OceanParser.RULE_parameter) || declaration instanceof OceanParameter) {
                    isVarargs = hasChildToken(declaration, OceanLexer.ELLIPSIS) || declaration.getText().contains("...");
                } else if (declaration.getParent() != null && (isRule(declaration.getParent(), OceanParser.RULE_parameter) || declaration.getParent() instanceof OceanParameter)) {
                    isVarargs = hasChildToken(declaration.getParent(), OceanLexer.ELLIPSIS) || declaration.getParent().getText().contains("...");
                }
                if (isVarargs && !resolved.contains("[]")) {
                    resolved = resolved + "[]";
                }
            }
            return resolved;
        }

        // forControl (from .. to) -> loop counter is int
        if (isRule(declaration, OceanParser.RULE_forControl)) {
            return "int";
        }

        // forStatement with IN: for (item in collection)
        if (isRule(declaration, OceanParser.RULE_forStatement)) {
            PsiElement inExpr = null;
            boolean afterIn = false;
            for (PsiElement child : declaration.getChildren()) {
                if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.IN) {
                    afterIn = true;
                    continue;
                }
                if (afterIn && isRule(child, OceanParser.RULE_expression)) {
                    inExpr = child;
                    break;
                }
            }
            if (inExpr != null) {
                String collType = guessType(inExpr);
                if (collType != null) {
                    String elemType = resolveIndexAccessType(collType);
                    if (elemType != null) return elemType;
                }
            }
        }

        // catchClause: catch (IOException e) or catch (A | B e)
        if (isRule(declaration, OceanParser.RULE_catchClause)) {
            PsiElement cType = findChildByRule(declaration, OceanParser.RULE_type);
            if (cType != null) {
                return resolveDeclaredType(cType.getText(), declaration.getProject());
            }
            return "java.lang.Exception";
        }

        // switchPattern: case String s -> ...
        if (isRule(declaration, OceanParser.RULE_switchPattern) || isRule(declaration, OceanParser.RULE_instanceofPattern)) {
            PsiElement sType = findChildByRule(declaration, OceanParser.RULE_type);
            if (sType != null) {
                return resolveDeclaredType(sType.getText(), declaration.getProject());
            }
        }

        // Lambda parameter: (item) -> ...
        // If enclosing expression is e.g. list.forEach(item -> ...), resolve item type from list receiver
        if (isRule(declaration, OceanParser.RULE_parameter) || isRule(declaration, OceanParser.RULE_identifierList)) {
            PsiElement lambdaExpr = declaration.getParent();
            while (lambdaExpr != null && !isRule(lambdaExpr, OceanParser.RULE_expression)) {
                lambdaExpr = lambdaExpr.getParent();
            }
            if (lambdaExpr != null) {
                // Calculate 0-based parameter index
                int paramIndex = 0;
                PsiElement pParent = declaration.getParent();
                if (pParent != null) {
                    int idx = 0;
                    for (PsiElement sibling : pParent.getChildren()) {
                        if (sibling == declaration) {
                            paramIndex = idx;
                            break;
                        }
                        if (isRule(sibling, OceanParser.RULE_parameter) || isRule(sibling, OceanParser.RULE_anyId)) {
                            idx++;
                        }
                    }
                }

                PsiElement argList = lambdaExpr.getParent();
                PsiElement outerCall = argList != null ? argList.getParent() : null;
                if (isRule(outerCall, OceanParser.RULE_expression)) {
                    PsiElement dot = findTokenChild(outerCall, OceanLexer.DOT);
                    if (dot == null) dot = findTokenChild(outerCall, OceanLexer.SAFE_DOT);
                    if (dot != null) {
                        PsiElement receiver = prevVisibleSibling(dot);
                        if (receiver != null) {
                            String recType = guessType(receiver);
                            if (recType != null) {
                                if (recType.contains("Map")) {
                                    List<String> typeArgs = extractTypeArguments(recType);
                                    if (paramIndex == 0 && !typeArgs.isEmpty()) {
                                        return typeArgs.get(0);
                                    }
                                    if (paramIndex == 1 && typeArgs.size() >= 2) {
                                        return typeArgs.get(1);
                                    }
                                }
                                String elemType = resolveIndexAccessType(recType);
                                if (elemType != null) return elemType;
                            }
                        }
                    }
                }
            }
        }

        // Initializer expression
        PsiElement initializer = findInitializerExpression(declaration);
        if (initializer != null) {
            String inferred = guessType(initializer);
            if (inferred == null) {
                inferred = guessTypeFromTextOrElement(initializer.getText(), initializer);
            }
            if (inferred != null && !"java.lang.Object".equals(inferred)) return inferred;
        }
        
        return "java.lang.Object";
    }

    public static String findMemberType(String rawClassName, String memberName, Project project) {
        return findMemberType(rawClassName, memberName, project, null);
    }

    public static String findMemberType(String rawClassName, String memberName, Project project, PsiElement context) {
        if ("length".equals(memberName)) return "int";
        if (rawClassName == null) return null;

        Map<String, String> typeMapping = buildTypeSubstitutionMap(rawClassName, project);
        String className = cleanGeneric(rawClassName);
        className = OceanScopeDiscovery.normalizeTypeName(className);
        if (STDLIB_ALIASES.containsKey(className)) className = STDLIB_ALIASES.get(className);

        // Fast path for String built-ins
        if ("String".equals(className) || "java.lang.String".equals(className)) {
            switch (memberName) {
                case "toUpperCase", "toLowerCase", "trim", "strip", "substring", "replace", "replaceAll", "replaceFirst", "concat", "repeat" -> { return "java.lang.String"; }
                case "indexOf", "lastIndexOf", "compareTo", "compareToIgnoreCase" -> { return "int"; }
                case "isEmpty", "isBlank", "contains", "startsWith", "endsWith", "equals", "equalsIgnoreCase", "matches" -> { return "boolean"; }
                case "charAt" -> { return "char"; }
                case "getBytes" -> { return "byte[]"; }
                case "toCharArray" -> { return "char[]"; }
                case "split" -> { return "java.lang.String[]"; }
            }
        }

        // Fast path for Scanner / OceanInput
        if ("java.util.Scanner".equals(className) || "OceanInput".equals(className)) {
            switch (memberName) {
                case "nextLine", "next" -> { return "java.lang.String"; }
                case "nextInt" -> { return "int"; }
                case "nextDouble" -> { return "double"; }
                case "nextLong" -> { return "long"; }
                case "nextFloat" -> { return "float"; }
                case "nextBoolean", "hasNext", "hasNextLine", "hasNextInt", "hasNextDouble" -> { return "boolean"; }
            }
        }

        // Fast path for Collections
        if (className.contains("List")) {
            switch (memberName) {
                case "size" -> { return "int"; }
                case "isEmpty", "contains", "containsAll", "add", "remove", "addAll" -> { return "boolean"; }
                case "get", "getFirst", "getLast", "removeAt" -> {
                    return typeMapping.getOrDefault("E", typeMapping.getOrDefault("T", "java.lang.Object"));
                }
                case "filter", "reversed", "sorted", "subList", "take", "drop" -> {
                    String elem = typeMapping.getOrDefault("E", typeMapping.getOrDefault("T", "java.lang.Object"));
                    return "ocean.stdlib.OceanList<" + boxType(elem) + ">";
                }
                case "iterator" -> {
                    String elem = typeMapping.getOrDefault("E", typeMapping.getOrDefault("T", "java.lang.Object"));
                    return "java.util.Iterator<" + boxType(elem) + ">";
                }
                case "toArray" -> {
                    String elem = typeMapping.getOrDefault("E", typeMapping.getOrDefault("T", "java.lang.Object"));
                    return elem + "[]";
                }
                case "clear" -> { return "void"; }
            }
        }

        if (className.contains("Set")) {
            switch (memberName) {
                case "size" -> { return "int"; }
                case "isEmpty", "contains", "containsAll", "add", "remove", "addAll" -> { return "boolean"; }
                case "filter" -> {
                    String elem = typeMapping.getOrDefault("E", typeMapping.getOrDefault("T", "java.lang.Object"));
                    return "ocean.stdlib.OceanSet<" + boxType(elem) + ">";
                }
                case "iterator" -> {
                    String elem = typeMapping.getOrDefault("E", typeMapping.getOrDefault("T", "java.lang.Object"));
                    return "java.util.Iterator<" + boxType(elem) + ">";
                }
                case "clear" -> { return "void"; }
            }
        }

        if (className.contains("Map")) {
            switch (memberName) {
                case "size" -> { return "int"; }
                case "isEmpty", "containsKey", "containsValue" -> { return "boolean"; }
                case "get", "getOrDefault", "remove", "putIfAbsent", "computeIfAbsent" -> {
                    return typeMapping.getOrDefault("V", "java.lang.Object");
                }
                case "keySet", "keys" -> {
                    String k = typeMapping.getOrDefault("K", "java.lang.Object");
                    return "ocean.stdlib.OceanSet<" + k + ">";
                }
                case "values" -> {
                    String v = typeMapping.getOrDefault("V", "java.lang.Object");
                    return "ocean.stdlib.OceanList<" + v + ">";
                }
                case "clear" -> { return "void"; }
            }
        }

        if (className.contains("Pair")) {
            switch (memberName) {
                case "first", "getFirst" -> { return typeMapping.getOrDefault("A", "java.lang.Object"); }
                case "second", "getSecond" -> { return typeMapping.getOrDefault("B", "java.lang.Object"); }
            }
        }

        if (className.contains("Triple")) {
            switch (memberName) {
                case "first", "getFirst" -> { return typeMapping.getOrDefault("A", "java.lang.Object"); }
                case "second", "getSecond" -> { return typeMapping.getOrDefault("B", "java.lang.Object"); }
                case "third", "getThird" -> { return typeMapping.getOrDefault("C", "java.lang.Object"); }
            }
        }

        if (className.contains("Optional")) {
            switch (memberName) {
                case "get", "orElse", "orElseGet" -> { return typeMapping.getOrDefault("T", "java.lang.Object"); }
                case "isPresent", "isEmpty" -> { return "boolean"; }
            }
        }

        if (className.contains("Result")) {
            switch (memberName) {
                case "unwrap", "get" -> { return typeMapping.getOrDefault("T", "java.lang.Object"); }
                case "unwrapError", "getError" -> { return typeMapping.getOrDefault("E", "java.lang.Object"); }
                case "isOk", "isErr", "isError" -> { return "boolean"; }
            }
        }

        if (className.contains("CompletableFuture") || className.contains("Future")) {
            switch (memberName) {
                case "join", "get" -> { return typeMapping.getOrDefault("T", "java.lang.Object"); }
                case "isDone", "isCompletedExceptionally", "isCancelled" -> { return "boolean"; }
            }
        }

        List<PsiElement> results = new ArrayList<>();
        OceanScopeDiscovery.resolveMemberChain(className, memberName, results, project, new HashSet<>(), context);

        // Overload resolution: if context is a call or part of a call, rank candidates
        OceanOverloadResolver.CallInfo callInfo = (context != null) ? OceanOverloadResolver.findCallInfo(context) : OceanOverloadResolver.CallInfo.notACall();
        if (callInfo.isCall && results.size() > 1 && project != null) {
            results = OceanOverloadResolver.filterAndRankCandidates(results, callInfo.argumentTypes, project);
        }

        for (PsiElement m : results) {
            if (m instanceof OceanMethodDeclaration) {
                String ret = OceanScopeDiscovery.getMethodReturnType(m);
                return substituteType(ret, typeMapping);
            } else if (m instanceof OceanAnnotationMemberDeclaration) {
                PsiElement t = findChildByRule(m, OceanParser.RULE_type);
                if (t != null) {
                    return substituteType(t.getText().trim(), typeMapping);
                }
            } else if (m instanceof PsiMethod jm) {
                PsiType rt = jm.getReturnType();
                if (rt != null) {
                    return substitutePsiType(rt, typeMapping);
                }
            }
            String symType = determineTypeFromSymbol(m);
            if (symType != null) {
                return substituteType(symType, typeMapping);
            }
        }

        // Check primary constructor parameters if it's a data class: e.g. data class Point(int x, int y) -> point.x
        if (project != null) {
            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, className);
            if (oceanCls instanceof OceanClassDeclaration) {
                for (OceanParameter param : PsiTreeUtil.findChildrenOfType(oceanCls, OceanParameter.class)) {
                    if (memberName.equals(param.getName())) {
                        String pType = determineTypeFromSymbol(param);
                        if (pType != null) {
                            return substituteType(pType, typeMapping);
                        }
                    }
                }
            }
        }

        // Check Java class directly
        if (project != null) {
            PsiClass javaCls = OceanScopeDiscovery.resolveJavaClass(project, className, context);
            if (javaCls != null) {
                PsiMethod[] methods = javaCls.findMethodsByName(memberName, true);
                List<PsiElement> methodList = new ArrayList<>(Arrays.asList(methods));
                if (callInfo.isCall && methodList.size() > 1) {
                    methodList = OceanOverloadResolver.filterAndRankCandidates(methodList, callInfo.argumentTypes, project);
                }
                for (PsiElement cand : methodList) {
                    if (cand instanceof PsiMethod jm) {
                        PsiType rt = jm.getReturnType();
                        if (rt != null) {
                            return substitutePsiType(rt, typeMapping);
                        }
                    }
                }
                PsiField field = javaCls.findFieldByName(memberName, true);
                if (field != null) {
                    return substitutePsiType(field.getType(), typeMapping);
                }
            }
        }

        return null;
    }

    private static String guessExpressionType(PsiElement element) {
        if (!isRule(element, OceanParser.RULE_expression) && !isRule(element, OceanParser.RULE_primary)) return null;

        String text = element.getText().trim();
        if ("OceanInput".equals(text) || text.startsWith("OceanInput(")) {
            return "java.util.Scanner";
        }

        // New Object
        PsiElement typeChild = findChildByRule(element, OceanParser.RULE_type);
        if (typeChild != null && (text.startsWith("new ") || text.startsWith("new\t"))) {
            return resolveDeclaredType(typeChild.getText().trim(), element.getProject(), element);
        }

        // Collection Literals
        if (isRule(element, OceanParser.RULE_primary) || isRule(element, OceanParser.RULE_expression)) {
            String collType = guessCollectionLiteralType(element, text);
            if (collType != null) return collType;
        }

        // Array / List / Map Index Access: receiver[index]
        if (isRule(element, OceanParser.RULE_expression) && hasChildToken(element, OceanLexer.LBRACK)) {
            PsiElement lbrack = findTokenChild(element, OceanLexer.LBRACK);
            PsiElement receiver = null;
            if (lbrack != null) {
                receiver = prevVisibleSibling(lbrack);
            }
            if (isRule(receiver, OceanParser.RULE_expression) || isRule(receiver, OceanParser.RULE_primary)) {
                String receiverType = guessType(receiver);
                if (receiverType != null) {
                    String indexAccessType = resolveIndexAccessType(receiverType);
                    if (indexAccessType != null) return indexAccessType;
                }
            }
        }

        // Boolean Comparison / Logical Expressions
        if (hasChildToken(element, OceanLexer.EQUALS, OceanLexer.NOT_EQUALS, OceanLexer.LT, OceanLexer.GT, 
                OceanLexer.LESS_EQUAL, OceanLexer.GREATER_EQUAL, OceanLexer.LOGICAL_AND, OceanLexer.LOGICAL_OR, OceanLexer.INSTANCEOF)) {
            return "boolean";
        }

        // Member Call: receiver.member or receiver?.member
        PsiElement dot = findTokenChild(element, OceanLexer.DOT);
        if (dot == null) dot = findTokenChild(element, OceanLexer.SAFE_DOT);
        if (dot != null) {
            PsiElement receiver = prevVisibleSibling(dot);
            PsiElement member = nextVisibleSibling(dot);
            if (receiver != null && member != null) {
                String memberName = member.getText();
                int callStart = memberName.indexOf('(');
                if (callStart >= 0) memberName = memberName.substring(0, callStart).trim();
                if (!memberName.isEmpty()) {
                    String receiverType = guessType(receiver);
                    if (receiverType != null) {
                        String memberType = findMemberType(receiverType, memberName, element.getProject(), element);
                        if (memberType != null && hasChildToken(element, OceanLexer.SAFE_DOT)) {
                            if (!memberType.endsWith("?") && !"void".equals(memberType)) {
                                memberType = memberType + "?";
                            }
                        }
                        return memberType;
                    }
                }
            }
        }

        // Standalone Direct Function / Method Call: methodName(args)
        if (hasChildToken(element, OceanLexer.LPAREN)) {
            PsiElement lparen = findTokenChild(element, OceanLexer.LPAREN);
            if (lparen != null && lparen != element.getFirstChild()) {
                PsiElement funcNameNode = prevVisibleSibling(lparen);
                if (funcNameNode != null) {
                    String funcName = funcNameNode.getText().trim();
                    int lastDot = funcName.lastIndexOf('.');
                    if (lastDot >= 0) funcName = funcName.substring(lastDot + 1).trim();
                    int lt = funcName.indexOf('<');
                    if (lt >= 0) funcName = funcName.substring(0, lt).trim();

                    if (!funcName.isEmpty() && !"if".equals(funcName) && !"while".equals(funcName) && !"for".equals(funcName) && !"switch".equals(funcName) && !"catch".equals(funcName) && !"synchronized".equals(funcName) && !"lock".equals(funcName)) {
                        List<PsiElement> symbols = OceanScopeDiscovery.findVisibleSymbols(element);
                        for (PsiElement sym : symbols) {
                            if (sym instanceof PsiNamedElement pNamed && funcName.equals(pNamed.getName())) {
                                String t = determineTypeFromSymbol(sym);
                                if (t != null && !"java.lang.Object".equals(t)) return t;
                            }
                        }

                        // Also search containing class methods directly if not found in local visible symbols
                        PsiElement cur = element.getParent();
                        while (cur != null) {
                            if (isRule(cur, OceanParser.RULE_classDeclaration) || cur instanceof OceanClassDeclaration) {
                                for (PsiElement child : cur.getChildren()) {
                                    if (child instanceof OceanMethodDeclaration || isRule(child, OceanParser.RULE_methodDeclaration)) {
                                        if (child instanceof PsiNamedElement pNamed && funcName.equals(pNamed.getName())) {
                                            String t = determineTypeFromSymbol(child);
                                            if (t != null && !"java.lang.Object".equals(t)) return t;
                                        }
                                        PsiElement mNameNode = findChildByRule(child, OceanParser.RULE_anyId);
                                        if (mNameNode != null && funcName.equals(mNameNode.getText().trim())) {
                                            String t = determineTypeFromSymbol(child);
                                            if (t != null && !"java.lang.Object".equals(t)) return t;
                                        }
                                    }
                                }
                                break;
                            }
                            cur = cur.getParent();
                        }
                    }
                }
            }
        }

        // Cast expression: (Type) expr
        if (isRule(element, OceanParser.RULE_expression) && hasChildToken(element, OceanLexer.LPAREN)) {
            PsiElement lparen = findTokenChild(element, OceanLexer.LPAREN);
            if (lparen != null && lparen == element.getFirstChild()) {
                PsiElement castTypeNode = findChildByRule(element, OceanParser.RULE_type);
                PsiElement rparen = findTokenChild(element, OceanLexer.RPAREN);
                if (castTypeNode != null && rparen != null && castTypeNode.getTextOffset() < rparen.getTextOffset()) {
                    return resolveDeclaredType(castTypeNode.getText(), element.getProject());
                }
            }
        }

        // Parenthesized expression: (expr)
        if (hasChildToken(element, OceanLexer.LPAREN) && hasChildToken(element, OceanLexer.RPAREN)) {
            PsiElement lparen = findTokenChild(element, OceanLexer.LPAREN);
            PsiElement rparen = findTokenChild(element, OceanLexer.RPAREN);
            if (lparen != null && rparen != null && lparen == element.getFirstChild() && rparen == element.getLastChild()) {
                for (PsiElement child : element.getChildren()) {
                    if ((isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary)) && child != element) {
                        String innerType = guessType(child);
                        if (innerType != null && !"java.lang.Object".equals(innerType)) return innerType;
                    }
                }
            }
        }

        // Ternary expression: cond ? expr1 : expr2
        if (hasChildToken(element, OceanLexer.QUESTION) && hasChildToken(element, OceanLexer.COLON)) {
            PsiElement colon = findTokenChild(element, OceanLexer.COLON);
            PsiElement trueExpr = null;
            PsiElement falseExpr = null;
            if (colon != null) {
                trueExpr = prevVisibleSibling(colon);
                falseExpr = nextVisibleSibling(colon);
            }
            String t1 = trueExpr != null ? guessType(trueExpr) : null;
            String t2 = falseExpr != null ? guessType(falseExpr) : null;
            boolean trueNull = trueExpr != null && "null".equals(trueExpr.getText().trim());
            boolean falseNull = falseExpr != null && "null".equals(falseExpr.getText().trim());

            if (trueNull && t2 != null) {
                return t2.endsWith("?") ? t2 : (t2 + "?");
            }
            if (falseNull && t1 != null) {
                return t1.endsWith("?") ? t1 : (t1 + "?");
            }
            if (t1 != null && t2 != null) {
                String p = getPromotedNumericType(t1, t2);
                if (p != null) return p;
                if (t1.equals(t2)) return t1;
                boolean anyNull = t1.endsWith("?") || t2.endsWith("?");
                String s1 = OceanScopeDiscovery.normalizeTypeName(t1);
                String s2 = OceanScopeDiscovery.normalizeTypeName(t2);
                if (OceanScopeDiscovery.isSubclassOf(element.getProject(), s1, s2)) {
                    return anyNull && !s2.endsWith("?") ? s2 + "?" : s2;
                }
                if (OceanScopeDiscovery.isSubclassOf(element.getProject(), s2, s1)) {
                    return anyNull && !s1.endsWith("?") ? s1 + "?" : s1;
                }
            }
            if (t1 != null && !"java.lang.Object".equals(t1)) return t1;
            if (t2 != null) return t2;
        }

        // Null-coalescing expression: expr1 ?? expr2
        if (hasChildToken(element, OceanLexer.NULL_COALESCE)) {
            PsiElement nc = findTokenChild(element, OceanLexer.NULL_COALESCE);
            PsiElement left = null, right = null;
            if (nc != null) {
                left = prevVisibleSibling(nc);
                right = nextVisibleSibling(nc);
            }
            if (left != null && right != null) {
                String t1 = guessType(left);
                String t2 = guessType(right);
                if (t1 != null) {
                    String unwrapped = t1.endsWith("?") ? t1.substring(0, t1.length() - 1).trim() : t1;
                    if (t2 != null && !t2.endsWith("?") && !"null".equals(t2)) {
                        return unwrapped;
                    }
                    return t2 != null && t2.endsWith("?") ? unwrapped + "?" : unwrapped;
                }
                if (t2 != null) return t2;
            }
        }

        // String Concatenation & Arithmetic (+, -, *, /, %)
        if (hasChildToken(element, OceanLexer.PLUS, OceanLexer.MINUS, OceanLexer.STAR, OceanLexer.SLASH, OceanLexer.PERCENT)) {
            List<PsiElement> subExprs = new ArrayList<>();
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary)) {
                    subExprs.add(child);
                }
            }
            if (subExprs.size() >= 2) {
                String tLeft = guessType(subExprs.get(0));
                String tRight = guessType(subExprs.get(1));
                // String concatenation with PLUS
                if (hasChildToken(element, OceanLexer.PLUS)) {
                    String leftText = subExprs.get(0).getText().trim();
                    String rightText = subExprs.get(1).getText().trim();
                    if ("java.lang.String".equals(tLeft) || "java.lang.String".equals(tRight) ||
                        (tLeft != null && tLeft.contains("String")) || (tRight != null && tRight.contains("String")) ||
                        leftText.startsWith("$\"") || rightText.startsWith("$\"") ||
                        leftText.startsWith("\"") || rightText.startsWith("\"")) {
                        return "java.lang.String";
                    }
                }
                // Numeric promotion
                if ("double".equals(tLeft) || "double".equals(tRight)) return "double";
                if ("float".equals(tLeft) || "float".equals(tRight)) return "float";
                if ("long".equals(tLeft) || "long".equals(tRight)) return "long";
                if ("int".equals(tLeft) || "int".equals(tRight) ||
                    "short".equals(tLeft) || "short".equals(tRight) ||
                    "byte".equals(tLeft) || "byte".equals(tRight) ||
                    "char".equals(tLeft) || "char".equals(tRight)) {
                    return "int";
                }
            }
        }

        // Await expression: await asyncTask()
        if (hasChildToken(element, OceanLexer.AWAIT)) {
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary)) {
                    String taskType = guessType(child);
                    if (taskType != null) {
                        List<String> typeArgs = extractTypeArguments(taskType);
                        if (!typeArgs.isEmpty()) {
                            return typeArgs.get(0);
                        }
                        return "java.lang.Object";
                    }
                }
            }
        }

        // Unary expressions: !expr -> boolean, -expr / ~expr -> same type
        if (hasChildToken(element, OceanLexer.BANG)) {
            return "boolean";
        }
        if (hasChildToken(element, OceanLexer.TILDE, OceanLexer.AMP, OceanLexer.PIPE, OceanLexer.CARET, OceanLexer.LSHIFT, OceanLexer.RSHIFT, OceanLexer.URSHIFT)) {
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary)) {
                    String t = guessType(child);
                    if (t != null) return t;
                }
            }
            return "int";
        }

        return null;
    }

    private static String extractEnclosingDeclarationType(PsiElement element) {
        if (element == null) return null;
        PsiElement decl = element.getParent();
        while (decl != null && !isRule(decl, OceanParser.RULE_variableDeclaration) && !isRule(decl, OceanParser.RULE_fieldDeclaration)) {
            decl = decl.getParent();
        }
        if (decl != null) {
            PsiElement tNode = findChildByRule(decl, OceanParser.RULE_type);
            if (tNode != null) {
                String resolved = resolveDeclaredType(tNode.getText(), element.getProject());
                if (resolved != null && resolved.contains("<") && resolved.contains(">")) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private static String guessCollectionLiteralType(PsiElement element, String text) {
        if (text.startsWith("#{")) {
            List<String> elemTypes = collectLiteralElementTypes(element, text, "#{", "}");
            if (!elemTypes.isEmpty()) {
                String first = elemTypes.get(0);
                boolean allSame = elemTypes.stream().allMatch(first::equals);
                if (allSame) {
                    return "ocean.stdlib.OceanSet<" + boxType(first) + ">";
                }
            } else {
                String declType = extractEnclosingDeclarationType(element);
                if (declType != null && declType.contains("Set")) {
                    return declType;
                }
            }
            return "ocean.stdlib.OceanSet";
        }

        if (text.startsWith("{") && (text.contains(":") || text.equals("{}"))) {
            Map.Entry<String, String> kv = collectMapEntryTypes(element, text);
            if (kv != null && kv.getKey() != null && kv.getValue() != null) {
                return "ocean.stdlib.OceanMap<" + boxType(kv.getKey()) + ", " + boxType(kv.getValue()) + ">";
            }
            String declType = extractEnclosingDeclarationType(element);
            if (declType != null && declType.contains("Map")) {
                return declType;
            }
            return "ocean.stdlib.OceanMap";
        }

        if (text.startsWith("[") && text.endsWith("]")) {
            List<String> elemTypes = collectLiteralElementTypes(element, text, "[", "]");
            if (elemTypes.isEmpty()) {
                // If enclosed in a typed declaration e.g. OceanList<String> list = []
                String declType = extractEnclosingDeclarationType(element);
                return Objects.requireNonNullElse(declType, "ocean.stdlib.OceanList");
            }
            boolean allInt = elemTypes.stream().allMatch("int"::equals);
            if (allInt) return "ocean.stdlib.OceanIntList";

            boolean allDoubleOrInt = elemTypes.stream().allMatch(t -> "double".equals(t) || "int".equals(t));
            boolean anyDouble = elemTypes.stream().anyMatch("double"::equals);
            if (allDoubleOrInt && anyDouble) return "ocean.stdlib.OceanDoubleList";

            boolean allLong = elemTypes.stream().allMatch("long"::equals);
            if (allLong) return "ocean.stdlib.OceanLongList";

            boolean allFloat = elemTypes.stream().allMatch("float"::equals);
            if (allFloat) return "ocean.stdlib.OceanFloatList";

            boolean allBool = elemTypes.stream().allMatch(t -> "boolean".equals(t) || "bool".equals(t));
            if (allBool) return "ocean.stdlib.OceanBooleanList";

            boolean allChar = elemTypes.stream().allMatch("char"::equals);
            if (allChar) return "ocean.stdlib.OceanCharList";

            boolean allByte = elemTypes.stream().allMatch("byte"::equals);
            if (allByte) return "ocean.stdlib.OceanByteList";

            boolean allShort = elemTypes.stream().allMatch("short"::equals);
            if (allShort) return "ocean.stdlib.OceanShortList";

            String first = elemTypes.get(0);
            boolean allSame = elemTypes.stream().allMatch(first::equals);
            if (allSame) {
                return "ocean.stdlib.OceanList<" + boxType(first) + ">";
            }
            return "ocean.stdlib.OceanList";
        }

        return null;
    }

    private static List<String> collectLiteralElementTypes(PsiElement element, String text, String prefix, String suffix) {
        List<String> types = new ArrayList<>();
        if (element != null) {
            PsiElement argList = findChildByRule(element, OceanParser.RULE_argumentList);
            if (argList == null) {
                PsiElement prim = findChildByRule(element, OceanParser.RULE_primary);
                if (prim != null) argList = findChildByRule(prim, OceanParser.RULE_argumentList);
            }
            if (argList != null) {
                for (PsiElement child : argList.getChildren()) {
                    if (isRule(child, OceanParser.RULE_argument)) {
                        PsiElement expr = findChildByRule(child, OceanParser.RULE_expression);
                        if (expr != null) {
                            String t = guessType(expr);
                            if (t != null) types.add(t);
                        }
                    }
                }
            }
        }
        if (types.isEmpty() && text.startsWith(prefix) && text.endsWith(suffix)) {
            String inner = text.substring(prefix.length(), text.length() - suffix.length()).trim();
            if (!inner.isEmpty()) {
                List<String> parts = splitGenericArguments(inner);
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("\"") && part.endsWith("\"")) types.add("java.lang.String");
                    else if (part.equals("true") || part.equals("false")) types.add("boolean");
                    else if (part.startsWith("'") && part.endsWith("'")) types.add("char");
                    else if (part.matches("-?\\d+\\.\\d+[dD]?")) types.add("double");
                    else if (part.matches("-?\\d+\\.\\d+[fF]")) types.add("float");
                    else if (part.matches("-?\\d+[lL]")) types.add("long");
                    else if (part.matches("-?\\d+")) types.add("int");
                }
            }
        }
        return types;
    }

    private static Map.Entry<String, String> collectMapEntryTypes(PsiElement element, String text) {
        if (element != null) {
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_mapEntry)) {
                    List<PsiElement> exprs = new ArrayList<>();
                    for (PsiElement ec : child.getChildren()) {
                        if (isRule(ec, OceanParser.RULE_expression)) exprs.add(ec);
                    }
                    if (exprs.size() >= 2) {
                        String k = guessType(exprs.get(0));
                        String v = guessType(exprs.get(1));
                        if (k != null && v != null) {
                            return Map.entry(k, v);
                        }
                    }
                }
            }
        }
        if (text.startsWith("{") && text.endsWith("}") && text.contains(":")) {
            String inner = text.substring(1, text.length() - 1).trim();
            int colon = inner.indexOf(':');
            if (colon > 0) {
                String kStr = inner.substring(0, colon).trim();
                String vStr = inner.substring(colon + 1).trim();
                int comma = vStr.indexOf(',');
                if (comma > 0) vStr = vStr.substring(0, comma).trim();
                String k = kStr.startsWith("\"") ? "java.lang.String" : (kStr.matches("-?\\d+") ? "int" : null);
                String v = vStr.startsWith("\"") ? "java.lang.String" : (vStr.matches("-?\\d+") ? "int" : null);
                if (k != null && v != null) return Map.entry(k, v);
            }
        }
        return null;
    }

    private static PsiElement findInitializerExpression(PsiElement declaration) {
        if (declaration == null) return null;
        
        // 1. Direct variableDeclarator or through variableDeclaration/fieldDeclaration
        List<PsiElement> targets = new ArrayList<>();
        if (isRule(declaration, OceanParser.RULE_variableDeclarator) || declaration instanceof OceanVariableDeclarator) {
            targets.add(declaration);
        } else {
            for (PsiElement child : declaration.getChildren()) {
                if (isRule(child, OceanParser.RULE_variableDeclarator) || child instanceof OceanVariableDeclarator) {
                    targets.add(child);
                }
            }
            if (targets.isEmpty()) {
                targets.add(declaration);
            }
        }

        for (PsiElement target : targets) {
            boolean afterAssign = false;
            for (PsiElement child : target.getChildren()) {
                if ("=".equals(child.getText()) || 
                    (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.ASSIGN)) {
                    afterAssign = true;
                    continue;
                }
                if (afterAssign && isRule(child, OceanParser.RULE_expression)) return child;
            }
        }
        return null;
    }

    private static String checkLiteralType(PsiElement element) {
        if (element == null) return null;
        String t = element.getText().trim();
        if (t.isEmpty()) return null;

        // String & Interpolated String
        if ((t.startsWith("\"") && t.endsWith("\"")) ||
            t.startsWith("\"\"\"") ||
            t.startsWith("$\"") ||
            t.startsWith("$\"\"\"")) {
            return "java.lang.String";
        }
        if ("true".equals(t) || "false".equals(t)) {
            return "boolean";
        }
        if (t.startsWith("'") && t.endsWith("'") && t.length() >= 3) {
            return "char";
        }

        // Token-based check if available
        if (element.getNode() != null) {
            IElementType type = element.getNode().getElementType();
            if (type instanceof TokenIElementType ti) {
                int tt = ti.getANTLRTokenType();
                if (tt == OceanLexer.NUMBER) {
                    if (t.endsWith("L") || t.endsWith("l")) return "long";
                    if (t.endsWith("F") || t.endsWith("f")) return "float";
                    if (t.endsWith("D") || t.endsWith("d") || t.contains(".")) return "double";
                    return "int";
                }
                if (tt == OceanLexer.CHAR_LITERAL) return "char";
                if (tt == OceanLexer.STRING_LITERAL || tt == OceanLexer.MULTILINE_STRING || tt == OceanLexer.INTERPOLATED_STRING || tt == OceanLexer.MULTILINE_INTERPOLATED_STRING) return "java.lang.String";
                if (tt == OceanLexer.TRUE || tt == OceanLexer.FALSE) return "boolean";
            }
        }

        // Numeric checks with underscore support
        String cleanNum = t.replace("_", "");
        if (cleanNum.matches("^-?0[xX][0-9a-fA-F]+[lL]?$") || cleanNum.matches("^-?0[bB][01]+[lL]?$")) {
            return cleanNum.endsWith("L") || cleanNum.endsWith("l") ? "long" : "int";
        }
        if (cleanNum.matches("^-?\\d+[lL]$")) return "long";
        if (cleanNum.matches("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fF]$")) return "float";
        if (cleanNum.matches("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?[dD]$") ||
            cleanNum.matches("^-?\\d+\\.\\d+([eE][+-]?\\d+)?$") ||
            cleanNum.matches("^-?\\d+[eE][+-]?\\d+$")) return "double";
        if (cleanNum.matches("^-?\\d+$")) return "int";

        return null;
    }

    private static boolean isRule(PsiElement e, int ruleIdx) {
        return e != null && e.getNode() != null && e.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIdx;
    }

    private static PsiElement findChildByRule(PsiElement parent, int ruleIdx) {
        if (parent == null) return null;
        for (PsiElement child : parent.getChildren()) {
            if (isRule(child, ruleIdx)) return child;
        }
        return null;
    }

    private static PsiElement findTokenChild(PsiElement element, int tokenType) {
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == tokenType) return child;
        }
        return null;
    }

    private static boolean hasChildToken(PsiElement element, int... tokenTypes) {
        Set<Integer> tokens = new HashSet<>();
        for (int t : tokenTypes) tokens.add(t);
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && tokens.contains(ti.getANTLRTokenType())) {
                return true;
            }
        }
        return false;
    }

    private static PsiElement prevVisibleSibling(PsiElement element) {
        PsiElement sibling = element.getPrevSibling();
        while (sibling != null && sibling.getText().trim().isEmpty()) sibling = sibling.getPrevSibling();
        return sibling;
    }

    private static PsiElement nextVisibleSibling(PsiElement element) {
        PsiElement sibling = element.getNextSibling();
        while (sibling != null && sibling.getText().trim().isEmpty()) sibling = sibling.getNextSibling();
        return sibling;
    }

    private static String findEnclosingClassName(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration)) {
                PsiElement extReceiver = OceanScopeDiscovery.getExtensionReceiverTypeNode(cur);
                if (extReceiver != null) {
                    return extReceiver.getText().trim();
                }
            }
            if (isRule(cur, OceanParser.RULE_classDeclaration) || isRule(cur, OceanParser.RULE_interfaceDeclaration) || isRule(cur, OceanParser.RULE_enumDeclaration) || isRule(cur, OceanParser.RULE_annotationDeclaration)) {
                if (cur instanceof PsiNamedElement named) {
                    String n = named.getName();
                    if (n != null && !n.isEmpty()) return n;
                }
                for (PsiElement child : cur.getChildren()) {
                    if (child instanceof OceanNamedElement named) {
                        String n = named.getName();
                        if (n != null && !n.isEmpty()) return n;
                    }
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static String findEnclosingSuperClassName(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_classDeclaration) || isRule(cur, OceanParser.RULE_interfaceDeclaration)) {
                String ext = OceanScopeDiscovery.extractExtendsName(cur);
                if (ext != null && !ext.isEmpty()) return ext;
                List<String> ifaces = OceanScopeDiscovery.extractImplementsNames(cur);
                if (!ifaces.isEmpty()) return ifaces.get(0);
                return "java.lang.Object";
            }
            cur = cur.getParent();
        }
        return "java.lang.Object";
    }

    public static boolean isInterpolatedString(PsiElement element) {
        if (element == null || element.getNode() == null) return false;
        if (element.getNode().getElementType() instanceof TokenIElementType ti) {
            int tt = ti.getANTLRTokenType();
            return tt == OceanLexer.INTERPOLATED_STRING || tt == OceanLexer.MULTILINE_INTERPOLATED_STRING;
        }
        return false;
    }

    public static boolean interpolatedStringContainsVariable(String text, String varName) {
        if (text == null || varName == null || varName.isEmpty()) return false;
        if (!text.startsWith("$\"") && !text.startsWith("$\"\"\"")) return false;

        Pattern varPattern = Pattern.compile("(?<!\\.)\\b" + Pattern.quote(varName) + "\\b");

        int i = text.startsWith("$\"\"\"") ? 4 : 2;
        int end = text.startsWith("$\"\"\"") ? text.length() - 3 : text.length() - 1;

        while (i < end) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2; // skip escape sequence
                continue;
            }
            if (c == '{') {
                if (i + 1 < end && text.charAt(i + 1) == '{') {
                    i += 2; // escaped '{{'
                    continue;
                }
                // Start of an interpolation expression
                int braceDepth = 1;
                int startExpr = i + 1;
                i++;
                while (i < end && braceDepth > 0) {
                    char ec = text.charAt(i);
                    if (ec == '\\') {
                        i += 2;
                        continue;
                    }
                    if (ec == '{') braceDepth++;
                    else if (ec == '}') braceDepth--;
                    if (braceDepth == 0) break;
                    i++;
                }
                int endExpr = i;
                if (startExpr < endExpr) {
                    String expr = text.substring(startExpr, endExpr);
                    if (varPattern.matcher(expr).find()) {
                        return true;
                    }
                }
                if (i < end && text.charAt(i) == '}') {
                    i++;
                }
                continue;
            }
            i++;
        }
        return false;
    }

    private static String getPromotedNumericType(String a, String b) {
        if (a == null || b == null) return null;
        String na = normalizePrimitiveNumeric(a);
        String nb = normalizePrimitiveNumeric(b);
        if (na == null || nb == null) return null;
        if ("double".equals(na) || "double".equals(nb)) return "double";
        if ("float".equals(na) || "float".equals(nb)) return "float";
        if ("long".equals(na) || "long".equals(nb)) return "long";
        return "int";
    }

    private static String normalizePrimitiveNumeric(String t) {
        if (t == null) return null;
        String s = t.trim();
        if (s.endsWith("?")) s = s.substring(0, s.length() - 1).trim();
        if (s.startsWith("java.lang.")) s = s.substring("java.lang.".length());
        return switch (s.toLowerCase()) {
            case "double" -> "double";
            case "float" -> "float";
            case "long" -> "long";
            case "int", "integer", "short", "byte", "char", "character" -> "int";
            default -> null;
        };
    }

    public static boolean isAssignable(String targetType, String sourceType, Project project) {
        if (targetType == null || sourceType == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(targetType);
        String s = OceanScopeDiscovery.normalizeTypeName(sourceType);
        if (t.equals(s)) return true;
        if ("Object".equals(t) || "java.lang.Object".equals(t)) return true;
        if ("null".equals(s)) return true;

        // Boxed / Primitive equivalence
        if (OceanOverloadResolver.isBoxedEquivalent(s, t) || OceanOverloadResolver.isBoxedEquivalent(t, s)) {
            return true;
        }
        // Primitive widening
        if (OceanOverloadResolver.isPrimitiveWidening(s, t)) {
            return true;
        }

        // Subclass / interface implementation
        return OceanScopeDiscovery.isSubclassOf(project, s, t);
    }
}
