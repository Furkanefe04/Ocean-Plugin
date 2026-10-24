package ocean.plugin;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.antlr.intellij.adaptor.lexer.RuleIElementType;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanStructureViewElement implements StructureViewTreeElement, SortableTreeElement {
    private final PsiElement element;

    public OceanStructureViewElement(PsiElement element) {
        this.element = element;
    }

    @Override
    public Object getValue() {
        return element;
    }

    @Override
    public void navigate(boolean requestFocus) {
        if (element instanceof NavigationItem) {
            ((NavigationItem) element).navigate(requestFocus);
        }
    }

    @Override
    public boolean canNavigate() {
        return element instanceof NavigationItem && ((NavigationItem) element).canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return element instanceof NavigationItem && ((NavigationItem) element).canNavigateToSource();
    }

    @NotNull
    @Override
    public String getAlphaSortKey() {
        String name = (element instanceof PsiNamedElement) ? ((PsiNamedElement) element).getName() : null;
        return name != null ? name : "";
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Nullable
            @Override
            public String getPresentableText() {
                boolean isMethod = element instanceof OceanMethodDeclaration ||
                        (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration);
                if (isMethod) {
                    String name = (element instanceof PsiNamedElement ne) ? ne.getName() : null;
                    if (name == null) {
                        Matcher m = Pattern.compile("function\\s+([a-zA-Z_][a-zA-Z0-9_]*)").matcher(element.getText());
                        if (m.find()) name = m.group(1);
                    }
                    if (name == null) name = "function";
                    boolean isAsync = element.getText().contains("async ");
                    String prefix = isAsync ? "async " : "";
                    String params = (element instanceof OceanMethodDeclaration md) ? extractMethodParamsText(md) : "()";
                    String retType = (element instanceof OceanMethodDeclaration md) ? extractMethodReturnType(md) : null;
                    return retType != null ? prefix + name + params + ": " + retType : prefix + name + params;
                }
                boolean isClass = element instanceof OceanClassDeclaration ||
                        (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_classDeclaration);
                if (isClass) {
                    String name = (element instanceof PsiNamedElement ne) ? ne.getName() : null;
                    String txt = element.getText();
                    if (name == null) {
                        Matcher m = Pattern.compile("class\\s+([a-zA-Z_][a-zA-Z0-9_]*)").matcher(txt);
                        if (m.find()) name = m.group(1);
                    }
                    boolean isData = txt.contains("data class");
                    boolean isSealed = txt.contains("sealed ");
                    String prefix = isSealed ? (isData ? "sealed data class " : "sealed class ") : (isData ? "data class " : "");
                    return prefix + (name != null ? name : "class");
                }
                boolean isIface = element instanceof OceanInterfaceDeclaration ||
                        (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_interfaceDeclaration);
                if (isIface) {
                    String name = (element instanceof PsiNamedElement ne) ? ne.getName() : null;
                    String txt = element.getText();
                    if (name == null) {
                        Matcher m = Pattern.compile("interface\\s+([a-zA-Z_][a-zA-Z0-9_]*)").matcher(txt);
                        if (m.find()) name = m.group(1);
                    }
                    boolean isSealed = txt.contains("sealed ");
                    String prefix = isSealed ? "sealed interface " : "";
                    return prefix + (name != null ? name : "interface");
                }
                if (element instanceof OceanFieldDeclaration field) {
                    String name = field.getName();
                    String type = extractFieldType(field);
                    return type != null ? name + ": " + type : name;
                }
                if (element instanceof OceanParameter param) {
                    String name = param.getName();
                    String type = extractParamType(param);
                    return type != null ? name + ": " + type : name;
                }
                if (element instanceof OceanVariableDeclarator d) {
                    String name = d.getName();
                    String type = OceanTypeHelper.guessType(d);
                    return (type != null && !type.equals("Object")) ? name + ": " + type : name;
                }
                if (element instanceof PsiNamedElement) return ((PsiNamedElement) element).getName();
                return element.getContainingFile().getName();
            }

            @Nullable
            @Override
            public String getLocationString() {
                return null;
            }

            @Nullable
            @Override
            public Icon getIcon(boolean unused) {
                if (element instanceof OceanClassDeclaration) return OceanIcons.ICON;
                if (element instanceof OceanInterfaceDeclaration) return PlatformIcons.INTERFACE_ICON;
                if (element instanceof OceanEnumDeclaration) return PlatformIcons.ENUM_ICON;
                if (element instanceof OceanAnnotationDeclaration) return PlatformIcons.ANNOTATION_TYPE_ICON;
                if (element instanceof OceanMethodDeclaration) return PlatformIcons.METHOD_ICON;
                if (element instanceof OceanFieldDeclaration) return PlatformIcons.FIELD_ICON;
                if (element instanceof OceanParameter) return PlatformIcons.FIELD_ICON;
                if (element instanceof OceanVariableDeclaration) return PlatformIcons.VARIABLE_ICON;
                if (element instanceof OceanVariableDeclarator d) return d.isField() ? PlatformIcons.FIELD_ICON : PlatformIcons.VARIABLE_ICON;
                if (element instanceof OceanEnumConstant) return PlatformIcons.FIELD_ICON;
                if (element instanceof OceanAnnotationMemberDeclaration) return PlatformIcons.FIELD_ICON;
                return null;
            }
        };
    }

    @NotNull
    @Override
    public TreeElement @NotNull [] getChildren() {
        List<TreeElement> treeElements = new ArrayList<>();
        if (element instanceof PsiFile) {
            Collection<PsiElement> classes = PsiTreeUtil.findChildrenOfAnyType(element,
                OceanClassDeclaration.class, OceanInterfaceDeclaration.class, OceanEnumDeclaration.class, OceanAnnotationDeclaration.class);
            for (PsiElement cls : classes) {
                treeElements.add(new OceanStructureViewElement(cls));
            }
        } else if (element instanceof OceanClassDeclaration || element instanceof OceanInterfaceDeclaration || element instanceof OceanEnumDeclaration || element instanceof OceanAnnotationDeclaration) {
            collectClassMembers(element, treeElements);
        } else if (element instanceof OceanFieldDeclaration) {
            Collection<OceanVariableDeclarator> decls = PsiTreeUtil.findChildrenOfType(element, OceanVariableDeclarator.class);
            if (decls.size() > 1) {
                for (OceanVariableDeclarator d : decls) {
                    treeElements.add(new OceanStructureViewElement(d));
                }
            }
        }
        return treeElements.toArray(new TreeElement[0]);
    }

    private void collectClassMembers(PsiElement node, List<TreeElement> result) {
        // Collect primary constructor parameters for data/header classes: class Point(int x, int y)
        if (node instanceof OceanClassDeclaration) {
            for (PsiElement child : node.getChildren()) {
                if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                        ri.getRuleIndex() == OceanParser.RULE_parameterList) {
                    for (OceanParameter param : PsiTreeUtil.findChildrenOfType(child, OceanParameter.class)) {
                        result.add(new OceanStructureViewElement(param));
                    }
                }
            }
        }

        for (PsiElement child : node.getChildren()) {
            if (child instanceof OceanMethodDeclaration ||
                child instanceof OceanFieldDeclaration ||
                child instanceof OceanEnumConstant ||
                child instanceof OceanAnnotationMemberDeclaration ||
                child instanceof OceanClassDeclaration ||
                child instanceof OceanInterfaceDeclaration ||
                child instanceof OceanEnumDeclaration ||
                child instanceof OceanAnnotationDeclaration) {
                result.add(new OceanStructureViewElement(child));
            } else if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                    ri.getRuleIndex() == OceanParser.RULE_memberDeclaration) {
                collectClassMembers(child, result);
            }
        }
    }

    private static String extractMethodParamsText(OceanMethodDeclaration method) {
        for (PsiElement child : method.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                    ri.getRuleIndex() == OceanParser.RULE_parameterList) {
                return "(" + child.getText().trim() + ")";
            }
        }
        return "()";
    }

    private static String extractMethodReturnType(OceanMethodDeclaration method) {
        return OceanScopeDiscovery.getMethodReturnType(method);
    }

    private static String extractFieldType(OceanFieldDeclaration field) {
        for (PsiElement child : field.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                    ri.getRuleIndex() == OceanParser.RULE_type) {
                return child.getText().trim();
            }
        }
        return null;
    }

    private static String extractParamType(OceanParameter param) {
        for (PsiElement child : param.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri &&
                    ri.getRuleIndex() == OceanParser.RULE_type) {
                return child.getText().trim();
            }
        }
        return null;
    }
}
