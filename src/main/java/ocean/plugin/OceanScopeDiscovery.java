package ocean.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanScopeDiscovery {

    public static List<PsiElement> findVisibleSymbols(PsiElement position) {
        List<PsiElement> symbols = new ArrayList<>();
        Set<PsiElement> seen = new HashSet<>();
        PsiElement current = position;

        while (current != null) {
            if (current instanceof PsiFile) {
                findAllDeclarationsInScope(current, position, false, symbols, seen);
                break;
            }

            if (isScope(current)) {
                boolean localScope = isRule(current, OceanParser.RULE_block) ||
                        isRule(current, OceanParser.RULE_forStatement) ||
                        isRule(current, OceanParser.RULE_ifStatement) ||
                        isRule(current, OceanParser.RULE_whileStatement) ||
                        isRule(current, OceanParser.RULE_doWhileStatement) ||
                        isRule(current, OceanParser.RULE_tryStatement) ||
                        isRule(current, OceanParser.RULE_catchClause) ||
                        isRule(current, OceanParser.RULE_switchStatement) ||
                        isRule(current, OceanParser.RULE_switchCase) ||
                        isRule(current, OceanParser.RULE_switchExpressionCase) ||
                        (isRule(current, OceanParser.RULE_expression) && hasArrowToken(current));
                findAllDeclarationsInScope(current, position, localScope, symbols, seen);

                if (isRule(current, OceanParser.RULE_classDeclaration) || isRule(current, OceanParser.RULE_interfaceDeclaration)) {
                    collectInheritedMembers(current, symbols, seen, position.getProject(), new HashSet<>());
                }
            }
            current = current.getParent();
        }
        return symbols;
    }

    private static void collectInheritedMembers(PsiElement cls, List<PsiElement> results, Set<PsiElement> seen, Project project, Set<String> visited) {
        if (cls == null || project == null) return;
        String superName = extractExtendsName(cls);
        if (superName != null && visited.add(superName)) {
            collectParentMembers(superName, results, seen, project, visited);
        }
        for (String iface : extractImplementsNames(cls)) {
            if (iface != null && visited.add(iface)) {
                collectParentMembers(iface, results, seen, project, visited);
            }
        }
    }

    private static void collectParentMembers(String parentName, List<PsiElement> results, Set<PsiElement> seen, Project project, Set<String> visited) {
        parentName = normalizeTypeName(parentName);
        if (parentName == null || parentName.isEmpty()) return;

        // 1. In Ocean
        PsiElement oceanCls = findOceanClass(project, parentName);
        if (oceanCls != null) {
            collectNamedElementsRecursive(oceanCls, null, false, results, seen);
            collectInheritedMembers(oceanCls, results, seen, project, visited);
        } else {
            // 2. In Java
            PsiClass javaCls = resolveJavaClass(project, parentName);
            if (javaCls != null) {
                for (PsiMethod m : javaCls.getAllMethods()) {
                    if (seen.add(m)) results.add(m);
                }
                for (PsiField f : javaCls.getAllFields()) {
                    if (seen.add(f)) results.add(f);
                }
            }
        }
    }

    private static void findAllDeclarationsInScope(PsiElement scope, PsiElement position, boolean checkOffset, List<PsiElement> results, Set<PsiElement> seen) {
        collectNamedElementsRecursive(scope, position, checkOffset, results, seen);
    }

    private static void collectNamedElementsRecursive(PsiElement root, PsiElement position, boolean checkOffset, List<PsiElement> results, Set<PsiElement> seen) {
        if (root == null) return;
        for (PsiElement child : root.getChildren()) {
            if (checkOffset && position != null && child.getTextOffset() >= position.getTextOffset()) continue;

            if (child instanceof OceanFieldDeclaration || child instanceof OceanVariableDeclaration) {
                boolean hasDeclarator = false;
                for (PsiElement c : child.getChildren()) {
                    if (c instanceof OceanVariableDeclarator) {
                        hasDeclarator = true;
                        break;
                    }
                }
                if (hasDeclarator) {
                    collectNamedElementsRecursive(child, position, checkOffset, results, seen);
                    continue;
                }
            }

            if (child instanceof OceanNamedElement && seen.add(child)) {
                results.add(child);
            }

            if (!checkOffset || !isScope(child)) {
                collectNamedElementsRecursive(child, position, checkOffset, results, seen);
            }
        }
    }

    public static void resolveMemberChain(String className, String memberName, List<PsiElement> results, Project project, Set<String> visited) {
        resolveMemberChain(className, memberName, results, project, visited, null);
    }

    public static void resolveMemberChain(String className, String memberName, List<PsiElement> results, Project project, Set<String> visited, PsiElement context) {
        if ("length".equals(memberName) && className != null &&
                (className.endsWith("[]") || className.equals("java.lang.Object[]"))) {
            return;
        }
        className = normalizeTypeName(className);
        if (className == null || !visited.add(className) || project == null) return;

        PsiFile contextFile = context instanceof PsiFile pf ? pf : (context != null ? context.getContainingFile() : null);

        // 1. If className is an explicitly imported Java class in contextFile, prioritize Java!
        if (contextFile != null) {
            OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(contextFile);
            for (String explicit : fileInfo.explicitImports) {
                if (explicit.endsWith("." + className) || explicit.equals(className)) {
                    PsiClass javaCls = resolveJavaClass(project, explicit, context);
                    if (javaCls != null) {
                        addJavaMethodsSorted(results, javaCls, memberName);
                        PsiField f = javaCls.findFieldByName(memberName, true);
                        if (f != null) results.add(f);
                        if (!results.isEmpty()) return;
                    }
                }
            }
        }

        // 2. If className is fully qualified (contains '.'), try Java first
        if (className.contains(".")) {
            PsiClass javaCls = resolveJavaClass(project, className, context);
            if (javaCls != null) {
                addJavaMethodsSorted(results, javaCls, memberName);
                PsiField f = javaCls.findFieldByName(memberName, true);
                if (f != null) results.add(f);
                if (!results.isEmpty()) return;
            }
        }

        // 3. Search in Ocean (current file, package, explicit ocean imports, etc.)
        PsiElement oceanCls = findOceanClass(project, className, contextFile);
        if (oceanCls != null) {
            List<PsiElement> allMembers = new ArrayList<>();
            collectNamedElementsRecursive(oceanCls, null, false, allMembers, new HashSet<>());
            for (PsiElement m : allMembers) {
                if (m instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                    results.add(m);
                }
            }
            if (results.size() > 1) {
                results.sort((a, b) -> Boolean.compare(OceanOverloadResolver.isPrivateMember(a), OceanOverloadResolver.isPrivateMember(b)));
            }

            String superName = extractExtendsName(oceanCls);
            if (superName != null) resolveMemberChain(superName, memberName, results, project, visited, context);
            for (String iface : extractImplementsNames(oceanCls)) resolveMemberChain(iface, memberName, results, project, visited, context);
        }

        // 4. Search in Java (context-aware: checks wildcard imports, java.lang, etc.)
        if (results.isEmpty()) {
            PsiClass javaCls = resolveJavaClass(project, className, context);
            if (javaCls != null) {
                addJavaMethodsSorted(results, javaCls, memberName);
                PsiField f = javaCls.findFieldByName(memberName, true);
                if (f != null) results.add(f);
                if ("length".equals(memberName) && className.endsWith("[]")) results.add(javaCls);
            }
        }

        // 5. Extension methods
        if (results.isEmpty()) {
            findExtensionMethods(project, className, memberName, results);
        }
    }

    private static void addJavaMethodsSorted(List<PsiElement> results, PsiClass javaCls, String memberName) {
        PsiMethod[] methods = javaCls.findMethodsByName(memberName, true);
        Arrays.sort(methods, (m1, m2) -> Boolean.compare(
                m1.hasModifierProperty(PsiModifier.PRIVATE),
                m2.hasModifierProperty(PsiModifier.PRIVATE)
        ));
        results.addAll(Arrays.asList(methods));
    }

    public static Map<String, PsiElement> getProjectOceanClasses(Project project) {
        if (project == null || project.isDisposed()) return Collections.emptyMap();
        CachedValuesManager manager = CachedValuesManager.getManager(project);
        if (manager == null) return Collections.emptyMap();
        return manager.getCachedValue(project, () -> {
            Map<String, PsiElement> map = new HashMap<>();
            FileTypeIndex.processFiles(OceanFileType.INSTANCE, vf -> {
                PsiFile pf = PsiManager.getInstance(project).findFile(vf);
                if (pf != null) {
                    String pkg = extractOceanPackage(pf);
                    PsiElement[] declarations = PsiTreeUtil.collectElements(pf, e -> {
                        if (e.getNode() == null) return false;
                        IElementType type = e.getNode().getElementType();
                        if (type instanceof RuleIElementType ri) {
                            int idx = ri.getRuleIndex();
                            return idx == OceanParser.RULE_classDeclaration || 
                                   idx == OceanParser.RULE_interfaceDeclaration || 
                                   idx == OceanParser.RULE_enumDeclaration ||
                                   idx == OceanParser.RULE_annotationDeclaration;
                        }
                        return false;
                    });
                    for (PsiElement decl : declarations) {
                        String name = findDeclarationName(decl);
                        if (name != null && !name.isEmpty()) {
                            map.putIfAbsent(name, decl);
                            if (!pkg.isEmpty()) {
                                map.put(pkg + "." + name, decl);
                            }
                        }
                    }
                }
                return true;
            }, GlobalSearchScope.allScope(project));
            return CachedValueProvider.Result.create(map, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    public static String findDeclarationName(PsiElement decl) {
        if (decl instanceof PsiNamedElement named) {
            String n = named.getName();
            if (n != null && !n.isEmpty()) return n;
        }
        for (PsiElement child : decl.getChildren()) {
            if (isRule(child, OceanParser.RULE_anyId)) {
                String text = child.getText();
                int angle = text.indexOf('<');
                return (angle > 0) ? text.substring(0, angle).trim() : text.trim();
            }
        }
        return null;
    }

    public static PsiElement findOceanClass(Project project, String name) {
        return findOceanClass(project, name, null);
    }

    public static PsiElement findOceanClass(Project project, String name, PsiFile contextFile) {
        name = normalizeTypeName(name);
        if (name == null || name.isEmpty() || project == null) return null;

        Map<String, PsiElement> map = getProjectOceanClasses(project);

        String simpleName = name;
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) simpleName = name.substring(lastDot + 1);

        // 1. If name is fully qualified (contains '.'), prioritize exact match in map
        if (lastDot >= 0) {
            PsiElement cls = map.get(name);
            if (cls != null && cls.isValid()) return cls;

            // Check if this is a nested class: Outer.Inner
            String outerName = name.substring(0, lastDot);
            String innerSimple = name.substring(lastDot + 1);
            PsiElement outerCls = findOceanClass(project, outerName, contextFile);
            if (outerCls != null) {
                PsiElement innerCls = findClassDeep(outerCls, innerSimple);
                if (innerCls != null && innerCls.isValid()) return innerCls;
            }
            return null;
        }

        // 2. If context file is provided and name is simple, prioritize nearest declarations:
        if (contextFile != null) {
            // A. Current file
            PsiElement localCls = findClassDeep(contextFile, simpleName);
            if (localCls != null) return localCls;

            // B. Explicit imports in contextFile (e.g. import com.foo.bar.Service; or import org.apache.commons.lang3.StringUtils;)
            // Single-type imports ALWAYS take precedence over same-package declarations!
            OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(contextFile);
            for (String explicit : fileInfo.explicitImports) {
                if (explicit.endsWith("." + simpleName) || explicit.equals(simpleName)) {
                    PsiElement impCls = map.get(explicit);
                    if (impCls != null && impCls.isValid()) return impCls;
                    // If explicitly imported as a Java / 3rd-party class, do not let an unimported Ocean class hijack it!
                    if (JavaPsiFacade.getInstance(project).findClass(explicit, GlobalSearchScope.allScope(project)) != null) {
                        return null;
                    }
                }
            }

            // C. Same package
            String pkg = extractOceanPackage(contextFile);
            if (!pkg.isEmpty()) {
                PsiElement pkgCls = map.get(pkg + "." + simpleName);
                if (pkgCls != null && pkgCls.isValid()) return pkgCls;
            }

            // D. Standard Java / stdlib class check for simple names
            PsiClass stdJava = resolveJavaClass(project, simpleName, contextFile);
            if (stdJava != null && stdJava.getQualifiedName() != null &&
                    (stdJava.getQualifiedName().startsWith("java.") || stdJava.getQualifiedName().startsWith("ocean.stdlib."))) {
                return null;
            }
        }

        // 3. Simple name match in map
        PsiElement cls = map.get(simpleName);
        if (cls != null && cls.isValid()) return cls;

        try {
            GlobalSearchScope allScope = GlobalSearchScope.allScope(project);
            // 4. File-name search: simpleName.ocean
            PsiFile[] files = FilenameIndex.getFilesByName(project, simpleName + ".ocean", allScope);
            for (PsiFile pf : files) {
                cls = findClassDeep(pf, simpleName);
                if (cls != null) return cls;
            }

            final String targetSimpleName = simpleName;
            final PsiElement[] found = new PsiElement[1];
            FileTypeIndex.processFiles(OceanFileType.INSTANCE, vf -> {
                PsiFile pf = PsiManager.getInstance(project).findFile(vf);
                if (pf != null) {
                    PsiElement c = findClassDeep(pf, targetSimpleName);
                    if (c != null) {
                        found[0] = c;
                        return false;
                    }
                }
                return true;
            }, allScope);

            return found[0];
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String extractOceanPackage(PsiFile file) {
        if (file == null) return "";
        try {
            OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(file);
            if (fileInfo != null && fileInfo.packageName != null) {
                return fileInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        String text = file.getText();
        Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    public static PsiElement findClassDeep(PsiElement root, String name) {
        if (root == null || name == null) return null;
        if (isRule(root, OceanParser.RULE_classDeclaration) || 
            isRule(root, OceanParser.RULE_interfaceDeclaration) || 
            isRule(root, OceanParser.RULE_enumDeclaration) || 
            isRule(root, OceanParser.RULE_annotationDeclaration)) {
            
            if (root instanceof PsiNamedElement named && name.equals(named.getName())) {
                return root;
            }
            for (PsiElement child : root.getChildren()) {
                if (isRule(child, OceanParser.RULE_anyId)) {
                    String idText = child.getText();
                    int angle = idText.indexOf('<');
                    String cleanName = (angle > 0) ? idText.substring(0, angle).trim() : idText.trim();
                    if (name.equals(cleanName)) {
                        return root;
                    }
                }
            }
        }
        for (PsiElement child : root.getChildren()) {
            PsiElement res = findClassDeep(child, name);
            if (res != null) return res;
        }
        return null;
    }

    public static PsiClass resolveJavaClass(Project project, String name) {
        return resolveJavaClass(project, name, null);
    }

    public static PsiClass resolveJavaClass(Project project, String name, PsiElement context) {
        if (project == null || project.isDisposed()) return null;
        name = normalizeTypeName(name);
        if (name == null || name.isEmpty()) return null;
        String stdlibAlias = OceanTypeHelper.getStdlibAlias(name);
        if (stdlibAlias != null) {
            name = stdlibAlias;
        } else {
            switch (name) {
                case "Date" -> name = "java.util.Date";
                case "List" -> name = "java.util.List";
                case "Map" -> name = "java.util.Map";
                case "Set" -> name = "java.util.Set";
                case "File" -> name = "java.io.File";
            }
        }

        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        if (facade == null) return null;
        GlobalSearchScope scope;
        try {
            scope = GlobalSearchScope.allScope(project);
        } catch (Exception ignored) {
            return null;
        }

        // 1. Direct FQCN check
        PsiClass cls = facade.findClass(name, scope);
        if (cls != null) return cls;

        // 2. Check context file imports (explicit & wildcard) and current package
        if (context != null) {
            PsiFile file = context instanceof PsiFile pf ? pf : context.getContainingFile();
            if (file != null) {
                OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(file);
                // Explicit imports: e.g. import java.util.ArrayList;
                for (String explicit : fileInfo.explicitImports) {
                    if (explicit.endsWith("." + name) || explicit.equals(name)) {
                        cls = facade.findClass(explicit, scope);
                        if (cls != null) return cls;
                    }
                }
                // Same package Java classes
                if (!fileInfo.packageName.isEmpty()) {
                    cls = facade.findClass(fileInfo.packageName + "." + name, scope);
                    if (cls != null) return cls;
                }
                // Wildcard imports: e.g. import java.util.*;
                for (String wild : fileInfo.wildcardImports) {
                    cls = facade.findClass(wild + "." + name, scope);
                    if (cls != null) return cls;
                }
            }
        }

        // 3. Fallback well-known prefixes
        cls = facade.findClass("ocean.stdlib." + name, scope);
        if (cls != null) return cls;
        cls = facade.findClass("java.lang." + name, scope);
        if (cls != null) return cls;
        cls = facade.findClass("java.util." + name, scope);
        if (cls != null) return cls;
        cls = facade.findClass("java.io." + name, scope);
        return cls;
    }

    public static String normalizeTypeName(String name) {
        if (name == null) return null;
        String normalized = name.trim();
        while (normalized.endsWith("?")) normalized = normalized.substring(0, normalized.length() - 1).trim();
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) normalized = normalized.substring(0, genericStart).trim();
        while (normalized.endsWith("[]")) normalized = normalized.substring(0, normalized.length() - 2).trim();
        while (normalized.endsWith("?")) normalized = normalized.substring(0, normalized.length() - 1).trim();
        return normalized.trim();
    }

    public static Map<String, List<OceanMethodDeclaration>> getProjectExtensionMethods(Project project) {
        if (project == null || project.isDisposed()) return Collections.emptyMap();
        CachedValuesManager manager = CachedValuesManager.getManager(project);
        if (manager == null) return Collections.emptyMap();
        return manager.getCachedValue(project, () -> {
            Map<String, List<OceanMethodDeclaration>> map = new HashMap<>();
            FileTypeIndex.processFiles(OceanFileType.INSTANCE, vf -> {
                PsiFile pf = PsiManager.getInstance(project).findFile(vf);
                if (pf != null) {
                    Collection<OceanMethodDeclaration> methods = PsiTreeUtil.findChildrenOfType(pf, OceanMethodDeclaration.class);
                    for (OceanMethodDeclaration method : methods) {
                        PsiElement extTypeNode = getExtensionReceiverTypeNode(method);
                        if (extTypeNode != null) {
                            PsiElement nameId = method.getNameIdentifier();
                            if (nameId != null) {
                                String mName = nameId.getText().trim();
                                if (!mName.isEmpty()) {
                                    map.computeIfAbsent(mName, k -> new ArrayList<>()).add(method);
                                }
                            } else {
                                String text = method.getText();
                                int funcIdx = text.indexOf("function");
                                if (funcIdx > 0) {
                                    Matcher matcher = Pattern.compile("\\b([\\w.<>\\[\\]]+)\\s*\\.\\s*function\\s+([\\w]+)\\s*\\(").matcher(text);
                                    if (matcher.find()) {
                                        String mName = matcher.group(2).trim();
                                        map.computeIfAbsent(mName, k -> new ArrayList<>()).add(method);
                                    }
                                }
                            }
                        } else {
                            // Check via regex fallback in case AST is in error state or recovery
                            String text = method.getText();
                            int funcIdx = text.indexOf("function");
                            if (funcIdx > 0) {
                                Matcher matcher = Pattern.compile("\\b([\\w.<>\\[\\]]+)\\s*\\.\\s*function\\s+([\\w]+)\\s*\\(").matcher(text);
                                if (matcher.find()) {
                                    String mName = matcher.group(2).trim();
                                    map.computeIfAbsent(mName, k -> new ArrayList<>()).add(method);
                                }
                            }
                        }
                    }
                }
                return true;
            }, GlobalSearchScope.projectScope(project));
            return CachedValueProvider.Result.create(map, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    public static void findExtensionMethods(Project project, String receiverType, String memberName, List<PsiElement> results) {
        String normalizedReceiver = normalizeTypeName(receiverType);
        if (normalizedReceiver == null || memberName == null || project == null) return;
        String simpleReceiver = normalizedReceiver;
        int dotIdx = simpleReceiver.lastIndexOf('.');
        if (dotIdx >= 0) simpleReceiver = simpleReceiver.substring(dotIdx + 1);
        final String receiverSimpleName = simpleReceiver;

        Map<String, List<OceanMethodDeclaration>> extMap = getProjectExtensionMethods(project);
        List<OceanMethodDeclaration> methods = extMap.get(memberName);
        if (methods == null || methods.isEmpty()) return;

        for (OceanMethodDeclaration method : methods) {
            if (!method.isValid()) continue;
            PsiElement extTypeNode = getExtensionReceiverTypeNode(method);
            if (extTypeNode != null) {
                String extType = normalizeTypeName(extTypeNode.getText());
                if (normalizedReceiver.equals(extType) || receiverSimpleName.equals(extType) ||
                    isSubclassOf(project, normalizedReceiver, extType)) {
                    results.add(method);
                }
            } else {
                Pattern extensionPattern = Pattern.compile("\\b([\\w.<>\\[\\]]+)\\s*\\.\\s*function\\s+" + Pattern.quote(memberName) + "\\s*\\(");
                Matcher matcher = extensionPattern.matcher(method.getText());
                if (matcher.find()) {
                    String extType = normalizeTypeName(matcher.group(1));
                    if (normalizedReceiver.equals(extType) || receiverSimpleName.equals(extType) ||
                        isSubclassOf(project, normalizedReceiver, extType)) {
                        results.add(method);
                    }
                }
            }
        }
    }

    public static PsiElement getExtensionReceiverTypeNode(PsiElement method) {
        if (method == null) return null;
        PsiElement funcToken = null;
        for (PsiElement child = method.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null) {
                if (child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.FUNCTION) {
                    funcToken = child;
                    break;
                }
                if ("function".equals(child.getText())) {
                    funcToken = child;
                    break;
                }
            }
        }
        if (funcToken != null) {
            PsiElement prev = funcToken.getPrevSibling();
            while (prev != null) {
                if (prev instanceof PsiWhiteSpace || prev instanceof PsiComment) {
                    prev = prev.getPrevSibling();
                    continue;
                }
                if (".".equals(prev.getText()) ||
                        (prev.getNode() != null && prev.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.DOT)) {
                    PsiElement receiverTypeNode = prev.getPrevSibling();
                    while (receiverTypeNode != null) {
                        if (receiverTypeNode instanceof PsiWhiteSpace || receiverTypeNode instanceof PsiComment) {
                            receiverTypeNode = receiverTypeNode.getPrevSibling();
                            continue;
                        }
                        if (isRule(receiverTypeNode, OceanParser.RULE_type)) {
                            return receiverTypeNode;
                        }
                        for (PsiElement c : receiverTypeNode.getChildren()) {
                            if (isRule(c, OceanParser.RULE_type)) {
                                return c;
                            }
                        }
                        return receiverTypeNode;
                    }
                }
                break;
            }
        }
        return null;
    }

    public static PsiElement getMethodReturnTypeNode(PsiElement method) {
        if (method == null) return null;
        PsiElement extReceiver = getExtensionReceiverTypeNode(method);
        for (PsiElement child = method.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isRule(child, OceanParser.RULE_type)) {
                if (extReceiver != null && (child == extReceiver || child.getTextRange().equals(extReceiver.getTextRange()))) {
                    continue; // Skip the extension receiver type, this is not the return type
                }
                return child;
            }
        }
        return null;
    }

    public static boolean isVoidMethod(PsiElement method) {
        if (method == null) return false;
        PsiElement retTypeNode = getMethodReturnTypeNode(method);
        if (retTypeNode != null) {
            return "void".equalsIgnoreCase(retTypeNode.getText().trim());
        }
        for (PsiElement child = method.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.VOID) {
                return true;
            }
            if ("void".equals(child.getText())) {
                return true;
            }
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.FUNCTION) {
                break;
            }
        }
        return false;
    }

    public static boolean isAsyncMethod(PsiElement method) {
        if (method == null) return false;
        for (PsiElement child = method.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.ASYNC) {
                return true;
            }
            if ("async".equals(child.getText())) {
                return true;
            }
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.FUNCTION) {
                break;
            }
        }
        return false;
    }

    public static String getMethodReturnType(PsiElement method) {
        if (method == null) return "void";
        boolean async = isAsyncMethod(method);
        if (isVoidMethod(method)) {
            return async ? "java.util.concurrent.CompletableFuture<java.lang.Void>" : "void";
        }
        PsiElement retTypeNode = getMethodReturnTypeNode(method);
        String rawType = (retTypeNode != null) ? retTypeNode.getText().trim() : "void";
        if (!"void".equals(rawType) && method.getContainingFile() != null) {
            String resolved = OceanTypeHelper.resolveDeclaredType(rawType, method.getProject(), method);
            if (resolved != null && !resolved.isEmpty()) {
                rawType = resolved;
            }
        }
        if (async) {
            String boxed = OceanTypeHelper.boxType(rawType);
            return "java.util.concurrent.CompletableFuture<" + boxed + ">";
        }
        return rawType;
    }

    private static boolean isScope(PsiElement e) {
        if (e == null || e.getNode() == null) return false;
        IElementType type = e.getNode().getElementType();
        if (!(type instanceof RuleIElementType ri)) return false;
        int idx = ri.getRuleIndex();
        return idx == OceanParser.RULE_block || 
               idx == OceanParser.RULE_methodDeclaration || 
               idx == OceanParser.RULE_classDeclaration || 
               idx == OceanParser.RULE_forStatement ||
               idx == OceanParser.RULE_ifStatement ||
               idx == OceanParser.RULE_whileStatement ||
               idx == OceanParser.RULE_doWhileStatement ||
               idx == OceanParser.RULE_tryStatement ||
               idx == OceanParser.RULE_catchClause ||
               idx == OceanParser.RULE_switchStatement ||
               idx == OceanParser.RULE_switchCase ||
               idx == OceanParser.RULE_switchExpressionCase ||
               (idx == OceanParser.RULE_expression && hasArrowToken(e));
    }

    private static boolean hasArrowToken(PsiElement e) {
        if (e == null) return false;
        for (PsiElement child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti &&
                ti.getANTLRTokenType() == OceanLexer.ARROW) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRule(PsiElement e, int ruleIdx) {
        return e != null && e.getNode() != null && e.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIdx;
    }

    public static String extractExtendsName(PsiElement cls) {
        if (cls == null) return null;
        PsiElement extendsToken = null;
        for (PsiElement child : cls.getChildren()) {
            if (child.getNode() != null && "extends".equals(child.getText())) {
                extendsToken = child;
                break;
            }
        }
        if (extendsToken != null) {
            PsiElement sibling = extendsToken.getNextSibling();
            while (sibling != null) {
                if (isRule(sibling, OceanParser.RULE_type)) {
                    return sibling.getText().trim();
                }
                sibling = sibling.getNextSibling();
            }
        }
        // Fallback to regex that handles full type with optional generics: e.g. extends Box<String>
        Matcher m = Pattern.compile("\\bextends\\s+([\\w.<>\\[\\]]+)").matcher(cls.getText());
        return m.find() ? m.group(1).trim() : null;
    }

    public static List<String> splitTopLevelComma(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth = Math.max(0, depth - 1);

            if (c == ',' && depth == 0) {
                String part = sb.toString().trim();
                if (!part.isEmpty()) result.add(part);
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        String last = sb.toString().trim();
        if (!last.isEmpty()) result.add(last);
        return result;
    }

    public static List<String> extractImplementsNames(PsiElement cls) {
        List<String> names = new ArrayList<>();
        if (cls == null) return names;
        PsiElement implementsOrExtendsToken = null;
        boolean isInterface = isRule(cls, OceanParser.RULE_interfaceDeclaration);
        String targetKeyword = isInterface ? "extends" : "implements";
        
        for (PsiElement child = cls.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode() != null && targetKeyword.equals(child.getText())) {
                implementsOrExtendsToken = child;
                break;
            }
        }
        if (implementsOrExtendsToken == null) {
            for (PsiElement child : cls.getChildren()) {
                if (child.getNode() != null && targetKeyword.equals(child.getText())) {
                    implementsOrExtendsToken = child;
                    break;
                }
            }
        }
        if (implementsOrExtendsToken != null) {
            PsiElement sibling = implementsOrExtendsToken.getNextSibling();
            while (sibling != null) {
                if (isRule(sibling, OceanParser.RULE_typeList)) {
                    for (PsiElement typeChild : sibling.getChildren()) {
                        if (isRule(typeChild, OceanParser.RULE_type)) {
                            names.add(typeChild.getText().trim());
                        }
                    }
                    return names;
                }
                sibling = sibling.getNextSibling();
            }
        }
        
        // Fallback to regex
        Matcher m = Pattern.compile("\\b" + targetKeyword + "\\s+([^;{]+)").matcher(cls.getText());
        if (m.find()) {
            names.addAll(splitTopLevelComma(m.group(1).trim()));
        }
        return names;
    }

    public static boolean isSubclassOf(Project project, String subType, String superType) {
        if (subType == null || superType == null) return false;
        String rawSub = normalizeTypeName(subType);
        String rawSuper = normalizeTypeName(superType);
        if (rawSub.equals(rawSuper)) return true;
        if ("Object".equals(rawSuper) || "java.lang.Object".equals(rawSuper)) return true;
        if (project == null) return false;

        Set<String> visited = new HashSet<>();
        return checkSubclassRecursive(project, rawSub, rawSuper, visited);
    }

    private static boolean checkSubclassRecursive(Project project, String current, String targetSuper, Set<String> visited) {
        if (!visited.add(current)) return false;

        // 1. Check in Ocean
        PsiElement oceanCls = findOceanClass(project, current);
        if (oceanCls != null) {
            String ext = extractExtendsName(oceanCls);
            if (ext != null) {
                String rawExt = normalizeTypeName(ext);
                if (rawExt.equals(targetSuper) || checkSubclassRecursive(project, rawExt, targetSuper, visited)) {
                    return true;
                }
            }
            for (String iface : extractImplementsNames(oceanCls)) {
                String rawIface = normalizeTypeName(iface);
                if (rawIface.equals(targetSuper) || checkSubclassRecursive(project, rawIface, targetSuper, visited)) {
                    return true;
                }
            }
        }

        // 2. Check in Java
        PsiClass javaSub = resolveJavaClass(project, current);
        PsiClass javaSuper = resolveJavaClass(project, targetSuper);
        if (javaSub != null && javaSuper != null) {
            return javaSub.isInheritor(javaSuper, true) || javaSub.equals(javaSuper);
        }

        return false;
    }
}
