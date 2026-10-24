package ocean.plugin.psi;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import ocean.plugin.*;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiElement.class),
                new PsiReferenceProvider() {
                    @NotNull
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                          @NotNull ProcessingContext context) {
                        String text = element.getText();
                        if (OceanKeywords.isKeyword(text)) {
                            return PsiReference.EMPTY_ARRAY;
                        }
                        OceanIdReference referenceNode = findReferenceNode(element);
                        if (referenceNode != null) {
                            return new PsiReference[]{new DelegatingOceanReference(element, referenceNode)};
                        }
                        if (isIdentifierLike(element) && isImportOrTypeContext(element)) {
                            return new PsiReference[]{new ImportOrTypeReference(element)};
                        }
                        return PsiReference.EMPTY_ARRAY;
                    }
                });
    }

    private static @Nullable OceanIdReference findReferenceNode(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current instanceof OceanIdReference reference) return reference;
            current = current.getParent();
        }
        return null;
    }

    private static boolean isIdentifierLike(PsiElement element) {
        if (element == null || element.getNode() == null) return false;
        String text = element.getText();
        if (text == null || text.isEmpty() || OceanKeywords.isKeyword(text)) return false;
        String type = element.getNode().getElementType().toString();
        if (type.contains("IDENTIFIER") || type.contains("anyId")) return true;
        return text.matches("[A-Za-z_][A-Za-z0-9_]*");
    }


    private static boolean isImportOrTypeContext(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current.getNode() != null && current.getNode().getElementType() instanceof RuleIElementType ri) {
                int rule = ri.getRuleIndex();
                if (rule == OceanParser.RULE_importStatement ||
                        rule == OceanParser.RULE_type ||
                        rule == OceanParser.RULE_typeName ||
                        rule == OceanParser.RULE_annotation) {
                    return true;
                }
                if (rule == OceanParser.RULE_expression ||
                        rule == OceanParser.RULE_statement ||
                        rule == OceanParser.RULE_block ||
                        rule == OceanParser.RULE_methodDeclaration ||
                        rule == OceanParser.RULE_classDeclaration) {
                    return false;
                }
            }
            current = current.getParent();
        }
        return false;
    }

    private static class ImportOrTypeReference extends PsiReferenceBase<PsiElement> implements PsiPolyVariantReference {
        private ImportOrTypeReference(@NotNull PsiElement element) {
            super(element, new TextRange(0, element.getTextLength()), false);
        }

        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            PsiElement resolved = resolve();
            return resolved == null ? ResolveResult.EMPTY_ARRAY : new ResolveResult[]{new PsiElementResolveResult(resolved)};
        }

        @Override
        public @Nullable PsiElement resolve() {
            String name = myElement.getText();
            if (name == null || name.isEmpty() || "*".equals(name) || OceanKeywords.isKeyword(name)) return null;

            String qualified = qualifiedImportAt(myElement);
            JavaPsiFacade facade = JavaPsiFacade.getInstance(myElement.getProject());
            PsiFile containingFile = myElement.getContainingFile();

            // 1. If in an import statement with a qualified path (e.g. models.User or animal.Animal):
            if (qualified != null && !qualified.isEmpty()) {
                if (qualified.endsWith("." + name) || qualified.equals(name)) {
                    // Clicking the imported class name:
                    // A. Search Ocean class by qualified name
                    PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(myElement.getProject(), qualified, containingFile);
                    if (oceanCls != null) return oceanCls;

                    // B. Search Java class by qualified name
                    PsiClass psiClass = facade.findClass(qualified, GlobalSearchScope.allScope(myElement.getProject()));
                    if (psiClass != null) return psiClass;

                    // C. Fallback: Search Ocean class by simple name
                    oceanCls = OceanScopeDiscovery.findOceanClass(myElement.getProject(), name, containingFile);
                    if (oceanCls != null) return oceanCls;

                    // D. Fallback: Search Java class by simple name
                    psiClass = OceanScopeDiscovery.resolveJavaClass(myElement.getProject(), name);
                    if (psiClass != null) return psiClass;
                } else {
                    // Clicking an intermediate package segment (e.g. `animal` in `animal.Animal`):
                    String prefix = getImportPrefixUpTo(myElement);
                    if (prefix != null && !prefix.isEmpty()) {
                        PsiPackage pkg = facade.findPackage(prefix);
                        if (pkg != null) return pkg;

                        PsiElement prefixOceanCls = OceanScopeDiscovery.findOceanClass(myElement.getProject(), prefix, containingFile);
                        if (prefixOceanCls != null) return prefixOceanCls;

                        PsiClass prefixJavaCls = facade.findClass(prefix, GlobalSearchScope.allScope(myElement.getProject()));
                        if (prefixJavaCls != null) return prefixJavaCls;
                    }
                }
            }

            // 2. Search Ocean class by simple name
            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(myElement.getProject(), name, containingFile);
            if (oceanCls != null) return oceanCls;

            // 3. Search Java class by simple name
            return OceanScopeDiscovery.resolveJavaClass(myElement.getProject(), name);
        }

        @Override
        public @NotNull Object @NotNull [] getVariants() {
            return new Object[0];
        }

        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) {
            PsiElement newIdentifier = OceanElementFactory.createIdentifier(myElement.getProject(), newElementName);
            if (newIdentifier != null) {
                myElement.replace(newIdentifier);
            }
            return myElement;
        }

        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            if (!(element instanceof PsiNamedElement named)) return false;
            String name = named.getName();
            if (name == null || !name.equals(myElement.getText())) return false;
            PsiElement resolved = resolve();
            if (resolved == element) return true;
            for (ResolveResult rr : multiResolve(false)) {
                if (rr.getElement() == element) return true;
            }
            return false;
        }

        private static @Nullable String qualifiedImportAt(PsiElement element) {
            PsiElement current = element;
            while (current != null) {
                if (current.getNode() != null && current.getNode().getElementType() instanceof RuleIElementType ri &&
                        ri.getRuleIndex() == OceanParser.RULE_importStatement) {
                    StringBuilder sb = new StringBuilder();
                    for (PsiElement child = current.getFirstChild(); child != null; child = child.getNextSibling()) {
                        String ct = child.getText().trim();
                        if ("import".equals(ct) || "static".equals(ct) || ";".equals(ct) || ct.isEmpty()) {
                            continue;
                        }
                        sb.append(ct);
                    }
                    String qualified = sb.toString().trim();
                    if (qualified.endsWith(".*")) {
                        return null;
                    }
                    return qualified.isEmpty() ? null : qualified;
                }
                current = current.getParent();
            }
            return null;
        }

        private static @Nullable String getImportPrefixUpTo(PsiElement element) {
            PsiElement current = element;
            while (current != null) {
                if (current.getNode() != null && current.getNode().getElementType() instanceof RuleIElementType ri &&
                        ri.getRuleIndex() == OceanParser.RULE_importStatement) {
                    StringBuilder sb = new StringBuilder();
                    for (PsiElement child = current.getFirstChild(); child != null; child = child.getNextSibling()) {
                        String ct = child.getText().trim();
                        if ("import".equals(ct) || "static".equals(ct) || ";".equals(ct) || ct.isEmpty()) {
                            continue;
                        }
                        sb.append(ct);
                        if (child == element || PsiTreeUtil.isAncestor(child, element, false)) {
                            break;
                        }
                    }
                    String res = sb.toString().trim();
                    if (res.endsWith(".")) res = res.substring(0, res.length() - 1);
                    return res.isEmpty() ? null : res;
                }
                current = current.getParent();
            }
            return null;
        }
    }

    private static class DelegatingOceanReference extends PsiReferenceBase<PsiElement> implements PsiPolyVariantReference {
        private final OceanIdReference delegate;

        private DelegatingOceanReference(@NotNull PsiElement element, @NotNull OceanIdReference delegate) {
            super(element, new TextRange(0, element.getTextLength()), false);
            this.delegate = delegate;
        }

        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            return delegate.multiResolve(incompleteCode);
        }

        @Override
        public @Nullable PsiElement resolve() {
            return delegate.resolve();
        }

        @Override
        public @NotNull Object @NotNull [] getVariants() {
            return delegate.getVariants();
        }

        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) {
            return delegate.handleElementRename(newElementName);
        }

        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            return delegate.isReferenceTo(element);
        }

        @Override
        public boolean isSoft() {
            return delegate.isSoft();
        }
    }
}
