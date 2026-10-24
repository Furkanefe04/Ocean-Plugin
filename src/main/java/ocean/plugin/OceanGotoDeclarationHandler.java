package ocean.plugin;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.Nullable;

public class OceanGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) return null;

        String text = null;
        try {
            text = sourceElement.getText();
        } catch (Exception ignored) {}

        PsiFile file = null;
        try {
            file = sourceElement.getContainingFile();
        } catch (Exception ignored) {}

        PsiElement atOffset = null;
        if (file != null) {
            try {
                atOffset = file.findElementAt(offset);
            } catch (Exception ignored) {}
        }

        PsiElement elementToResolve = atOffset != null ? atOffset : sourceElement;
        if (atOffset != null) {
            try {
                text = atOffset.getText();
            } catch (Exception ignored) {}
        }

        // 1. Keyword check - strictly return EMPTY_ARRAY to prevent Find Usages or fallback hyperlinks,
        // EXCEPT for "String", "OceanInput", "OceanOutput", or "main" which represent types/callables
        if (OceanKeywords.isKeyword(text)) {
            String trimmed = text.trim();
            if ("String".equals(trimmed) || "OceanInput".equals(trimmed) || "OceanOutput".equals(trimmed)) {
                Project project = null;
                try {
                    project = sourceElement.getProject();
                } catch (Exception ignored) {}
                if (project != null) {
                    PsiElement target = resolveTypeTarget(trimmed, project, file);
                    if (target != null) {
                        return new PsiElement[]{target};
                    }
                }
            }
            if ("main".equals(trimmed)) {
                // Allow reference resolution to proceed for 'main' method declaration/call
            } else {
                return PsiElement.EMPTY_ARRAY;
            }
        }

        Project project = null;
        try {
            project = sourceElement.getProject();
        } catch (Exception ignored) {}
        if (project == null) return null;

        // 2. Import statement navigation: resolve imported class, member, or package segment
        PsiElement importStmt = findEnclosingRule(elementToResolve, OceanParser.RULE_importStatement);
        if (importStmt != null) {
            PsiElement target = resolveImportTarget(importStmt, elementToResolve, project, file);
            if (target != null) {
                return new PsiElement[]{target};
            }
        }

        // 3. Type context or static class receiver navigation (e.g. Animal in 'class Dog extends Animal', or 'StringUtils' in 'StringUtils.trim()'):
        if (isTypeContext(elementToResolve) || isPotentialTypeReceiver(elementToResolve)) {
            String typeName = elementToResolve.getText().trim();
            if (!typeName.isEmpty() && (!OceanKeywords.isKeyword(typeName) || "String".equals(typeName))) {
                PsiElement target = resolveTypeTarget(typeName, project, file);
                if (target != null) {
                    return new PsiElement[]{target};
                }
            }
        }

        // 4. Reference navigation (Method calls, variable usages, etc.):
        // Resolves via OceanIdReference and navigates directly to the most specific resolved target.
        PsiReference ref = elementToResolve.getReference();
        if (ref == null && elementToResolve.getParent() != null) {
            ref = elementToResolve.getParent().getReference();
        }
        if (ref instanceof OceanIdReference idRef) {
            ResolveResult[] resolveResults = idRef.multiResolve(false);
            if (resolveResults.length > 0) {
                PsiElement target = resolveResults[0].getElement();
                if (target != null && target != elementToResolve && target != ref.getElement()) {
                    return new PsiElement[]{target};
                }
            }
        }

        return null;
    }

    private static PsiElement resolveImportTarget(PsiElement importStmt, PsiElement element, Project project, PsiFile file) {
        // Build qualified import string
        boolean isStatic = false;
        StringBuilder sb = new StringBuilder();
        for (PsiElement child = importStmt.getFirstChild(); child != null; child = child.getNextSibling()) {
            String ct = child.getText().trim();
            if ("import".equals(ct) || ";".equals(ct) || ct.isEmpty()) continue;
            if ("static".equals(ct)) {
                isStatic = true;
                continue;
            }
            sb.append(ct);
        }
        String qualified = sb.toString().trim();
        if (qualified.isEmpty() || qualified.endsWith(".*")) {
            return null;
        }

        String clickedText = element.getText().trim();
        if (clickedText.isEmpty() || ";".equals(clickedText) || ".".equals(clickedText)) {
            return null;
        }

        // If clicked on the last identifier (class name or static member)
        if (qualified.endsWith("." + clickedText) || qualified.equals(clickedText)) {
            if (isStatic) {
                int lastDot = qualified.lastIndexOf('.');
                if (lastDot > 0) {
                    String ownerClass = qualified.substring(0, lastDot);
                    String memberName = qualified.substring(lastDot + 1);
                    PsiElement oceanOwner = OceanScopeDiscovery.findOceanClass(project, ownerClass, file);
                    if (oceanOwner != null) {
                        for (OceanNamedElement named : PsiTreeUtil.findChildrenOfType(oceanOwner, OceanNamedElement.class)) {
                            if (memberName.equals(named.getName())) {
                                return named;
                            }
                        }
                    }
                    PsiClass javaOwner = JavaPsiFacade.getInstance(project).findClass(ownerClass, GlobalSearchScope.allScope(project));
                    if (javaOwner != null) {
                        for (PsiField f : javaOwner.getFields()) {
                            if (memberName.equals(f.getName())) return f;
                        }
                        for (PsiMethod m : javaOwner.findMethodsByName(memberName, true)) {
                            return m;
                        }
                    }
                }
            }

            // Normal class import:
            // 1. Prioritize full qualified class in Java (handles standard Java & 3rd-party dependencies)
            PsiClass javaCls = JavaPsiFacade.getInstance(project).findClass(qualified, GlobalSearchScope.allScope(project));
            if (javaCls != null) return javaCls;
            javaCls = OceanScopeDiscovery.resolveJavaClass(project, qualified);
            if (javaCls != null) return javaCls;

            // 2. Full qualified class in Ocean (exact match)
            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, qualified, file);
            if (oceanCls != null) return oceanCls;

            // 3. Fallback: only if qualified is not fully resolvable, check simple name if clicked
            oceanCls = OceanScopeDiscovery.findOceanClass(project, clickedText, file);
            if (oceanCls != null) return oceanCls;
            javaCls = OceanScopeDiscovery.resolveJavaClass(project, clickedText);
            return javaCls;
        } else {
            // Clicked intermediate package segment: e.g. "animals" in "zoo.animals.Animal"
            String prefix = getPrefixUpTo(importStmt, element);
            if (prefix != null && !prefix.isEmpty()) {
                // 1. Prioritize real Java/library package
                PsiPackage pkg = JavaPsiFacade.getInstance(project).findPackage(prefix);
                if (pkg != null) return pkg;

                // 2. Check if prefix is an exact Ocean package or class
                PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, prefix, file);
                if (oceanCls != null) return oceanCls;

                // 3. Check if prefix is a Java class (e.g. inner class import)
                return JavaPsiFacade.getInstance(project).findClass(prefix, GlobalSearchScope.allScope(project));
            }
        }
        return null;
    }

    private static String getPrefixUpTo(PsiElement importStmt, PsiElement target) {
        StringBuilder sb = new StringBuilder();
        for (PsiElement child = importStmt.getFirstChild(); child != null; child = child.getNextSibling()) {
            String ct = child.getText().trim();
            if ("import".equals(ct) || "static".equals(ct) || ";".equals(ct) || ct.isEmpty()) continue;
            sb.append(ct);
            if (child == target || PsiTreeUtil.isAncestor(child, target, false)) {
                break;
            }
        }
        String res = sb.toString().trim();
        while (res.endsWith(".")) res = res.substring(0, res.length() - 1);
        return res;
    }

    private static PsiElement resolveTypeTarget(String typeName, Project project, PsiFile file) {
        if (file != null) {
            // A. Local file
            PsiElement local = OceanScopeDiscovery.findClassDeep(file, typeName);
            if (local != null) return local;

            // B. Explicit imports (prioritize imported Java / 3rd party classes!)
            OceanIdReference.OceanFileInfo info = OceanIdReference.getFileInfo(file);
            for (String explicit : info.explicitImports) {
                if (explicit.endsWith("." + typeName) || explicit.equals(typeName)) {
                    PsiClass javaCls = JavaPsiFacade.getInstance(project).findClass(explicit, GlobalSearchScope.allScope(project));
                    if (javaCls != null) return javaCls;
                    javaCls = OceanScopeDiscovery.resolveJavaClass(project, explicit);
                    if (javaCls != null) return javaCls;
                    PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, explicit, file);
                    if (oceanCls != null) return oceanCls;
                }
            }

            // C. Same package Ocean class
            if (!info.packageName.isEmpty()) {
                PsiElement pkgCls = OceanScopeDiscovery.findOceanClass(project, info.packageName + "." + typeName, file);
                if (pkgCls != null) return pkgCls;
            }

            // D. Wildcard imports
            for (String wildcard : info.wildcardImports) {
                String full = wildcard + "." + typeName;
                PsiClass javaCls = JavaPsiFacade.getInstance(project).findClass(full, GlobalSearchScope.allScope(project));
                if (javaCls != null) return javaCls;
                javaCls = OceanScopeDiscovery.resolveJavaClass(project, full);
                if (javaCls != null) return javaCls;
                PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, full, file);
                if (oceanCls != null) return oceanCls;
            }
        }

        // Check standard Java / stdlib classes before arbitrary project-wide Ocean search
        PsiClass stdJava = OceanScopeDiscovery.resolveJavaClass(project, typeName, file);
        if (stdJava != null && stdJava.getQualifiedName() != null &&
                (stdJava.getQualifiedName().startsWith("java.") || stdJava.getQualifiedName().startsWith("ocean.stdlib."))) {
            return stdJava;
        }

        // C. Global Ocean class search
        PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, typeName, file);
        if (oceanCls != null) return oceanCls;

        // D. Global Java class search
        return stdJava != null ? stdJava : OceanScopeDiscovery.resolveJavaClass(project, typeName, file);
    }

    private static boolean isTypeContext(PsiElement element) {
        if (element == null) return false;
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(element);
        if (prev != null && ("new".equals(prev.getText().trim()) || "instanceof".equals(prev.getText().trim()) || "extends".equals(prev.getText().trim()) || "implements".equals(prev.getText().trim()) || "restricts".equals(prev.getText().trim()))) {
            return true;
        }

        PsiElement cur = element;
        while (cur != null) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                int rule = ri.getRuleIndex();
                if (rule == OceanParser.RULE_type ||
                        rule == OceanParser.RULE_typeName ||
                        rule == OceanParser.RULE_typeList ||
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

    private static boolean isPotentialTypeReceiver(PsiElement element) {
        if (element == null) return false;
        String text = element.getText().trim();
        if (text.isEmpty() || !Character.isUpperCase(text.charAt(0))) return false;

        PsiElement next = PsiTreeUtil.nextVisibleLeaf(element);
        if (next != null && (".".equals(next.getText().trim()) || "::".equals(next.getText().trim()))) {
            List<PsiElement> symbols = OceanScopeDiscovery.findVisibleSymbols(element);
            for (PsiElement sym : symbols) {
                if ((sym instanceof OceanVariableDeclarator || sym instanceof OceanParameter)) {
                    PsiNamedElement named = (PsiNamedElement) sym;
                    if (text.equals(named.getName())) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private static PsiElement findEnclosingRule(PsiElement element, int ruleIndex) {
        PsiElement cur = element;
        while (cur != null) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri) {
                if (ri.getRuleIndex() == ruleIndex) return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }
}
