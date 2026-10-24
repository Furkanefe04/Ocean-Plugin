package ocean.plugin;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.IncorrectOperationException;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.*;

/**
 * IntentionAction (💡 bulb) that appears when the caret is inside a class
 * that extends an abstract Ocean class or implements a Java interface, and
 * there are missing method implementations. Inserts Ocean-syntax stubs.
 */
public class OceanImplementMethodsAction implements IntentionAction {

    record MethodStub(String name, String returnType, String params) {}

    @NotNull @Override public String getText()       { return "Implement required methods"; }
    @NotNull @Override public String getFamilyName() { return "Ocean"; }
    @Override public boolean startInWriteAction()    { return false; }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (editor == null || !(file instanceof OceanFile)) return false;
        PsiElement cls = findEnclosingClass(editor, file);
        if (cls == null) return false;
        return !collectMissingMethods(project, cls).isEmpty();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        PsiElement cls = findEnclosingClass(editor, file);
        if (cls == null) return;

        List<MethodStub> missing = collectMissingMethods(project, cls);
        if (missing.isEmpty()) return;

        boolean hasBraces = hasTokenChild(cls, OceanLexer.LBRACE);

        StringBuilder sb = new StringBuilder();
        if (hasBraces) {
            for (MethodStub s : missing) {
                String ret = s.returnType() != null && !s.returnType().isBlank() ? s.returnType() + " " : "void ";
                sb.append("\n\n    @Override\n    ")
                  .append("public ").append(ret).append("function ").append(s.name())
                  .append("(").append(s.params()).append(") {\n")
                  .append("        // TODO: implement\n")
                  .append("    }");
            }
            int insertOffset = cls.getTextRange().getEndOffset() - 1;
            String code = sb.toString();
            WriteCommandAction.runWriteCommandAction(project, () ->
                editor.getDocument().insertString(insertOffset, code));
        } else {
            sb.append(" {\n");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) sb.append("\n");
                MethodStub s = missing.get(i);
                String ret = s.returnType() != null && !s.returnType().isBlank() ? s.returnType() + " " : "void ";
                sb.append("    @Override\n")
                  .append("    public ").append(ret).append("function ").append(s.name())
                  .append("(").append(s.params()).append(") {\n")
                  .append("        // TODO: implement\n")
                  .append("    }\n");
            }
            sb.append("}");
            int insertOffset = cls.getTextRange().getEndOffset();
            String code = sb.toString();
            WriteCommandAction.runWriteCommandAction(project, () ->
                editor.getDocument().insertString(insertOffset, code));
        }
    }

    // =========================================================================
    // Core logic
    // =========================================================================

    List<MethodStub> collectMissingMethods(Project project, PsiElement cls) {
        List<String> parentNames = extractParentNames(cls);
        if (parentNames.isEmpty()) return List.of();

        Set<String> implementedExact = new HashSet<>();
        Set<String> implementedCounts = new HashSet<>();
        collectImplemented(project, cls, cls, implementedExact, implementedCounts, new HashSet<>());

        Set<String> seen = new LinkedHashSet<>();
        List<MethodStub> result = new ArrayList<>();

        for (String parent : parentNames) {
            for (MethodStub stub : resolveRequiredMethods(project, parent, cls)) {
                String name = stub.name();
                String exactSig = name + extractParamTypes(stub.params());
                int paramCount = countParams(stub.params());
                String countSig = name + "/" + paramCount;

                boolean isImplemented = implementedExact.contains(exactSig) || implementedCounts.contains(countSig);
                if (!isImplemented && seen.add(exactSig)) {
                    result.add(stub);
                }
            }
        }
        return result;
    }

    private int countParams(String paramsStr) {
        if (paramsStr == null || paramsStr.isBlank()) return 0;
        return splitParams(paramsStr).size();
    }

    private int countParamsFromSig(String sig) {
        if (sig == null || sig.equals("()")) return 0;
        String inside = sig.substring(1, sig.length() - 1);
        if (inside.isBlank()) return 0;
        return splitParams(inside).size();
    }

    private List<String> splitParams(String paramsStr) {
        if (paramsStr == null || paramsStr.isBlank()) return List.of();
        List<String> list = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : paramsStr.toCharArray()) {
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                if (!current.isEmpty()) {
                    list.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty() && !current.toString().trim().isEmpty()) {
            list.add(current.toString().trim());
        }
        return list;
    }

    /** Extracts class names from extends/implements or anonymous class creation using AST traversal. */
    private List<String> extractParentNames(PsiElement cls) {
        if (cls == null) return List.of();
        List<String> names = new ArrayList<>();

        // 1. AST Traversal for classDeclaration or interfaceDeclaration
        boolean afterExtends = false;
        boolean afterImplements = false;
        for (PsiElement c : cls.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti) {
                int tt = ti.getANTLRTokenType();
                if (tt == OceanLexer.EXTENDS) {
                    afterExtends = true;
                    afterImplements = false;
                    continue;
                } else if (tt == OceanLexer.IMPLEMENTS) {
                    afterImplements = true;
                    afterExtends = false;
                    continue;
                } else if (tt == OceanLexer.LBRACE) {
                    break;
                }
            }
            if (afterExtends) {
                if (isRuleIndex(c, OceanParser.RULE_type)) {
                    String n = OceanScopeDiscovery.normalizeTypeName(c.getText().trim());
                    if (n != null && !n.isEmpty()) names.add(n);
                } else if (isRuleIndex(c, OceanParser.RULE_typeList)) {
                    for (PsiElement tc : c.getChildren()) {
                        if (isRuleIndex(tc, OceanParser.RULE_type)) {
                            String n = OceanScopeDiscovery.normalizeTypeName(tc.getText().trim());
                            if (n != null && !n.isEmpty()) names.add(n);
                        }
                    }
                }
            } else if (afterImplements) {
                if (isRuleIndex(c, OceanParser.RULE_typeList)) {
                    for (PsiElement tc : c.getChildren()) {
                        if (isRuleIndex(tc, OceanParser.RULE_type)) {
                            String n = OceanScopeDiscovery.normalizeTypeName(tc.getText().trim());
                            if (n != null && !n.isEmpty()) names.add(n);
                        }
                    }
                } else if (isRuleIndex(c, OceanParser.RULE_type)) {
                    String n = OceanScopeDiscovery.normalizeTypeName(c.getText().trim());
                    if (n != null && !n.isEmpty()) names.add(n);
                }
            }
        }

        // 2. Anonymous class creation: new Parent(...) { ... }
        if (isRuleIndex(cls, OceanParser.RULE_expression) && hasTokenChild(cls, OceanLexer.NEW)) {
            PsiElement typeNode = directChildByRuleIndex(cls, OceanParser.RULE_type);
            if (typeNode != null) {
                String n = OceanScopeDiscovery.normalizeTypeName(typeNode.getText().trim());
                if (n != null && !n.isEmpty()) names.add(n);
            }
        }

        if (!names.isEmpty()) return names;

        // 3. Fallback to header text parsing
        return extractParentNamesFromText(cls.getText());
    }

    private List<String> extractParentNamesFromText(String classText) {
        int openBrace = classText.indexOf('{');
        String header = (openBrace > 0) ? classText.substring(0, openBrace) : classText;

        List<String> names = new ArrayList<>();
        Matcher ext = Pattern.compile("\\bextends\\s+([\\w\\.<>]+)").matcher(header);
        if (ext.find()) {
            names.add(OceanScopeDiscovery.normalizeTypeName(ext.group(1)));
        }

        Matcher imp = Pattern.compile("\\bimplements\\s+([^\\{]+)").matcher(header.trim());
        if (imp.find()) {
            for (String s : splitParams(imp.group(1))) {
                String t = OceanScopeDiscovery.normalizeTypeName(s);
                if (t != null && !t.isEmpty()) names.add(t);
            }
        }

        Matcher newObj = Pattern.compile("\\bnew\\s+([\\w\\.<>]+)").matcher(header);
        if (newObj.find()) {
            names.add(OceanScopeDiscovery.normalizeTypeName(newObj.group(1)));
        }

        return names;
    }

    /** Collects method/constructor signatures already defined in the class and its superclasses. */
    private void collectImplemented(Project project, PsiElement cls, PsiElement context, Set<String> exactSigs, Set<String> countSigs, Set<String> visited) {
        if (cls == null) return;

        for (PsiElement child : cls.getChildren()) {
            if (isRuleIndex(child, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement member : child.getChildren()) {
                    if (isRuleIndex(member, OceanParser.RULE_methodDeclaration)
                            || isRuleIndex(member, OceanParser.RULE_constructorDeclaration)) {
                        if (!isOceanAbstractMethod(member)) {
                            String mName = null;
                            if (member instanceof PsiNamedElement pNamed && pNamed.getName() != null) {
                                mName = pNamed.getName();
                            } else {
                                PsiElement anyId = directChildByRuleIndex(member, OceanParser.RULE_anyId);
                                if (anyId != null) mName = anyId.getText().trim();
                            }
                            if (mName != null && !mName.isEmpty()) {
                                String paramSig = getParamTypesSignature(member);
                                exactSigs.add(mName + paramSig);
                                int pCount = countParamsFromSig(paramSig);
                                countSigs.add(mName + "/" + pCount);
                            }
                        }
                    }
                }
            }
        }

        // Recursively traverse superclasses
        String headerText = getClassHeader(cls);
        String extName = extractExtendsNameFromHeader(headerText);
        if (extName == null) {
            Matcher newMatcher = Pattern.compile("\\bnew\\s+([\\w\\.<>]+)").matcher(headerText);
            if (newMatcher.find()) {
                extName = newMatcher.group(1).replaceAll("<[^>]*>", "").trim();
            }
        }
        if (extName != null && visited.add(extName)) {
            PsiElement oceanSuper = findOceanClassInContextOrProject(project, extName, context);
            if (oceanSuper != null) {
                collectImplemented(project, oceanSuper, context, exactSigs, countSigs, visited);
            } else {
                PsiClass javaSuper = resolveJavaClassInContext(project, extName, context);
                if (javaSuper != null) {
                    for (PsiMethod jm : javaSuper.getAllMethods()) {
                        if (!jm.isConstructor() && !jm.hasModifierProperty(PsiModifier.ABSTRACT)) {
                            exactSigs.add(jm.getName() + getJavaMethodParamSig(jm));
                            countSigs.add(jm.getName() + "/" + jm.getParameterList().getParametersCount());
                        }
                    }
                }
            }
        }
    }

    private String extractExtendsNameFromHeader(String header) {
        Matcher ext = Pattern.compile("\\bextends\\s+([\\w\\.<>]+)").matcher(header);
        if (ext.find()) {
            return ext.group(1).replaceAll("<[^>]*>", "").trim();
        }
        return null;
    }

    private String getJavaMethodParamSig(PsiMethod method) {
        StringBuilder sb = new StringBuilder("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(javaTypeToOcean(params[i].getType()));
        }
        sb.append(")");
        return sb.toString();
    }

    private String getParamTypesSignature(PsiElement method) {
        PsiElement pl = directChildByRuleIndex(method, OceanParser.RULE_parameterList);
        if (pl == null) return "()";
        List<String> types = new ArrayList<>();
        for (PsiElement c : pl.getChildren()) {
            if (isRuleIndex(c, OceanParser.RULE_parameter)) {
                PsiElement t = directChildByRuleIndex(c, OceanParser.RULE_type);
                types.add(t != null ? OceanScopeDiscovery.normalizeTypeName(t.getText().trim()) : "variable");
            }
        }
        return "(" + String.join(",", types) + ")";
    }

    private String extractParamTypes(String paramsStr) {
        if (paramsStr == null || paramsStr.isBlank()) return "()";
        List<String> types = new ArrayList<>();
        for (String p : splitParams(paramsStr)) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                String[] parts = trimmed.split("\\s+");
                types.add(OceanScopeDiscovery.normalizeTypeName(parts[0]));
            }
        }
        return "(" + String.join(",", types) + ")";
    }

    /** Resolves what methods must be implemented for a given parent name. */
    private List<MethodStub> resolveRequiredMethods(Project project, String parentName, PsiElement context) {
        PsiElement oceanCls = findOceanClassInContextOrProject(project, parentName, context);
        if (oceanCls != null) {
            return findOceanAbstractMethods(project, parentName, context, new HashSet<>());
        }
        
        PsiClass javaCls = resolveJavaClassInContext(project, parentName, context);
        if (javaCls != null && (javaCls.isInterface() || javaCls.hasModifierProperty(PsiModifier.ABSTRACT))) {
            return extractJavaAbstractMethods(javaCls);
        }
        return List.of();
    }

    private PsiClass resolveJavaClassInContext(Project project, String parentName, PsiElement context) {
        if (context != null) {
            PsiFile file = context.getContainingFile();
            if (file != null) {
                OceanIdReference.OceanFileInfo info = OceanIdReference.getFileInfo(file);
                for (String exp : info.explicitImports) {
                    if (exp.endsWith("." + parentName) || exp.equals(parentName)) {
                        PsiClass jc = JavaPsiFacade.getInstance(project).findClass(exp, GlobalSearchScope.allScope(project));
                        if (jc != null) return jc;
                    }
                }
                for (String wild : info.wildcardImports) {
                    PsiClass jc = JavaPsiFacade.getInstance(project).findClass(wild + "." + parentName, GlobalSearchScope.allScope(project));
                    if (jc != null) return jc;
                }
            }
        }
        return OceanScopeDiscovery.resolveJavaClass(project, parentName);
    }

    // ---- Ocean abstract method resolution ----

    private List<MethodStub> findOceanAbstractMethods(Project project, String className, PsiElement context, Set<String> visited) {
        if (className == null || !visited.add(className)) return List.of();
        List<MethodStub> result = new ArrayList<>();

        PsiElement oceanCls = findOceanClassInContextOrProject(project, className, context);
        if (oceanCls != null) {
            for (PsiElement child : oceanCls.getChildren()) {
                if (!isRuleIndex(child, OceanParser.RULE_memberDeclaration)) continue;
                for (PsiElement member : child.getChildren()) {
                    if (!isRuleIndex(member, OceanParser.RULE_methodDeclaration)) continue;
                    if (isOceanAbstractMethod(member)) {
                        String mName = null;
                        if (member instanceof PsiNamedElement pNamed && pNamed.getName() != null) {
                            mName = pNamed.getName();
                        } else {
                            PsiElement mid = directChildByRuleIndex(member, OceanParser.RULE_anyId);
                            if (mid != null) mName = mid.getText().trim();
                        }
                        if (mName != null && !mName.isEmpty()) {
                            result.add(new MethodStub(
                                mName,
                                getReturnType(member),
                                getParamSignature(member)
                            ));
                        }
                    }
                }
            }

            // Only traverse parents of oceanCls if oceanCls is itself an interface or abstract class!
            boolean isAbstractOrInterface = isRuleIndex(oceanCls, OceanParser.RULE_interfaceDeclaration) ||
                                            oceanCls.getText().contains("abstract");
            if (isAbstractOrInterface) {
                for (String gp : extractParentNames(oceanCls)) {
                    result.addAll(findOceanAbstractMethods(project, gp, context, visited));
                }
            }
        }
        return result;
    }

    private PsiElement findOceanClassInContextOrProject(Project project, String className, PsiElement context) {
        if (context != null) {
            PsiFile file = context.getContainingFile();
            if (file != null) {
                // 1. Same file
                PsiElement inSameFile = OceanScopeDiscovery.findClassDeep(file, className);
                if (inSameFile != null) return inSameFile;

                // 2. Same directory / package
                PsiDirectory dir = file.getContainingDirectory();
                if (dir != null) {
                    PsiFile sibling = dir.findFile(className + ".ocean");
                    if (sibling != null) {
                        PsiElement inSibling = OceanScopeDiscovery.findClassDeep(sibling, className);
                        if (inSibling != null) return inSibling;
                    }
                    for (PsiFile f : dir.getFiles()) {
                        if (f.getName().endsWith(".ocean")) {
                            PsiElement inDir = OceanScopeDiscovery.findClassDeep(f, className);
                            if (inDir != null) return inDir;
                        }
                    }
                }

                // 3. Imports and package-qualified lookup
                OceanIdReference.OceanFileInfo info = OceanIdReference.getFileInfo(file);
                for (String exp : info.explicitImports) {
                    if (exp.endsWith("." + className) || exp.equals(className)) {
                        PsiElement impCls = OceanScopeDiscovery.findOceanClass(project, exp);
                        if (impCls != null) return impCls;
                        if (JavaPsiFacade.getInstance(project).findClass(exp, GlobalSearchScope.allScope(project)) != null) {
                            return null;
                        }
                    }
                }
                if (!info.packageName.isEmpty()) {
                    PsiElement localOceanCls = OceanScopeDiscovery.findOceanClass(project, info.packageName + "." + className);
                    if (localOceanCls != null) return localOceanCls;
                }
                for (String wild : info.wildcardImports) {
                    PsiElement wildCls = OceanScopeDiscovery.findOceanClass(project, wild + "." + className);
                    if (wildCls != null) return wildCls;
                }
            }
        }
        return OceanScopeDiscovery.findOceanClass(project, className);
    }

    /**
     * A method is abstract if:
     * 1. It has the `abstract` keyword, OR
     * 2. Its last token is `;` instead of a `}` block.
     * We check by looking for a RULE_block child — if none exists → abstract.
     */
    private boolean isOceanAbstractMethod(PsiElement method) {
        // Check for abstract modifier in modifiers
        for (PsiElement child : method.getChildren()) {
            if (isRuleIndex(child, OceanParser.RULE_modifier)) {
                if (child.getText().contains("abstract")) return true;
            }
            // Has a real block → not abstract
            if (isRuleIndex(child, OceanParser.RULE_block)) return false;
        }
        // No block found → abstract
        return true;
    }

    /** Returns only the header part of `class X extends Y implements Z {` */
    private String getClassHeader(PsiElement cls) {
        String full = cls.getText();
        int brace = full.indexOf('{');
        return brace > 0 ? full.substring(0, brace) : full;
    }

    private List<MethodStub> extractJavaAbstractMethods(PsiClass cls) {
        if (cls == null) return List.of();

        List<MethodStub> result = new ArrayList<>();
        boolean isInterface = cls.isInterface();

        for (PsiMethod m : (isInterface ? cls.getMethods() : cls.getAllMethods())) {
            if (m.isConstructor()) continue;
            if (isObjectMethod(m)) continue;
            boolean isDefault  = m.hasModifierProperty(PsiModifier.DEFAULT);
            boolean isStatic   = m.hasModifierProperty(PsiModifier.STATIC);
            boolean isAbstract = m.hasModifierProperty(PsiModifier.ABSTRACT);
            boolean needsImpl  = isInterface ? (!isDefault && !isStatic) : isAbstract;
            if (!needsImpl) continue;

            result.add(new MethodStub(
                m.getName(),
                javaTypeToOcean(m.getReturnType()),
                buildJavaParamStr(m)
            ));
        }
        return result;
    }

    private boolean isObjectMethod(PsiMethod m) {
        String name = m.getName();
        int paramCount = m.getParameterList().getParametersCount();
        if ("equals".equals(name) && paramCount == 1) return true;
        if ("hashCode".equals(name) && paramCount == 0) return true;
        if ("toString".equals(name) && paramCount == 0) return true;
        if ("getClass".equals(name) && paramCount == 0) return true;
        if ("notify".equals(name) && paramCount == 0) return true;
        if ("notifyAll".equals(name) && paramCount == 0) return true;
        if ("wait".equals(name)) return true;
        if ("clone".equals(name) && paramCount == 0) return true;
        return "finalize".equals(name) && paramCount == 0;
    }

    private String javaTypeToOcean(PsiType type) {
        if (type == null) return "void";
        return switch (type.getCanonicalText()) {
            case "void"    -> "void";
            case "int"     -> "int";
            case "long"    -> "long";
            case "double"  -> "double";
            case "float"   -> "float";
            case "boolean" -> "bool";
            case "char"    -> "char";
            case "byte"    -> "byte";
            case "short"   -> "short";
            case "java.lang.String" -> "String";
            default -> {
                String raw = type.getCanonicalText();
                int dot = raw.lastIndexOf('.');
                yield dot >= 0 ? raw.substring(dot + 1) : raw;
            }
        };
    }

    private String buildJavaParamStr(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(javaTypeToOcean(params[i].getType())).append(" ").append(params[i].getName());
        }
        return sb.toString();
    }

    // =========================================================================
    // PSI helpers
    // =========================================================================

    /**
     * Walks up the PSI tree from the caret position to find the enclosing
     * classDeclaration rule node.
     * <p>
     * IMPORTANT: file.findElementAt() returns a LEAF node (often LeafPsiElement,
     * not OceanPsiNode). We must walk up using getParent() until we hit an
     * OceanPsiNode whose element type is RULE_classDeclaration.
     */
    private PsiElement findEnclosingClass(Editor editor, PsiFile file) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement el = file.findElementAt(offset);
        PsiElement found = findEnclosingClassFromElement(el, file.getProject());
        if (found != null) return found;

        if (offset > 0) {
            PsiElement elPrev = file.findElementAt(offset - 1);
            if (elPrev != el) {
                found = findEnclosingClassFromElement(elPrev, file.getProject());
                return found;
            }
        }
        return null;
    }

    private PsiElement findEnclosingClassFromElement(PsiElement el, Project project) {
        while (el != null) {
            if (isRuleIndex(el, OceanParser.RULE_classDeclaration)) return el;
            if (isRuleIndex(el, OceanParser.RULE_expression) && hasTokenChild(el, OceanLexer.NEW)) {
                if (hasTokenChild(el, OceanLexer.LBRACE) || !collectMissingMethods(project, el).isEmpty()) {
                    return el;
                }
            }
            el = el.getParent();
        }
        return null;
    }

    /**
     * Checks if the given PSI element is a rule node with the specified ANTLR rule index.
     * Works on both OceanPsiNode and any PSI element backed by RuleIElementType.
     */
    private boolean isRuleIndex(PsiElement e, int ruleIndex) {
        if (e == null || e.getNode() == null) return false;
        IElementType type = e.getNode().getElementType();
        return type instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIndex;
    }

    private boolean hasTokenChild(PsiElement element, int tokenType) {
        for (PsiElement child : element.getChildren()) {
            if (child.getNode() != null) {
                IElementType type = child.getNode().getElementType();
                if (type instanceof TokenIElementType ti && ti.getANTLRTokenType() == tokenType) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the first DIRECT child with the given rule index (non-recursive). */
    private PsiElement directChildByRuleIndex(PsiElement e, int ruleIndex) {
        for (PsiElement c : e.getChildren()) {
            if (isRuleIndex(c, ruleIndex)) return c;
        }
        return null;
    }

    private String getReturnType(PsiElement method) {
        return OceanScopeDiscovery.getMethodReturnType(method);
    }

    private String getParamSignature(PsiElement method) {
        PsiElement pl = directChildByRuleIndex(method, OceanParser.RULE_parameterList);
        if (pl == null) return "";
        StringBuilder sb = new StringBuilder();
        List<PsiElement> params = new ArrayList<>();
        for (PsiElement c : pl.getChildren()) {
            if (isRuleIndex(c, OceanParser.RULE_parameter)) params.add(c);
        }
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            PsiElement typeN = directChildByRuleIndex(params.get(i), OceanParser.RULE_type);
            PsiElement nameN = directChildByRuleIndex(params.get(i), OceanParser.RULE_anyId);
            String rawType = typeN != null ? typeN.getText().trim() : null;
            String cleanType;
            if (rawType == null || "variable".equals(rawType) || "value".equals(rawType)) {
                cleanType = "Object";
            } else {
                cleanType = rawType;
            }
            sb.append(cleanType)
              .append(" ")
              .append(nameN != null ? nameN.getText().trim() : "param" + i);
        }
        return sb.toString();
    }

    private PsiClass resolveJavaClass(Project project, String name) {
        return OceanScopeDiscovery.resolveJavaClass(project, name);
    }

    private List<PsiFile> oceanFiles(Project project) {
        List<PsiFile> files = new ArrayList<>();
        FileTypeIndex.processFiles(OceanFileType.INSTANCE, vf -> {
            PsiFile pf = PsiManager.getInstance(project).findFile(vf);
            if (pf != null) files.add(pf);
            return true;
        }, GlobalSearchScope.projectScope(project));
        return files;
    }
}
