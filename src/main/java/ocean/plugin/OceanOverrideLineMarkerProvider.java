package ocean.plugin;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanOverrideLineMarkerProvider implements LineMarkerProvider {
    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof LeafPsiElement)) return null;
        PsiElement parent = element.getParent();
        if (parent == null || parent.getNode() == null) return null;
        if (!(parent.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_anyId)) return null;

        PsiElement methodNode = parent.getParent();
        if (methodNode == null || methodNode.getNode() == null) return null;
        if (!(methodNode.getNode().getElementType() instanceof RuleIElementType r && r.getRuleIndex() == OceanParser.RULE_methodDeclaration)) return null;

        // Ensure this is the actual name identifier of the method (last direct anyId, not return type)
        PsiElement nameIdentifier = (methodNode instanceof OceanMethodDeclaration md)
                ? md.getNameIdentifier()
                : lastDirectChildByRuleIndex(methodNode, OceanParser.RULE_anyId);
        if (nameIdentifier != parent && nameIdentifier != element) return null;

        String methodName = element.getText().trim();
        if ("main".equals(methodName)) return null; // main method is not considered overriding usually

        // Walk up to find containing class/interface declaration
        PsiElement classNode = methodNode.getParent();
        while (classNode != null) {
            if (classNode.getNode() != null && classNode.getNode().getElementType() instanceof RuleIElementType re && 
                (re.getRuleIndex() == OceanParser.RULE_classDeclaration || re.getRuleIndex() == OceanParser.RULE_interfaceDeclaration)) {
                break;
            }
            classNode = classNode.getParent();
        }
        if (classNode == null) return null;

        // Check 1: Recursive call marker (if method calls itself directly)
        if (hasDirectRecursiveCall(methodNode, methodName)) {
            return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                AllIcons.Gutter.RecursiveMethod,
                psiElement -> "Recursive call",
                null,
                GutterIconRenderer.Alignment.LEFT,
                () -> "Recursive call"
            );
        }

        // Check 2: If this is an interface declaration or abstract method, check if it is implemented in this file/project
        boolean isInterfaceDecl = (classNode.getNode() != null && classNode.getNode().getElementType() instanceof RuleIElementType re
                && re.getRuleIndex() == OceanParser.RULE_interfaceDeclaration);
        if (isInterfaceDecl) {
            String ifaceName = findNameForElement(classNode);
            if (ifaceName != null) {
                List<PsiElement> implementingMethods = findImplementingMethodsInFile(classNode.getContainingFile(), ifaceName, methodName);
                if (!implementingMethods.isEmpty()) {
                    PsiElement firstImpl = implementingMethods.get(0);
                    String implClass = getContainingClassName(firstImpl);
                    String tooltip = "Is implemented in " + (implClass != null ? implClass : "subclasses");
                    return new LineMarkerInfo<>(element, element.getTextRange(), AllIcons.Gutter.OverridenMethod, psiElement -> tooltip,
                        (mouseEvent, psiElement) -> {
                            if (firstImpl instanceof Navigatable nav && nav.canNavigate()) {
                                nav.navigate(true);
                            }
                        }, GutterIconRenderer.Alignment.LEFT, () -> tooltip
                    );
                }
            }
        }

        // Check 3: Super method override / implementation (up arrow)
        String extendsName = extractExtendsName(classNode);
        List<String> implementsNames = extractImplementsNames(classNode);

        List<PsiElement> superMethods = new ArrayList<>();
        Project project = element.getProject();
        String localParamSig = getMethodParamTypesSignature(methodNode);

        if (extendsName != null) {
            resolveMethodInSuper(element, extendsName, methodName, localParamSig, superMethods, project, new HashSet<>());
        }
        for (String iface : implementsNames) {
            resolveMethodInSuper(element, iface, methodName, localParamSig, superMethods, project, new HashSet<>());
        }

        if (!superMethods.isEmpty()) {
            PsiElement target = superMethods.get(0);
            String className = getContainingClassName(target);
            boolean isInterfaceOrAbstract = isInterfaceOrAbstractTarget(target);
            Icon icon = isInterfaceOrAbstract ? AllIcons.Gutter.ImplementingMethod : AllIcons.Gutter.OverridingMethod;
            String tooltip = (isInterfaceOrAbstract ? "Implements" : "Overrides") + " method in " + className;

            return new LineMarkerInfo<>(element, element.getTextRange(), icon, psiElement -> tooltip,
                (mouseEvent, psiElement) -> {
                    if (target instanceof Navigatable nav && nav.canNavigate()) {
                        nav.navigate(true);
                    }
                }, GutterIconRenderer.Alignment.LEFT, () -> tooltip
            );
        }

        return null;
    }

    private boolean hasDirectRecursiveCall(PsiElement methodNode, String methodName) {
        if (methodNode == null || methodName == null || methodName.isEmpty()) return false;
        PsiElement block = directChildByRuleIndex(methodNode, OceanParser.RULE_block);
        if (block == null) return false;

        PsiElement[] calls = PsiTreeUtil.collectElements(block, e -> {
            if (e.getNode() == null) return false;
            if (e instanceof LeafPsiElement && methodName.equals(e.getText())) {
                PsiElement next = PsiTreeUtil.skipWhitespacesAndCommentsForward(e);
                if (next != null && "(".equals(next.getText())) {
                    PsiElement prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(e);
                    if (prev != null && (".".equals(prev.getText()) || "?.".equals(prev.getText()))) {
                        PsiElement receiver = PsiTreeUtil.skipWhitespacesAndCommentsBackward(prev);
                        return receiver != null && "this".equals(receiver.getText());
                    }
                    return true;
                }
            }
            return false;
        });
        return calls.length > 0;
    }

    private List<PsiElement> findImplementingMethodsInFile(PsiFile file, String ifaceName, String methodName) {
        if (file == null || ifaceName == null || methodName == null) return Collections.emptyList();
        List<PsiElement> results = new ArrayList<>();
        PsiElement[] classes = PsiTreeUtil.collectElements(file, e -> {
            if (e.getNode() == null) return false;
            if (e.getNode().getElementType() instanceof RuleIElementType ri) {
                return ri.getRuleIndex() == OceanParser.RULE_classDeclaration;
            }
            return false;
        });

        for (PsiElement cls : classes) {
            List<String> ifaces = extractImplementsNames(cls);
            if (ifaces.contains(ifaceName)) {
                PsiElement[] methods = PsiTreeUtil.collectElements(cls, m -> {
                    if (m.getNode() == null) return false;
                    return m.getNode().getElementType() instanceof RuleIElementType ri
                            && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration;
                });
                for (PsiElement m : methods) {
                    if (methodName.equals(findNameForElement(m))) {
                        results.add(m);
                    }
                }
            }
        }

        if (results.isEmpty()) {
            Map<String, PsiElement> projectClasses = OceanScopeDiscovery.getProjectOceanClasses(file.getProject());
            for (PsiElement cls : projectClasses.values()) {
                if (cls == null || cls.getContainingFile() == file) continue;
                List<String> ifaces = extractImplementsNames(cls);
                if (ifaces.contains(ifaceName)) {
                    PsiElement[] methods = PsiTreeUtil.collectElements(cls, m -> {
                        if (m.getNode() == null) return false;
                        return m.getNode().getElementType() instanceof RuleIElementType ri
                                && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration;
                    });
                    for (PsiElement m : methods) {
                        if (methodName.equals(findNameForElement(m))) {
                            results.add(m);
                        }
                    }
                }
            }
        }
        return results;
    }

    private void resolveMethodInSuper(PsiElement context, String superName, String methodName, String paramSig, List<PsiElement> results, Project project, Set<String> visited) {
        if (superName == null || !visited.add(superName)) return;
        superName = OceanScopeDiscovery.normalizeTypeName(superName);

        // 1. Search in Ocean
        PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, superName);
        if (oceanCls != null) {
            PsiElement[] methods = PsiTreeUtil.collectElements(oceanCls, e -> {
                if (e.getNode() == null) return false;
                IElementType type = e.getNode().getElementType();
                return type instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration;
            });
            for (PsiElement m : methods) {
                String name = findNameForElement(m);
                if (methodName.equals(name)) {
                    String sSig = getMethodParamTypesSignature(m);
                    if (paramSig == null || paramSig.equals(sSig)) {
                        results.add(m);
                    }
                }
            }

            // Recurse to grand parents
            String grandExtends = extractExtendsName(oceanCls);
            if (grandExtends != null) resolveMethodInSuper(context, grandExtends, methodName, paramSig, results, project, visited);
            for (String grandIface : extractImplementsNames(oceanCls)) {
                resolveMethodInSuper(context, grandIface, methodName, paramSig, results, project, visited);
            }
        } else {
            // 2. Search in Java
            PsiClass javaCls = resolveJavaClassInContext(project, context, superName);
            if (javaCls != null) {
                PsiMethod[] javaMethods = javaCls.findMethodsByName(methodName, true);
                for (PsiMethod jm : javaMethods) {
                    String jSig = getJavaMethodParamTypesSignature(jm);
                    if (paramSig == null || paramSig.equals(jSig)) {
                        results.add(jm);
                    }
                }
            }
        }
    }

    private PsiClass resolveJavaClassInContext(Project project, PsiElement context, String className) {
        if (context != null) {
            PsiFile file = context.getContainingFile();
            if (file != null) {
                OceanIdReference.OceanFileInfo info = OceanIdReference.getFileInfo(file);
                for (String exp : info.explicitImports) {
                    if (exp.endsWith("." + className) || exp.equals(className)) {
                        PsiClass jc = JavaPsiFacade.getInstance(project).findClass(exp, GlobalSearchScope.allScope(project));
                        if (jc != null) return jc;
                    }
                }
                for (String wild : info.wildcardImports) {
                    PsiClass jc = JavaPsiFacade.getInstance(project).findClass(wild + "." + className, GlobalSearchScope.allScope(project));
                    if (jc != null) return jc;
                }
            }
        }
        return OceanScopeDiscovery.resolveJavaClass(project, className);
    }

    private String getMethodParamTypesSignature(PsiElement method) {
        PsiElement paramList = directChildByRuleIndex(method, OceanParser.RULE_parameterList);
        if (paramList == null) return "()";
        List<String> types = new ArrayList<>();
        for (PsiElement p : paramList.getChildren()) {
            if (p.getNode() != null && p.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_parameter) {
                PsiElement t = directChildByRuleIndex(p, OceanParser.RULE_type);
                String typeName = t != null ? t.getText().trim() : "Object";
                int arrayDim = 0;
                String baseType = typeName;
                while (baseType.endsWith("[]")) {
                    arrayDim++;
                    baseType = baseType.substring(0, baseType.length() - 2).trim();
                }
                boolean isVarargs = p.getText().contains("...");
                String normalized = OceanScopeDiscovery.normalizeTypeName(baseType);
                if (arrayDim > 0) {
                    normalized += "[]".repeat(arrayDim);
                }
                if (isVarargs) {
                    normalized += "...";
                }
                types.add(normalized);
            }
        }
        return "(" + String.join(",", types) + ")";
    }

    private String getJavaMethodParamTypesSignature(PsiMethod jm) {
        List<String> types = new ArrayList<>();
        for (PsiParameter p : jm.getParameterList().getParameters()) {
            PsiType pType = p.getType();
            if (pType instanceof PsiEllipsisType ellipsisType) {
                String comp = javaTypeToOcean(ellipsisType.getComponentType());
                types.add(OceanScopeDiscovery.normalizeTypeName(comp) + "...");
            } else if (pType instanceof PsiArrayType arrayType) {
                int dim = pType.getArrayDimensions();
                PsiType comp = arrayType.getDeepComponentType();
                String compOcean = javaTypeToOcean(comp);
                types.add(OceanScopeDiscovery.normalizeTypeName(compOcean) + "[]".repeat(dim));
            } else {
                types.add(OceanScopeDiscovery.normalizeTypeName(javaTypeToOcean(pType)));
            }
        }
        return "(" + String.join(",", types) + ")";
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

    private String getContainingClassName(PsiElement method) {
        if (method instanceof PsiMethod jm) {
            PsiClass parent = jm.getContainingClass();
            return parent != null ? parent.getName() : "Java Super Class";
        }
        PsiElement classNode = method.getParent();
        while (classNode != null) {
            if (classNode.getNode() != null && classNode.getNode().getElementType() instanceof RuleIElementType r &&
                (r.getRuleIndex() == OceanParser.RULE_classDeclaration || r.getRuleIndex() == OceanParser.RULE_interfaceDeclaration)) {
                String name = findNameForElement(classNode);
                return name != null ? name : "Ocean Super Class";
            }
            classNode = classNode.getParent();
        }
        return "Super Class";
    }

    private boolean isInterfaceOrAbstractTarget(PsiElement method) {
        if (method instanceof PsiMethod jm) {
            PsiClass parent = jm.getContainingClass();
            return (parent != null && parent.isInterface()) || jm.hasModifierProperty(PsiModifier.ABSTRACT);
        }
        PsiElement classNode = method.getParent();
        while (classNode != null) {
            if (classNode.getNode() != null && classNode.getNode().getElementType() instanceof RuleIElementType r) {
                if (r.getRuleIndex() == OceanParser.RULE_interfaceDeclaration) return true;
                if (r.getRuleIndex() == OceanParser.RULE_classDeclaration) {
                    return classNode.getText().contains("abstract ");
                }
            }
            classNode = classNode.getParent();
        }
        return false;
    }

    private String findNameForElement(PsiElement element) {
        if (element instanceof PsiNamedElement named) {
            String name = named.getName();
            if (name != null) return name;
        }
        for (PsiElement child : element.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_anyId) {
                String text = child.getText();
                int angle = text.indexOf('<');
                return (angle > 0) ? text.substring(0, angle).trim() : text.trim();
            }
        }
        return null;
    }

    private String extractExtendsName(PsiElement cls) {
        if (cls == null) return null;
        boolean afterExtends = false;
        for (PsiElement c : cls.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.EXTENDS) {
                afterExtends = true;
                continue;
            }
            if (afterExtends) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_type) {
                    return OceanScopeDiscovery.normalizeTypeName(c.getText().trim());
                }
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && (ti.getANTLRTokenType() == OceanLexer.IMPLEMENTS || ti.getANTLRTokenType() == OceanLexer.LBRACE)) {
                    break;
                }
            }
        }
        Matcher m = Pattern.compile("\\bextends\\s+([\\w\\.<>]+)").matcher(cls.getText());
        return m.find() ? OceanScopeDiscovery.normalizeTypeName(m.group(1)) : null;
    }

    private List<String> extractImplementsNames(PsiElement cls) {
        List<String> names = new ArrayList<>();
        if (cls == null) return names;
        boolean afterImplements = false;
        for (PsiElement c : cls.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.IMPLEMENTS) {
                afterImplements = true;
                continue;
            }
            if (afterImplements) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType ri) {
                    if (ri.getRuleIndex() == OceanParser.RULE_typeList) {
                        for (PsiElement tc : c.getChildren()) {
                            if (tc.getNode() != null && tc.getNode().getElementType() instanceof RuleIElementType tri && tri.getRuleIndex() == OceanParser.RULE_type) {
                                String n = OceanScopeDiscovery.normalizeTypeName(tc.getText().trim());
                                if (n != null && !n.isEmpty()) names.add(n);
                            }
                        }
                        return names;
                    }
                    if (ri.getRuleIndex() == OceanParser.RULE_type) {
                        String n = OceanScopeDiscovery.normalizeTypeName(c.getText().trim());
                        if (n != null && !n.isEmpty()) names.add(n);
                    }
                }
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.LBRACE) {
                    break;
                }
            }
        }
        if (!names.isEmpty()) return names;
        Matcher m = Pattern.compile("\\bimplements\\s+([^\\{]+)").matcher(cls.getText());
        if (m.find()) {
            for (String s : OceanScopeDiscovery.splitTopLevelComma(m.group(1))) {
                String t = OceanScopeDiscovery.normalizeTypeName(s);
                if (t != null && !t.isEmpty()) names.add(t);
            }
        }
        return names;
    }

    private PsiElement directChildByRuleIndex(PsiElement e, int ruleIndex) {
        for (PsiElement c : e.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIndex) {
                return c;
            }
        }
        return null;
    }

    private PsiElement lastDirectChildByRuleIndex(PsiElement e, int ruleIndex) {
        PsiElement last = null;
        for (PsiElement c : e.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIndex) {
                last = c;
            }
        }
        return last;
    }
}
