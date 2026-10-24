package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanIdReference extends OceanPsiNode implements PsiPolyVariantReference {
    public OceanIdReference(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public PsiReference getReference() { return this; }

    @Override
    public @NotNull PsiElement getElement() { return this; }

    @Override
    public @NotNull TextRange getRangeInElement() { return new TextRange(0, getTextLength()); }

    @Override
    public @Nullable PsiElement resolve() {
        ResolveResult[] resolveResults = multiResolve(false);
        if (resolveResults.length == 0) return null;
        for (ResolveResult rr : resolveResults) {
            PsiElement el = rr.getElement();
            if (el != null && (!OceanOverloadResolver.isPrivateMember(el) || isAccessibleSelfAccess(el))) {
                return el;
            }
        }
        return resolveResults[0].getElement();
    }

    private boolean isAccessibleSelfAccess(PsiElement target) {
        if (target == null) return false;
        PsiFile targetFile = target.getContainingFile();
        PsiFile thisFile = getContainingFile();
        if (targetFile != null && targetFile.equals(thisFile)) {
            PsiElement receiver = findMemberReceiver();
            if (receiver == null || "this".equals(receiver.getText().trim())) {
                return true;
            }
            PsiElement targetEnclosing = findEnclosingType(target);
            PsiElement callerEnclosing = findEnclosingType(this);
            return targetEnclosing != null && targetEnclosing.equals(callerEnclosing);
        }
        return false;
    }

    private PsiElement findEnclosingType(PsiElement element) {
        PsiElement cur = element != null ? element.getParent() : null;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                int idx = ri.getRuleIndex();
                if (idx == OceanParser.RULE_classDeclaration ||
                    idx == OceanParser.RULE_interfaceDeclaration ||
                    idx == OceanParser.RULE_enumDeclaration) {
                    return cur;
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static final ResolveCache.PolyVariantResolver<OceanIdReference> RESOLVER = OceanIdReference::multiResolveInner;

    @Override
    public @NotNull ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        return ResolveCache.getInstance(getProject()).resolveWithCaching(this, RESOLVER, false, incompleteCode);
    }

    private @NotNull ResolveResult @NotNull [] multiResolveInner(boolean incompleteCode) {
        List<ResolveResult> results = new ArrayList<>();
        String text = getText();
        if (text == null || text.isEmpty()) return new ResolveResult[0];

        // 1. Built-in Reserved Names and Keywords (never resolve to self or search usages)
        if (isBuiltIn(text)) {
            if ("String".equals(text) && isTypeContext()) {
                PsiClass strClass = OceanScopeDiscovery.resolveJavaClass(getProject(), "java.lang.String", getContainingFile());
                if (strClass != null) {
                    return new ResolveResult[]{new PsiElementResolveResult(strClass)};
                }
            }
            return new ResolveResult[0];
        }

        // 1.1 Type Context Resolution (e.g. extends Animal, implements IService, Animal a, new Animal(), List<Animal>)
        // Only resolves to types (classes, interfaces, enums, annotations, type parameters).
        // Prevents matching irrelevant constructors, methods, fields, or local variables.
        if (isTypeContext()) {
            PsiFile containingFile = getContainingFile();
            if (containingFile != null) {
                // A. Current file: find class/interface/enum/annotation with matching name
                PsiElement localCls = OceanScopeDiscovery.findClassDeep(containingFile, text);
                if (localCls != null) {
                    results.add(new PsiElementResolveResult(localCls));
                    return results.toArray(new ResolveResult[0]);
                }

                // B. Explicit imports (e.g. import foo.bar.Animal;) - explicit imports ALWAYS shadow same package!
                OceanFileInfo info = getFileInfo(containingFile);
                for (String explicit : info.explicitImports) {
                    if (explicit.endsWith("." + text) || explicit.equals(text)) {
                        PsiClass importedClass = JavaPsiFacade.getInstance(getProject()).findClass(explicit, GlobalSearchScope.allScope(getProject()));
                        if (importedClass == null) {
                            importedClass = OceanScopeDiscovery.resolveJavaClass(getProject(), explicit);
                        }
                        if (importedClass != null) {
                            results.add(new PsiElementResolveResult(importedClass));
                            return results.toArray(new ResolveResult[0]);
                        }
                        PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), explicit, containingFile);
                        if (oceanCls != null) {
                            results.add(new PsiElementResolveResult(oceanCls));
                            return results.toArray(new ResolveResult[0]);
                        }
                    }
                }

                // C. Package-local scope (same package/directory)
                if (!info.packageName.isEmpty()) {
                    PsiElement pkgOceanCls = OceanScopeDiscovery.findOceanClass(getProject(), info.packageName + "." + text, containingFile);
                    if (pkgOceanCls != null) {
                        results.add(new PsiElementResolveResult(pkgOceanCls));
                        return results.toArray(new ResolveResult[0]);
                    }
                }

                // D. Wildcard imports (e.g. import foo.bar.*;)
                for (String pkg : info.wildcardImports) {
                    String fullImport = pkg + "." + text;
                    PsiClass importedClass = JavaPsiFacade.getInstance(getProject()).findClass(fullImport, GlobalSearchScope.allScope(getProject()));
                    if (importedClass == null) {
                        importedClass = OceanScopeDiscovery.resolveJavaClass(getProject(), fullImport);
                    }
                    if (importedClass != null) {
                        results.add(new PsiElementResolveResult(importedClass));
                        return results.toArray(new ResolveResult[0]);
                    }
                    PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), fullImport, containingFile);
                    if (oceanCls != null) {
                        results.add(new PsiElementResolveResult(oceanCls));
                        return results.toArray(new ResolveResult[0]);
                    }
                }
            }

            // E. Generic type parameters (T, E, K, V) in enclosing class/method
            if (looksLikeTypeParam(text)) {
                PsiElement typeParamDecl = resolveAsTypeParameter(text);
                if (typeParamDecl != null) {
                    results.add(new PsiElementResolveResult(typeParamDecl));
                    return results.toArray(new ResolveResult[0]);
                }
            }

            // F. Global project search (Ocean -> Java -> java.lang)
            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), text, containingFile);
            if (oceanCls != null) {
                results.add(new PsiElementResolveResult(oceanCls));
                return results.toArray(new ResolveResult[0]);
            }
            PsiClass jc = OceanScopeDiscovery.resolveJavaClass(getProject(), text);
            if (jc != null) {
                results.add(new PsiElementResolveResult(jc));
                return results.toArray(new ResolveResult[0]);
            }

            return new ResolveResult[0];
        }

        // 1.4 Named Argument Key (e.g. foo(arg = 10))
        if (isNamedArgumentKey()) {
            results.add(new PsiElementResolveResult(this));
            return results.toArray(new ResolveResult[0]);
        }

        // 1.5 Annotation Attribute Key (e.g. @Column(name = "...", nullable = false))
        if (isAnnotationAttributeKey()) {
            String annoTypeName = findEnclosingAnnotationTypeName();
            if (annoTypeName != null) {
                resolveInClass(annoTypeName, text, results);
                if (!results.isEmpty()) return results.toArray(new ResolveResult[0]);
            }
            results.add(new PsiElementResolveResult(this));
            return results.toArray(new ResolveResult[0]);
        }

        // 1.8. Qualified TypeName / Package segment resolution (e.g. java.lang.Class, com.example.User)
        PsiElement typeNameParent = findEnclosingTypeName();
        if (typeNameParent != null) {
            String fullTypeName = typeNameParent.getText().trim();
            if (fullTypeName.contains(".")) {
                PsiClass jc = OceanScopeDiscovery.resolveJavaClass(getProject(), fullTypeName);
                if (jc != null) {
                    String prefix = getPackagePrefixUpTo(typeNameParent, this);
                    if (prefix != null) {
                        PsiPackage pkg = JavaPsiFacade.getInstance(getProject()).findPackage(prefix);
                        if (pkg != null) {
                            results.add(new PsiElementResolveResult(pkg));
                            return results.toArray(new ResolveResult[0]);
                        }
                    }
                    results.add(new PsiElementResolveResult(jc));
                    return results.toArray(new ResolveResult[0]);
                }
                PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), fullTypeName);
                if (oceanCls != null) {
                    results.add(new PsiElementResolveResult(oceanCls));
                    return results.toArray(new ResolveResult[0]);
                }
                String prefix = getPackagePrefixUpTo(typeNameParent, this);
                if (prefix != null) {
                    PsiPackage pkg = JavaPsiFacade.getInstance(getProject()).findPackage(prefix);
                    if (pkg != null) {
                        results.add(new PsiElementResolveResult(pkg));
                        return results.toArray(new ResolveResult[0]);
                    }
                }
            }
        }
        
        // 2. Member Access (DOT / SAFE_DOT)
        PsiElement receiver = findMemberReceiver();
        if (receiver != null && receiver != this) {
            String receiverType = OceanTypeHelper.guessTypeFromTextOrElement(receiver.getText(), receiver);
            if (receiverType == null) {
                receiverType = OceanTypeHelper.guessType(receiver);
            }
            if (receiverType != null) {
                resolveInClass(receiverType, text, results);
                if (!results.isEmpty()) return results.toArray(new ResolveResult[0]);
            }

            if (receiverType == null) {
                resolveInClass(null, text, results);
                if (!results.isEmpty()) return results.toArray(new ResolveResult[0]);
            }

            return ResolveResult.EMPTY_ARRAY;
        }

        boolean inTypeContext = isTypeContext();
        boolean memberReceiver = isMemberReceiver();

        // 3. Local Scoping
        List<PsiElement> visibleSymbols = OceanScopeDiscovery.findVisibleSymbols(this);
        List<PsiElement> matchedLocalSymbols = new ArrayList<>();
        for (PsiElement symbol : visibleSymbols) {
            if (inTypeContext && (symbol instanceof OceanMethodDeclaration || symbol instanceof OceanFieldDeclaration ||
                    symbol instanceof OceanVariableDeclarator || symbol instanceof OceanParameter ||
                    symbol instanceof OceanLoopVariable || symbol instanceof OceanCatchVariable)) {
                continue; // In type context (like extends/implements/type name), ignore constructors, methods and variables
            }
            if (memberReceiver && (symbol instanceof OceanMethodDeclaration && isConstructor(symbol))) {
                continue; // In receiver position (e.g. MyClass.staticField), ignore constructors so it resolves to the class
            }
            if (symbol instanceof PsiNamedElement pNamed && text.equals(pNamed.getName())) {
                matchedLocalSymbols.add(pNamed);
            }
        }
        if (!matchedLocalSymbols.isEmpty()) {
            OceanOverloadResolver.CallInfo callInfo = OceanOverloadResolver.findCallInfo(this);
            if (callInfo.isCall && matchedLocalSymbols.size() > 1) {
                matchedLocalSymbols = OceanOverloadResolver.filterAndRankCandidates(matchedLocalSymbols, callInfo.argumentTypes, getProject());
            } else if (!callInfo.isCall && memberReceiver) {
                // If this is a member receiver (e.g. MyClass.staticField), prioritize class declarations over methods/constructors
                for (PsiElement sym : matchedLocalSymbols) {
                    if (sym instanceof OceanClassDeclaration || sym instanceof OceanInterfaceDeclaration || sym instanceof OceanEnumDeclaration) {
                        results.add(new PsiElementResolveResult(sym));
                        return results.toArray(new ResolveResult[0]);
                    }
                }
            }
            for (PsiElement sym : matchedLocalSymbols) {
                results.add(new PsiElementResolveResult(sym));
            }
            return results.toArray(new ResolveResult[0]);
        }

        // 4. Imports and Package-Local Scope (Cached per file)
        PsiFile file = getContainingFile();
        if (file != null) {
            OceanFileInfo info = getFileInfo(file);
            
            // 4a. Explicit imports (explicit imports ALWAYS shadow same-package classes!)
            for (String explicit : info.explicitImports) {
                if (explicit.endsWith("." + text) || explicit.equals(text)) {
                    PsiClass importedClass = JavaPsiFacade.getInstance(getProject()).findClass(explicit, GlobalSearchScope.allScope(getProject()));
                    if (importedClass == null) {
                        importedClass = OceanScopeDiscovery.resolveJavaClass(getProject(), explicit);
                    }
                    if (importedClass != null) {
                        results.add(new PsiElementResolveResult(importedClass));
                        return results.toArray(new ResolveResult[0]);
                    }
                    PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), explicit, file);
                    if (oceanCls != null) {
                        results.add(new PsiElementResolveResult(oceanCls));
                        return results.toArray(new ResolveResult[0]);
                    }
                }
            }

            // 4b. Package-Local Scope
            if (!info.packageName.isEmpty()) {
                PsiElement localOceanCls = OceanScopeDiscovery.findOceanClass(getProject(), info.packageName + "." + text, file);
                if (localOceanCls != null) {
                    results.add(new PsiElementResolveResult(localOceanCls));
                    return results.toArray(new ResolveResult[0]);
                }
            }

            // 4c. Single static imports (e.g. import static util.Math.calculate)
            for (String stat : info.staticImports) {
                if (!stat.endsWith(".*") && stat.endsWith("." + text)) {
                    String owner = stat.substring(0, stat.length() - text.length() - 1);
                    resolveInClass(owner, text, results, true);
                    if (!results.isEmpty()) return results.toArray(new ResolveResult[0]);
                }
            }

            // 4d. Wildcard imports
            for (String pkg : info.wildcardImports) {
                String fullImport = pkg + "." + text;
                PsiClass importedClass = JavaPsiFacade.getInstance(getProject()).findClass(fullImport, GlobalSearchScope.allScope(getProject()));
                if (importedClass == null) {
                    importedClass = OceanScopeDiscovery.resolveJavaClass(getProject(), fullImport);
                }
                if (importedClass != null) {
                    results.add(new PsiElementResolveResult(importedClass));
                    return results.toArray(new ResolveResult[0]);
                }
                PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), fullImport, file);
                if (oceanCls != null) {
                    results.add(new PsiElementResolveResult(oceanCls));
                    return results.toArray(new ResolveResult[0]);
                }
            }

            // 4e. Static wildcard imports (e.g. import static util.Math.*)
            for (String stat : info.staticImports) {
                if (stat.endsWith(".*")) {
                    String owner = stat.substring(0, stat.length() - 2);
                    resolveInClass(owner, text, results, true);
                    if (!results.isEmpty()) return results.toArray(new ResolveResult[0]);
                }
            }
        }

        // 4e. Switch Case Context (e.g. switch(color) { case RED: ... })
        PsiElement switchTarget = findEnclosingSwitchTarget();
        if (switchTarget != null) {
            String switchType = OceanTypeHelper.guessType(switchTarget);
            if (switchType != null) {
                resolveInClass(switchType, text, results);
                if (!results.isEmpty()) return results.toArray(new ResolveResult[0]);
            }
        }

        // 5. P3-B: Generic type parameter name resolution (T, E, K, V, etc.)
        // Before falling through to global search, check if this identifier is a type parameter
        // declared in an enclosing class or method declaration (e.g. class Foo<T>, function<K> ...)
        if (looksLikeTypeParam(text)) {
            PsiElement typeParamDecl = resolveAsTypeParameter(text);
            if (typeParamDecl != null) {
                results.add(new PsiElementResolveResult(typeParamDecl));
                return results.toArray(new ResolveResult[0]);
            }
        }

        // 6. Global Search (Ocean -> Java -> java.lang) - only for potential type names
        if (Character.isUpperCase(text.charAt(0)) || text.contains(".")) {
            // Prioritize standard Java / stdlib classes before arbitrary project-wide Ocean classes
            PsiClass stdJava = OceanScopeDiscovery.resolveJavaClass(getProject(), text, file);
            if (stdJava != null && stdJava.getQualifiedName() != null &&
                    (stdJava.getQualifiedName().startsWith("java.") || stdJava.getQualifiedName().startsWith("ocean.stdlib."))) {
                results.add(new PsiElementResolveResult(stdJava));
                return results.toArray(new ResolveResult[0]);
            }

            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), text, file);
            if (oceanCls != null) {
                results.add(new PsiElementResolveResult(oceanCls));
            } else {
                PsiClass jc = stdJava != null ? stdJava : OceanScopeDiscovery.resolveJavaClass(getProject(), text, file);
                if (jc != null) results.add(new PsiElementResolveResult(jc));
            }
        }

        return results.toArray(new ResolveResult[0]);
    }

    public static class OceanFileInfo {
        public final String packageName;
        public final List<String> explicitImports = new ArrayList<>();
        public final List<String> wildcardImports = new ArrayList<>();
        public final List<String> staticImports = new ArrayList<>();

        public OceanFileInfo(PsiFile file) {
            String pkg = "";
            if (file != null) {
                // 1. AST Search for package and imports
                PsiElement[] declarations = PsiTreeUtil.collectElements(file, e -> {
                    if (e.getNode() == null || !(e.getNode().getElementType() instanceof RuleIElementType ri)) return false;
                    int idx = ri.getRuleIndex();
                    return idx == OceanParser.RULE_packageDeclaration || idx == OceanParser.RULE_importStatement;
                });
                for (PsiElement decl : declarations) {
                    if (decl.getNode().getElementType() instanceof RuleIElementType ri) {
                        int idx = ri.getRuleIndex();
                        if (idx == OceanParser.RULE_packageDeclaration) {
                            StringBuilder sb = new StringBuilder();
                            for (PsiElement child = decl.getFirstChild(); child != null; child = child.getNextSibling()) {
                                String ct = child.getText().trim();
                                if ("package".equals(ct) || ";".equals(ct) || ct.isEmpty()) continue;
                                sb.append(ct);
                            }
                            String p = sb.toString().trim();
                            if (!p.isEmpty()) pkg = p;
                        } else if (idx == OceanParser.RULE_importStatement) {
                            boolean isStatic = false;
                            StringBuilder sb = new StringBuilder();
                            for (PsiElement child = decl.getFirstChild(); child != null; child = child.getNextSibling()) {
                                String ct = child.getText().trim();
                                if ("import".equals(ct) || ";".equals(ct) || ct.isEmpty()) continue;
                                if ("static".equals(ct)) {
                                    isStatic = true;
                                    continue;
                                }
                                sb.append(ct);
                            }
                            String s = sb.toString().trim();
                            if (!s.isEmpty()) {
                                if (isStatic) {
                                    staticImports.add(s);
                                } else if (s.endsWith(".*")) {
                                    wildcardImports.add(s.substring(0, s.length() - 2));
                                } else {
                                    explicitImports.add(s);
                                }
                            }
                        }
                    }
                }

                // 2. Regex fallback for package if not found in AST
                if (pkg.isEmpty()) {
                    String fileText = file.getText();
                    if (fileText != null && !fileText.isEmpty()) {
                        Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(fileText);
                        if (m.find()) {
                            pkg = m.group(1).trim();
                        }
                    }
                }

                // 3. Regex fallback for imports if AST was incomplete during editing
                if (explicitImports.isEmpty() && wildcardImports.isEmpty() && staticImports.isEmpty()) {
                    String fileText = file.getText();
                    if (fileText != null && !fileText.isEmpty()) {
                        Matcher im = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE).matcher(fileText);
                        while (im.find()) {
                            boolean isStatic = im.group(1) != null;
                            String imp = im.group(2).trim();
                            if (isStatic) {
                                staticImports.add(imp);
                            } else if (imp.endsWith(".*")) {
                                wildcardImports.add(imp.substring(0, imp.length() - 2));
                            } else {
                                explicitImports.add(imp);
                            }
                        }
                    }
                }
            }
            this.packageName = pkg;
        }
    }

    public static OceanFileInfo getFileInfo(PsiFile file) {
        if (file == null) return new OceanFileInfo(null);
        if (CachedValuesManager.getManager(file.getProject()) == null) {
            return new OceanFileInfo(file);
        }
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(new OceanFileInfo(file), file)
        );
    }

    private static final Set<String> BUILT_IN_KEYWORDS = Set.of(
            "OceanOutput", "OceanInput", "Output", "this", "super", "args",
            "true", "false", "null", "variable", "value",
            "int", "bool", "String", "void", "double", "float",
            "long", "char", "byte", "short",
            "throws", "throw", "annotation", "sealed", "non-sealed", "restricts",
            "async", "await", "native", "when", "trying", "catch", "finally",
            "for", "from", "to", "with", "increasing", "decreasing", "in",
            "while", "do", "if", "else", "switch", "case", "default",
            "return", "skip", "stop", "sync", "lock", "function",
            "class", "interface", "enum", "extends", "implements", "package", "import",
            "public", "private", "protected", "static", "final", "abstract", "instanceof", "new", "verify"
    );

    static boolean isBuiltIn(String text) {
        if (text == null || text.isEmpty()) return false;
        // Contextual keywords can be valid identifiers (variables, functions, fields)
        if ("data".equals(text) || "result".equals(text) || "main".equals(text)) return false;
        if (text.startsWith("OceanOutput") || text.startsWith("OceanInput")) return true;
        return OceanKeywords.isKeyword(text) || BUILT_IN_KEYWORDS.contains(text);
    }

    private void resolveInClass(String className, String memberName, List<ResolveResult> results) {
        resolveInClass(className, memberName, results, false);
    }

    private void resolveInClass(String className, String memberName, List<ResolveResult> results, boolean staticOnly) {
        if (memberName == null) return;
        if (className != null && "length".equals(memberName) && (className.endsWith("[]") || className.equals("java.lang.Object[]"))) {
            if (!staticOnly) results.add(new PsiElementResolveResult(this));
            return;
        }

        // Constructor method reference: ClassName::new
        if ("new".equals(memberName) && className != null) {
            if (!staticOnly) {
                PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(getProject(), className);
                if (oceanCls != null) {
                    for (PsiElement child : oceanCls.getChildren()) {
                        if (isRule(child, OceanParser.RULE_constructorDeclaration)) {
                            results.add(new PsiElementResolveResult(child));
                        }
                    }
                    if (!results.isEmpty()) return;
                }
                PsiClass javaCls = OceanScopeDiscovery.resolveJavaClass(getProject(), className);
                if (javaCls != null) {
                    for (PsiMethod ctor : javaCls.getConstructors()) {
                        results.add(new PsiElementResolveResult(ctor));
                    }
                    if (!results.isEmpty()) return;
                }
            }
        }

        List<PsiElement> members = new ArrayList<>();
        if (className != null) {
            OceanScopeDiscovery.resolveMemberChain(className, memberName, members, getProject(), new HashSet<>(), this);
        } else {
            Map<String, List<OceanMethodDeclaration>> extMap = OceanScopeDiscovery.getProjectExtensionMethods(getProject());
            List<OceanMethodDeclaration> extList = extMap.get(memberName);
            if (extList != null) members.addAll(extList);
        }

        if (staticOnly) {
            members.removeIf(m -> {
                if (m instanceof PsiModifierListOwner owner) {
                    return !owner.hasModifierProperty(PsiModifier.STATIC);
                }
                if (m != null) {
                    String t = m.getText();
                    int funcIdx = t.indexOf("function");
                    if (funcIdx > 0) return !t.substring(0, funcIdx).contains("static");
                    return !t.contains("static");
                }
                return true;
            });
        }

        // Overload disambiguation: check if this is a method call
        OceanOverloadResolver.CallInfo callInfo = OceanOverloadResolver.findCallInfo(this);
        if (callInfo.isCall && members.size() > 1) {
            members = OceanOverloadResolver.filterAndRankCandidates(members, callInfo.argumentTypes, getProject());
        }

        if (members.size() > 1) {
            members.sort((a, b) -> Boolean.compare(OceanOverloadResolver.isPrivateMember(a), OceanOverloadResolver.isPrivateMember(b)));
        }

        for (PsiElement m : members) results.add(new PsiElementResolveResult(m));
    }

    private PsiElement findMemberReceiver() {
        PsiElement parent = getParent();
        while (parent != null) {
            if (isExpressionRule(parent)) {
                PsiElement dot = null;
                for (PsiElement child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                    String childText = child.getText();
                    if (".".equals(childText) || "?.".equals(childText) || "::".equals(childText)) {
                        dot = child;
                        break;
                    }
                }
                if (dot != null) {
                    PsiElement member = nextVisibleSibling(dot);
                    if (member == this || PsiTreeUtil.isAncestor(member, this, false)) {
                        return prevVisibleSibling(dot);
                    }
                }
            }
            parent = parent.getParent();
        }
        return null;
    }

    private boolean isExpressionRule(PsiElement element) {
        return element != null &&
                element.getNode() != null &&
                element.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == OceanParser.RULE_expression;
    }

    private PsiElement prevVisibleSibling(PsiElement element) {
        PsiElement sibling = element.getPrevSibling();
        while (sibling != null && sibling.getText().trim().isEmpty()) sibling = sibling.getPrevSibling();
        return sibling;
    }

    private PsiElement nextVisibleSibling(PsiElement element) {
        PsiElement sibling = element.getNextSibling();
        while (sibling != null && sibling.getText().trim().isEmpty()) sibling = sibling.getNextSibling();
        return sibling;
    }

    private boolean isTypeContext() {
        PsiElement prev = prevVisibleSibling(this);
        if (prev != null && ("new".equals(prev.getText().trim()) || "instanceof".equals(prev.getText().trim()) || "extends".equals(prev.getText().trim()) || "implements".equals(prev.getText().trim()) || "restricts".equals(prev.getText().trim()))) {
            return true;
        }

        PsiElement cur = getParent();
        while (cur != null) {
            if (cur.getNode() != null &&
                    cur.getNode().getElementType() instanceof RuleIElementType ri) {
                int rule = ri.getRuleIndex();
                if (rule == OceanParser.RULE_type ||
                        rule == OceanParser.RULE_typeName ||
                        rule == OceanParser.RULE_typeParameter) {
                    return true;
                }
                if (rule == OceanParser.RULE_statement ||
                        rule == OceanParser.RULE_block) {
                    return false;
                }
            }
            cur = cur.getParent();
        }
        return false;
    }

    private boolean isMemberReceiver() {
        PsiElement parent = getParent();
        while (parent != null) {
            if (isExpressionRule(parent)) {
                for (PsiElement child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                    String childText = child.getText();
                    if (".".equals(childText) || "?.".equals(childText) || "::".equals(childText)) {
                        PsiElement receiver = prevVisibleSibling(child);
                        return receiver == this || PsiTreeUtil.isAncestor(receiver, this, false);
                    }
                }
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isConstructor(PsiElement element) {
        if (element == null) return false;
        if (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri) {
            if (ri.getRuleIndex() == OceanParser.RULE_constructorDeclaration) return true;
        }
        PsiElement p = element.getParent();
        while (p != null) {
            if (p.getNode() != null && p.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_constructorDeclaration) return true;
                if (ri.getRuleIndex() == OceanParser.RULE_methodDeclaration ||
                    ri.getRuleIndex() == OceanParser.RULE_classDeclaration) break;
            }
            p = p.getParent();
        }
        return false;
    }

    @Override
    public @NotNull String getCanonicalText() { return getText(); }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        PsiElement newIdentifier = OceanElementFactory.createIdentifier(getProject(), newElementName);
        if (newIdentifier != null) replace(newIdentifier);
        return this;
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) { return null; }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        if (!(element instanceof PsiNamedElement named)) return false;
        String name = named.getName();
        if (name == null || !name.equals(getText())) return false;

        PsiElement resolved = resolve();
        if (resolved == element || isSameTarget(resolved, element)) return true;

        for (ResolveResult rr : multiResolve(false)) {
            PsiElement el = rr.getElement();
            if (el == element || isSameTarget(el, element)) return true;
        }
        return false;
    }

    private static boolean isSameTarget(PsiElement resolved, PsiElement element) {
        if (resolved == null || element == null) return false;
        if (resolved.equals(element)) return true;
        if (resolved instanceof OceanNamedElement namedResolved && element instanceof OceanNamedElement namedTarget) {
            return namedResolved.getTextRange() != null && namedResolved.getTextRange().equals(namedTarget.getTextRange());
        }
        return false;
    }

    private PsiElement findEnclosingSwitchTarget() {
        PsiElement cur = getParent();
        while (cur != null) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                int r = ri.getRuleIndex();
                if (r == OceanParser.RULE_switchCase || r == OceanParser.RULE_switchExpressionCase) {
                    PsiElement switchStmt = cur.getParent();
                    if (switchStmt != null) {
                        for (PsiElement child : switchStmt.getChildren()) {
                            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType cri && cri.getRuleIndex() == OceanParser.RULE_expression) {
                                return child;
                            }
                        }
                    }
                    return null;
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private boolean isAnnotationAttributeKey() {
        PsiElement cur = getParent();
        while (cur != null) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                int r = ri.getRuleIndex();
                if (r == OceanParser.RULE_annotationElement) {
                    for (PsiElement child : cur.getChildren()) {
                        if ("=".equals(child.getText())) {
                            return getTextOffset() < child.getTextOffset();
                        }
                    }
                    return true;
                }
                if (r == OceanParser.RULE_expression || r == OceanParser.RULE_statement || r == OceanParser.RULE_block) {
                    return false;
                }
            }
            cur = cur.getParent();
        }
        return false;
    }

    private String findEnclosingAnnotationTypeName() {
        PsiElement cur = getParent();
        while (cur != null) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == OceanParser.RULE_annotation) {
                    for (PsiElement child : cur.getChildren()) {
                        if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType cri && cri.getRuleIndex() == OceanParser.RULE_typeName) {
                            return child.getText().trim();
                        }
                    }
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private PsiElement findEnclosingTypeName() {
        PsiElement cur = getParent();
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_typeName)) return cur;
            if (isRule(cur, OceanParser.RULE_statement) || cur instanceof PsiFile) break;
            cur = cur.getParent();
        }
        return null;
    }

    private String getPackagePrefixUpTo(PsiElement typeNameParent, PsiElement target) {
        StringBuilder sb = new StringBuilder();
        for (PsiElement child : typeNameParent.getChildren()) {
            String t = child.getText().trim();
            if (t.isEmpty()) continue;
            if (!sb.isEmpty() && !sb.toString().endsWith(".") && !t.startsWith(".")) {
                sb.append(".");
            }
            sb.append(t);
            if (child == target || PsiTreeUtil.isAncestor(child, target, false)) {
                return sb.toString();
            }
        }
        return null;
    }

    private boolean isRule(PsiElement element, int ruleIndex) {
        return element != null &&
                element.getNode() != null &&
                element.getNode().getElementType() instanceof RuleIElementType ri &&
                ri.getRuleIndex() == ruleIndex;
    }

    /**
     * P3-B: Returns true if the name looks like a generic type parameter identifier
     * (e.g. single uppercase letter T, all-uppercase KEY/VAL/IN/OUT, or PascalCase In/Out/Item/Target).
     */
    public static boolean looksLikeTypeParam(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!Character.isUpperCase(name.charAt(0))) return false;
        return name.matches("^[a-zA-Z0-9_]{1,30}$");
    }

    /**
     * P3-B: Walk up the PSI tree from this element to find an enclosing class or
     * method declaration that declares a type parameter with the given name.
     * Returns the type parameter node if found, null otherwise.
     */
    private PsiElement resolveAsTypeParameter(String name) {
        PsiElement cur = getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (cur.getNode() != null &&
                    cur.getNode().getElementType() instanceof RuleIElementType ri) {
                int r = ri.getRuleIndex();
                if (r == OceanParser.RULE_classDeclaration ||
                    r == OceanParser.RULE_interfaceDeclaration ||
                    r == OceanParser.RULE_methodDeclaration) {
                    // Look for RULE_typeParameter children whose text matches
                    PsiElement found = findTypeParamByName(cur, name);
                    if (found != null) return found;
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private boolean isNamedArgumentKey() {
        PsiElement cur = getParent();
        if (cur != null && cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
            if (ri.getRuleIndex() == OceanParser.RULE_argument) {
                for (PsiElement child : cur.getChildren()) {
                    if ("=".equals(child.getText())) {
                        return getTextOffset() < child.getTextOffset();
                    }
                }
            }
        }
        return false;
    }

    /**
     * P3-B: Search immediate children of {@code decl} for a RULE_typeParameter
     * node whose identifier part matches {@code name}.
     */
    private PsiElement findTypeParamByName(PsiElement decl, String name) {
        for (PsiElement child : decl.getChildren()) {
            if (child.getNode() != null &&
                    child.getNode().getElementType() instanceof RuleIElementType ri &&
                    ri.getRuleIndex() == OceanParser.RULE_typeParameter) {
                if (child instanceof PsiNamedElement named) {
                    if (name.equals(named.getName())) return child;
                }
                String tpText = child.getText().trim();
                while (tpText.startsWith("+") || tpText.startsWith("-")) tpText = tpText.substring(1).trim();
                int spaceIdx = tpText.indexOf(' ');
                int colonIdx = tpText.indexOf('<');
                int endIdx = tpText.length();
                if (spaceIdx > 0) endIdx = spaceIdx;
                if (colonIdx > 0) endIdx = Math.min(endIdx, colonIdx);
                String tpName = tpText.substring(0, endIdx).trim();
                if (name.equals(tpName)) return child;
            }
        }
        // Regex fallback: parse header text for type parameters
        String headerText = decl.getText();
        int lbrace = headerText.indexOf('{');
        String header = lbrace > 0 ? headerText.substring(0, lbrace) : headerText;
        int lt = header.indexOf('<');
        int gt = header.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String paramList = header.substring(lt + 1, gt);
            for (String param : OceanTypeHelper.splitGenericArguments(paramList)) {
                String p = param.trim();
                while (p.startsWith("+") || p.startsWith("-")) p = p.substring(1).trim();
                int sp = p.indexOf(' ');
                int col = p.indexOf('<');
                int end = p.length();
                if (sp > 0) end = sp;
                if (col > 0) end = Math.min(end, col);
                String n = p.substring(0, end).trim();
                if (name.equals(n)) {
                    // Return the declaration itself as the resolved target
                    return decl;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isSoft() { return false; }
}
