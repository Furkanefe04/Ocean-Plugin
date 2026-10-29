package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import ocean.plugin.inspections.OceanInspectionHolder;
import ocean.plugin.quickfix.*;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class OceanAnnotator implements Annotator {

    private static final AnnotationBuilder DUMMY_ANNOTATION_BUILDER = (AnnotationBuilder) Proxy.newProxyInstance(OceanAnnotator.class.getClassLoader(), new Class<?>[]{AnnotationBuilder.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        return switch (name) {
                            case "create", "createAnnotation" -> null;
                            case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "DummyAnnotationBuilder";
                            default -> proxy;
                        };
                    }
            );

    private static AnnotationHolder wrapForHighlightingOnly(AnnotationHolder delegate) {
        return (AnnotationHolder) Proxy.newProxyInstance(OceanAnnotator.class.getClassLoader(), new Class<?>[]{AnnotationHolder.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("newAnnotation".equals(name) || "newSilentAnnotation".equals(name)) {
                        HighlightSeverity severity = (args != null && args.length > 0 && args[0] instanceof HighlightSeverity)
                                ? (HighlightSeverity) args[0]
                                : null;
                        if (severity != null && (severity == HighlightSeverity.ERROR ||
                                severity == HighlightSeverity.WARNING ||
                                severity == HighlightSeverity.WEAK_WARNING ||
                                "ERROR".equalsIgnoreCase(severity.getName()) ||
                                "WARNING".equalsIgnoreCase(severity.getName()) ||
                                "WEAK WARNING".equalsIgnoreCase(severity.getName()) ||
                                severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal)) {
                            return DUMMY_ANNOTATION_BUILDER;
                        }
                    }
                    if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "HighlightingOnlyAnnotationHolderProxy";
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException ite) {
                        throw ite.getCause() != null ? ite.getCause() : ite;
                    }
                }
        );
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        // In batch mode (Analyze > Inspect Code...), suppress annotator reporting
        // so errors are not funneled into the generic 'General > Annotator' group.
        // OceanCompilerInspection handles reporting under the 'Ocean' inspection group instead.
        if (holder.isBatchMode() && !(holder instanceof OceanInspectionHolder)) {
            return;
        }

        // When running directly as an Annotator (not through OceanCompilerInspection),
        // wrap holder to suppress ERROR and WARNING annotations so they do not appear under
        // the generic 'General > Annotator' group. All compiler and semantic errors are collected
        // and reported exclusively by OceanCompilerInspection under 'Ocean > Compiler and semantic errors'.
        if (!(holder instanceof OceanInspectionHolder) && !"HighlightingOnlyAnnotationHolderProxy".equals(holder.toString())) {
            holder = wrapForHighlightingOnly(holder);
        }

        // 1. Semantic Highlighting for     Declarations
        if (element instanceof OceanNamedElement) {
            highlightDeclaration(element, holder);
        }

        // 2. Reference Checking (Unresolved References & Highlighting)
        if (element.getReference() != null || (isRule(element, OceanParser.RULE_anyId) && "_".equals(element.getText().trim()))) {
            if ("_".equals(element.getText().trim())) {
                if (!isDeclarationOrPatternContext(element)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] The unnamed variable '_' cannot be read or referenced.")
                            .range(element.getTextRange())
                            .create();
                }
                return;
            }
            PsiReference ref = element.getReference();
            if (ref != null && !isInsideImportOrPackage(element) && !isPackagePrefixInTypeName(element)) {
                PsiElement resolved = ref.resolve();
                if (resolved == null) {
                    checkReference(element, holder);
                } else {
                    highlightResolvedReference(element, resolved, holder);
                    highlightCallIfNeeded(element, holder);
                    checkEffectivelyFinal(element, holder);
                    checkStaticContextAccess(element, resolved, holder);
                    checkAccessControl(element, resolved, holder);
                    checkAmbiguousInterfaceFieldReference(element, resolved, holder);
                    checkDefiniteAssignment(element, resolved, holder);
                }
            } else if (isPackagePrefixInTypeName(element)) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(element.getTextRange())
                        .textAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)
                        .create();
            }
        }

        // 3. Interpolated Strings Highlighting
        if (isInterpolatedString(element)) {
            highlightInterpolatedString(element, holder);
        }

        // 4. Custom highlighting for @Override and Annotations
        if (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_annotation) {
            PsiElement atNode = element.getFirstChild();
            PsiElement typeNode = null;
            for (PsiElement c : element.getChildren()) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof RuleIElementType cri && cri.getRuleIndex() == OceanParser.RULE_typeName) {
                    typeNode = c;
                    break;
                }
            }
            if (atNode != null) {
                int endOffset = (typeNode != null) ? typeNode.getTextRange().getEndOffset() : atNode.getTextRange().getEndOffset();
                TextRange headerRange = new TextRange(atNode.getTextRange().getStartOffset(), endOffset);
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(headerRange)
                        .textAttributes(OceanSyntaxHighlighter.ANNOTATION)
                        .create();
            }
        }

        // 5. Semantic Modifier Highlighting & THIS in static context & Package/Import Keyword Override
        if (element.getNode() != null && element.getNode().getElementType() instanceof TokenIElementType ti) {
            int tokenType = ti.getANTLRTokenType();
            if (isInsideImportOrPackage(element)) {
                // If it's not the 'package', 'import', or 'static' keyword itself, and not punctuation,
                // apply semantic highlighting for classes, static members (purple & italic), etc.
                if (tokenType != OceanLexer.PACKAGE && tokenType != OceanLexer.IMPORT && tokenType != OceanLexer.STATIC
                        && tokenType != OceanLexer.DOT && tokenType != OceanLexer.SEMI && tokenType != OceanLexer.STAR) {
                    highlightImportOrPackageElement(element, holder);
                    return;
                }
            }
            if (tokenType == OceanLexer.THIS) {
                if (isInsideStaticContext(element)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0008] 'this' cannot be referenced from a static context")
                            .range(element.getTextRange())
                            .create();
                }
            } else if (tokenType == OceanLexer.STOP_KW) {
                checkStopStatement(element, holder);
            } else if (tokenType == OceanLexer.SKIP_KW) {
                checkSkipStatement(element, holder);
            } else if (tokenType == OceanLexer.RESULT_KW) {
                checkResultStatement(element, holder);
            } else if (tokenType == OceanLexer.AWAIT) {
                checkAwaitExpression(element, holder);
            } else if (tokenType == OceanLexer.PUBLIC || tokenType == OceanLexer.PRIVATE || tokenType == OceanLexer.PROTECTED ||
                    tokenType == OceanLexer.STATIC || tokenType == OceanLexer.FINAL || tokenType == OceanLexer.ABSTRACT ||
                    tokenType == OceanLexer.SYNC || tokenType == OceanLexer.LOCK || tokenType == OceanLexer.DATA ||
                    tokenType == OceanLexer.SEALED || tokenType == OceanLexer.NON_SEALED || tokenType == OceanLexer.RESTRICTS ||
                    tokenType == OceanLexer.ASYNC || tokenType == OceanLexer.NATIVE) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(element.getTextRange())
                        .textAttributes(OceanSyntaxHighlighter.MODIFIER)
                        .create();
            }
        }

        if (isRule(element, OceanParser.RULE_compilationUnit)) {
            checkCompilationUnit(element, holder);
        } else if (isRule(element, OceanParser.RULE_classDeclaration)) {
            checkClassDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_interfaceDeclaration)) {
            checkInterfaceDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_enumDeclaration)) {
            checkEnumDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_methodDeclaration)) {
            checkMethodDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_assignment)) {
            checkAssignment(element, holder);
        } else if (isRule(element, OceanParser.RULE_switchStatement)) {
            checkSwitchStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_switchCase) || isRule(element, OceanParser.RULE_switchExpressionCase)) {
            checkSwitchCase(element, holder);
        } else if (isRule(element, OceanParser.RULE_expression)) {
            checkExpression(element, holder);
        } else if (isRule(element, OceanParser.RULE_ifStatement)) {
            checkIfStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_forStatement)) {
            checkForStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_whileStatement) || isRule(element, OceanParser.RULE_doWhileStatement)) {
            checkLoopCondition(element, holder);
        } else if (isRule(element, OceanParser.RULE_tryStatement)) {
            checkTryStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_lockBlockStatement)) {
            checkLockStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_variableDeclaration)) {
            checkVariableDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_constructorDeclaration)) {
            checkConstructorDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_annotationDeclaration)) {
            checkAnnotationDeclaration(element, holder);
        } else if (isRule(element, OceanParser.RULE_block)) {
            // Unreachable statement inspection handled by OceanUnreachableCodeInspection
        } else if (isRule(element, OceanParser.RULE_statement)) {
            checkStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_returnStatement)) {
            checkReturnStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_annotation)) {
            // P3-C: Check annotation member value types
            checkAnnotationValues(element, holder);
        } else if (isRule(element, OceanParser.RULE_typeParameter)) {
            checkTypeParameter(element, holder);
        } else if (isRule(element, OceanParser.RULE_verifyStatement)) {
            checkVerifyStatement(element, holder);
        } else if (isRule(element, OceanParser.RULE_type)) {
            checkType(element, holder);
        }
    }

    private void highlightDeclaration(PsiElement element, AnnotationHolder holder) {
        PsiElement nameNode = null;
        if (element instanceof OceanNamedElement named) {
            nameNode = named.getNameIdentifier();
        }
        if (nameNode == null) {
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_anyId)) {
                    nameNode = child;
                    break;
                }
            }
        }
        if (nameNode == null) {
            if (element instanceof OceanLoopVariable || element instanceof OceanCatchVariable || element instanceof OceanLabelDeclaration || element instanceof OceanPatternVariable) {
                nameNode = element;
            } else {
                return;
            }
        }
        TextRange nameRange = nameNode.getTextRange();

        if (element instanceof OceanInterfaceDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.INTERFACE_NAME)
                    .create();
        } else if (element instanceof OceanAnnotationDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.ANNOTATION)
                    .create();
        } else if (element instanceof OceanClassDeclaration ||
            element instanceof OceanEnumDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.CLASS_NAME)
                    .create();
        } else if (element instanceof OceanTypeParameter) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.TYPE_PARAMETER)
                    .create();
        } else if (element instanceof OceanMethodDeclaration || element instanceof OceanAnnotationMemberDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.METHOD_DECLARATION)
                    .create();
        } else if (element instanceof OceanParameter) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.PARAMETER)
                    .create();
        } else if (element instanceof OceanVariableDeclarator declarator) {
            if (declarator.isField()) {
                boolean isStatic = hasModifierToken(declarator.getParent(), OceanLexer.STATIC);
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(nameRange)
                        .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_FIELD : OceanSyntaxHighlighter.INSTANCE_FIELD)
                        .create();
            } else {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(nameRange)
                        .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                        .create();
            }
        } else if (element instanceof OceanFieldDeclaration) {
            boolean hasDeclarator = false;
            for (PsiElement c : element.getChildren()) {
                if (c instanceof OceanVariableDeclarator) { hasDeclarator = true; break; }
            }
            if (!hasDeclarator) {
                boolean isStatic = hasModifierToken(element, OceanLexer.STATIC);
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(nameRange)
                        .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_FIELD : OceanSyntaxHighlighter.INSTANCE_FIELD)
                        .create();
            }
        } else if (element instanceof OceanEnumConstant) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.STATIC_FIELD)
                    .create();
        } else if (element instanceof OceanVariableDeclaration) {
            boolean hasDeclarator = false;
            for (PsiElement c : element.getChildren()) {
                if (c instanceof OceanVariableDeclarator) { hasDeclarator = true; break; }
            }
            if (!hasDeclarator) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(nameRange)
                        .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                        .create();
            }
        } else if (element instanceof OceanLoopVariable || element instanceof OceanCatchVariable ||
                   element instanceof OceanLabelDeclaration || element instanceof OceanPatternVariable) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(nameRange)
                    .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                    .create();
        }
    }

    private void highlightResolvedReference(PsiElement element, PsiElement resolved, AnnotationHolder holder) {
        if (resolved == null) return;

        if (resolved instanceof PsiPackage) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)
                    .create();
            return;
        }
        
        if (resolved instanceof PsiEnumConstant || resolved instanceof OceanEnumConstant) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.STATIC_FIELD)
                    .create();
            return;
        }

        if (resolved instanceof OceanLoopVariable || resolved instanceof OceanCatchVariable || resolved instanceof OceanLabelDeclaration || resolved instanceof OceanPatternVariable) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                    .create();
            return;
        }

        if (resolved instanceof OceanAnnotationMemberDeclaration) {
            PsiElement next = PsiTreeUtil.skipWhitespacesAndCommentsForward(element);
            boolean isCall = next != null && "(".equals(next.getText());
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(isCall ? OceanSyntaxHighlighter.METHOD_CALL : OceanSyntaxHighlighter.INSTANCE_FIELD)
                    .create();
            return;
        }

        if (resolved instanceof OceanVariableDeclarator declarator) {
            if (declarator.isField()) {
                boolean isStatic = hasModifierToken(declarator.getParent(), OceanLexer.STATIC);
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(element.getTextRange())
                        .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_FIELD : OceanSyntaxHighlighter.INSTANCE_FIELD)
                        .create();
            } else {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(element.getTextRange())
                        .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                        .create();
            }
            return;
        }

        if (resolved instanceof OceanFieldDeclaration || resolved instanceof PsiField) {
            boolean isStatic = resolved instanceof PsiField pf ? pf.hasModifierProperty(PsiModifier.STATIC) : hasModifierToken(resolved, OceanLexer.STATIC);
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_FIELD : OceanSyntaxHighlighter.INSTANCE_FIELD)
                    .create();
            return;
        }

        if (resolved instanceof OceanVariableDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                    .create();
            return;
        }

        if (resolved instanceof OceanParameter || resolved instanceof PsiParameter) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.PARAMETER)
                    .create();
            return;
        }

        if (resolved instanceof OceanMethodDeclaration || resolved instanceof PsiMethod) {
            boolean isStatic = resolved instanceof PsiMethod pm
                    ? pm.hasModifierProperty(PsiModifier.STATIC)
                    : hasModifierToken(resolved, OceanLexer.STATIC);
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_METHOD : OceanSyntaxHighlighter.METHOD_CALL)
                    .create();
            return;
        }

        if (resolved instanceof OceanInterfaceDeclaration || (resolved instanceof PsiClass pc && pc.isInterface() && !pc.isAnnotationType())) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.INTERFACE_NAME)
                    .create();
            return;
        }

        if (resolved instanceof OceanAnnotationDeclaration || (resolved instanceof PsiClass pc && pc.isAnnotationType())) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.ANNOTATION)
                    .create();
            return;
        }

        if (resolved instanceof OceanClassDeclaration ||
                resolved instanceof OceanEnumDeclaration ||
                resolved instanceof PsiClass) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.CLASS_NAME)
                    .create();
            return;
        }

        if (resolved instanceof OceanTypeParameter) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.TYPE_PARAMETER)
                    .create();
            return;
        }

        PsiElement parent = resolved.getParent();
        if (parent == null || parent.getNode() == null) return;
        if (!(parent.getNode().getElementType() instanceof RuleIElementType pri)) return;
        
        int pIdx = pri.getRuleIndex();
        if (pIdx == OceanParser.RULE_fieldDeclaration || pIdx == OceanParser.RULE_annotationMemberDeclaration) {
            boolean isStatic = hasModifierToken(parent, OceanLexer.STATIC);
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_FIELD : OceanSyntaxHighlighter.INSTANCE_FIELD)
                    .create();
        } else if (pIdx == OceanParser.RULE_enumConstant) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.STATIC_FIELD)
                    .create();
        } else if (pIdx == OceanParser.RULE_variableDeclaration || pIdx == OceanParser.RULE_resource || pIdx == OceanParser.RULE_forControl || pIdx == OceanParser.RULE_forStatement || pIdx == OceanParser.RULE_catchClause || pIdx == OceanParser.RULE_statement) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.LOCAL_VARIABLE)
                    .create();
        } else if (pIdx == OceanParser.RULE_parameter) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.PARAMETER)
                    .create();
        } else if (pIdx == OceanParser.RULE_interfaceDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.INTERFACE_NAME)
                    .create();
        } else if (pIdx == OceanParser.RULE_annotationDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.ANNOTATION)
                    .create();
        } else if (pIdx == OceanParser.RULE_classDeclaration || pIdx == OceanParser.RULE_enumDeclaration) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(OceanSyntaxHighlighter.CLASS_NAME)
                    .create();
        }
    }

    private void highlightCallIfNeeded(PsiElement element, AnnotationHolder holder) {
        PsiElement parent = element.getParent();
        if (parent == null || parent.getNode() == null) return;
        if (!(parent.getNode().getElementType() instanceof RuleIElementType pri)) return;
        
        int pIdx = pri.getRuleIndex();
        if (pIdx == OceanParser.RULE_expression || pIdx == OceanParser.RULE_primary) {
            PsiElement next = PsiTreeUtil.skipWhitespacesAndCommentsForward(element);
            if (next != null && "(".equals(next.getText())) {
                PsiReference ref = element.getReference();
                PsiElement target = ref != null ? ref.resolve() : null;
                boolean isStatic = false;
                if (target instanceof PsiMethod pm) {
                    isStatic = pm.hasModifierProperty(PsiModifier.STATIC);
                } else if (target instanceof OceanMethodDeclaration omd) {
                    isStatic = hasModifierToken(omd, OceanLexer.STATIC);
                }
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(element.getTextRange())
                        .textAttributes(isStatic ? OceanSyntaxHighlighter.STATIC_METHOD : OceanSyntaxHighlighter.METHOD_CALL)
                        .create();
            }
        }
    }

    private void checkReference(PsiElement element, AnnotationHolder holder) {
        if (isInsideImportOrPackage(element)) return;
        PsiReference ref = element.getReference();
        if (ref != null && ref.resolve() == null) {
            String text = element.getText();
            if (isLikelyBuiltIn(text)) return;

            // Check ambiguous static wildcard imports [E0009]
            PsiFile containingFile = element.getContainingFile();
            if (containingFile != null) {
                OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(containingFile);
                if (fileInfo != null && !fileInfo.staticImports.isEmpty()) {
                    List<String> matchingOwners = new ArrayList<>();
                    Project proj = element.getProject();
                    for (String stat : fileInfo.staticImports) {
                        if (stat.endsWith(".*")) {
                            String owner = stat.substring(0, stat.length() - 2);
                            PsiElement oceanOwner = findClassInSameFileOrProject(proj, element, owner);
                            if (oceanOwner != null) {
                                for (PsiElement c : oceanOwner.getChildren()) {
                                    if (isRule(c, OceanParser.RULE_memberDeclaration)) {
                                        for (PsiElement m : c.getChildren()) {
                                            if (isRule(m, OceanParser.RULE_fieldDeclaration)) {
                                                for (PsiElement v : m.getChildren()) {
                                                    if (isRule(v, OceanParser.RULE_variableDeclarator)) {
                                                        PsiElement id = directChildByRuleIndex(v, OceanParser.RULE_anyId);
                                                        if (id != null && text.equals(id.getText().trim())) {
                                                            matchingOwners.add(owner);
                                                            break;
                                                        }
                                                    }
                                                }
                                            } else if (isRule(m, OceanParser.RULE_methodDeclaration)) {
                                                PsiElement id = directChildByRuleIndex(m, OceanParser.RULE_anyId);
                                                if (id != null && text.equals(id.getText().trim())) {
                                                    matchingOwners.add(owner);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (matchingOwners.size() > 1) {
                        PsiElement nextLeaf = PsiTreeUtil.nextVisibleLeaf(element);
                        if (nextLeaf == null) nextLeaf = element.getNextSibling();
                        boolean isMethod = nextLeaf != null && "(".equals(nextLeaf.getText() != null ? nextLeaf.getText().trim() : "");
                        if (isMethod) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0009] Ambiguous static method reference '" + text + "'. Matches multiple static wildcard imports: " + matchingOwners)
                                    .range(element.getTextRange())
                                    .create();
                        } else {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0009] Ambiguous static field reference '" + text + "'. Matches multiple static wildcard imports: " + matchingOwners)
                                    .range(element.getTextRange())
                                    .create();
                        }
                        return;
                    }
                }
            }

            Project project = element.getProject();
            List<String> matches = findMatchingClasses(project, text);

            PsiElement nextLeaf = PsiTreeUtil.nextVisibleLeaf(element);
            if (nextLeaf == null) nextLeaf = element.getNextSibling();
            boolean isMethodCall = nextLeaf != null && "(".equals(nextLeaf.getText() != null ? nextLeaf.getText().trim() : "");

            String errorMsg = isMethodCall
                    ? "[E0009] Unresolved reference: " + text
                    : "[E0009] Undefined variable: '" + text + "'";

            AnnotationBuilder builder = holder.newAnnotation(HighlightSeverity.ERROR, errorMsg)
                    .range(element.getTextRange())
                    .textAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES);

            for (String match : matches) {
                builder = builder.withFix(new OceanImportClassQuickFix(match));
            }

            if (isMethodCall) {
                builder = builder.withFix(new OceanCreateMethodQuickFix(element));
            } else {
                builder = builder.withFix(new OceanCreateFieldQuickFix(element));
            }

            builder.create();
        }
    }

    private List<String> findMatchingClasses(Project project, String className) {
        List<String> matches = new ArrayList<>();
        if (project == null || className == null || className.isEmpty()) return matches;

        // 1. Search in Ocean project cache (O(1))
        Map<String, PsiElement> oceanClasses = OceanScopeDiscovery.getProjectOceanClasses(project);
        for (Map.Entry<String, PsiElement> entry : oceanClasses.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("." + className) || key.equals(className)) {
                if (key.contains(".")) {
                    if (!matches.contains(key)) matches.add(key);
                } else {
                    PsiFile pf = entry.getValue().getContainingFile();
                    String pkg = pf != null ? OceanScopeDiscovery.extractOceanPackage(pf) : "";
                    String qn = pkg.isEmpty() ? key : pkg + "." + key;
                    if (!matches.contains(qn)) matches.add(qn);
                }
            }
        }

        // 2. Search in Java packages
        if (com.intellij.psi.search.ProjectScopeBuilder.getInstance(project) == null) return matches;
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        PsiClass[] javaClasses = cache.getClassesByName(className, GlobalSearchScope.allScope(project));
        for (PsiClass jc : javaClasses) {
            String qn = jc.getQualifiedName();
            if (qn != null && !qn.startsWith("java.lang.") && !qn.startsWith("ocean.stdlib.") && !matches.contains(qn)) {
                matches.add(qn);
            }
        }

        return matches;
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

    private String extractOceanPackage(PsiFile file) {
        String text = file.getText();
        Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private void highlightInterpolatedString(PsiElement element, AnnotationHolder holder) {
        String text = element.getText();
        int baseOffset = element.getTextRange().getStartOffset();

        // 1. Base string color (green)
        holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                .range(element.getTextRange())
                .textAttributes(OceanSyntaxHighlighter.STRING)
                .create();

        // 2. Tokenize escapes and interpolation expressions
        // Matches:
        // - Escapes: \\\\, \\", \\', \\n, \\t, \\r, \\b, \\f, \\{, \\}, \\uXXXX, {{, }}
        // - Expressions: { ... }
        Pattern pattern = Pattern.compile("\\\\(?:[\\\\\"'ntrbf{}]|u[0-9a-fA-F]{4})|\\{\\{|}}|\\{[^}]*}");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int matchStart = baseOffset + matcher.start();
            int matchEnd = baseOffset + matcher.end();
            String match = matcher.group();

            if (match.startsWith("{") && !match.equals("{{")) {
                // Opening brace '{'
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(matchStart, matchStart + 1))
                        .textAttributes(OceanSyntaxHighlighter.BRACES)
                        .create();

                // Closing brace '}'
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(matchEnd - 1, matchEnd))
                        .textAttributes(OceanSyntaxHighlighter.BRACES)
                        .create();

                // Inner expression
                String innerExpr = match.substring(1, match.length() - 1);
                int innerStartOffset = matchStart + 1;
                highlightInterpolationExpressionBody(innerExpr, innerStartOffset, holder);
            } else {
                // String escape sequence (orange)
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(matchStart, matchEnd))
                        .textAttributes(OceanSyntaxHighlighter.STRING_ESCAPE)
                        .create();
            }
        }
    }

    private void highlightInterpolationExpressionBody(String expr, int baseOffset, AnnotationHolder holder) {
        // Tokenize inner expression into numbers, strings, keywords, method calls, identifiers, and operators
        Pattern exprPattern = Pattern.compile(
                "\"(?:\\\\.|[^\"\\\\])*\"" +                  // String literals
                        "|\\b\\d+(?:\\.\\d+)?[fFdDlL]?\\b" +    // Number literals
                        "|\\b(?:true|false|null|this|new|await|super)\\b" + // Keywords/literals
                "|\\b[a-zA-Z_][a-zA-Z0-9_]*(?=\\s*\\()" +   // Method call
                "|\\b[a-zA-Z_][a-zA-Z0-9_]*\\b" +           // Identifier / variable
                "|[+\\-*/%?:<>=!&|^.]+"                     // Operators
        );
        Matcher m = exprPattern.matcher(expr);
        int lastPos = 0;
        while (m.find()) {
            int tokenStart = baseOffset + m.start();
            int tokenEnd = baseOffset + m.end();
            String token = m.group();

            if (token.startsWith("\"")) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(tokenStart, tokenEnd))
                        .textAttributes(OceanSyntaxHighlighter.STRING)
                        .create();
            } else if (Character.isDigit(token.charAt(0))) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(tokenStart, tokenEnd))
                        .textAttributes(OceanSyntaxHighlighter.NUMBER)
                        .create();
            } else if (token.equals("true") || token.equals("false") || token.equals("null") ||
                       token.equals("this") || token.equals("new") || token.equals("await") || token.equals("super")) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(tokenStart, tokenEnd))
                        .textAttributes(OceanSyntaxHighlighter.KEYWORD)
                        .create();
            } else if (Character.isLetter(token.charAt(0)) || token.charAt(0) == '_') {
                // Check if followed by '(' in the original expression
                boolean isMethodCall = false;
                int endIdx = m.end();
                while (endIdx < expr.length() && Character.isWhitespace(expr.charAt(endIdx))) {
                    endIdx++;
                }
                if (endIdx < expr.length() && expr.charAt(endIdx) == '(') {
                    isMethodCall = true;
                }
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(tokenStart, tokenEnd))
                        .textAttributes(isMethodCall ? OceanSyntaxHighlighter.METHOD_CALL : OceanSyntaxHighlighter.LOCAL_VARIABLE)
                        .create();
            } else {
                holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                        .range(new TextRange(tokenStart, tokenEnd))
                        .textAttributes(OceanSyntaxHighlighter.OPERATOR)
                        .create();
            }
        }
    }

    private boolean isInterpolatedString(PsiElement element) {
        if (element.getNode() == null) return false;
        if (element.getNode().getElementType() instanceof TokenIElementType ti) {
            int tt = ti.getANTLRTokenType();
            return tt == OceanLexer.INTERPOLATED_STRING || tt == OceanLexer.MULTILINE_INTERPOLATED_STRING;
        }
        return false;
    }

    private static final Set<String> BUILT_IN_TYPES = Set.of(
            "OceanOutput", "OceanInput", "Output", "main", "this", "super", "args",
            "true", "false", "null", "variable", "value",
            "int", "bool", "String", "void", "double", "float",
            "long", "short", "byte", "char", "data", "result",
            "throws", "throw", "annotation", "sealed", "non-sealed", "restricts",
            "async", "await", "native", "when", "verify", "skip", "stop",
            "OceanList", "OceanMap", "OceanSet",
            "OceanIntList", "OceanLongList", "OceanDoubleList", "OceanFloatList",
            "OceanBooleanList", "OceanByteList", "OceanShortList", "OceanCharList",
            "Task", "ScopedValue", "OceanOptional", "OceanResult",
            "OceanPair", "OceanTriple", "OceanStack", "OceanQueue",
            "OceanCounter", "OceanBitSet", "OceanMath", "OceanString",
            "OceanFile", "OceanHttp", "OceanHttpResponse",
            "OceanDate", "OceanTime", "OceanRegex", "StrBuilder"
    );

    private boolean isLikelyBuiltIn(String text) {
        return text != null && (OceanKeywords.isKeyword(text) || BUILT_IN_TYPES.contains(text));
    }

    // ========== Semantic Analyzer Error Highlight Integration ==========

    private boolean isRule(PsiElement e, int ruleIndex) {
        return e != null && e.getNode() != null && e.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIndex;
    }

    private boolean isLambdaExpr(PsiElement e) {
        if (!isRule(e, OceanParser.RULE_expression)) return false;
        for (PsiElement child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode().getElementType().toString().equals("ARROW") || "->".equals(child.getText())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNewObjectExpr(PsiElement e) {
        if (!isRule(e, OceanParser.RULE_expression)) return false;
        PsiElement first = e.getFirstChild();
        return first != null && ("new".equals(first.getText()) || first.getNode().getElementType().toString().equals("NEW"));
    }

    private boolean isMainMethod(PsiElement e) {
        if (!isRule(e, OceanParser.RULE_methodDeclaration)) return false;
        for (PsiElement child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
            if ("main".equals(child.getText()) || child.getNode().getElementType().toString().equals("MAIN")) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrefixOrPostfixExpr(PsiElement e) {
        if (!isRule(e, OceanParser.RULE_expression)) return false;
        for (PsiElement child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
            String text = child.getText();
            if ("++".equals(text) || "--".equals(text)) {
                return true;
            }
        }
        return false;
    }

    private void checkClassDeclaration(PsiElement cls, AnnotationHolder holder) {
        String extendsName = extractExtendsName(cls);
        List<String> implementsNames = extractImplementsNames(cls);
        List<String> parents = new ArrayList<>();
        if (extendsName != null) parents.add(extendsName);
        parents.addAll(implementsNames);

        String className = findNameForElement(cls);
        PsiElement nameId = directChildByRuleIndex(cls, OceanParser.RULE_anyId);
        if (nameId == null) nameId = cls;

        // 0. Public class file name check [E0012]
        if (hasModifierToken(cls, OceanLexer.PUBLIC) && isTopLevelType(cls)) {
            PsiFile containingFile = cls.getContainingFile();
            if (containingFile != null) {
                String fileName = containingFile.getName();
                if (fileName.endsWith(".ocean")) {
                    String expectedName = fileName.substring(0, fileName.length() - ".ocean".length());
                    if (!expectedName.equals(className)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0012] Class '" + className + "' is public, should be declared in a file named '" + className + ".ocean'")
                              .range(nameId.getTextRange())
                              .create();
                    }
                }
            }
        }

        // 1. Data class restrictions: cannot be abstract or sealed (they can extend superclasses and have subclasses)
        if (hasModifierToken(cls, OceanLexer.DATA)) {
            if (hasModifierToken(cls, OceanLexer.ABSTRACT) || hasModifierToken(cls, OceanLexer.SEALED)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "Data class '" + className + "' cannot be abstract or sealed")
                      .range(nameId.getTextRange())
                      .create();
            }
        }

        // Modifier conflicts for class
        if (hasModifierToken(cls, OceanLexer.ABSTRACT) && hasModifierToken(cls, OceanLexer.FINAL)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot be both 'abstract' and 'final'")
                  .range(nameId.getTextRange())
                  .create();
        }
        if (hasModifierToken(cls, OceanLexer.SEALED) && hasModifierToken(cls, OceanLexer.NON_SEALED)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot be both 'sealed' and 'non-sealed'")
                  .range(nameId.getTextRange())
                  .create();
        }

        // 2. Sealed class restricts check
        if (hasModifierToken(cls, OceanLexer.SEALED)) {
            PsiElement restrictsNode = directChildByRuleIndex(cls, OceanParser.RULE_restrictsClause);
            if (restrictsNode == null) {
                holder.newAnnotation(HighlightSeverity.WARNING, "Sealed class '" + className + "' must specify permitted subclasses with 'restricts'")
                      .range(nameId.getTextRange())
                      .create();
            }
        }

        // 3. Final extends & Sealed extends & Cyclic inheritance check
        boolean superIsSealed = false;
        if (extendsName != null) {
            // Self-inheritance check: class A extends A
            if (extendsName.equals(className)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0004] Cyclic inheritance involving class '" + className + "'")
                      .range(nameId.getTextRange())
                      .create();
                return;
            }

            // Cyclic chain check: class A extends B where B extends A
            Set<String> visited = new HashSet<>();
            visited.add(className);
            String current = extendsName;
            boolean cyclic = false;
            while (current != null) {
                if (visited.contains(current)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0004] Cyclic inheritance involving class '" + className + "'")
                          .range(nameId.getTextRange())
                          .create();
                    cyclic = true;
                    break;
                }
                visited.add(current);
                PsiElement superCls = findClassInSameFileOrProject(cls.getProject(), cls, current);
                if (superCls != null) {
                    current = extractExtendsName(superCls);
                } else {
                    break;
                }
            }
            if (cyclic) return;

            // Direct inheritance prohibitions: java.lang.Enum, java.lang.Record, and enum classes
            if ("java.lang.Enum".equals(extendsName) || "java/lang/Enum".equals(extendsName) || "Enum".equals(extendsName)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot directly inherit from java.lang.Enum")
                        .range(nameId.getTextRange())
                        .create();
                return;
            }
            if ("java.lang.Record".equals(extendsName) || "java/lang/Record".equals(extendsName) || "Record".equals(extendsName)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot directly inherit from java.lang.Record")
                        .range(nameId.getTextRange())
                        .create();
                return;
            }

            // Generic class cannot extend java.lang.Throwable [E0010]
            boolean hasTypeParams = false;
            for (PsiElement c : cls.getChildren()) {
                if (isRule(c, OceanParser.RULE_typeParameter)) {
                    hasTypeParams = true;
                    break;
                }
            }
            if (hasTypeParams && isThrowableType(cls.getProject(), cls, extendsName)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Generic class '" + className + "' cannot extend java.lang.Throwable")
                        .range(nameId.getTextRange())
                        .create();
                return;
            }

            boolean isFinal = false;
            boolean isSealed = false;
            PsiElement oceanSuper = findClassInSameFileOrProject(cls.getProject(), cls, extendsName);
            if (oceanSuper != null) {
                if (isRule(oceanSuper, OceanParser.RULE_interfaceDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot extend interface '" + extendsName + "' (use 'implements' instead)")
                            .range(nameId.getTextRange())
                            .create();
                    return;
                }
                if (isRule(oceanSuper, OceanParser.RULE_enumDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot inherit from enum '" + extendsName + "'")
                            .range(nameId.getTextRange())
                            .create();
                    return;
                }
                if (hasModifierToken(oceanSuper, OceanLexer.FINAL)) {
                    isFinal = true;
                }
                if (hasModifierToken(oceanSuper, OceanLexer.SEALED)) {
                    isSealed = true;
                }
            } else {
                PsiClass javaSuper = resolveJavaClassInContext(cls.getProject(), cls, extendsName);
                if (javaSuper != null) {
                    if (javaSuper.isInterface()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot extend interface '" + extendsName + "' (use 'implements' instead)")
                                .range(nameId.getTextRange())
                                .create();
                        return;
                    }
                    if (javaSuper.isEnum()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot inherit from enum '" + extendsName + "'")
                                .range(nameId.getTextRange())
                                .create();
                        return;
                    }
                    if (javaSuper.hasModifierProperty(PsiModifier.FINAL)) {
                        isFinal = true;
                    }
                    if (javaSuper.hasModifierProperty(PsiModifier.SEALED)) {
                        isSealed = true;
                    }
                }
            }

            if (isFinal) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot inherit from final class '" + extendsName + "'.")
                      .range(nameId.getTextRange())
                      .create();
            }

            if (isSealed) {
                superIsSealed = true;
                boolean hasSealedModifier = hasModifierToken(cls, OceanLexer.FINAL) ||
                                            hasModifierToken(cls, OceanLexer.SEALED) ||
                                            hasModifierToken(cls, OceanLexer.NON_SEALED);
                if (!hasSealedModifier) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "Subclass '" + className + "' of sealed class '" + extendsName + "' must be declared 'final', 'sealed', or 'non-sealed'")
                          .range(nameId.getTextRange())
                          .create();
                }
            }
        }

        // Check implements targets: duplicate interface, cannot implement normal class or annotation
        Set<String> seenImplemented = new HashSet<>();
        for (String ifaceName : implementsNames) {
            String rawIface = OceanScopeDiscovery.normalizeTypeName(ifaceName);
            if (!seenImplemented.add(rawIface)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Duplicate interface: '" + ifaceName + "' is implemented more than once in '" + className + "'")
                        .range(nameId.getTextRange())
                        .create();
            }
            PsiElement oTarget = findClassInSameFileOrProject(cls.getProject(), cls, rawIface);
            if (oTarget != null) {
                if (isRule(oTarget, OceanParser.RULE_classDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot implement class '" + ifaceName + "'. Only interfaces can be implemented")
                            .range(nameId.getTextRange())
                            .create();
                } else if (isRule(oTarget, OceanParser.RULE_annotationDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot implement annotation '" + ifaceName + "'")
                            .range(nameId.getTextRange())
                            .create();
                }
            } else {
                PsiClass jTarget = resolveJavaClassInContext(cls.getProject(), cls, rawIface);
                if (jTarget != null) {
                    if (jTarget.isAnnotationType()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot implement annotation '" + ifaceName + "'")
                                .range(nameId.getTextRange())
                                .create();
                    } else if (!jTarget.isInterface()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Class '" + className + "' cannot implement class '" + ifaceName + "'. Only interfaces can be implemented")
                                .range(nameId.getTextRange())
                                .create();
                    }
                }
            }
        }

        boolean implementsSealed = false;
        for (String ifaceName : implementsNames) {
            String rawIface = OceanScopeDiscovery.normalizeTypeName(ifaceName);
            PsiElement oTarget = findClassInSameFileOrProject(cls.getProject(), cls, rawIface);
            if (oTarget != null) {
                if (hasModifierToken(oTarget, OceanLexer.SEALED)) {
                    implementsSealed = true;
                    String clsPkg = getFilePackage(cls);
                    String ifacePkg = getFilePackage(oTarget);
                    if (!clsPkg.equals(ifacePkg)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Permitted implementation '" + className + "' in package '" + (clsPkg.isEmpty() ? "unnamed" : clsPkg) + "' cannot implement sealed interface '" + ifaceName + "' in package '" + (ifacePkg.isEmpty() ? "unnamed" : ifacePkg) + "': sealed interface and its permitted implementations must belong to the same package.")
                                .range(nameId.getTextRange())
                                .create();
                    }
                }
            } else {
                PsiClass jTarget = resolveJavaClassInContext(cls.getProject(), cls, rawIface);
                if (jTarget != null && jTarget.hasModifierProperty(PsiModifier.SEALED)) {
                    implementsSealed = true;
                }
            }
        }

        if (hasModifierToken(cls, OceanLexer.NON_SEALED)) {
            if (!superIsSealed && !implementsSealed) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Modifier 'non-sealed' is only permitted on subclasses of a sealed class or interface. Direct supertype is not sealed.")
                        .range(nameId.getTextRange())
                        .create();
            }
        }

        // 4. Abstract method implementation check
        boolean isAbstract = hasModifierToken(cls, OceanLexer.ABSTRACT) || isRule(cls, OceanParser.RULE_interfaceDeclaration);
        if (!isAbstract) {
            OceanImplementMethodsAction action = new OceanImplementMethodsAction();
            List<OceanImplementMethodsAction.MethodStub> missing = action.collectMissingMethods(cls.getProject(), cls);
            if (missing != null && !missing.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("[E0010] Class '").append(className).append("' is not abstract and must implement methods: ");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(missing.get(i).name()).append("(").append(missing.get(i).params()).append(")");
                }
                holder.newAnnotation(HighlightSeverity.ERROR, sb.toString())
                      .range(nameId.getTextRange())
                      .withFix(new OceanImplementMethodsAction())
                      .create();
            }
        }

        // 5. Duplicate members check
        checkDuplicateMembers(cls, holder);

        // 6. Overrides compatibility check
        checkMethodOverrides(cls, holder, parents);

        // 7. Uninitialized field check [E0001]
        checkClassFieldInitialization(cls, holder);

        // 7b. Interface inheritance conflicts (diamond defaults & return types) [E0010]
        checkInterfaceInheritanceConflicts(cls, holder, className, nameId, implementsNames, false);

        // 8. Non-static inner classes cannot declare static initializer blocks [E0010]
        boolean isInnerClass = findEnclosingClass(cls) != null;
        boolean isStaticClass = hasModifierToken(cls, OceanLexer.STATIC);
        if (isInnerClass && !isStaticClass) {
            for (PsiElement child : cls.getChildren()) {
                if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                    boolean isStatic = hasModifierToken(child, OceanLexer.STATIC) || hasTokenChild(child, OceanLexer.STATIC);
                    if (isStatic) {
                        for (PsiElement m : child.getChildren()) {
                            if (isRule(m, OceanParser.RULE_block)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Non-static inner classes cannot declare static initializer blocks")
                                        .range(m.getTextRange())
                                        .create();
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkEnumDeclaration(PsiElement enumDecl, AnnotationHolder holder) {
        Set<String> constants = new HashSet<>();
        for (PsiElement child : enumDecl.getChildren()) {
            if (isRule(child, OceanParser.RULE_enumConstant)) {
                PsiElement id = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (id != null) {
                    String name = id.getText().trim();
                    if (!constants.add(name)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Duplicate enum constant: '" + name + "'")
                                .range(id.getTextRange())
                                .create();
                    }
                }
            } else if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement m : child.getChildren()) {
                    if (isRule(m, OceanParser.RULE_methodDeclaration)) {
                        PsiElement mId = directChildByRuleIndex(m, OceanParser.RULE_anyId);
                        if (mId != null) {
                            String mName = mId.getText().trim();
                            if ("clone".equals(mName)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enums cannot declare a 'clone' method")
                                        .range(mId.getTextRange())
                                        .create();
                            } else if ("name".equals(mName) || "ordinal".equals(mName) || "compareTo".equals(mName) || "equals".equals(mName) || "hashCode".equals(mName)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enum cannot override final method from java.lang.Enum: '" + mName + "'")
                                        .range(mId.getTextRange())
                                        .create();
                            }
                        }
                    } else if (isRule(m, OceanParser.RULE_fieldDeclaration)) {
                        for (PsiElement c : m.getChildren()) {
                            if (isRule(c, OceanParser.RULE_variableDeclarator)) {
                                PsiElement fId = directChildByRuleIndex(c, OceanParser.RULE_anyId);
                                if (fId != null && constants.contains(fId.getText().trim())) {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Enum field '" + fId.getText().trim() + "' conflicts with enum constant of the same name.")
                                            .range(fId.getTextRange())
                                            .create();
                                }
                            }
                        }
                    }
                }
            } else if (isRule(child, OceanParser.RULE_fieldDeclaration)) {
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_variableDeclarator)) {
                        PsiElement fId = directChildByRuleIndex(c, OceanParser.RULE_anyId);
                        if (fId != null && constants.contains(fId.getText().trim())) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Enum field '" + fId.getText().trim() + "' conflicts with enum constant of the same name.")
                                    .range(fId.getTextRange())
                                    .create();
                        }
                    }
                }
            }
        }

        // Check enum implements targets: cannot implement normal class or annotation
        List<String> implementsNames = extractImplementsNames(enumDecl);
        PsiElement nameId = directChildByRuleIndex(enumDecl, OceanParser.RULE_anyId);
        String enumName = nameId != null ? nameId.getText().trim() : "Enum";
        for (String ifaceName : implementsNames) {
            String rawIface = OceanScopeDiscovery.normalizeTypeName(ifaceName);
            PsiElement oTarget = findClassInSameFileOrProject(enumDecl.getProject(), enumDecl, rawIface);
            if (oTarget != null) {
                if (isRule(oTarget, OceanParser.RULE_classDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enum '" + enumName + "' cannot implement class '" + ifaceName + "'")
                            .range(nameId != null ? nameId.getTextRange() : enumDecl.getTextRange())
                            .create();
                } else if (isRule(oTarget, OceanParser.RULE_annotationDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enum '" + enumName + "' cannot implement annotation '" + ifaceName + "'")
                            .range(nameId != null ? nameId.getTextRange() : enumDecl.getTextRange())
                            .create();
                }
            } else {
                PsiClass jTarget = resolveJavaClassInContext(enumDecl.getProject(), enumDecl, rawIface);
                if (jTarget != null) {
                    if (jTarget.isAnnotationType()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enum '" + enumName + "' cannot implement annotation '" + ifaceName + "'")
                                .range(nameId != null ? nameId.getTextRange() : enumDecl.getTextRange())
                                .create();
                    } else if (!jTarget.isInterface()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enum '" + enumName + "' cannot implement class '" + ifaceName + "'")
                                .range(nameId != null ? nameId.getTextRange() : enumDecl.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private void checkInterfaceDeclaration(PsiElement iface, AnnotationHolder holder) {
        PsiElement nameId = directChildByRuleIndex(iface, OceanParser.RULE_anyId);
        String ifaceName = nameId != null ? nameId.getText().trim() : null;
        if (ifaceName == null) return;

        checkDuplicateMembers(iface, holder);

        // Cyclic inheritance & supertype checks in interface
        PsiElement typeListElem = directChildByRuleIndex(iface, OceanParser.RULE_typeList);
        List<PsiElement> typeNodes = new ArrayList<>();
        if (typeListElem != null) {
            for (PsiElement c : typeListElem.getChildren()) {
                if (isRule(c, OceanParser.RULE_type)) typeNodes.add(c);
            }
        }

        Set<String> seenExtended = new HashSet<>();
        for (PsiElement tn : typeNodes) {
            String superName = tn.getText().trim();
            if (!seenExtended.add(superName)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Duplicate interface: '" + superName + "' is extended more than once in '" + ifaceName + "'")
                        .range(tn.getTextRange())
                        .create();
            }
            if (ifaceName.equals(superName)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0004] Cyclic inheritance involving interface '" + ifaceName + "'")
                        .range(nameId.getTextRange())
                        .create();
                return;
            }
            if (hasInterfaceInheritanceCycle(iface.getProject(), iface, superName, ifaceName, new HashSet<>(Set.of(ifaceName)))) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0004] Cyclic inheritance involving interface '" + ifaceName + "'")
                        .range(nameId.getTextRange())
                        .create();
                return;
            }
            // Interface cannot extend class or annotation [E0010]
            PsiElement oTarget = findClassInSameFileOrProject(iface.getProject(), iface, superName);
            if (oTarget != null) {
                if (isRule(oTarget, OceanParser.RULE_classDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface '" + ifaceName + "' cannot extend class '" + superName + "'. Interfaces can only extend other interfaces")
                            .range(tn.getTextRange())
                            .create();
                } else if (isRule(oTarget, OceanParser.RULE_annotationDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface '" + ifaceName + "' cannot extend annotation '" + superName + "'")
                            .range(tn.getTextRange())
                            .create();
                }
            } else {
                PsiClass jTarget = resolveJavaClassInContext(iface.getProject(), iface, superName);
                if (jTarget != null) {
                    if (jTarget.isAnnotationType()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface '" + ifaceName + "' cannot extend annotation '" + superName + "'")
                                .range(tn.getTextRange())
                                .create();
                    } else if (!jTarget.isInterface()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface '" + ifaceName + "' cannot extend class '" + superName + "'. Interfaces can only extend other interfaces")
                                .range(tn.getTextRange())
                                .create();
                    }
                }
            }
        }

        boolean anySuperSealed = false;
        String ifacePkg = getFilePackage(iface);
        for (PsiElement tn : typeNodes) {
            String superName = tn.getText().trim();
            PsiElement oTarget = findClassInSameFileOrProject(iface.getProject(), iface, superName);
            if (oTarget != null) {
                if (hasModifierToken(oTarget, OceanLexer.SEALED)) {
                    anySuperSealed = true;
                    String supPkg = getFilePackage(oTarget);
                    if (!ifacePkg.equals(supPkg)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Permitted subinterface '" + ifaceName + "' in package '" + (ifacePkg.isEmpty() ? "unnamed" : ifacePkg) + "' cannot extend sealed interface '" + superName + "' in package '" + (supPkg.isEmpty() ? "unnamed" : supPkg) + "': sealed interface and its permitted subinterfaces must belong to the same package.")
                                .range(tn.getTextRange())
                                .create();
                    }
                }
            } else {
                PsiClass jTarget = resolveJavaClassInContext(iface.getProject(), iface, superName);
                if (jTarget != null && jTarget.hasModifierProperty(PsiModifier.SEALED)) {
                    anySuperSealed = true;
                }
            }
        }

        if (hasModifierToken(iface, OceanLexer.NON_SEALED)) {
            if (!anySuperSealed) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Modifier 'non-sealed' is only permitted on subinterfaces of a sealed interface hierarchy. No superinterface is sealed.")
                        .range(nameId.getTextRange())
                        .create();
            }
        }

        if (hasModifierToken(iface, OceanLexer.SEALED)) {
            PsiElement restrictsNode = directChildByRuleIndex(iface, OceanParser.RULE_restrictsClause);
            if (restrictsNode != null) {
                PsiElement typeList = directChildByRuleIndex(restrictsNode, OceanParser.RULE_typeList);
                if (typeList != null) {
                    for (PsiElement c : typeList.getChildren()) {
                        if (isRule(c, OceanParser.RULE_type)) {
                            String permName = c.getText().trim();
                            PsiElement pTarget = findClassInSameFileOrProject(iface.getProject(), iface, permName);
                            if (pTarget != null) {
                                String permPkg = getFilePackage(pTarget);
                                if (!ifacePkg.equals(permPkg)) {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Permitted type '" + permName + "' in package '" + (permPkg.isEmpty() ? "unnamed" : permPkg) + "' cannot be permitted by sealed interface '" + ifaceName + "' in package '" + (ifacePkg.isEmpty() ? "unnamed" : ifacePkg) + "': sealed interface and its permitted types must belong to the same package.")
                                            .range(c.getTextRange())
                                            .create();
                                }
                            }
                        }
                    }
                }
            }
        }

        List<String> extendedInterfaces = new ArrayList<>();
        for (PsiElement tn : typeNodes) {
            extendedInterfaces.add(tn.getText().trim());
        }
        checkInterfaceInheritanceConflicts(iface, holder, ifaceName, nameId, extendedInterfaces, true);

        // Interfaces cannot declare constructors, mutable fields, or instance initializer blocks
        for (PsiElement child : iface.getChildren()) {
            if (isRule(child, OceanParser.RULE_constructorDeclaration)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interfaces cannot declare constructors")
                        .range(child.getTextRange())
                        .create();
            } else if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement m : child.getChildren()) {
                    if (isRule(m, OceanParser.RULE_fieldDeclaration)) {
                        if (hasTokenChild(m, OceanLexer.VARIABLE)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot declare mutable ('variable') field in interface. All interface fields are implicitly constant")
                                    .range(m.getTextRange())
                                    .create();
                        }
                    } else if (isRule(m, OceanParser.RULE_block)) {
                        boolean isStatic = hasModifierToken(child, OceanLexer.STATIC) || hasTokenChild(child, OceanLexer.STATIC);
                        if (!isStatic) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Initializer blocks ({ ... }) cannot be defined in interfaces. Only 'static { ... }' blocks are supported")
                                    .range(m.getTextRange())
                                    .create();
                        }
                    }
                }
            } else if (isRule(child, OceanParser.RULE_fieldDeclaration)) {
                if (hasTokenChild(child, OceanLexer.VARIABLE)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot declare mutable ('variable') field in interface. All interface fields are implicitly constant")
                            .range(child.getTextRange())
                            .create();
                }
            } else if (isRule(child, OceanParser.RULE_methodDeclaration)) {
                boolean isDefault = hasModifierToken(child, OceanLexer.DEFAULT);
                boolean isStatic = hasModifierToken(child, OceanLexer.STATIC);
                boolean isAbstract = hasModifierToken(child, OceanLexer.ABSTRACT);
                boolean isPrivate = hasModifierToken(child, OceanLexer.PRIVATE);

                if (isDefault && isStatic) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface method cannot be both 'default' and 'static'")
                            .range(child.getTextRange())
                            .create();
                }
                if (isDefault && isAbstract) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface method cannot be both 'default' and 'abstract'")
                            .range(child.getTextRange())
                            .create();
                }
                if (isDefault && isPrivate) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface method cannot be both 'default' and 'private'")
                            .range(child.getTextRange())
                            .create();
                }

                if (hasModifierToken(child, OceanLexer.PROTECTED)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Modifier 'protected' not allowed on interface methods")
                            .range(child.getTextRange())
                            .create();
                }
                if (hasModifierToken(child, OceanLexer.FINAL)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Modifier 'final' not allowed on interface methods")
                            .range(child.getTextRange())
                            .create();
                }
                if (hasModifierToken(child, OceanLexer.SYNC)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Modifier 'sync' not allowed on interface methods")
                            .range(child.getTextRange())
                            .create();
                }
                boolean isStaticOrPrivate = isStatic || isPrivate;
                PsiElement methodBlock = directChildByRuleIndex(child, OceanParser.RULE_block);
                if (isStaticOrPrivate && methodBlock == null) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Static or private interface methods must have a body")
                            .range(child.getTextRange())
                            .create();
                }

                if (isDefault) {
                    PsiElement mId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                    if (mId != null) {
                        String mName = mId.getText().trim();
                        PsiElement pList = directChildByRuleIndex(child, OceanParser.RULE_parameterList);
                        int pCount = 0;
                        if (pList != null) {
                            for (PsiElement p : pList.getChildren()) {
                                if (isRule(p, OceanParser.RULE_parameter)) pCount++;
                            }
                        }
                        if (("equals".equals(mName) && pCount == 1) ||
                            ("hashCode".equals(mName) && pCount == 0) ||
                            ("toString".equals(mName) && pCount == 0)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Default method '" + mName + "' in interface cannot override public method from java.lang.Object")
                                    .range(mId.getTextRange())
                                    .create();
                        }
                    }
                }
            }
        }
    }

    private boolean hasInterfaceInheritanceCycle(Project project, PsiElement context, String currentIface, String startIface, Set<String> visited) {
        if (currentIface == null) return false;
        if (visited.contains(currentIface)) {
            return currentIface.equals(startIface);
        }
        visited.add(currentIface);

        PsiElement oTarget = findClassInSameFileOrProject(project, context, currentIface);
        if (isRule(oTarget, OceanParser.RULE_interfaceDeclaration)) {
            List<PsiElement> superNodes = extractInterfaceExtendsTypeNodes(oTarget);
            for (PsiElement sn : superNodes) {
                String nextSuper = sn.getText().trim();
                if (nextSuper.equals(startIface)) {
                    return true;
                }
                if (hasInterfaceInheritanceCycle(project, oTarget, nextSuper, startIface, new HashSet<>(visited))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<PsiElement> extractInterfaceExtendsTypeNodes(PsiElement iface) {
        List<PsiElement> result = new ArrayList<>();
        PsiElement typeList = directChildByRuleIndex(iface, OceanParser.RULE_typeList);
        if (typeList != null) {
            for (PsiElement c : typeList.getChildren()) {
                if (isRule(c, OceanParser.RULE_type)) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    private void checkAssignment(PsiElement assignment, AnnotationHolder holder) {
        PsiElement lhs = assignment.getFirstChild();
        if (lhs == null) return;
        String lhsText = lhs.getText().trim();

        if ("this".equals(lhsText)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign a value to 'this'")
                    .range(lhs.getTextRange())
                    .create();
            return;
        }

        if ("super".equals(lhsText)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign a value to 'super'")
                    .range(lhs.getTextRange())
                    .create();
            return;
        }

        if (lhsText.endsWith(".length")) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot modify final field 'length' of array.")
                    .range(lhs.getTextRange())
                    .create();
            return;
        }

        if (!isAssignableTarget(lhs)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Invalid assignment target. Left-hand side must be a variable, field, or array element.")
                    .range(lhs.getTextRange())
                    .create();
            return;
        }

        // Void expression cannot be assigned to a variable [E0002]
        PsiElement rhsExpr = directChildByRuleIndex(assignment, OceanParser.RULE_expression);
        if (rhsExpr != null) {
            String rhsType = OceanTypeHelper.guessType(rhsExpr);
            if (isVoidType(rhsType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] 'void' expression cannot be assigned to a variable")
                        .range(rhsExpr.getTextRange())
                        .create();
                return;
            }

            // Compound assignment operators check: &=, |=, ^=, <<=, >>=, >>>= [E0003]
            int opType = -1;
            for (PsiElement child : assignment.getChildren()) {
                if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti) {
                    int tt = ti.getANTLRTokenType();
                    if (tt == OceanLexer.AMP_ASSIGN || tt == OceanLexer.PIPE_ASSIGN || tt == OceanLexer.CARET_ASSIGN ||
                        tt == OceanLexer.LSHIFT_ASSIGN || tt == OceanLexer.RSHIFT_ASSIGN || tt == OceanLexer.URSHIFT_ASSIGN) {
                        opType = tt;
                        break;
                    }
                }
            }

            if (opType != -1) {
                String lhsType = OceanTypeHelper.guessType(lhs);
                if (lhsType != null && rhsType != null) {
                    if (opType == OceanLexer.AMP_ASSIGN || opType == OceanLexer.PIPE_ASSIGN || opType == OceanLexer.CARET_ASSIGN) {
                        boolean lhsInt = isIntegerType(lhsType);
                        boolean rhsInt = isIntegerType(rhsType);
                        boolean lhsBool = isBooleanType(lhsType);
                        boolean rhsBool = isBooleanType(rhsType);
                        if (!((lhsInt && rhsInt) || (lhsBool && rhsBool))) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Bitwise assignment operator incompatible types: '" + lhsType + "' and '" + rhsType + "'")
                                    .range(assignment.getTextRange())
                                    .create();
                        }
                    } else {
                        if (!isIntegerType(lhsType)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Shift assignment operator left-hand side must be an integer type: '" + lhsType + "'")
                                    .range(lhs.getTextRange())
                                    .create();
                        }
                        if (!isIntegerType(rhsType)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Shift assignment operator right-hand side must be an integer type: '" + rhsType + "'")
                                    .range(rhsExpr.getTextRange())
                                    .create();
                        }
                    }
                }
            }

            // Narrowing compound assignment check [E0003]
            boolean isCompound = false;
            for (PsiElement child : assignment.getChildren()) {
                String ct = child.getText() != null ? child.getText().trim() : "";
                if (ct.equals("+=") || ct.equals("-=") || ct.equals("*=") || ct.equals("/=") || ct.equals("%=") ||
                    ct.equals("&=") || ct.equals("|=") || ct.equals("^=") || ct.equals("<<=") || ct.equals(">>=") || ct.equals(">>>=")) {
                    isCompound = true;
                    break;
                }
            }
            String lhsType = OceanTypeHelper.guessType(lhs);
            if (isCompound && lhsType != null && rhsType != null) {
                if (isCompoundNarrowing(lhsType, rhsType)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Narrowing compound assignment detected: '" + toHumanReadableType(rhsType) + "' cannot be assigned to '" + toHumanReadableType(lhsType) + "' without explicit cast '(" + toHumanReadableType(lhsType) + ")'.")
                            .range(assignment.getTextRange())
                            .create();
                }
            } else if (!isCompound && lhsType != null && rhsType != null && !"null".equals(rhsType)) {
                if (!OceanTypeHelper.isAssignable(lhsType, rhsType, assignment.getProject())) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible types: cannot convert '" + toHumanReadableType(rhsType) + "' to '" + toHumanReadableType(lhsType) + "'")
                            .range(rhsExpr.getTextRange())
                            .create();
                }
            }
        }

        // Collect OceanIdReference in lhs to find target variable or field being assigned
        PsiElement[] idRefs = PsiTreeUtil.collectElements(lhs, e -> e instanceof OceanIdReference);
        PsiElement targetRef = idRefs.length > 0 ? idRefs[idRefs.length - 1] : null;

        PsiElement resolved = null;
        String varName = null;
        if (targetRef instanceof OceanIdReference oir) {
            varName = targetRef.getText().trim();
            resolved = oir.resolve();
        } else {
            PsiElement id = directChildByRuleIndex(lhs, OceanParser.RULE_anyId);
            if (id != null) {
                varName = id.getText().trim();
                PsiReference r = id.getReference();
                if (r != null) resolved = r.resolve();
                if (resolved == null) {
                    for (PsiElement sym : OceanScopeDiscovery.findVisibleSymbols(id)) {
                        if (varName.equals(OceanScopeDiscovery.findDeclarationName(sym))) {
                            resolved = sym;
                            break;
                        }
                    }
                }
            } else {
                PsiReference r = lhs.getReference();
                if (r != null) {
                    resolved = r.resolve();
                    varName = lhs.getText().trim();
                }
            }
        }

        if (resolved != null) {
            boolean isFinalOrValue = false;
            boolean isField = false;

            if (resolved instanceof OceanVariableDeclarator declarator) {
                isField = declarator.isField();
                PsiElement parent = declarator.getParent();
                if (parent != null) {
                    if (hasTokenChild(parent, OceanLexer.FINAL) || hasTokenChild(parent, OceanLexer.VALUE) || hasModifierToken(parent, OceanLexer.FINAL)) {
                        isFinalOrValue = true;
                    }
                }
            } else if (resolved instanceof OceanLoopVariable loopVar) {
                PsiElement parent = loopVar.getParent();
                if (parent != null && (hasTokenChild(parent, OceanLexer.FINAL) || hasTokenChild(parent, OceanLexer.VALUE))) {
                    isFinalOrValue = true;
                }
            } else if (resolved instanceof OceanParameter param) {
                if (hasModifierToken(param, OceanLexer.FINAL) || hasTokenChild(param, OceanLexer.FINAL) || hasTokenChild(param, OceanLexer.VALUE)) {
                    isFinalOrValue = true;
                }
            } else if (resolved instanceof PsiModifierListOwner psiOwner) {
                isField = resolved instanceof PsiField;
                if (psiOwner.hasModifierProperty(PsiModifier.FINAL)) {
                    isFinalOrValue = true;
                }
            }

            // Try-with-resources resource variable cannot be reassigned [E0002]
            if (isTryWithResourcesResource(resolved)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Resource '" + varName + "' in try-with-resources statement must be final or effectively final and cannot be reassigned")
                        .range(targetRef != null ? targetRef.getTextRange() : lhs.getTextRange())
                        .create();
                return;
            }

            boolean isStatic = false;
            if (isField) {
                if (resolved instanceof OceanVariableDeclarator dec) {
                    PsiElement p = dec.getParent();
                    PsiElement enc = findEnclosingClass(dec);
                    boolean isInterface = isRule(enc, OceanParser.RULE_interfaceDeclaration);
                    if (isInterface || (p != null && (hasModifierToken(p, OceanLexer.STATIC) || hasTokenChild(p, OceanLexer.STATIC)))) {
                        isStatic = true;
                    }
                } else {
                    PsiField psiField = (PsiField) resolved;
                    if (psiField.hasModifierProperty(PsiModifier.STATIC)) {
                        isStatic = true;
                    }
                }
            }

            if (isFinalOrValue) {
                if (isInsideLoop(assignment)) {
                    if (isStatic) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign static final field '" + varName + "' inside a loop.")
                                .range(targetRef != null ? targetRef.getTextRange() : lhs.getTextRange())
                                .create();
                    } else {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign final field '" + varName + "' inside a loop")
                                .range(targetRef != null ? targetRef.getTextRange() : lhs.getTextRange())
                                .create();
                    }
                    return;
                }
                boolean inConstructor = isInsideConstructor(assignment);
                if (isField) {
                    boolean isBlankFinal;
                    if (resolved instanceof OceanVariableDeclarator dec) {
                        isBlankFinal = dec.getInitializer() == null;
                    } else {
                        PsiField psiField = (PsiField) resolved;
                        isBlankFinal = psiField.getInitializer() == null;
                    }
                    if (isStatic) {
                        // Static final field cannot be assigned in constructor or if already initialized / outside static init
                        if (inConstructor || !isBlankFinal) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign a value to static final field '" + varName + "' (already initialized)")
                                    .range(targetRef != null ? targetRef.getTextRange() : lhs.getTextRange())
                                    .create();
                        }
                    } else if (!inConstructor || !isBlankFinal) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign a value to final field '" + varName + "'")
                                .range(targetRef != null ? targetRef.getTextRange() : lhs.getTextRange())
                                .create();
                    }
                } else {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot assign a value to final variable '" + varName + "'")
                            .range(targetRef != null ? targetRef.getTextRange() : lhs.getTextRange())
                            .create();
                }
            }
        }
    }

    private boolean isCompoundNarrowing(String targetType, String sourceType) {
        if (targetType == null || sourceType == null) return false;
        String t = toHumanReadableType(targetType).toLowerCase();
        String s = toHumanReadableType(sourceType).toLowerCase();
        // 1. Floating point to integer (double/float -> int/long/short/byte/char)
        if (("double".equals(s) || "float".equals(s)) && isIntegerType(targetType)) {
            return true;
        }
        // 2. Double to float
        if ("double".equals(s) && "float".equals(t)) {
            return true;
        }
        // 3. Long to smaller integer (long -> int/short/byte/char)
        return "long".equals(s) && ("int".equals(t) || "short".equals(t) || "byte".equals(t) || "char".equals(t));
    }

    private void checkSwitchStatement(PsiElement switchStmt, AnnotationHolder holder) {
        Set<String> seenLabels = new HashSet<>();
        boolean hasArrow = false;
        boolean hasColon = false;
        int nullCount = 0;

        PsiElement selectorExpr = directChildByRuleIndex(switchStmt, OceanParser.RULE_expression);
        String selectorType = selectorExpr != null ? OceanTypeHelper.guessType(selectorExpr) : null;
        if (selectorType != null) {
            String normType = selectorType.trim().toLowerCase();
            if ("long".equals(normType) || "java.lang.long".equals(normType) ||
                "float".equals(normType) || "java.lang.float".equals(normType) ||
                "double".equals(normType) || "java.lang.double".equals(normType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Selector expression in switch cannot be of type 'long', 'float', or 'double': '" + selectorType + "'")
                        .range(selectorExpr.getTextRange())
                        .create();
            }
        }

        // Track patterns seen for dominance checking [E0003]
        record PriorPattern(String typeDesc, boolean isTotal, String displayText) {}
        List<PriorPattern> seenPatterns = new ArrayList<>();
        boolean hasTotalPattern = false;
        String totalPatternText = null;

        for (PsiElement child : switchStmt.getChildren()) {
            if (isRule(child, OceanParser.RULE_switchCase)) {
                if (hasTokenChild(child, OceanLexer.ARROW)) hasArrow = true;
                if (hasTokenChild(child, OceanLexer.COLON)) hasColon = true;

                boolean hasWhenGuard = hasTokenChild(child, OceanLexer.WHEN);

                for (PsiElement caseChild : child.getChildren()) {
                    if (isRule(caseChild, OceanParser.RULE_expression) || isRule(caseChild, OceanParser.RULE_primary)) {
                        String label = caseChild.getText().trim();
                        if (label.contains("(") && label.contains(")") && !isRule(caseChild, OceanParser.RULE_switchPattern)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Constant expression required for switch case label.")
                                    .range(caseChild.getTextRange())
                                    .create();
                        }
                        if ("null".equals(label)) {
                            if (hasTotalPattern) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'case null' label cannot appear after total pattern ('" + (totalPatternText != null ? totalPatternText : "Object") + "').")
                                        .range(caseChild.getTextRange())
                                        .create();
                            }
                            nullCount++;
                            if (nullCount > 1) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Duplicate 'null' label: multiple 'case null' labels are not permitted in a switch block")
                                        .range(caseChild.getTextRange())
                                        .create();
                            }
                            if (hasWhenGuard) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'case null' label cannot have a 'when' guard")
                                        .range(caseChild.getTextRange())
                                        .create();
                            }
                            if (isPrimitiveType(selectorType)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Label 'case null' is not allowed with primitive switch selector type '" + selectorType + "'")
                                        .range(caseChild.getTextRange())
                                        .create();
                            }
                        }

                        // Constant value bounds check for primitive switch selector [E0003]
                        if (selectorType != null) {
                            String cleanTarget = selectorType.trim();
                            Long constVal = null;
                            try {
                                constVal = Long.parseLong(label);
                            } catch (NumberFormatException ignored) {}

                            if (constVal != null) {
                                if ("byte".equalsIgnoreCase(cleanTarget) || "Byte".equals(cleanTarget) || "B".equals(cleanTarget)) {
                                    if (constVal < Byte.MIN_VALUE || constVal > Byte.MAX_VALUE) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Constant value (" + constVal + ") exceeds bounds for switch selector type 'byte' [-128, 127].")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                } else if ("short".equalsIgnoreCase(cleanTarget) || "Short".equals(cleanTarget) || "S".equals(cleanTarget)) {
                                    if (constVal < Short.MIN_VALUE || constVal > Short.MAX_VALUE) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Constant value (" + constVal + ") exceeds bounds for switch selector type 'short' [-32768, 32767].")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                } else if ("char".equalsIgnoreCase(cleanTarget) || "Character".equals(cleanTarget) || "C".equals(cleanTarget)) {
                                    if (constVal < Character.MIN_VALUE || constVal > Character.MAX_VALUE) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Constant value (" + constVal + ") exceeds bounds for switch selector type 'char' [0, 65535].")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                }
                            }

                            // Switch selector vs label type compatibility [E0003]
                            if (!"null".equals(label)) {
                                String labelType = OceanTypeHelper.guessType(caseChild);
                                String targetSimple = toHumanReadableType(selectorType);
                                String labelSimple = toHumanReadableType(labelType);
                                boolean isEnum = isEnumType(switchStmt.getProject(), switchStmt, selectorType);
                                if (isEnum) {
                                    boolean isEnumConstant = isEnumConstantIdentifier(caseChild, label);
                                    if (!isEnumConstant) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Invalid case constant type for enum switch selector ('" + targetSimple + "').")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                } else if ("String".equalsIgnoreCase(targetSimple)) {
                                    if (labelType != null && !("String".equalsIgnoreCase(labelSimple) || label.startsWith("\""))) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible case label type ('" + labelSimple + "') for switch selector ('String').")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                } else if ("boolean".equalsIgnoreCase(targetSimple)) {
                                    if (labelType != null && !("boolean".equalsIgnoreCase(labelSimple) || "true".equals(label) || "false".equals(label))) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible case label type ('" + labelSimple + "') for switch selector ('boolean').")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                } else if (isIntegerType(selectorType)) {
                                    if (labelType != null && ("String".equalsIgnoreCase(labelSimple) || label.startsWith("\"") || "boolean".equalsIgnoreCase(labelSimple) || "double".equalsIgnoreCase(labelSimple) || "float".equalsIgnoreCase(labelSimple))) {
                                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible case label type ('" + labelSimple + "') for switch selector ('" + targetSimple + "').")
                                                .range(caseChild.getTextRange())
                                                .create();
                                    }
                                }
                            }
                        }

                        // Pattern dominance check for expressions [E0003]
                        if (!hasWhenGuard && hasTotalPattern) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Dominated pattern: switch label is dominated by a preceding pattern and cannot be reached: '" + label + "'.")
                                    .range(caseChild.getTextRange())
                                    .create();
                        }

                        if (!seenLabels.add(label)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Duplicate case label: '" + label + "'")
                                    .range(caseChild.getTextRange())
                                    .create();
                        }
                    } else if (isRule(caseChild, OceanParser.RULE_switchPattern) || isRule(caseChild, OceanParser.RULE_instanceofPattern)) {
                        String patternText = caseChild.getText().trim();
                        if (patternText.contains("(") && patternText.contains(")") && isPrimitiveType(selectorType)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Record pattern cannot be applied to primitive selector type '" + selectorType + "'.")
                                    .range(caseChild.getTextRange())
                                    .create();
                        }

                        // Pattern dominance check for patterns [E0003]
                        for (PriorPattern prior : seenPatterns) {
                            if (prior.isTotal || (prior.typeDesc != null && prior.typeDesc.equals("Object"))) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Dominated pattern: switch label is dominated by a preceding pattern and cannot be reached: '" + patternText + "'.")
                                        .range(caseChild.getTextRange())
                                        .create();
                                break;
                            }
                        }

                        if (!hasWhenGuard) {
                            boolean isTotal = patternText.startsWith("Object ") || patternText.equals("Object") || patternText.equals("_");
                            if (selectorType != null && patternText.startsWith(selectorType + " ")) {
                                isTotal = true;
                            }
                            seenPatterns.add(new PriorPattern(patternText.split("\\s+")[0], isTotal, patternText));
                            if (isTotal) {
                                hasTotalPattern = true;
                                if (totalPatternText == null) totalPatternText = patternText;
                            }
                        }
                    }
                }
            } else if (isRule(child, OceanParser.RULE_defaultCase)) {
                // Check if default label is dominated by an earlier total pattern [E0003]
                if (hasTotalPattern) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Switch 'default' label is dominated by a preceding total pattern ('" + (totalPatternText != null ? totalPatternText : "Object") + "') and cannot be reached.")
                            .range(child.getTextRange())
                            .create();
                }
            }
        }

        if (hasArrow && hasColon) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Different case kinds (arrow '->' and colon ':') cannot be mixed in switch.")
                    .range(switchStmt.getTextRange())
                    .create();
        }
    }

    private void checkSwitchCase(PsiElement caseElem, AnnotationHolder holder) {
        if (hasTokenChild(caseElem, OceanLexer.ARROW)) {
            PsiElement caseBlock = directChildByRuleIndex(caseElem, OceanParser.RULE_block);
            if (caseBlock != null) {
                boolean hasResult = false;
                PsiElement[] elements = PsiTreeUtil.collectElements(caseBlock, e -> hasTokenChild(e, OceanLexer.RESULT_KW) || hasTokenChild(e, OceanLexer.THROW) || e.getText().trim().startsWith("result ") || e.getText().trim().startsWith("throw "));
                if (elements.length > 0) hasResult = true;

                if (!hasResult) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] All execution paths in switch expression block must yield a result value")
                            .range(caseBlock.getTextRange())
                            .create();
                }
            }
        }
    }

    private boolean isEnumType(Project project, PsiElement context, String typeName) {
        if (typeName == null) return false;
        String raw = extractRawType(typeName);
        PsiElement oceanCls = findClassInSameFileOrProject(project, context, raw);
        if (isRule(oceanCls, OceanParser.RULE_enumDeclaration)) {
            return true;
        }
        PsiClass javaCls = resolveJavaClassInContext(project, context, raw);
        return javaCls != null && javaCls.isEnum();
    }

    private boolean isEnumConstantIdentifier(PsiElement element, String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.startsWith("\"") || text.startsWith("'") || "true".equals(text) || "false".equals(text)) return false;
        return !Character.isDigit(text.charAt(0)) && (text.length() == 1 || (text.charAt(0) != '-' && text.charAt(0) != '+') || !Character.isDigit(text.charAt(1)));
    }

    private void checkMethodDeclaration(PsiElement method, AnnotationHolder holder) {
        boolean isVoid = OceanScopeDiscovery.isVoidMethod(method);
        PsiElement type = isVoid ? null : OceanScopeDiscovery.getMethodReturnTypeNode(method);

        boolean isStatic = hasModifierToken(method, OceanLexer.STATIC);

        PsiElement block = directChildByRuleIndex(method, OceanParser.RULE_block);
        if (block != null) {
            List<PsiElement> returnStmts = collectTopLevelReturnStatements(block);
            if (isVoid) {
                for (PsiElement ret : returnStmts) {
                    for (PsiElement rc : ret.getChildren()) {
                        if (isRule(rc, OceanParser.RULE_expression)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "Cannot return a value from a method with void result type")
                                    .range(rc.getTextRange())
                                    .create();
                        }
                    }
                }
            } else {
                boolean isMain = false;
                for (PsiElement c : method.getChildren()) {
                    if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.MAIN) {
                        isMain = true;
                        break;
                    }
                }
                if (!isMain && type != null) {
                    if (!allPathsReturn(block)) {
                        PsiElement nameId = directChildByRuleIndex(method, OceanParser.RULE_anyId);
                        String mName = nameId != null ? nameId.getText().trim() : "method";
                        String retType = type.getText().trim();
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] Missing return statement in method '" + mName + "' with non-void return type '" + retType + "'")
                                .range(nameId != null ? nameId.getTextRange() : method.getTextRange())
                                .withFix(new OceanAddReturnStatementQuickFix(method, retType))
                                .create();
                    } else {
                        for (PsiElement ret : returnStmts) {
                            boolean hasExpr = false;
                            for (PsiElement rc : ret.getChildren()) {
                                if (isRule(rc, OceanParser.RULE_expression)) {
                                    hasExpr = true;
                                    break;
                                }
                            }
                            if (!hasExpr) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] Missing return value in method with non-void return type '" + type.getText().trim() + "'")
                                        .range(ret.getTextRange())
                                        .withFix(new OceanAddReturnStatementQuickFix(method, type.getText().trim()))
                                        .create();
                            }
                        }
                    }
                }
            }
        }

        // Abstract method cannot declare a body
        if (hasModifierToken(method, OceanLexer.ABSTRACT) && block != null) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Abstract method cannot declare a body")
                    .range(method.getTextRange())
                    .withFix(new OceanRemoveMethodBodyQuickFix(block))
                    .create();
        }

        // Native method checks
        if (hasModifierToken(method, OceanLexer.NATIVE)) {
            if (block != null) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Native methods cannot declare a body")
                        .range(method.getTextRange())
                        .withFix(new OceanRemoveMethodBodyQuickFix(block))
                        .create();
            }
            if (hasModifierToken(method, OceanLexer.ASYNC)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Native methods cannot be declared 'async'")
                        .range(method.getTextRange())
                        .create();
            }
            PsiElement encClass = findEnclosingClass(method);
            if (encClass != null) {
                if (isRule(encClass, OceanParser.RULE_interfaceDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Native methods cannot be declared in interfaces")
                            .range(method.getTextRange())
                            .create();
                } else if (isRule(encClass, OceanParser.RULE_annotationDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Native methods cannot be declared in annotations")
                            .range(method.getTextRange())
                            .create();
                }
            }
        }

        // Interface method modifier restrictions [E0010]
        PsiElement encIfaceOrClass = findEnclosingClass(method);
        if (isRule(encIfaceOrClass, OceanParser.RULE_interfaceDeclaration)) {
            if (hasModifierToken(method, OceanLexer.PROTECTED)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface methods cannot be declared 'protected'.")
                        .range(method.getTextRange())
                        .create();
            }
            if (hasModifierToken(method, OceanLexer.FINAL)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface methods cannot be declared 'final'.")
                        .range(method.getTextRange())
                        .create();
            }
            if (hasModifierToken(method, OceanLexer.SYNC) || hasTokenChild(method, OceanLexer.LOCK)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Interface methods cannot be declared 'sync'.")
                        .range(method.getTextRange())
                        .create();
            }
        }

        // Static method cannot be marked with @Override
        if (isStatic) {
            for (PsiElement child : method.getChildren()) {
                if (isRule(child, OceanParser.RULE_annotation)) {
                    if (child.getText().contains("Override")) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Static method cannot be marked with @Override")
                                .range(child.getTextRange())
                                .withFix(new OceanRemoveOverrideQuickFix(child))
                                .create();
                    }
                }
            }

            // Cannot use 'super' in a static context [E0008]
            if (block != null) {
                for (PsiElement child : PsiTreeUtil.collectElements(block, e -> hasTokenChild(e, OceanLexer.SUPER))) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0008] Cannot use 'super' in a static context")
                            .range(child.getTextRange())
                            .create();
                }
            }
        }

        // Method modifier conflicts [E0010]
        boolean isAbstract = hasModifierToken(method, OceanLexer.ABSTRACT);
        boolean isPrivate = hasModifierToken(method, OceanLexer.PRIVATE);
        boolean isFinal = hasModifierToken(method, OceanLexer.FINAL);
        boolean isNative = hasModifierToken(method, OceanLexer.NATIVE);
        boolean isSync = hasModifierToken(method, OceanLexer.SYNC) || hasTokenChild(method, OceanLexer.LOCK);

        PsiElement methodNameId = directChildByRuleIndex(method, OceanParser.RULE_anyId);
        String methodName = methodNameId != null ? methodNameId.getText().trim() : "method";

        if (isAbstract && isStatic) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method '" + methodName + "' cannot be both 'abstract' and 'static'")
                    .range(method.getTextRange())
                    .create();
        }
        if (isAbstract && isPrivate) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method '" + methodName + "' cannot be both 'private' and 'abstract'")
                    .range(method.getTextRange())
                    .create();
        }
        if (isAbstract && isFinal) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method '" + methodName + "' cannot be both 'abstract' and 'final'")
                    .range(method.getTextRange())
                    .create();
        }
        if (isAbstract && isNative) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method '" + methodName + "' cannot be both 'native' and 'abstract'")
                    .range(method.getTextRange())
                    .create();
        }
        if (isAbstract && isSync) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method '" + methodName + "' cannot be both 'abstract' and 'sync'")
                    .range(method.getTextRange())
                    .create();
        }

        // Duplicate parameter, varargs position, variable/value type & JVM slot check
        Set<String> seenParams = new HashSet<>();
        for (PsiElement child : method.getChildren()) {
            if (isRule(child, OceanParser.RULE_parameterList)) {
                List<PsiElement> paramList = new ArrayList<>();
                for (PsiElement p : child.getChildren()) {
                    if (isRule(p, OceanParser.RULE_parameter)) {
                        paramList.add(p);
                    }
                }
                if (paramList.size() > 255) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method parameter slots limit exceeded (" + paramList.size() + " > 255). JVM permits at most 255 parameter slots")
                            .range(child.getTextRange())
                            .create();
                }
                for (int i = 0; i < paramList.size(); i++) {
                    PsiElement p = paramList.get(i);
                    PsiElement pId = directChildByRuleIndex(p, OceanParser.RULE_anyId);
                    if (pId != null) {
                        String pName = pId.getText().trim();
                        if (!"_".equals(pName) && !seenParams.add(pName)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Duplicate method parameter: '" + pName + "'")
                                    .range(pId.getTextRange())
                                    .create();
                        }
                    }
                    // Cannot use 'variable' / 'value' type for parameter [E0010]
                    if (hasTokenChild(p, OceanLexer.VARIABLE) || hasTokenChild(p, OceanLexer.VALUE)) {
                        String pName = pId != null ? pId.getText().trim() : p.getText().trim();
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot use 'variable' / 'value' type for method or constructor parameter: '" + pName + "'")
                                .range(p.getTextRange())
                                .create();
                    }
                    // Varargs must be the last parameter
                    if (p.getText().contains("...") && i < paramList.size() - 1) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Varargs parameter ('...') must be the last parameter in the parameter list")
                                .range(p.getTextRange())
                                .create();
                    }
                }
            }
        }

        // @SafeVarargs annotation check [E0010]
        checkSafeVarargsAnnotation(method, holder, isStatic, isPrivate, isFinal, false);

        // Static method referencing class type parameter check [E0010]
        checkStaticMethodClassTypeParameter(method, isStatic, holder);

        // Variance position check (+T in parameter, -T in return) [E0010]
        checkVariancePosition(method, isStatic, holder);

        // Throws clause check [E0010]
        checkThrowsClause(method, holder);
    }

    private void checkConstructorDeclaration(PsiElement constructor, AnnotationHolder holder) {
        PsiElement block = directChildByRuleIndex(constructor, OceanParser.RULE_block);
        if (block == null) return;

        PsiElement ctorId = directChildByRuleIndex(constructor, OceanParser.RULE_anyId);
        String ctorName = ctorId != null ? ctorId.getText().trim() : "";
        if ("main".equals(ctorName)) {
            // Main method parsed as constructorDeclaration due to grammar rule precedence
            return;
        }

        PsiElement encClass = findEnclosingClass(constructor);
        if (encClass != null && !ctorName.isEmpty()) {
            PsiElement clsId = directChildByRuleIndex(encClass, OceanParser.RULE_anyId);
            String clsName = clsId != null ? clsId.getText().trim() : "";
            if (!clsName.isEmpty() && !ctorName.equals(clsName)) {
                // Name does not match class name -> not a constructor
                return;
            }
        }

        boolean isEnum = isRule(encClass, OceanParser.RULE_enumDeclaration);

        // Constructor modifier validation [E0010]
        if (isEnum) {
            if (hasModifierToken(constructor, OceanLexer.PUBLIC) || hasModifierToken(constructor, OceanLexer.PROTECTED)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Enum constructor can only have 'private' modifier.")
                        .range(constructor.getTextRange())
                        .create();
            }
        } else {
            // Normal class constructor: only public, protected, private are allowed
            if (hasModifierToken(constructor, OceanLexer.STATIC) || hasModifierToken(constructor, OceanLexer.FINAL) ||
                hasModifierToken(constructor, OceanLexer.ABSTRACT) || hasModifierToken(constructor, OceanLexer.SYNC) ||
                hasModifierToken(constructor, OceanLexer.NATIVE) || hasModifierToken(constructor, OceanLexer.ASYNC)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Constructors can only have 'public', 'protected', or 'private' modifiers.")
                        .range(constructor.getTextRange())
                        .create();
            }
        }

        // Find all statements in constructor body
        List<PsiElement> statements = new ArrayList<>();
        for (PsiElement child : block.getChildren()) {
            if (isRule(child, OceanParser.RULE_statement)) {
                statements.add(child);
            }
        }

        int explicitCtorCalls = 0;
        int firstExplicitCtorStmtIndex = -1;
        for (int i = 0; i < statements.size(); i++) {
            PsiElement stmt = statements.get(i);
            String sText = stmt.getText().trim();
            boolean isSuperCall = sText.startsWith("super") && sText.substring("super".length()).trim().startsWith("(");
            boolean isThisCall = sText.startsWith("this") && sText.substring("this".length()).trim().startsWith("(");
            if (isSuperCall || isThisCall) {
                explicitCtorCalls++;
                if (firstExplicitCtorStmtIndex == -1) {
                    firstExplicitCtorStmtIndex = i;
                }
            }
        }

        int seenExplicitCalls = 0;
        int statementIndex = 0;
        for (PsiElement stmt : statements) {
            statementIndex++;
            // Check if this statement is an explicit constructor invocation: this(...) or super(...)
            String sText = stmt.getText().trim();
            boolean isSuperCall = sText.startsWith("super(") || sText.startsWith("super (");
            boolean isThisCall = sText.startsWith("this(") || sText.startsWith("this (");
            boolean isThisOrSuperCall = isSuperCall || isThisCall;

            if (isThisOrSuperCall) {
                seenExplicitCalls++;
                if (seenExplicitCalls > 1) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Constructor cannot contain multiple 'super()' or 'this()' calls")
                            .range(stmt.getTextRange())
                            .create();
                }
                if (isSuperCall && isEnum) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Explicit 'super()' call is not allowed in enum constructors. Only 'this()' can be used")
                            .range(stmt.getTextRange())
                            .create();
                } else if (!isSuperCall) {
                    PsiElement paramList = directChildByRuleIndex(constructor, OceanParser.RULE_parameterList);
                    List<String> myParamTypes = new ArrayList<>();
                    if (paramList != null) {
                        for (PsiElement p : paramList.getChildren()) {
                            if (isRule(p, OceanParser.RULE_parameter)) {
                                PsiElement pType = directChildByRuleIndex(p, OceanParser.RULE_type);
                                myParamTypes.add(pType != null ? pType.getText().trim() : "java.lang.Object");
                            }
                        }
                    }
                    List<String> thisArgTypes = new ArrayList<>();
                    for (PsiElement e : PsiTreeUtil.collectElements(stmt, elem -> isRule(elem, OceanParser.RULE_expression))) {
                        String t = e.getText().trim();
                        if (t.startsWith("this(") || t.equals("this")) {
                            for (PsiElement c : e.getChildren()) {
                                if (isRule(c, OceanParser.RULE_argumentList)) {
                                    for (PsiElement arg : c.getChildren()) {
                                        if (isRule(arg, OceanParser.RULE_argument) || isRule(arg, OceanParser.RULE_expression)) {
                                            String guessed = OceanTypeHelper.guessType(arg);
                                            thisArgTypes.add(guessed != null ? guessed : "java.lang.Object");
                                        }
                                    }
                                }
                            }
                            break;
                        }
                    }
                    boolean sameSignature = myParamTypes.size() == thisArgTypes.size();
                    if (sameSignature && !myParamTypes.isEmpty()) {
                        for (int pi = 0; pi < myParamTypes.size(); pi++) {
                            String pT = myParamTypes.get(pi);
                            String aT = thisArgTypes.get(pi);
                            if (!pT.equals(aT) && !pT.equals("java.lang.Object") && !aT.equals("java.lang.Object")
                                    && !OceanOverloadResolver.getSimpleTypeName(pT).equals(OceanOverloadResolver.getSimpleTypeName(aT))) {
                                sameSignature = false;
                                break;
                            }
                        }
                    }
                    if (sameSignature) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Recursive constructor invocation: 'this(...)'")
                                .range(stmt.getTextRange())
                                .create();
                    }
                }
            } else if (explicitCtorCalls > 0 && seenExplicitCalls == 0) {
                // Ocean Flexible Constructor Bodies (JEP 482): Statements before explicit super(...) or this(...) call
                // cannot reference 'this' or 'super', cannot invoke instance method
                for (PsiElement expr : PsiTreeUtil.collectElements(stmt, elem -> isRule(elem, OceanParser.RULE_expression))) {
                    String text = expr.getText().trim();
                    if (text.startsWith("this.") && text.contains("(")) {
                        String methodName = text.substring(5, text.indexOf('(')).trim();
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot invoke instance method '" + methodName + "' before supertype constructor has been called")
                                .range(expr.getTextRange())
                                .create();
                        break;
                    }
                    if ("this".equals(text) || "super".equals(text) || text.startsWith("this.") || text.startsWith("super.")) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Cannot reference 'this' or 'super' before supertype constructor has been called")
                                .range(expr.getTextRange())
                                .create();
                        break;
                    }
                }
            }
        }

        // Check superclass default no-arg constructor requirement [E0010]
        if (explicitCtorCalls == 0 && isRule(encClass, OceanParser.RULE_classDeclaration)) {
            String extendsName = extractExtendsName(encClass);
            if (extendsName != null && !extendsName.isEmpty() && !"Object".equals(extendsName) && !"java.lang.Object".equals(extendsName)) {
                checkSuperclassNoArgConstructor(constructor, encClass, extendsName, holder);
            }
        }

        // Check constructor parameters for variable/value type and slot limit
        for (PsiElement child : constructor.getChildren()) {
            if (isRule(child, OceanParser.RULE_parameterList)) {
                List<PsiElement> paramList = new ArrayList<>();
                for (PsiElement p : child.getChildren()) {
                    if (isRule(p, OceanParser.RULE_parameter)) {
                        paramList.add(p);
                    }
                }
                if (paramList.size() > 255) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Method parameter slots limit exceeded (" + paramList.size() + " > 255). JVM permits at most 255 parameter slots")
                            .range(child.getTextRange())
                            .create();
                }
                for (PsiElement p : paramList) {
                    if (hasTokenChild(p, OceanLexer.VARIABLE) || hasTokenChild(p, OceanLexer.VALUE)) {
                        PsiElement pId = directChildByRuleIndex(p, OceanParser.RULE_anyId);
                        String pName = pId != null ? pId.getText().trim() : p.getText().trim();
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot use 'variable' / 'value' type for method or constructor parameter: '" + pName + "'")
                                .range(p.getTextRange())
                                .create();
                    }
                }
            }
        }

        // @SafeVarargs annotation check for constructors [E0010]
        checkSafeVarargsAnnotation(constructor, holder, false, false, false, true);

        // Throws clause check for constructors [E0010]
        checkThrowsClause(constructor, holder);
    }

    private void checkThrowsClause(PsiElement methodOrConstructor, AnnotationHolder holder) {
        PsiElement throwsNode = directChildByRuleIndex(methodOrConstructor, OceanParser.RULE_typeList);
        if (throwsNode == null) return;

        Set<String> seenThrows = new HashSet<>();
        for (PsiElement child : throwsNode.getChildren()) {
            if (isRule(child, OceanParser.RULE_type)) {
                String typeText = child.getText().trim();
                String rawType = extractRawType(typeText);

                if (isPrimitiveType(rawType)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Exception type cannot be a primitive type: '" + typeText + "'")
                            .range(child.getTextRange())
                            .create();
                    continue;
                }

                if (typeText.contains("[")) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Exception type cannot be an array: '" + typeText + "'")
                            .range(child.getTextRange())
                            .create();
                    continue;
                }

                if (!seenThrows.add(rawType)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Duplicate exception type in throws clause: '" + rawType + "'")
                            .range(child.getTextRange())
                            .create();
                    continue;
                }

                // Check extends Throwable
                boolean isThrowable = false;
                if ("RuntimeException".equals(rawType) || "Error".equals(rawType) || rawType.endsWith("Exception") || rawType.endsWith("Error") || rawType.endsWith("Throwable")) {
                    isThrowable = true;
                } else {
                    PsiClass javaCls = resolveJavaClassInContext(methodOrConstructor.getProject(), methodOrConstructor, rawType);
                    if (javaCls != null) {
                        isThrowable = OceanScopeDiscovery.isSubclassOf(methodOrConstructor.getProject(), javaCls.getQualifiedName(), "java.lang.Throwable");
                    } else {
                        PsiElement oceanCls = findClassInSameFileOrProject(methodOrConstructor.getProject(), methodOrConstructor, rawType);
                        if (oceanCls != null) {
                            String superName = extractExtendsName(oceanCls);
                            isThrowable = superName != null && (superName.endsWith("Exception") || superName.endsWith("Throwable"));
                        }
                    }
                }

                if (!isThrowable) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Declared exception type '" + rawType + "' must extend java.lang.Throwable")
                            .range(child.getTextRange())
                            .create();
                }
            }
        }
    }

    private void checkAnnotationDeclaration(PsiElement annotationDecl, AnnotationHolder holder) {
        PsiElement nameId = directChildByRuleIndex(annotationDecl, OceanParser.RULE_anyId);
        String annoName = nameId != null ? nameId.getText().trim() : null;
        if (annoName != null) {
            Set<String> visited = new HashSet<>();
            Set<String> stack = new HashSet<>();
            if (hasAnnotationCycle(annotationDecl.getProject(), annotationDecl, annoName, visited, stack)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0004] Cyclic dependency involving annotation '" + annoName + "'.")
                        .range(nameId.getTextRange())
                        .create();
            }
        }

        for (PsiElement child : annotationDecl.getChildren()) {
            if (isRule(child, OceanParser.RULE_annotationMemberDeclaration)) {
                // 1. Cannot declare a body
                PsiElement block = directChildByRuleIndex(child, OceanParser.RULE_block);
                if (block != null) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Annotation methods cannot declare a body")
                            .range(block.getTextRange())
                            .create();
                }

                // 2. Modifiers: cannot be static, final, sync, private, or protected
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_modifier)) {
                        String modText = c.getText().trim();
                        if ("static".equals(modText) || "final".equals(modText) || "sync".equals(modText) || "lock".equals(modText) ||
                            "private".equals(modText) || "protected".equals(modText)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Annotation methods cannot be declared '" + modText + "'")
                                    .range(c.getTextRange())
                                    .create();
                        }
                    }
                }

                // 3. Return type restriction
                PsiElement typeNode = directChildByRuleIndex(child, OceanParser.RULE_type);
                if (typeNode != null) {
                    String rawType = extractRawType(typeNode.getText().trim());
                    if (!isValidAnnotationReturnType(rawType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Invalid return type for annotation member: Only primitive types, String, Class, Enum, or 1D arrays thereof are allowed")
                                .range(typeNode.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private boolean hasAnnotationCycle(Project project, PsiElement context, String currentAnnoName, Set<String> visited, Set<String> stack) {
        if (currentAnnoName == null || currentAnnoName.isEmpty()) return false;
        String raw = extractRawType(currentAnnoName);
        if (stack.contains(raw)) return true;
        if (visited.contains(raw)) return false;
        visited.add(raw);
        stack.add(raw);

        PsiElement annoDecl = findClassInSameFileOrProject(project, context, raw);
        if (isRule(annoDecl, OceanParser.RULE_annotationDeclaration)) {
            for (PsiElement child : annoDecl.getChildren()) {
                if (isRule(child, OceanParser.RULE_annotationMemberDeclaration)) {
                    PsiElement typeNode = directChildByRuleIndex(child, OceanParser.RULE_type);
                    if (typeNode != null) {
                        String memberType = extractRawType(typeNode.getText().trim());
                        if (memberType.endsWith("[]")) memberType = memberType.substring(0, memberType.length() - 2).trim();
                        PsiElement target = findClassInSameFileOrProject(project, context, memberType);
                        if (isRule(target, OceanParser.RULE_annotationDeclaration)) {
                            if (hasAnnotationCycle(project, context, memberType, visited, stack)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        stack.remove(raw);
        return false;
    }

    private boolean isValidAnnotationReturnType(String rawType) {
        if (rawType == null) return false;
        String t = rawType.replace("[]", "").trim();
        if (isPrimitiveType(t)) return true;
        return true;
    }

    private void checkVoidArguments(PsiElement expr, AnnotationHolder holder) {
        if (expr == null) return;
        for (PsiElement child : expr.getChildren()) {
            if (isRule(child, OceanParser.RULE_argumentList)) {
                for (PsiElement arg : child.getChildren()) {
                    if (isRule(arg, OceanParser.RULE_argument) || isRule(arg, OceanParser.RULE_expression)) {
                        String argType = OceanTypeHelper.guessType(arg);
                        if (isVoidType(argType)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'void' expression cannot be passed as a method argument")
                                    .range(arg.getTextRange())
                                    .create();
                        }
                    }
                }
            }
        }
    }

    private void checkStringConcatVoid(PsiElement expr, AnnotationHolder holder) {
        if (expr == null || !hasTokenChild(expr, OceanLexer.PLUS)) return;
        List<PsiElement> operands = new ArrayList<>();
        for (PsiElement child : expr.getChildren()) {
            if (isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary)) {
                operands.add(child);
            }
        }
        if (operands.size() < 2) return;
        PsiElement left = operands.get(0);
        PsiElement right = operands.get(1);
        String leftType = OceanTypeHelper.guessType(left);
        String rightType = OceanTypeHelper.guessType(right);

        boolean leftString = "java.lang.String".equals(leftType) || "String".equals(leftType) || left.getText().trim().startsWith("\"") || left.getText().trim().startsWith("$\"");
        boolean rightString = "java.lang.String".equals(rightType) || "String".equals(rightType) || right.getText().trim().startsWith("\"") || right.getText().trim().startsWith("$\"");

        if (leftString || rightString) {
            if (isVoidType(leftType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'void' expression cannot be used in string concatenation")
                        .range(left.getTextRange())
                        .create();
            }
            if (isVoidType(rightType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'void' expression cannot be used in string concatenation")
                        .range(right.getTextRange())
                        .create();
            }
        }
    }

    private void checkStringBinaryOperators(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;
        List<PsiElement> subExprs = new ArrayList<>();
        TokenIElementType opToken = null;
        for (PsiElement c : expr.getChildren()) {
            if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                subExprs.add(c);
            } else if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti) {
                int tt = ti.getANTLRTokenType();
                if (tt != OceanLexer.LPAREN && tt != OceanLexer.RPAREN && tt != OceanLexer.COMMA &&
                    tt != OceanLexer.SEMI && tt != OceanLexer.DOT && tt != OceanLexer.LBRACK && tt != OceanLexer.RBRACK) {
                    opToken = ti;
                }
            }
        }
        if (subExprs.size() >= 2 && opToken != null) {
            int tt = opToken.getANTLRTokenType();
            PsiElement lhs = subExprs.get(0);
            PsiElement rhs = subExprs.get(1);
            String lhsType = OceanTypeHelper.guessType(lhs);
            String rhsType = OceanTypeHelper.guessType(rhs);
            if (tt == OceanLexer.EQUALS || tt == OceanLexer.NOT_EQUALS) {
                checkEqualityAndIncomparableTypes(expr, tt, lhs, rhs, lhsType, rhsType, holder);
            } else if (tt != OceanLexer.PLUS) {
                boolean lhsStr = "String".equals(lhsType) || "java.lang.String".equals(lhsType) || lhs.getText().trim().startsWith("\"");
                boolean rhsStr = "String".equals(rhsType) || "java.lang.String".equals(rhsType) || rhs.getText().trim().startsWith("\"");
                if (lhsStr && rhsStr) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Invalid operator for String. Supported operators are ('==', '!=', '+').")
                            .range(expr.getTextRange())
                            .create();
                } else {
                    checkBinaryArithmeticAndComparison(expr, tt, lhs, rhs, lhsType, rhsType, holder);
                }
            }
        }
    }

    private void checkEqualityAndIncomparableTypes(PsiElement expr, int opToken, PsiElement lhs, PsiElement rhs, String lhsType, String rhsType, AnnotationHolder holder) {
        if (lhsType == null || rhsType == null) return;
        String opStr = opToken == OceanLexer.EQUALS ? "==" : "!=";
        boolean leftBool = "boolean".equals(lhsType) || "java.lang.Boolean".equals(lhsType);
        boolean rightBool = "boolean".equals(rhsType) || "java.lang.Boolean".equals(rhsType);
        boolean leftObj = "Object".equals(lhsType) || "java.lang.Object".equals(lhsType);
        boolean rightObj = "Object".equals(rhsType) || "java.lang.Object".equals(rhsType);

        if (leftBool || rightBool) {
            if (!((leftBool && rightObj) || (rightBool && leftObj)) && !(leftBool && rightBool)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Equality operator " + opStr + " cannot be applied to '" + toHumanReadableType(lhsType) + "', '" + toHumanReadableType(rhsType) + "'")
                        .range(expr.getTextRange())
                        .create();
            }
            return;
        }

        boolean leftPrim = isPrimitiveType(lhsType);
        boolean rightPrim = isPrimitiveType(rhsType);
        if (leftPrim && rightPrim) {
            boolean leftNum = isNumericType(lhsType);
            boolean rightNum = isNumericType(rhsType);
            if (!leftNum || !rightNum) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Equality operator " + opStr + " cannot be applied to '" + toHumanReadableType(lhsType) + "', '" + toHumanReadableType(rhsType) + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        } else if (leftPrim || rightPrim) {
            String primType = leftPrim ? lhsType : rhsType;
            String refType = leftPrim ? rhsType : lhsType;
            boolean validUnboxing = false;
            if (isNumericType(primType)) {
                if (isNumericType(refType) || "java.lang.Number".equals(refType) || "Number".equals(refType) || leftObj || rightObj) {
                    validUnboxing = true;
                }
            }
            if (!validUnboxing) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Equality operator " + opStr + " cannot be applied to '" + toHumanReadableType(lhsType) + "', '" + toHumanReadableType(rhsType) + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        } else {
            if (!leftObj && !rightObj) {
                Project project = expr.getProject();
                String rawLeft = extractRawType(lhsType);
                String rawRight = extractRawType(rhsType);
                boolean convertible = rawLeft.equals(rawRight)
                        || OceanScopeDiscovery.isSubclassOf(project, rawLeft, rawRight)
                        || OceanScopeDiscovery.isSubclassOf(project, rawRight, rawLeft);
                if (!convertible) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incomparable types: '" + toHumanReadableType(lhsType) + "' and '" + toHumanReadableType(rhsType) + "' cannot be compared with " + opStr)
                            .range(expr.getTextRange())
                            .create();
                }
            }
        }
    }

    private String toHumanReadableType(String type) {
        if (type == null) return "unknown";
        String t = type.trim();
        if (t.endsWith("?")) t = t.substring(0, t.length() - 1);
        if (t.contains("<")) {
            t = t.substring(0, t.indexOf('<')).trim();
        }
        if (t.startsWith("java.lang.")) {
            return t.substring("java.lang.".length());
        }
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < t.length() - 1) {
            return t.substring(lastDot + 1);
        }
        return t;
    }

    private void checkBinaryArithmeticAndComparison(PsiElement expr, int opToken, PsiElement lhs, PsiElement rhs, String lhsType, String rhsType, AnnotationHolder holder) {
        if (lhsType == null || rhsType == null) return;
        boolean leftNum = isNumericType(lhsType);
        boolean rightNum = isNumericType(rhsType);

        if (opToken == OceanLexer.STAR || opToken == OceanLexer.SLASH || opToken == OceanLexer.PERCENT || opToken == OceanLexer.MINUS) {
            String opStr = opToken == OceanLexer.STAR ? "*" : (opToken == OceanLexer.SLASH ? "/" : (opToken == OceanLexer.PERCENT ? "%" : "-"));
            if (!leftNum || !rightNum) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Arithmetic operator '" + opStr + "' can only be applied to numeric types: '" + toHumanReadableType(lhsType) + "' and '" + toHumanReadableType(rhsType) + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        } else if (opToken == OceanLexer.LT || opToken == OceanLexer.LESS_EQUAL || opToken == OceanLexer.GT || opToken == OceanLexer.GREATER_EQUAL) {
            String opStr = opToken == OceanLexer.LT ? "<" : (opToken == OceanLexer.LESS_EQUAL ? "<=" : (opToken == OceanLexer.GT ? ">" : ">="));
            if (!leftNum || !rightNum) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Comparison operator " + opStr + " cannot be applied to '" + toHumanReadableType(lhsType) + "', '" + toHumanReadableType(rhsType) + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        }
    }

    private void checkTernaryExpression(PsiElement expr, AnnotationHolder holder) {
        if (!hasTokenChild(expr, OceanLexer.QUESTION) || !hasTokenChild(expr, OceanLexer.COLON)) return;

        // Collect child expressions in ternary: cond ? trueBranch : falseBranch
        List<PsiElement> branches = new ArrayList<>();
        for (PsiElement child : expr.getChildren()) {
            if (isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary)) {
                branches.add(child);
            }
        }

        if (branches.size() >= 3) {
            PsiElement condBranch = branches.get(0);
            String condType = OceanTypeHelper.guessType(condBranch);
            if (isKnownNonBoolean(condType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Ternary conditional expression must be of type boolean: '" + condType + "'")
                        .range(condBranch.getTextRange())
                        .create();
            }

            PsiElement trueBranch = branches.get(1);
            PsiElement falseBranch = branches.get(2);

            String trueType = OceanTypeHelper.guessType(trueBranch);
            String falseType = OceanTypeHelper.guessType(falseBranch);

            if (isVoidType(trueType) || isVoidType(falseType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Ternary branches cannot return void")
                        .range(expr.getTextRange())
                        .create();
                return;
            }

            boolean trueNull = "null".equals(trueBranch.getText().trim());
            boolean falseNull = "null".equals(falseBranch.getText().trim());

            // In compiler IRSemanticAnalyzer, combining null with primitive in ternary is supported and yields a boxed nullable type (e.g. Integer? or int?)
            if (!trueNull && !falseNull && trueType != null && falseType != null) {
                boolean truePrim = isPrimitiveType(trueType);
                boolean falsePrim = isPrimitiveType(falseType);
                if (truePrim != falsePrim) {
                    boolean bothNum = isNumericType(trueType) && isNumericType(falseType);
                    boolean bothBool = "boolean".equalsIgnoreCase(trueType) && "boolean".equalsIgnoreCase(falseType);
                    if (!bothNum && !bothBool) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible branch types in ternary expression: '" + trueType + "' and '" + falseType + "'.")
                                .range(expr.getTextRange())
                                .create();
                    }
                } else if (truePrim) {
                    // Both primitive: e.g. int vs boolean
                    boolean bothNum = isNumericType(trueType) && isNumericType(falseType);
                    boolean bothBool = "boolean".equalsIgnoreCase(trueType) && "boolean".equalsIgnoreCase(falseType);
                    if (!bothNum && !bothBool) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible branch types in ternary expression: '" + trueType + "' and '" + falseType + "'.")
                                .range(expr.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private boolean isInsideStaticContext(PsiElement element) {
        PsiElement current = element.getParent();
        while (current != null) {
            // Anonymous class boundary: new Type(...) { ... }
            if (isRule(current, OceanParser.RULE_expression) && hasTokenChild(current, OceanLexer.NEW) && hasTokenChild(current, OceanLexer.LBRACE)) {
                return false;
            }
            if (isRule(current, OceanParser.RULE_methodDeclaration)) {
                if (isExtensionMethod(current)) {
                    return false;
                }
                return hasModifierToken(current, OceanLexer.STATIC) || isMainMethod(current);
            }
            if (isRule(current, OceanParser.RULE_constructorDeclaration)) {
                return false;
            }
            if (isRule(current, OceanParser.RULE_fieldDeclaration)) {
                if (hasModifierToken(current, OceanLexer.STATIC)) {
                    return true;
                }
            }
            if (isRule(current, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement c : current.getChildren()) {
                    if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.STATIC) {
                        return true;
                    }
                }
            }
            if (isRule(current, OceanParser.RULE_classDeclaration) || isRule(current, OceanParser.RULE_interfaceDeclaration) || isRule(current, OceanParser.RULE_enumDeclaration)) {
                break;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean isInsideConstructor(PsiElement element) {
        PsiElement cur = element != null ? element.getParent() : null;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_constructorDeclaration)) return true;
            if (isRule(cur, OceanParser.RULE_classDeclaration) || isRule(cur, OceanParser.RULE_interfaceDeclaration)) break;
            cur = cur.getParent();
        }
        return false;
    }

    private boolean isInsideLoop(PsiElement element) {
        PsiElement cur = element != null ? element.getParent() : null;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_forStatement) || isRule(cur, OceanParser.RULE_whileStatement) || isRule(cur, OceanParser.RULE_doWhileStatement)) {
                return true;
            }
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration) ||
                isRule(cur, OceanParser.RULE_classDeclaration) || isRule(cur, OceanParser.RULE_interfaceDeclaration)) {
                break;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private void checkStaticContextAccess(PsiElement element, PsiElement resolved, AnnotationHolder holder) {
        if (!isInsideStaticContext(element)) return;

        // If the element is a member receiver (followed by '.' or '?.'), e.g. LongDoubleStackSafetyTest.sLong,
        // it is qualifying another member access and is not an unqualified instance member access.
        PsiElement nextLeaf = PsiTreeUtil.nextVisibleLeaf(element);
        if (nextLeaf != null && (".".equals(nextLeaf.getText()) || "?.".equals(nextLeaf.getText()) || "::".equals(nextLeaf.getText()))) {
            return;
        }

        // Determine if resolved is a non-static member (instance method or instance field)
        boolean isNonStatic = false;
        boolean isMethod = false;

        if (resolved instanceof OceanMethodDeclaration methodDecl) {
            // Constructors are not instance methods accessed in static context
            if (isRule(methodDecl, OceanParser.RULE_constructorDeclaration)) {
                return;
            }
            if (!hasModifierToken(methodDecl, OceanLexer.STATIC)) {
                isNonStatic = true;
                isMethod = true;
            }
        } else if (resolved instanceof OceanVariableDeclarator declarator && declarator.isField()) {
            PsiElement parent = declarator.getParent();
            PsiElement enclosingClass = findEnclosingClass(declarator);
            boolean isInterface = isRule(enclosingClass, OceanParser.RULE_interfaceDeclaration);
            if (parent != null && !hasModifierToken(parent, OceanLexer.STATIC) && !isInterface) {
                isNonStatic = true;
            }
        } else if (resolved instanceof PsiMethod psiMethod) {
            if (!psiMethod.hasModifierProperty(PsiModifier.STATIC)) {
                isNonStatic = true;
                isMethod = true;
            }
        } else if (resolved instanceof PsiField psiField) {
            PsiClass containingClass = psiField.getContainingClass();
            boolean isInterface = containingClass != null && containingClass.isInterface();
            if (!psiField.hasModifierProperty(PsiModifier.STATIC) && !isInterface) {
                isNonStatic = true;
            }
        }

        if (!isNonStatic) return;

        // Check if element is accessed through an explicit instance receiver: e.g. `obj.foo()`
        // If element is immediately preceded by a dot '.', check what comes before the dot.
        PsiElement prevLeaf = PsiTreeUtil.prevVisibleLeaf(element);
        if (prevLeaf != null && (".".equals(prevLeaf.getText()) || "?.".equals(prevLeaf.getText()))) {
            PsiElement receiver = PsiTreeUtil.prevVisibleLeaf(prevLeaf);
            if (receiver != null) {
                String recText = receiver.getText().trim();
                // If receiver is "this" or "super", it's invalid in static context
                if ("this".equals(recText) || "super".equals(recText)) {
                    String text = element.getText().trim();
                    String msg = isMethod
                            ? "[E0008] Non-static method '" + text + "' cannot be referenced from a static context"
                            : "[E0008] Non-static field '" + text + "' cannot be referenced from a static context";
                    holder.newAnnotation(HighlightSeverity.ERROR, msg)
                            .range(element.getTextRange())
                            .create();
                    return;
                }
                // Check if receiver resolves to a class (e.g. MyClass.instanceField or MyClass.instanceMethod())
                PsiReference recRef = receiver.getReference();
                PsiElement recResolved = recRef != null ? recRef.resolve() : null;
                if (recResolved == null) {
                    recResolved = OceanScopeDiscovery.resolveJavaClass(element.getProject(), recText, element);
                }
                if (recResolved instanceof PsiClass || recResolved instanceof OceanClassDeclaration) {
                    String text = element.getText().trim();
                    String msg = isMethod
                            ? "[E0008] Non-static method '" + text + "' cannot be referenced from a static context"
                            : "[E0008] Non-static field '" + text + "' cannot be referenced from a static context";
                    holder.newAnnotation(HighlightSeverity.ERROR, msg)
                            .range(element.getTextRange())
                            .create();
                    return;
                }
                // Receiver is an instance variable or expression (e.g. p.foo() or new Foo().foo()) -> ALLOWED!
                return;
            }
        }

        // Unqualified call/access: e.g. foo() or x = 10 inside static method
        String text = element.getText().trim();
        String msg = isMethod
                ? "[E0008] Non-static method '" + text + "' cannot be referenced from a static context"
                : "[E0008] Non-static field '" + text + "' cannot be referenced from a static context";
        holder.newAnnotation(HighlightSeverity.ERROR, msg)
                .range(element.getTextRange())
                .create();
    }

    private PsiElement findEnclosingClass(PsiElement element) {
        PsiElement cur = element != null ? element.getParent() : null;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_classDeclaration) ||
                isRule(cur, OceanParser.RULE_interfaceDeclaration) ||
                isRule(cur, OceanParser.RULE_enumDeclaration)) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private boolean isTopLevelType(PsiElement element) {
        PsiElement cur = element != null ? element.getParent() : null;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_classDeclaration) ||
                isRule(cur, OceanParser.RULE_interfaceDeclaration) ||
                isRule(cur, OceanParser.RULE_enumDeclaration) ||
                isRule(cur, OceanParser.RULE_methodDeclaration) ||
                isRule(cur, OceanParser.RULE_constructorDeclaration) ||
                isRule(cur, OceanParser.RULE_block)) {
                return false;
            }
            cur = cur.getParent();
        }
        return true;
    }

    private void checkAccessControl(PsiElement element, PsiElement resolved, AnnotationHolder holder) {
        if (resolved == null) return;

        boolean isPrivate = false;
        boolean isProtected = false;
        boolean isPackagePrivate = false;
        String declaringClassName = null;
        String memberName = element.getText().trim();
        PsiElement declaringClass = null;

        if (resolved instanceof PsiModifierListOwner mlo) {
            if (mlo.hasModifierProperty(PsiModifier.PRIVATE)) {
                isPrivate = true;
            } else if (mlo.hasModifierProperty(PsiModifier.PROTECTED)) {
                isProtected = true;
            } else if (!mlo.hasModifierProperty(PsiModifier.PUBLIC)) {
                isPackagePrivate = true;
            }

            if (isPrivate || isProtected || isPackagePrivate) {
                if (mlo instanceof PsiMember member && member.getContainingClass() != null) {
                    declaringClassName = member.getContainingClass().getName();
                    if (member.getContainingClass().isInterface() && !isPrivate) {
                        isProtected = false;
                        isPackagePrivate = false;
                    }
                } else if (mlo instanceof PsiClass cls) {
                    declaringClassName = cls.getName();
                    if (cls.isInterface() && !isPrivate) {
                        isProtected = false;
                        isPackagePrivate = false;
                    }
                }
            }
        } else {
            // Ocean element
            PsiElement p = resolved;
            while (p != null && !(p instanceof PsiFile)) {
                if (isRule(p, OceanParser.RULE_fieldDeclaration) ||
                    isRule(p, OceanParser.RULE_methodDeclaration) ||
                    isRule(p, OceanParser.RULE_constructorDeclaration) ||
                    isRule(p, OceanParser.RULE_classDeclaration)) {
                    if (hasModifierToken(p, OceanLexer.PRIVATE)) {
                        isPrivate = true;
                    } else if (hasModifierToken(p, OceanLexer.PROTECTED)) {
                        isProtected = true;
                    } else if (!hasModifierToken(p, OceanLexer.PUBLIC)) {
                        isPackagePrivate = true;
                    }
                    break;
                }
                p = p.getParent();
            }

            if (isPrivate || isProtected || isPackagePrivate) {
                declaringClass = findEnclosingClass(resolved);
                if (declaringClass != null) {
                    declaringClassName = findNameForElement(declaringClass);
                    // Members of interfaces are implicitly public unless explicitly marked private
                    if (isRule(declaringClass, OceanParser.RULE_interfaceDeclaration)) {
                        if (!isPrivate) {
                            isProtected = false;
                            isPackagePrivate = false;
                        }
                    }
                }
            }
        }

        if ((isPrivate || isProtected || isPackagePrivate) && declaringClassName != null) {
            PsiElement callingClass = findEnclosingClass(element);
            boolean allowed = false;

            if (isPrivate) {
                if (declaringClass != null && callingClass != null) {
                    if (declaringClass == callingClass || PsiTreeUtil.isAncestor(declaringClass, element, false) || PsiTreeUtil.isAncestor(callingClass, resolved, false)) {
                        allowed = true;
                    }
                }
            } else if (isProtected) {
                // Allowed if same class, descendant class (inheritance), or same package
                if (callingClass != null) {
                    if (declaringClass != null && (declaringClass == callingClass || PsiTreeUtil.isAncestor(declaringClass, element, false) || PsiTreeUtil.isAncestor(callingClass, resolved, false))) {
                        allowed = true;
                    } else if (OceanScopeDiscovery.isSubclassOf(element.getProject(), findNameForElement(callingClass), declaringClassName)) {
                        allowed = true;
                    }
                }
                if (!allowed) {
                    // Check same package
                    PsiFile callerFile = element.getContainingFile();
                    PsiFile targetFile = resolved.getContainingFile();
                    if (callerFile != null && targetFile != null) {
                        OceanIdReference.OceanFileInfo cInfo = OceanIdReference.getFileInfo(callerFile);
                        String targetPkg = (targetFile instanceof PsiJavaFile pjf) ? pjf.getPackageName() : OceanIdReference.getFileInfo(targetFile).packageName;
                        if (cInfo != null && cInfo.packageName.equals(targetPkg)) {
                            allowed = true;
                        }
                    }
                }
            } else {
                // Allowed only if in the same package
                PsiFile callerFile = element.getContainingFile();
                PsiFile targetFile = resolved.getContainingFile();
                if (callerFile != null && targetFile != null) {
                    OceanIdReference.OceanFileInfo cInfo = OceanIdReference.getFileInfo(callerFile);
                    String targetPkg = (targetFile instanceof PsiJavaFile pjf) ? pjf.getPackageName() : (OceanIdReference.getFileInfo(targetFile) != null ? OceanIdReference.getFileInfo(targetFile).packageName : null);
                    if (cInfo != null && cInfo.packageName.equals(targetPkg)) {
                        allowed = true;
                    }
                }
            }

            if (!allowed && hasAccessibleOverloadForCall(element, resolved, memberName)) {
                allowed = true;
            }

            if (!allowed) {
                String accessType = isPrivate ? "private" : (isProtected ? "protected" : "package-private");
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0014] Member '" + memberName + "' has " + accessType + " access in '" + declaringClassName + "'")
                        .range(element.getTextRange())
                        .create();
            }
        }
    }

    private boolean hasAccessibleOverloadForCall(PsiElement element, PsiElement resolved, String memberName) {
        if (resolved == null || element == null || memberName == null) return false;

        Project project = element.getProject();
        OceanOverloadResolver.CallInfo callInfo = OceanOverloadResolver.findCallInfo(element);

        // 1. Java class methods
        if (resolved instanceof PsiMethod method) {
            PsiClass psiClass = method.getContainingClass();
            if (psiClass != null) {
                PsiMethod[] allMethods = psiClass.findMethodsByName(memberName, true);
                for (PsiMethod m : allMethods) {
                    if (m == resolved) continue;
                    if (!m.hasModifierProperty(PsiModifier.PRIVATE)) {
                        if (!callInfo.isCall) {
                            return true;
                        }
                        OceanOverloadResolver.MethodCandidate mc = OceanOverloadResolver.extractCandidate(m, project);
                        if (mc != null) {
                            int score = OceanOverloadResolver.scoreCandidate(mc, callInfo.argumentTypes, project);
                            if (score >= 0 || mc.paramTypes().size() == callInfo.argumentTypes.size() || mc.isVarArgs()) {
                                return true;
                            }
                        }
                    }
                }
            }
        } else {
            // 2. Ocean class methods
            PsiElement encClass = findEnclosingClass(resolved);
            if (encClass != null) {
                List<PsiElement> members = new ArrayList<>();
                OceanScopeDiscovery.resolveMemberChain(findNameForElement(encClass), memberName, members, project, new HashSet<>(), element);
                for (PsiElement m : members) {
                    if (m == resolved) continue;
                    if (!OceanOverloadResolver.isPrivateMember(m)) {
                        if (!callInfo.isCall) {
                            return true;
                        }
                        OceanOverloadResolver.MethodCandidate mc = OceanOverloadResolver.extractCandidate(m, project);
                        if (mc != null) {
                            int score = OceanOverloadResolver.scoreCandidate(mc, callInfo.argumentTypes, project);
                            if (score >= 0 || mc.paramTypes().size() == callInfo.argumentTypes.size() || mc.isVarArgs()) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private List<PsiElement> collectTopLevelReturnStatements(PsiElement root) {
        List<PsiElement> results = new ArrayList<>();
        collectTopLevelStatementsRecursive(root, OceanParser.RULE_returnStatement, results);
        return results;
    }

    private List<PsiElement> collectTopLevelThrowStatements(PsiElement root) {
        List<PsiElement> results = new ArrayList<>();
        collectTopLevelStatementsRecursive(root, OceanParser.RULE_statement, results);
        // Filter for throw statements
        List<PsiElement> throwStmts = new ArrayList<>();
        for (PsiElement s : results) {
            if (hasTokenChild(s, OceanLexer.THROW)) {
                throwStmts.add(s);
            }
        }
        return throwStmts;
    }

    private void collectTopLevelStatementsRecursive(PsiElement element, int targetRuleIdx, List<PsiElement> results) {
        if (element == null) return;
        for (PsiElement child : element.getChildren()) {
            // Do NOT descend into nested lambdas, local methods, or local classes
            if (isRule(child, OceanParser.RULE_classDeclaration) ||
                isRule(child, OceanParser.RULE_methodDeclaration) ||
                isRule(child, OceanParser.RULE_constructorDeclaration)) {
                continue;
            }
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_expression) {
                // If this is a LambdaExpr: LPAREN ... RPAREN ARROW (block | expression)
                if (hasTokenChild(child, OceanLexer.ARROW)) {
                    continue;
                }
            }

            if (isRule(child, targetRuleIdx)) {
                results.add(child);
            }
            collectTopLevelStatementsRecursive(child, targetRuleIdx, results);
        }
    }

    private boolean isFinallyBlock(PsiElement block) {
        if (block == null) return false;
        PsiElement parent = block.getParent();
        if (!isRule(parent, OceanParser.RULE_tryStatement)) return false;
        boolean foundFinally = false;
        for (PsiElement child : parent.getChildren()) {
            if ("finally".equals(child.getText().trim())) {
                foundFinally = true;
            } else if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti) {
                if (ti.getANTLRTokenType() == OceanLexer.FINALLY) {
                    foundFinally = true;
                }
            }
            if (child == block && foundFinally) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnbrokenInfiniteLoop(PsiElement block) {
        if (block == null) return false;
        for (PsiElement child : block.getChildren()) {
            if (isRule(child, OceanParser.RULE_statement)) {
                for (PsiElement stmtChild : child.getChildren()) {
                    if (isRule(stmtChild, OceanParser.RULE_whileStatement)) {
                        PsiElement expr = directChildByRuleIndex(stmtChild, OceanParser.RULE_expression);
                        if (expr != null && "true".equals(expr.getText().trim())) {
                            PsiElement body = directChildByRuleIndex(stmtChild, OceanParser.RULE_statement);
                            if (body != null && !loopCanBreak(body, null)) {
                                return true;
                            }
                        }
                    } else if (isRule(stmtChild, OceanParser.RULE_doWhileStatement)) {
                        PsiElement expr = directChildByRuleIndex(stmtChild, OceanParser.RULE_expression);
                        if (expr != null && "true".equals(expr.getText().trim())) {
                            PsiElement body = directChildByRuleIndex(stmtChild, OceanParser.RULE_statement);
                            if (body != null && !loopCanBreak(body, null)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean loopCanBreak(PsiElement body, String targetLabel) {
        if (body == null) return false;
        final boolean[] canBreak = new boolean[]{false};
        body.accept(new PsiRecursiveElementWalkingVisitor() {
            private int nestedLoopDepth = 0;

            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (canBreak[0]) return;

                // Stop boundary: lambdas, local methods, inner classes
                if (isRule(element, OceanParser.RULE_classDeclaration) ||
                    isRule(element, OceanParser.RULE_methodDeclaration) ||
                    isRule(element, OceanParser.RULE_constructorDeclaration)) {
                    return;
                }
                if (isRule(element, OceanParser.RULE_expression) && hasTokenChild(element, OceanLexer.ARROW)) {
                    return;
                }

                // Check for stop statement (break)
                if (element.getNode() != null && element.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.STOP_KW) {
                    PsiElement stmtParent = element.getParent();
                    PsiElement labelId = (stmtParent != null) ? directChildByRuleIndex(stmtParent, OceanParser.RULE_anyId) : null;
                    String stopLabel = labelId != null ? labelId.getText().trim() : null;

                    if (targetLabel != null && targetLabel.equals(stopLabel)) {
                        canBreak[0] = true;
                        return;
                    } else if (targetLabel == null && stopLabel == null && nestedLoopDepth == 0) {
                        canBreak[0] = true;
                        return;
                    }
                }

                boolean isLoopOrSwitch = isRule(element, OceanParser.RULE_whileStatement) ||
                                         isRule(element, OceanParser.RULE_doWhileStatement) ||
                                         isRule(element, OceanParser.RULE_forStatement) ||
                                         isRule(element, OceanParser.RULE_switchStatement);

                if (isLoopOrSwitch && element != body) {
                    nestedLoopDepth++;
                    try {
                        super.visitElement(element);
                    } finally {
                        nestedLoopDepth--;
                    }
                    return;
                }

                super.visitElement(element);
            }
        });
        return canBreak[0];
    }

    private void checkStopStatement(PsiElement stopToken, AnnotationHolder holder) {
        PsiElement cur = stopToken.getParent();
        boolean insideLoopOrSwitch = false;
        boolean insideFinally = false;
        boolean insideLambda = false;

        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) ||
                isRule(cur, OceanParser.RULE_constructorDeclaration) ||
                isRule(cur, OceanParser.RULE_classDeclaration)) {
                break;
            }
            if (isRule(cur, OceanParser.RULE_expression) && hasTokenChild(cur, OceanLexer.ARROW)) {
                insideLambda = true;
                break;
            }
            if (isFinallyBlock(cur)) {
                insideFinally = true;
                break;
            }
            if (isRule(cur, OceanParser.RULE_forStatement) ||
                isRule(cur, OceanParser.RULE_whileStatement) ||
                isRule(cur, OceanParser.RULE_doWhileStatement)) {
                insideLoopOrSwitch = true;
            }
            if (isRule(cur, OceanParser.RULE_switchStatement)) {
                insideLoopOrSwitch = true;
            }
            if (isRule(cur, OceanParser.RULE_expression) && hasTokenChild(cur, OceanLexer.SWITCH)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Cannot 'stop' (break) out of a switch expression; use 'result' to yield a value.")
                        .range(stopToken.getTextRange())
                        .create();
                return;
            }
            cur = cur.getParent();
        }

        PsiElement stopStmt = stopToken.getParent();
        PsiElement stopLabelId = stopStmt != null ? directChildByRuleIndex(stopStmt, OceanParser.RULE_anyId) : null;
        if (stopLabelId != null) {
            String targetLabel = stopLabelId.getText().trim();
            PsiElement target = findEnclosingLabeledStatement(stopStmt, targetLabel);
            if (target == null) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Undefined label: '" + targetLabel + "'")
                        .range(stopLabelId.getTextRange())
                        .create();
                return;
            }
            if (insideFinally) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'stop' cannot be used inside a 'finally' block")
                        .range(stopToken.getTextRange())
                        .create();
                return;
            }
            if (insideLambda) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'stop' (break) statement cannot jump outside lambda boundary")
                        .range(stopToken.getTextRange())
                        .create();
                return;
            }
            // Labeled stop target is valid
            return;
        }

        if (insideFinally) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'stop' cannot be used inside a 'finally' block")
                    .range(stopToken.getTextRange())
                    .create();
        } else if (insideLambda) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'stop' (break) statement cannot jump outside lambda boundary")
                    .range(stopToken.getTextRange())
                    .create();
        } else if (!insideLoopOrSwitch) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'stop' can only be used inside a loop or switch statement")
                    .range(stopToken.getTextRange())
                    .create();
        }
    }

    private void checkSkipStatement(PsiElement skipToken, AnnotationHolder holder) {
        PsiElement cur = skipToken.getParent();
        boolean insideLoop = false;
        boolean insideFinally = false;
        boolean insideLambda = false;

        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) ||
                isRule(cur, OceanParser.RULE_constructorDeclaration) ||
                isRule(cur, OceanParser.RULE_classDeclaration)) {
                break;
            }
            if (isRule(cur, OceanParser.RULE_expression) && hasTokenChild(cur, OceanLexer.ARROW)) {
                insideLambda = true;
                break;
            }
            if (isFinallyBlock(cur)) {
                insideFinally = true;
                break;
            }
            if (isRule(cur, OceanParser.RULE_forStatement) ||
                isRule(cur, OceanParser.RULE_whileStatement) ||
                isRule(cur, OceanParser.RULE_doWhileStatement)) {
                insideLoop = true;
            }
            cur = cur.getParent();
        }

        PsiElement skipStmt = skipToken.getParent();
        PsiElement skipLabelId = skipStmt != null ? directChildByRuleIndex(skipStmt, OceanParser.RULE_anyId) : null;
        if (skipLabelId != null) {
            String targetLabel = skipLabelId.getText().trim();
            PsiElement target = findEnclosingLabeledStatement(skipStmt, targetLabel);
            if (target == null) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Undefined label: '" + targetLabel + "'")
                        .range(skipLabelId.getTextRange())
                        .create();
                return;
            }
            boolean isLoop = isTargetLoop(target);
            if (!isLoop) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'skip' (continue) target must be a loop, '" + targetLabel + "' is not a loop.")
                        .range(skipLabelId.getTextRange())
                        .create();
                return;
            }
            if (insideFinally) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'skip' cannot be used inside a 'finally' block")
                        .range(skipToken.getTextRange())
                        .create();
                return;
            }
            if (insideLambda) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'skip' (continue) statement cannot jump outside lambda boundary")
                        .range(skipToken.getTextRange())
                        .create();
                return;
            }
            // Labeled skip target is valid
            return;
        }

        if (insideFinally) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'skip' cannot be used inside a 'finally' block")
                    .range(skipToken.getTextRange())
                    .create();
        } else if (insideLambda) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'skip' (continue) statement cannot jump outside lambda boundary")
                    .range(skipToken.getTextRange())
                    .create();
        } else if (!insideLoop) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'skip' can only be used inside a loop")
                    .range(skipToken.getTextRange())
                    .create();
        }
    }

    private boolean isTargetLoop(PsiElement target) {
        if (target == null) return false;
        if (isRule(target, OceanParser.RULE_forStatement) ||
            isRule(target, OceanParser.RULE_whileStatement) ||
            isRule(target, OceanParser.RULE_doWhileStatement) ||
            hasTokenChild(target, OceanLexer.FOR) ||
            hasTokenChild(target, OceanLexer.WHILE)) {
            return true;
        }
        for (PsiElement child : target.getChildren()) {
            if (isRule(child, OceanParser.RULE_forStatement) ||
                isRule(child, OceanParser.RULE_whileStatement) ||
                isRule(child, OceanParser.RULE_doWhileStatement)) {
                return true;
            }
            if (isRule(child, OceanParser.RULE_statement)) {
                if (isTargetLoop(child)) return true;
            }
        }
        return false;
    }

    private PsiElement findEnclosingLabeledStatement(PsiElement from, String labelName) {
        PsiElement cur = from.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_classDeclaration)) break;
            PsiElement anyId = directChildByRuleIndex(cur, OceanParser.RULE_anyId);
            if (anyId != null && labelName.equals(anyId.getText().trim()) && hasTokenChild(cur, OceanLexer.COLON)) {
                return cur;
            }
            for (PsiElement child : cur.getChildren()) {
                if (isRule(child, OceanParser.RULE_statement) || child.getNode() != null) {
                    PsiElement childId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                    if (childId != null && labelName.equals(childId.getText().trim()) && hasTokenChild(child, OceanLexer.COLON)) {
                        return child;
                    }
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private void checkResultStatement(PsiElement resultToken, AnnotationHolder holder) {
        PsiElement cur = resultToken.getParent();
        boolean insideSwitchExpr = false;
        boolean insideLambda = false;

        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) ||
                isRule(cur, OceanParser.RULE_constructorDeclaration) ||
                isRule(cur, OceanParser.RULE_classDeclaration)) {
                break;
            }
            if (isRule(cur, OceanParser.RULE_expression) && hasTokenChild(cur, OceanLexer.ARROW)) {
                insideLambda = true;
                break;
            }
            if (isRule(cur, OceanParser.RULE_switchStatement) || (isRule(cur, OceanParser.RULE_expression) && hasTokenChild(cur, OceanLexer.SWITCH))) {
                insideSwitchExpr = true;
                break;
            }
            cur = cur.getParent();
        }

        if (insideLambda) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'result' statement cannot jump outside lambda boundary")
                    .range(resultToken.getTextRange())
                    .create();
        } else if (!insideSwitchExpr) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'result' statement can only be used within a switch expression block")
                    .range(resultToken.getTextRange())
                    .create();
        } else {
            // Check if result statement yields void [E0003]
            PsiElement stmt = resultToken.getParent();
            if (stmt != null) {
                PsiElement expr = directChildByRuleIndex(stmt, OceanParser.RULE_expression);
                if (expr != null) {
                    String yieldType = OceanTypeHelper.guessType(expr);
                    if (isVoidType(yieldType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Switch expression branches cannot yield void.")
                                .range(expr.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private void checkReturnStatement(PsiElement returnStmt, AnnotationHolder holder) {
        PsiElement cur = returnStmt.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            // Stop at method, constructor, or lambda boundaries
            if (isRule(cur, OceanParser.RULE_methodDeclaration) ||
                isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                break;
            }
            if (isRule(cur, OceanParser.RULE_expression) && hasTokenChild(cur, OceanLexer.ARROW)) {
                // Lambda boundary
                break;
            }

            if (isFinallyBlock(cur)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] 'return' statement cannot be used inside finally block (abrupt completion)")
                        .range(returnStmt.getTextRange())
                        .create();
                return;
            }

            // Check if cur is a block directly inside a memberDeclaration in a class or enum
            if (isRule(cur, OceanParser.RULE_block)) {
                PsiElement parent = cur.getParent();
                if (isRule(parent, OceanParser.RULE_memberDeclaration)) {
                    boolean isStatic = hasModifierToken(parent, OceanLexer.STATIC) || hasTokenChild(parent, OceanLexer.STATIC);
                    if (isStatic) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] 'return' statement cannot be used inside static initializer block")
                                .range(returnStmt.getTextRange())
                                .create();
                    } else {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] 'return' statement cannot be used inside instance initializer block")
                                .range(returnStmt.getTextRange())
                                .create();
                    }
                    return;
                }
            }

            cur = cur.getParent();
        }

        // Check if return has an expression in a void method or constructor
        PsiElement expr = directChildByRuleIndex(returnStmt, OceanParser.RULE_expression);
        if (expr != null) {
            if (cur != null) {
                if (isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] Cannot return a value from constructor")
                            .range(expr.getTextRange())
                            .create();
                } else if (isRule(cur, OceanParser.RULE_methodDeclaration)) {
                    if (OceanScopeDiscovery.isVoidMethod(cur)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0005] Cannot return a value from a method with void return type")
                                .range(expr.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private boolean isExtensionMethod(PsiElement method) {
        if (method == null) return false;
        if (OceanScopeDiscovery.getExtensionReceiverTypeNode(method) != null) return true;
        String text = method.getText();
        int funcIdx = text.indexOf("function");
        if (funcIdx > 0) {
            int dotIdx = text.lastIndexOf('.', funcIdx);
            return dotIdx >= 0;
        }
        return false;
    }

    private boolean hasModifierToken(PsiElement element, int tokenType) {
        if (element == null) return false;
        for (PsiElement child : element.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == tokenType) {
                return true;
            }
            if (isRule(child, OceanParser.RULE_modifier)) {
                ASTNode node = child.getNode();
                if (node != null) {
                    for (ASTNode sub = node.getFirstChildNode(); sub != null; sub = sub.getTreeNext()) {
                        if (sub.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == tokenType) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasTokenChild(PsiElement element, int tokenType) {
        return findTokenChild(element, tokenType) != null;
    }

    private PsiElement findTokenChild(PsiElement element, int tokenType) {
        if (element == null) return null;
        if (element.getNode() != null) {
            for (ASTNode sub = element.getNode().getFirstChildNode(); sub != null; sub = sub.getTreeNext()) {
                if (sub.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == tokenType) {
                    return sub.getPsi();
                }
            }
        }
        return null;
    }

    private void checkUnaryOperatorExpression(PsiElement expr, AnnotationHolder holder) {
        // Unary NOT: !expr
        if (expr.getChildren().length >= 2 && expr.getFirstChild() != null && "!".equals(expr.getFirstChild().getText().trim())) {
            PsiElement inner = expr.getChildren()[1];
            if (isRule(inner, OceanParser.RULE_expression) || isRule(inner, OceanParser.RULE_primary)) {
                String innerType = OceanTypeHelper.guessType(inner);
                if (isKnownNonBoolean(innerType)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Logical NOT operator (!) can only be applied to boolean expressions. Found: " + innerType)
                            .range(expr.getTextRange())
                            .create();
                }
            }
        }

        // Bitwise NOT: ~expr
        if (expr.getChildren().length >= 2 && expr.getFirstChild() != null && "~".equals(expr.getFirstChild().getText().trim())) {
            PsiElement inner = expr.getChildren()[1];
            if (isRule(inner, OceanParser.RULE_expression) || isRule(inner, OceanParser.RULE_primary)) {
                String innerType = OceanTypeHelper.guessType(inner);
                if (innerType != null && !isIntegerType(innerType)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Bitwise NOT (~) operator is only valid for integer types: '" + innerType + "'")
                            .range(expr.getTextRange())
                            .create();
                }
            }
        }

        // Unary minus: -expr (must not be --expr or binary a - b)
        String trimmedText = expr.getText().trim();
        if (trimmedText.startsWith("-") && !trimmedText.startsWith("--") && expr.getChildren().length >= 2 && expr.getFirstChild() != null && "-".equals(expr.getFirstChild().getText().trim())) {
            // Ensure first child is '-' and followed by a single expression (not binary expression)
            long exprChildCount = 0;
            PsiElement inner = null;
            for (PsiElement c : expr.getChildren()) {
                if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                    exprChildCount++;
                    if (inner == null) inner = c;
                }
            }
            if (exprChildCount == 1) {
                String innerType = OceanTypeHelper.guessType(inner);
                if (innerType != null && !isNumericType(innerType)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Unary minus (-) operator is only valid for numeric types: '" + innerType + "'")
                            .range(expr.getTextRange())
                            .create();
                }
            }
        }

        // Unary ++ or --: expr++ / ++expr / expr-- / --expr
        String text = expr.getText().trim();
        if (text.startsWith("++") || text.endsWith("++") || text.startsWith("--") || text.endsWith("--")) {
            for (PsiElement c : expr.getChildren()) {
                if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                    String opText = c.getText().trim();
                    if ("this".equals(opText)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Cannot increment or decrement 'this'")
                                .range(expr.getTextRange())
                                .create();
                        return;
                    } else if ("super".equals(opText)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Cannot increment or decrement 'super'")
                                .range(expr.getTextRange())
                                .create();
                        return;
                    }

                    String opType = OceanTypeHelper.guessType(c);
                    if (("String".equals(opType) || "boolean".equals(opType) || "Boolean".equals(opType))) {
                        String op = text.contains("++") ? "++" : "--";
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Operator '" + op + "' cannot be applied to '" + opType + "'")
                                .range(expr.getTextRange())
                                .create();
                        break;
                    }
                    // Try-with-resources resource variable cannot be mutated via ++/-- [E0002]
                    for (PsiElement id : PsiTreeUtil.collectElements(c, e -> e instanceof OceanIdReference)) {
                        if (id instanceof OceanIdReference oir) {
                            PsiElement res = oir.resolve();
                            if (isTryWithResourcesResource(res)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Resource '" + id.getText().trim() + "' in try-with-resources statement must be final or effectively final and cannot be reassigned")
                                        .range(id.getTextRange())
                                        .create();
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkInstanceOfExpression(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;
        boolean hasInstanceOf = false;
        PsiElement patternElem = null;
        for (PsiElement child : expr.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.INSTANCEOF) {
                hasInstanceOf = true;
            } else if (hasInstanceOf && isRule(child, OceanParser.RULE_instanceofPattern)) {
                patternElem = child;
                break;
            }
        }
        if (!hasInstanceOf || patternElem == null) return;

        PsiElement typeElem = directChildByRuleIndex(patternElem, OceanParser.RULE_type);
        if (typeElem != null) {
            String typeText = typeElem.getText().trim();
            if (typeText.contains("<") && typeText.contains(">")) {
                int open = typeText.indexOf('<');
                int close = typeText.lastIndexOf('>');
                if (open < close) {
                    String typeArgs = typeText.substring(open + 1, close).trim();
                    if (!"?".equals(typeArgs) && !"*".equals(typeArgs) && !typeArgs.isEmpty()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Cannot use non-reifiable type '" + typeText + "' in instanceof. Only raw type or unbounded wildcard ('<?>') is allowed due to type erasure")
                                .range(typeElem.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private void checkArrayIndexExpression(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;
        PsiElement lbrack = findTokenChild(expr, OceanLexer.LBRACK);
        PsiElement rbrack = findTokenChild(expr, OceanLexer.RBRACK);
        if (lbrack == null || rbrack == null) return;
        // Exclude 'new' creator expressions (handled in array creation)
        if (hasTokenChild(expr, OceanLexer.NEW)) return;

        List<PsiElement> children = new ArrayList<>();
        for (PsiElement c : expr.getChildren()) {
            if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                children.add(c);
            }
        }
        if (children.size() < 2) return;

        PsiElement targetObj = children.get(0);
        PsiElement indexExpr = children.get(1);

        // Ensure indexExpr is inside [ ]
        if (indexExpr.getTextOffset() < lbrack.getTextOffset() || indexExpr.getTextOffset() > rbrack.getTextOffset()) {
            return;
        }

        // 1. Array index access not supported on primitive types [E0003]
        String targetType = OceanTypeHelper.guessType(targetObj);
        if (targetType != null && !targetType.contains("[]") && isPrimitiveType(targetType)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Array index access is not supported on type '" + targetType + "'. Method 'get(int)' must be defined.")
                    .range(expr.getTextRange())
                    .create();
            return;
        }

        // 2. Index must be of type int [E0003]
        String indexType = OceanTypeHelper.guessType(indexExpr);
        if (indexType != null) {
            boolean isInt = "int".equalsIgnoreCase(indexType) || "Integer".equals(indexType) ||
                    "short".equalsIgnoreCase(indexType) || "byte".equalsIgnoreCase(indexType) || "char".equalsIgnoreCase(indexType);
            if (!isInt && (isKnownNonBoolean(indexType) || "boolean".equalsIgnoreCase(indexType) || "String".equalsIgnoreCase(indexType))) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Array index must be of type int: found '" + indexType + "'")
                        .range(indexExpr.getTextRange())
                        .create();
            }
        }
    }

    private void checkShiftExpression(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;
        TokenIElementType opToken = null;
        for (PsiElement child : expr.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti) {
                int tt = ti.getANTLRTokenType();
                if (tt == OceanLexer.LSHIFT || tt == OceanLexer.RSHIFT || tt == OceanLexer.URSHIFT) {
                    opToken = ti;
                    break;
                }
            }
        }
        if (opToken == null) return;

        List<PsiElement> subExprs = new ArrayList<>();
        for (PsiElement c : expr.getChildren()) {
            if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                subExprs.add(c);
            }
        }
        if (subExprs.size() >= 2) {
            String opStr = "<<";
            if (opToken.getANTLRTokenType() == OceanLexer.RSHIFT) {
                opStr = ">>";
            } else if (opToken.getANTLRTokenType() == OceanLexer.URSHIFT) {
                opStr = ">>>";
            }

            PsiElement lhs = subExprs.get(0);
            String lhsType = OceanTypeHelper.guessType(lhs);
            if (lhsType != null && !isIntegerType(lhsType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Shift operators (" + opStr + ") can only be applied to integer types: '" + lhsType + "'")
                        .range(lhs.getTextRange())
                        .create();
            }

            PsiElement rhs = subExprs.get(1);
            String rhsType = OceanTypeHelper.guessType(rhs);
            if (rhsType != null && !isIntegerType(rhsType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Shift distance must be an integer type: '" + rhsType + "'")
                        .range(rhs.getTextRange())
                        .create();
            }
        }
    }

    private void checkCastExpression(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;
        ASTNode firstNode = expr.getNode() != null ? expr.getNode().getFirstChildNode() : null;
        if (firstNode == null || !(firstNode.getElementType() instanceof TokenIElementType ti) || ti.getANTLRTokenType() != OceanLexer.LPAREN) {
            return;
        }
        PsiElement targetTypeElem = null;
        PsiElement operandExpr = null;
        for (PsiElement child : expr.getChildren()) {
            if (isRule(child, OceanParser.RULE_type) && targetTypeElem == null) {
                targetTypeElem = child;
            } else if (targetTypeElem != null && (isRule(child, OceanParser.RULE_expression) || isRule(child, OceanParser.RULE_primary))) {
                operandExpr = child;
            }
        }
        if (targetTypeElem == null || operandExpr == null) return;

        String targetType = OceanScopeDiscovery.normalizeTypeName(targetTypeElem.getText().trim());
        String sourceType = OceanTypeHelper.guessType(operandExpr);
        if (targetType == null || sourceType == null) return;

        sourceType = OceanScopeDiscovery.normalizeTypeName(sourceType);

        // String cannot be cast to numeric or boolean, and vice-versa
        boolean isSourceString = "String".equals(sourceType) || "java.lang.String".equals(sourceType);
        boolean isTargetString = "String".equals(targetType) || "java.lang.String".equals(targetType);
        boolean isSourceNumOrBool = isNumericType(sourceType) || "boolean".equals(sourceType) || "Boolean".equals(sourceType) || "java.lang.Boolean".equals(sourceType);
        boolean isTargetNumOrBool = isNumericType(targetType) || "boolean".equals(targetType) || "Boolean".equals(targetType) || "java.lang.Boolean".equals(targetType);

        if ((isSourceString && isTargetNumOrBool) || (isTargetString && isSourceNumOrBool)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Inconvertible types: cannot cast '" + sourceType + "' to '" + targetType + "'")
                    .range(expr.getTextRange())
                    .create();
            return;
        }

        // boolean cannot be cast to numeric, and vice-versa
        boolean isSourceBool = "boolean".equals(sourceType) || "Boolean".equals(sourceType) || "java.lang.Boolean".equals(sourceType);
        boolean isTargetBool = "boolean".equals(targetType) || "Boolean".equals(targetType) || "java.lang.Boolean".equals(targetType);
        boolean isSourceNum = isNumericType(sourceType);
        boolean isTargetNum = isNumericType(targetType);

        if ((isSourceBool && isTargetNum) || (isTargetBool && isSourceNum)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Inconvertible types: cannot cast '" + sourceType + "' to '" + targetType + "'")
                    .range(expr.getTextRange())
                    .create();
        }
    }

    private void checkLambdaExpression(PsiElement expr, AnnotationHolder holder) {
        if (!hasTokenChild(expr, OceanLexer.ARROW)) return;

        // Collect lambda parameters
        List<PsiElement> paramElements = new ArrayList<>();
        PsiElement pList = directChildByRuleIndex(expr, OceanParser.RULE_parameterList);
        if (pList != null) {
            for (PsiElement p : pList.getChildren()) {
                if (isRule(p, OceanParser.RULE_parameter)) paramElements.add(p);
            }
        }
        PsiElement idList = directChildByRuleIndex(expr, OceanParser.RULE_identifierList);
        if (idList != null) {
            for (PsiElement id : idList.getChildren()) {
                if (isRule(id, OceanParser.RULE_anyId)) paramElements.add(id);
            }
        }

        if (paramElements.isEmpty()) return;

        // 1. Duplicate lambda parameter [E0002]
        Set<String> seenParams = new HashSet<>();
        for (PsiElement p : paramElements) {
            PsiElement idNode = isRule(p, OceanParser.RULE_anyId) ? p : directChildByRuleIndex(p, OceanParser.RULE_anyId);
            if (idNode == null) continue;
            String name = idNode.getText().trim();
            if ("_".equals(name)) continue;

            if (!seenParams.add(name)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Duplicate lambda parameter: '" + name + "'")
                        .range(idNode.getTextRange())
                        .create();
            }
        }

        // 2. Lambda parameter cannot shadow existing variable in method scope [E0002]
        PsiElement encMethodOrCtor = null;
        PsiElement cur = expr.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                encMethodOrCtor = cur;
                break;
            }
            if (isRule(cur, OceanParser.RULE_classDeclaration)) break;
            cur = cur.getParent();
        }

        if (encMethodOrCtor != null) {
            Set<String> methodScopeVars = new HashSet<>();
            // Parameters of enclosing method
            for (PsiElement mChild : encMethodOrCtor.getChildren()) {
                if (isRule(mChild, OceanParser.RULE_parameterList)) {
                    for (PsiElement p : mChild.getChildren()) {
                        if (isRule(p, OceanParser.RULE_parameter)) {
                            PsiElement pId = directChildByRuleIndex(p, OceanParser.RULE_anyId);
                            if (pId != null && !"_".equals(pId.getText().trim())) {
                                methodScopeVars.add(pId.getText().trim());
                            }
                        }
                    }
                }
            }
            // Variables declared before this lambda
            for (PsiElement varDecl : PsiTreeUtil.collectElements(encMethodOrCtor, e -> isRule(e, OceanParser.RULE_variableDeclarator))) {
                if (varDecl.getTextRange().getStartOffset() >= expr.getTextRange().getStartOffset()) break;
                if (!isEnclosingScope(varDecl, expr)) continue;
                PsiElement vId = directChildByRuleIndex(varDecl, OceanParser.RULE_anyId);
                if (vId != null && !"_".equals(vId.getText().trim())) {
                    methodScopeVars.add(vId.getText().trim());
                }
            }

            for (PsiElement p : paramElements) {
                PsiElement idNode = isRule(p, OceanParser.RULE_anyId) ? p : directChildByRuleIndex(p, OceanParser.RULE_anyId);
                if (idNode == null) continue;
                String name = idNode.getText().trim();
                if ("_".equals(name)) continue;

                if (methodScopeVars.contains(name)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Lambda parameter '" + name + "' cannot shadow existing variable in method scope.")
                            .range(idNode.getTextRange())
                            .create();
                }
            }
        }
    }

    private void checkExpression(PsiElement expr, AnnotationHolder holder) {
        checkUnaryOperatorExpression(expr, holder);
        checkNullableAccess(expr, holder);
        checkMethodCallExceptions(expr, holder);
        checkVoidArguments(expr, holder);
        checkStringConcatVoid(expr, holder);
        checkTernaryExpression(expr, holder);
        checkInstanceOfExpression(expr, holder);
        checkCastExpression(expr, holder);
        checkLambdaExpression(expr, holder);
        checkArrayIndexExpression(expr, holder);
        checkShiftExpression(expr, holder);
        checkStringBinaryOperators(expr, holder);

        // 'super(...)' / 'this(...)' call can only be invoked inside a constructor [E0002]
        String exprText = expr.getText().trim();
        if (exprText.startsWith("super(") || exprText.startsWith("this(")) {
            PsiElement encCtor = PsiTreeUtil.getParentOfType(expr, PsiElement.class);
            boolean inCtor = false;
            PsiElement cur = expr.getParent();
            while (cur != null && !(cur instanceof PsiFile)) {
                if (isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                    inCtor = true;
                    break;
                }
                if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_classDeclaration)) {
                    break;
                }
                cur = cur.getParent();
            }
            if (!inCtor) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] 'super(...)' / 'this(...)' can only be invoked inside a constructor")
                        .range(expr.getTextRange())
                        .create();
            }
        }

        ASTNode firstNode = expr.getNode() != null ? expr.getNode().getFirstChildNode() : null;
        if (firstNode == null || !(firstNode.getElementType() instanceof TokenIElementType ti) || ti.getANTLRTokenType() != OceanLexer.NEW) {
            return;
        }

        String text = expr.getText();
        // Check for array creation: new Type[10]
        if (text.contains("[") && text.contains("]")) {
            int dimExprCount = 0;
            for (PsiElement child : expr.getChildren()) {
                if (isRule(child, OceanParser.RULE_type)) {
                    String tName = child.getText().trim();
                    if (isGenericTypeParam(tName)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Generic array creation: cannot create array of type parameter '" + tName + "'")
                                .range(child.getTextRange())
                                .create();
                    }
                } else if (isRule(child, OceanParser.RULE_expression)) {
                    dimExprCount++;
                    String dimType = OceanTypeHelper.guessType(child);
                    if (isKnownNonBoolean(dimType) && !isNumericType(dimType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Array dimension must be of type int: found '" + dimType + "'")
                                .range(child.getTextRange())
                                .create();
                    } else if ("boolean".equalsIgnoreCase(dimType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Array dimension must be of type int: found '" + dimType + "'")
                                .range(child.getTextRange())
                                .create();
                    }
                    // Check dimension expression for negative constants
                    String dimText = child.getText().trim();
                    try {
                        long dimVal = Long.parseLong(dimText);
                        if (dimVal < 0) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Array dimension cannot be negative: " + dimVal)
                                    .range(child.getTextRange())
                                    .create();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (dimExprCount > 255) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Array dimension limit exceeded (maximum 255 dimensions supported, " + dimExprCount + " specified).")
                        .range(expr.getTextRange())
                        .create();
            }
            // Array creation of interface or abstract class reference is completely valid!
            return;
        }

        // Check if this new expression has braces { ... }
        boolean hasBraces = false;
        ASTNode childNode = expr.getNode().getFirstChildNode();
        while (childNode != null) {
            if (childNode.getElementType() instanceof TokenIElementType tiToken && tiToken.getANTLRTokenType() == OceanLexer.LBRACE) {
                hasBraces = true;
                break;
            }
            childNode = childNode.getTreeNext();
        }

        // Find the type name element in `new Type(...)`
        PsiElement typeNode = null;
        for (PsiElement child : expr.getChildren()) {
            if (isRule(child, OceanParser.RULE_type)) {
                typeNode = child;
                break;
            }
        }
        if (typeNode == null) return;

        String typeName = typeNode.getText().trim();

        // 1. Cannot use wildcard type argument in object creation (new) [E0010]
        if (typeName.contains("<") && typeName.contains(">")) {
            int openIdx = typeName.indexOf('<');
            int closeIdx = typeName.lastIndexOf('>');
            if (openIdx < closeIdx) {
                String innerArgs = typeName.substring(openIdx + 1, closeIdx).trim();
                for (String arg : innerArgs.split(",")) {
                    String cleanArg = arg.trim();
                    if (cleanArg.startsWith("+") || cleanArg.startsWith("-")) cleanArg = cleanArg.substring(1).trim();
                    if (cleanArg.startsWith("?") || cleanArg.startsWith("*")) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot use wildcard type argument in object creation (new): '" + typeName + "'. Use a concrete type or diamond operator ('<>')")
                                .range(typeNode.getTextRange())
                                .create();
                        break;
                    }
                }
            }
        }

        String rawTypeName = extractRawType(typeName);
        boolean isInterface = false;
        boolean isAbstract = false;
        boolean isEnum = false;
        boolean isAnnotation = false;

        PsiElement oceanCls = findClassInSameFileOrProject(expr.getProject(), expr, rawTypeName);
        if (oceanCls != null) {
            isInterface = isRule(oceanCls, OceanParser.RULE_interfaceDeclaration);
            isEnum = isRule(oceanCls, OceanParser.RULE_enumDeclaration);
            isAnnotation = isRule(oceanCls, OceanParser.RULE_annotationDeclaration);
            isAbstract = hasModifierToken(oceanCls, OceanLexer.ABSTRACT);
        } else {
            PsiClass javaCls = resolveJavaClassInContext(expr.getProject(), expr, rawTypeName);
            if (javaCls != null) {
                isAnnotation = javaCls.isAnnotationType();
                isInterface = javaCls.isInterface() && !isAnnotation;
                isEnum = javaCls.isEnum();
                isAbstract = javaCls.hasModifierProperty(PsiModifier.ABSTRACT);
            }
        }

        if (isAnnotation) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Annotation type '" + rawTypeName + "' cannot be instantiated.")
                    .range(typeNode.getTextRange())
                    .create();
            return;
        }

        if (isEnum) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot instantiate enum '" + typeName + "'")
                    .range(typeNode.getTextRange())
                    .create();
            return;
        }

        if (!hasBraces) {
            // Direct instantiation error
            if (isInterface) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot instantiate interface '" + typeName + "'")
                      .range(typeNode.getTextRange())
                      .withFix(new OceanImplementMethodsAction())
                      .create();
            } else if (isAbstract) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot instantiate abstract class '" + typeName + "'")
                      .range(typeNode.getTextRange())
                      .withFix(new OceanImplementMethodsAction())
                      .create();
            }
        } else {
            // Anonymous class checks (needs to implement all abstract methods)
            OceanImplementMethodsAction action = new OceanImplementMethodsAction();
            List<OceanImplementMethodsAction.MethodStub> missing = action.collectMissingMethods(expr.getProject(), expr);
            if (missing != null && !missing.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("[E0010] Anonymous class must implement abstract methods: ");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) sb.append(", ");
                    OceanImplementMethodsAction.MethodStub stub = missing.get(i);
                    sb.append(stub.name()).append("(").append(stub.params()).append(")");
                }
                holder.newAnnotation(HighlightSeverity.ERROR, sb.toString())
                      .range(typeNode.getTextRange())
                      .withFix(new OceanImplementMethodsAction())
                      .create();
            }

            // Duplicate members check in anonymous class body
            checkDuplicateMembers(expr, holder);

            // Overrides compatibility check in anonymous class
            List<String> parents = new ArrayList<>();
            parents.add(typeName);
            checkMethodOverrides(expr, holder, parents);
        }
    }

    private void checkDuplicateMembers(PsiElement cls, AnnotationHolder holder) {
        Set<String> declaredFields = new HashSet<>();
        Set<String> declaredMethodSignatures = new HashSet<>();
        
        for (PsiElement child : cls.getChildren()) {
            if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement member : child.getChildren()) {
                    if (isRule(member, OceanParser.RULE_fieldDeclaration)) {
                        // fieldDeclaration → variableDeclarator → anyId
                        for (PsiElement declarator : member.getChildren()) {
                            if (!isRule(declarator, OceanParser.RULE_variableDeclarator)) continue;
                            PsiElement anyId = null;
                            for (PsiElement c : declarator.getChildren()) {
                                if (isRule(c, OceanParser.RULE_anyId)) { anyId = c; break; }
                            }
                            if (anyId != null) {
                                String fName = anyId.getText().trim();
                                if (!declaredFields.add(fName)) {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Duplicate field declaration: '" + fName + "' is already defined.")
                                          .range(anyId.getTextRange())
                                          .create();
                                }
                            }
                        }
                    } else if (isRule(member, OceanParser.RULE_methodDeclaration)) {
                        PsiElement anyId = null;
                        for (PsiElement c : member.getChildren()) {
                            if (isRule(c, OceanParser.RULE_anyId)) { anyId = c; break; }
                        }
                        if (anyId != null) {
                            String mName = anyId.getText().trim();
                            String paramSig = getMethodParamTypesSignature(member);
                            String sig = mName + paramSig;
                            if (!declaredMethodSignatures.add(sig)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Method '" + mName + "' is already defined with parameter signature " + paramSig + " in this class")
                                      .range(anyId.getTextRange())
                                      .create();
                            }
                        }
                    } else if (isRule(member, OceanParser.RULE_constructorDeclaration)) {
                        PsiElement anyId = null;
                        for (PsiElement c : member.getChildren()) {
                            if (isRule(c, OceanParser.RULE_anyId)) { anyId = c; break; }
                        }
                        String cName = anyId != null ? anyId.getText().trim() : "init";
                        String paramSig = getMethodParamTypesSignature(member);
                        String sig = "<init>" + paramSig;
                        if (!declaredMethodSignatures.add(sig)) {
                            PsiElement targetRange = anyId != null ? anyId : member;
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Constructor is already defined with parameter signature " + paramSig + " in this class")
                                  .range(targetRange.getTextRange())
                                  .create();
                        }
                    }
                }
            }
        }
    }

    private void checkMethodOverrides(PsiElement cls, AnnotationHolder holder, List<String> parentNames) {
        for (PsiElement child : cls.getChildren()) {
            if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement member : child.getChildren()) {
                    if (isRule(member, OceanParser.RULE_methodDeclaration)) {
                        PsiElement anyId = null;
                        for (PsiElement c : member.getChildren()) {
                            if (isRule(c, OceanParser.RULE_anyId)) { anyId = c; break; }
                        }
                        if (anyId == null) continue;
                        String methodName = anyId.getText().trim();
                        if ("main".equals(methodName)) continue;
                        String localParamSig = getMethodParamTypesSignature(member);

                        List<PsiElement> superMethods = new ArrayList<>();
                        for (String parent : parentNames) {
                            resolveMethodInSuper(cls, parent, methodName, localParamSig, superMethods, new HashSet<>());
                        }

                        if (!superMethods.isEmpty()) {
                            PsiElement superMethod = superMethods.get(0);

                            // 1. Check if super method is final
                            boolean isSuperFinal;
                            if (superMethod instanceof PsiMethod jm) {
                                isSuperFinal = jm.hasModifierProperty(PsiModifier.FINAL);
                            } else {
                                isSuperFinal = hasModifierToken(superMethod, OceanLexer.FINAL);
                            }
                            if (isSuperFinal) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot override final method '" + methodName + "'")
                                      .range(anyId.getTextRange())
                                      .create();
                            }

                            // Check static method override mismatch
                            boolean isSuperStatic = superMethod instanceof PsiMethod jm ? jm.hasModifierProperty(PsiModifier.STATIC) : hasModifierToken(superMethod, OceanLexer.STATIC);
                            boolean isLocalStatic = hasModifierToken(member, OceanLexer.STATIC);
                            if (isSuperStatic && !isLocalStatic) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Instance method '" + methodName + "' cannot override static method in super class")
                                      .range(anyId.getTextRange())
                                      .create();
                            } else if (!isSuperStatic && isLocalStatic) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Static method '" + methodName + "' cannot override instance method in super class")
                                      .range(anyId.getTextRange())
                                      .create();
                            }

                            // 2. Check weaker access privileges (public -> protected/private, protected -> private)
                            int localVis = getMethodVisibilityLevel(member);
                            int superVis = getSuperMethodVisibilityLevel(superMethod);
                            if (localVis < superVis) {
                                if (superVis == 3) {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot reduce visibility: method '" + methodName + "' overriding public method must be 'public'.")
                                          .range(anyId.getTextRange())
                                          .create();
                                } else {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Cannot reduce visibility: method '" + methodName + "' overriding protected method must be 'protected' or 'public'.")
                                          .range(anyId.getTextRange())
                                          .create();
                                }
                            }

                            // 3. Return type compatibility
                            String localReturn = getReturnType(member);
                            String superReturn = getSuperMethodReturnType(superMethod);
                            if (superReturn != null) {
                                if (!isReturnTypeCompatible(cls.getProject(), localReturn, superReturn)) {
                                    String dispLocal = "java.lang.String".equals(localReturn) ? "String" : OceanOverloadResolver.getSimpleTypeName(localReturn);
                                    String dispSuper = "java.lang.String".equals(superReturn) ? "String" : OceanOverloadResolver.getSimpleTypeName(superReturn);
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Incompatible return type when overriding method '" + methodName + "': '" + dispLocal + "' is not compatible with '" + dispSuper + "'.")
                                          .range(anyId.getTextRange())
                                          .create();
                                }
                            }

                            // 4. Checked exception compatibility [E0010]
                            checkOverrideThrowsExceptions(cls.getProject(), member, superMethod, anyId, holder);
                        }
                    }
                }
            }
        }
    }

    private void checkOverrideThrowsExceptions(Project project, PsiElement childMethod, PsiElement superMethod, PsiElement nameId, AnnotationHolder holder) {
        if (childMethod == null || superMethod == null || nameId == null) return;
        List<String> childThrows = new ArrayList<>();
        PsiElement childThrowsNode = directChildByRuleIndex(childMethod, OceanParser.RULE_typeList);
        if (childThrowsNode != null) {
            for (PsiElement c : childThrowsNode.getChildren()) {
                if (isRule(c, OceanParser.RULE_type)) {
                    childThrows.add(extractRawType(c.getText().trim()));
                }
            }
        }
        if (childThrows.isEmpty()) return;

        List<String> parentThrows = new ArrayList<>();
        String parentOwnerName = "Super";
        if (superMethod instanceof PsiMethod jm) {
            if (jm.getContainingClass() != null && jm.getContainingClass().getName() != null) {
                parentOwnerName = jm.getContainingClass().getName();
            }
            for (PsiClassType t : jm.getThrowsList().getReferencedTypes()) {
                parentThrows.add(extractRawType(t.getClassName()));
            }
        } else {
            PsiElement encParent = findEnclosingClass(superMethod);
            if (encParent != null) {
                String pName = findNameForElement(encParent);
                if (pName != null) parentOwnerName = pName;
            }
            PsiElement superThrowsNode = directChildByRuleIndex(superMethod, OceanParser.RULE_typeList);
            if (superThrowsNode != null) {
                for (PsiElement c : superThrowsNode.getChildren()) {
                    if (isRule(c, OceanParser.RULE_type)) {
                        parentThrows.add(extractRawType(c.getText().trim()));
                    }
                }
            }
        }

        for (String childExc : childThrows) {
            if (isUncheckedException(project, childMethod, childExc)) continue;
            boolean covered = false;
            for (String parentExc : parentThrows) {
                if (childExc.equals(parentExc) || isSubclassOf(project, childMethod, childExc, parentExc)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Overridden method in '" + parentOwnerName + "' does not throw checked exception '" + childExc + "'")
                        .range(nameId.getTextRange())
                        .create();
            }
        }
    }

    private boolean isUncheckedException(Project project, PsiElement context, String excName) {
        if (excName == null) return false;
        if ("RuntimeException".equals(excName) || "Error".equals(excName) ||
            "NullPointerException".equals(excName) || "IllegalArgumentException".equals(excName) ||
            "IllegalStateException".equals(excName) || "IndexOutOfBoundsException".equals(excName)) {
            return true;
        }
        return isSubclassOf(project, context, excName, "RuntimeException") ||
                isSubclassOf(project, context, excName, "Error");
    }

    private boolean isSubclassOf(Project project, PsiElement context, String childType, String superType) {
        if (childType == null || superType == null) return false;
        if (childType.equals(superType)) return true;
        PsiElement oceanCls = findClassInSameFileOrProject(project, context, childType);
        if (oceanCls != null) {
            String ext = extractExtendsName(oceanCls);
            if (ext != null) {
                if (ext.equals(superType) || isSubclassOf(project, oceanCls, ext, superType)) {
                    return true;
                }
            }
        }
        return OceanScopeDiscovery.isSubclassOf(project, childType, superType);
    }

    private void resolveMethodInSuper(PsiElement context, String superName, String methodName, String paramSig, List<PsiElement> results, Set<String> visited) {
        if (superName == null || !visited.add(superName)) return;
        superName = OceanScopeDiscovery.normalizeTypeName(superName);
        Project project = context.getProject();

        PsiElement oceanCls = findClassInSameFileOrProject(project, context, superName);
        if (oceanCls != null) {
            for (PsiElement child : oceanCls.getChildren()) {
                if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                    for (PsiElement member : child.getChildren()) {
                        if (isRule(member, OceanParser.RULE_methodDeclaration)) {
                            String name = findNameForElement(member);
                            if (methodName.equals(name)) {
                                String sSig = getMethodParamTypesSignature(member);
                                if (paramSig == null || isParamSignatureCompatible(paramSig, sSig)) {
                                    results.add(member);
                                }
                            }
                        }
                    }
                }
            }
            String grandExtends = extractExtendsName(oceanCls);
            if (grandExtends != null) resolveMethodInSuper(context, grandExtends, methodName, paramSig, results, visited);
            for (String grandIface : extractImplementsNames(oceanCls)) {
                resolveMethodInSuper(context, grandIface, methodName, paramSig, results, visited);
            }
        } else {
            PsiClass javaCls = resolveJavaClassInContext(project, context, superName);
            if (javaCls != null) {
                PsiMethod[] javaMethods = javaCls.findMethodsByName(methodName, true);
                for (PsiMethod jm : javaMethods) {
                    String jSig = getJavaMethodParamTypesSignature(jm);
                    if (paramSig == null || isParamSignatureCompatible(paramSig, jSig)) {
                        results.add(jm);
                    }
                }
            }
        }
    }

    private int getMethodVisibilityLevel(PsiElement method) {
        if (hasModifierToken(method, OceanLexer.PRIVATE)) return 0;
        if (hasModifierToken(method, OceanLexer.PROTECTED)) return 2;
        // In Ocean, methods without explicit access modifier default to 'public' (level 3)
        return 3;
    }

    private int getSuperMethodVisibilityLevel(PsiElement superMethod) {
        if (superMethod instanceof PsiMethod jm) {
            if (jm.hasModifierProperty(PsiModifier.PUBLIC)) return 3;
            if (jm.hasModifierProperty(PsiModifier.PROTECTED)) return 2;
            if (jm.hasModifierProperty(PsiModifier.PRIVATE)) return 0;
            return 1;
        }
        return getMethodVisibilityLevel(superMethod);
    }

    private boolean isParamSignatureCompatible(String localSig, String superSig) {
        if (localSig == null || superSig == null) return true;
        if (localSig.equals(superSig)) return true;
        String insideLocal = localSig.startsWith("(") && localSig.endsWith(")") ? localSig.substring(1, localSig.length() - 1) : localSig;
        String insideSuper = superSig.startsWith("(") && superSig.endsWith(")") ? superSig.substring(1, superSig.length() - 1) : superSig;
        String[] localParts = insideLocal.isBlank() ? new String[0] : insideLocal.split(",");
        String[] superParts = insideSuper.isBlank() ? new String[0] : insideSuper.split(",");
        if (localParts.length != superParts.length) return false;
        for (int i = 0; i < localParts.length; i++) {
            String rawL = localParts[i].trim();
            String rawS = superParts[i].trim();
            if (rawL.equals(rawS)) continue;
            // Handle varargs matching array: String... <-> String[]
            String strippedL = rawL.endsWith("...") ? rawL.substring(0, rawL.length() - 3) + "[]" : rawL;
            String strippedS = rawS.endsWith("...") ? rawS.substring(0, rawS.length() - 3) + "[]" : rawS;
            if (strippedL.equals(strippedS)) continue;

            String lp = OceanScopeDiscovery.normalizeTypeName(localParts[i]);
            String sp = OceanScopeDiscovery.normalizeTypeName(superParts[i]);
            if (lp.equals(sp)) continue;
            if (isGenericTypeParam(lp) || isGenericTypeParam(sp) || "Object".equals(sp) || "variable".equals(lp)) continue;
            if (!isRawTypeCompatible(null, lp, sp)) return false;
        }
        return true;
    }

    private PsiElement findClassInSameFileOrProject(Project project, PsiElement context, String className) {
        className = extractRawType(className);
        if (className == null || className.isEmpty()) return null;
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

    private PsiClass resolveJavaClassInContext(Project project, PsiElement context, String className) {
        className = extractRawType(className);
        if (className == null || className.isEmpty()) return null;
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
        PsiElement paramList = null;
        for (PsiElement c : method.getChildren()) {
            if (isRule(c, OceanParser.RULE_parameterList)) {
                paramList = c;
                break;
            }
        }
        if (paramList == null) return "()";
        List<String> types = new ArrayList<>();
        for (PsiElement p : paramList.getChildren()) {
            if (isRule(p, OceanParser.RULE_parameter)) {
                PsiElement t = null;
                for (PsiElement pc : p.getChildren()) {
                    if (isRule(pc, OceanParser.RULE_type)) {
                        t = pc;
                        break;
                    }
                }
                String typeName = t != null ? t.getText().trim() : "variable";
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

    private String getReturnType(PsiElement method) {
        return OceanScopeDiscovery.getMethodReturnType(method);
    }

    private String getSuperMethodReturnType(PsiElement superMethod) {
        if (superMethod instanceof PsiMethod jm) {
            PsiType returnType = jm.getReturnType();
            if (returnType == null) return "void";
            return javaTypeToOcean(returnType);
        }
        return getReturnType(superMethod);
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

    private boolean isReturnTypeCompatible(Project project, String localReturn, String superReturn) {
        if (localReturn == null || superReturn == null) return true;
        localReturn = localReturn.trim();
        superReturn = superReturn.trim();
        while (localReturn.endsWith("?")) localReturn = localReturn.substring(0, localReturn.length() - 1).trim();
        while (superReturn.endsWith("?")) superReturn = superReturn.substring(0, superReturn.length() - 1).trim();
        if (localReturn.equals(superReturn)) return true;
        if ("void".equals(localReturn) || "void".equals(superReturn)) return false;
        if ("Object".equals(superReturn) || "java.lang.Object".equals(superReturn) || "Any".equals(superReturn)) return true;
        if (isGenericTypeParam(localReturn) || isGenericTypeParam(superReturn)) return true;
        if (isNumericType(localReturn) && isNumericType(superReturn)) return true;

        String rawLocal = extractRawType(localReturn);
        String rawSuper = extractRawType(superReturn);

        if (isGenericTypeParam(rawLocal) || isGenericTypeParam(rawSuper)) return true;

        boolean rawCompatible = isRawTypeCompatible(project, rawLocal, rawSuper);
        if (!rawCompatible) return false;

        List<String> localArgs = extractTypeArguments(localReturn);
        List<String> superArgs = extractTypeArguments(superReturn);

        if (superArgs.isEmpty() || localArgs.isEmpty()) return true;
        if (localArgs.size() == superArgs.size()) {
            for (int i = 0; i < localArgs.size(); i++) {
                String lArg = localArgs.get(i);
                String sArg = superArgs.get(i);
                if (isGenericTypeParam(sArg) || isGenericTypeParam(lArg) || "?".equals(sArg) || "Object".equals(sArg)) {
                    continue;
                }
                if (!isReturnTypeCompatible(project, lArg, sArg)) {
                    return false;
                }
            }
            return true;
        }

        return true;
    }

    private boolean isNumericType(String type) {
        if (type == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(type);
        if (t.contains(".")) t = t.substring(t.lastIndexOf('.') + 1);
        return switch (t) {
            case "int", "long", "float", "double", "short", "byte", "char", "number", "Number",
                 "Integer", "Long", "Float", "Double", "Short", "Byte", "Character" -> true;
            default -> false;
        };
    }

    private boolean isIntegerType(String type) {
        if (type == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(type);
        if (t.contains(".")) t = t.substring(t.lastIndexOf('.') + 1);
        return switch (t) {
            case "int", "long", "short", "byte", "char",
                 "Integer", "Long", "Short", "Byte", "Character" -> true;
            default -> false;
        };
    }

    private boolean isRawTypeCompatible(Project project, String rawLocal, String rawSuper) {
        if (rawLocal.equals(rawSuper)) return true;
        if (normalizeStdlibType(rawLocal).equals(normalizeStdlibType(rawSuper))) return true;

        PsiElement localOcean = OceanScopeDiscovery.findOceanClass(project, rawLocal);
        if (localOcean != null) {
            String extendsName = extractExtendsName(localOcean);
            if (extendsName != null && isRawTypeCompatible(project, extractRawType(extendsName), rawSuper)) {
                return true;
            }
            for (String iface : extractImplementsNames(localOcean)) {
                if (isRawTypeCompatible(project, extractRawType(iface), rawSuper)) {
                    return true;
                }
            }
        }

        PsiClass localJava = OceanScopeDiscovery.resolveJavaClass(project, rawLocal);
        PsiClass superJava = OceanScopeDiscovery.resolveJavaClass(project, rawSuper);
        if (localJava != null && superJava != null) {
            return localJava.equals(superJava) || localJava.isInheritor(superJava, true);
        }
        return false;
    }

    private String normalizeStdlibType(String type) {
        if (type == null) return "";
        return switch (type) {
            case "OceanList", "List", "ArrayList", "java.util.List", "ocean.stdlib.OceanList" -> "List";
            case "OceanMap", "Map", "HashMap", "java.util.Map", "ocean.stdlib.OceanMap" -> "Map";
            case "OceanSet", "Set", "HashSet", "java.util.Set", "ocean.stdlib.OceanSet" -> "Set";
            default -> type;
        };
    }

    private String extractRawType(String typeName) {
        if (typeName == null) return "";
        String t = typeName.trim();
        while (t.endsWith("?")) t = t.substring(0, t.length() - 1).trim();
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt).trim();
        while (t.endsWith("[]")) t = t.substring(0, t.length() - 2).trim();
        while (t.endsWith("?")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    private List<String> extractTypeArguments(String typeName) {
        if (typeName == null) return List.of();
        typeName = typeName.trim();
        int lt = typeName.indexOf('<');
        int gt = typeName.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String inside = typeName.substring(lt + 1, gt);
            List<String> args = new ArrayList<>();
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            for (char c : inside.toCharArray()) {
                if (c == '<') depth++;
                else if (c == '>') depth--;
                else if (c == ',' && depth == 0) {
                    args.add(cur.toString().trim());
                    cur.setLength(0);
                    continue;
                }
                cur.append(c);
            }
            if (!cur.isEmpty()) args.add(cur.toString().trim());
            return args;
        }
        return List.of();
    }

    private boolean isGenericTypeParam(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        String t = typeName.trim();
        while (t.endsWith("?")) t = t.substring(0, t.length() - 1).trim();
        while (t.endsWith("[]")) t = t.substring(0, t.length() - 2).trim();
        if (t.isEmpty()) return false;
        if (t.length() == 1 && Character.isUpperCase(t.charAt(0))) return true;
        if (t.matches("^[A-Z0-9_]{1,12}$")) return true;
        return switch (t) {
            case "In", "Out", "Item", "Key", "Value", "Req", "Resp", "Source", "Target", "Element" -> true;
            default -> false;
        };
    }

    private PsiElement directChildByRuleIndex(PsiElement e, int ruleIndex) {
        for (PsiElement c : e.getChildren()) {
            if (isRule(c, ruleIndex)) return c;
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
                if (isRule(c, OceanParser.RULE_type)) {
                    return OceanScopeDiscovery.normalizeTypeName(c.getText().trim());
                }
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && (ti.getANTLRTokenType() == OceanLexer.IMPLEMENTS || ti.getANTLRTokenType() == OceanLexer.LBRACE)) {
                    break;
                }
            }
        }
        Matcher m = Pattern.compile("\\bextends\\s+([\\w.<>]+)").matcher(cls.getText());
        return m.find() ? OceanScopeDiscovery.normalizeTypeName(m.group(1)) : null;
    }

    private List<String> extractImplementsNames(PsiElement cls) {
        List<String> names = new ArrayList<>();
        if (cls == null) return names;
        for (PsiElement child = cls.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isRule(child, OceanParser.RULE_typeList)) {
                for (PsiElement tc : child.getChildren()) {
                    if (isRule(tc, OceanParser.RULE_type)) {
                        String n = OceanScopeDiscovery.normalizeTypeName(tc.getText().trim());
                        if (n != null && !n.isEmpty()) names.add(n);
                    }
                }
                if (!names.isEmpty()) return names;
            }
        }
        for (PsiElement c : cls.getChildren()) {
            if (isRule(c, OceanParser.RULE_typeList)) {
                for (PsiElement tc : c.getChildren()) {
                    if (isRule(tc, OceanParser.RULE_type)) {
                        String n = OceanScopeDiscovery.normalizeTypeName(tc.getText().trim());
                        if (n != null && !n.isEmpty()) names.add(n);
                    }
                }
                if (!names.isEmpty()) return names;
            }
        }
        boolean afterImplements = false;
        for (PsiElement c : cls.getChildren()) {
            if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.IMPLEMENTS) {
                afterImplements = true;
                continue;
            }
            if (afterImplements) {
                if (isRule(c, OceanParser.RULE_typeList)) {
                    for (PsiElement tc : c.getChildren()) {
                        if (isRule(tc, OceanParser.RULE_type)) {
                            String n = OceanScopeDiscovery.normalizeTypeName(tc.getText().trim());
                            if (n != null && !n.isEmpty()) names.add(n);
                        }
                    }
                    return names;
                }
                if (isRule(c, OceanParser.RULE_type)) {
                    String n = OceanScopeDiscovery.normalizeTypeName(c.getText().trim());
                    if (n != null && !n.isEmpty()) names.add(n);
                }
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.LBRACE) {
                    break;
                }
            }
        }
        if (!names.isEmpty()) return names;
        String kw = isRule(cls, OceanParser.RULE_interfaceDeclaration) && !cls.getText().contains("implements") ? "extends" : "implements";
        Matcher m = Pattern.compile("\\b" + kw + "\\s+([^;{]+)").matcher(cls.getText());
        if (m.find()) {
            for (String s : OceanScopeDiscovery.splitTopLevelComma(m.group(1))) {
                String t = OceanScopeDiscovery.normalizeTypeName(s);
                if (t != null && !t.isEmpty()) names.add(t);
            }
        }
        return names;
    }

    private boolean isInsideImportOrPackage(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_importStatement) || isRule(cur, OceanParser.RULE_packageDeclaration)) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private boolean isPackagePrefixInTypeName(PsiElement element) {
        PsiElement cur = element.getParent();
        while (cur != null) {
            if (isRule(cur, OceanParser.RULE_typeName)) {
                PsiElement next = element.getNextSibling();
                while (next != null) {
                    if (".".equals(next.getText().trim())) {
                        return true;
                    }
                    next = next.getNextSibling();
                }
                return false;
            }
            if (isRule(cur, OceanParser.RULE_statement) || cur instanceof PsiFile) break;
            cur = cur.getParent();
        }
        return false;
    }

    private boolean isVarOrParamControl(PsiElement element) {
        return element instanceof OceanVariableDeclaration || element instanceof OceanParameter ||
                element instanceof OceanLoopVariable || element instanceof OceanCatchVariable ||
                (element instanceof OceanVariableDeclarator d && !d.isField());
    }

    private void checkEffectivelyFinal(PsiElement element, AnnotationHolder holder) {
        PsiReference ref = element.getReference();
        if (ref == null) return;
        PsiElement resolved = ref.resolve();
        if (resolved == null) return;

        boolean isVarOrParam = isVarOrParamControl(resolved);

        if (!isVarOrParam) {
            PsiElement resolvedParent = resolved.getParent();
            if (resolvedParent != null && resolvedParent.getNode() != null && resolvedParent.getNode().getElementType() instanceof RuleIElementType pri) {
                int pIdx = pri.getRuleIndex();
                isVarOrParam = (pIdx == OceanParser.RULE_variableDeclaration || pIdx == OceanParser.RULE_parameter || pIdx == OceanParser.RULE_forControl || pIdx == OceanParser.RULE_catchClause);
            }
        }

        if (isVarOrParam) {
            if (isCapturedVariable(element, resolved)) {
                PsiElement scope = findEnclosingScope(resolved);
                if (resolved instanceof OceanLoopVariable || isVariableMutated(resolved, scope)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "Variable '" + element.getText() + "' accessed from within inner class must be final or effectively final").range(element.getTextRange()).create();
                }
            }
        }
    }

    private boolean isCapturedVariable(PsiElement reference, PsiElement declaration) {
        PsiElement current = reference;
        while (current != null && current != declaration) {
            if (isLambdaExpr(current)) return true;
            if (isNewObjectExpr(current)) {
                if (current.getText().contains("{")) return true;
            }
            if (isRule(current, OceanParser.RULE_classDeclaration) && !isTopLevelType(current)) return true;

            current = current.getParent();
        }
        return false;
    }

    private PsiElement findEnclosingScope(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (isRule(current, OceanParser.RULE_methodDeclaration) ||
                isRule(current, OceanParser.RULE_constructorDeclaration) ||
                isMainMethod(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isVariableMutated(PsiElement declaration, PsiElement enclosingScope) {
        if (enclosingScope == null) return false;
        final boolean[] mutated = new boolean[1];

        PsiTreeUtil.processElements(enclosingScope, element -> {
            if (element instanceof OceanIdReference) {
                PsiReference ref = element.getReference();
                if (ref != null && ref.resolve() == declaration) {
                    if (isMutatedReference(element)) {
                        mutated[0] = true;
                        return false;
                    }
                }
            }
            return true;
        });
        return mutated[0];
    }

    private boolean isMutatedReference(PsiElement ref) {
        PsiElement parent = ref.getParent();
        while (parent != null) {
            // If we encounter an array access or member/method call on the way up,
            // then it's a mutation of the element/field, not the base variable reference itself.
            if (isRule(parent, OceanParser.RULE_expression)) {
                // Check if this expression node has a dot '.', '?.' or a bracket '[' as a direct child token
                for (PsiElement child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti) {
                        int tt = ti.getANTLRTokenType();
                        if (tt == OceanLexer.LBRACK || tt == OceanLexer.DOT || tt == OceanLexer.SAFE_DOT) {
                            return false;
                        }
                    }
                    String text = child.getText();
                    if (".".equals(text) || "[".equals(text) || "?.".equals(text)) {
                        return false;
                    }
                }
            }
            if (isRule(parent, OceanParser.RULE_assignment)) {
                PsiElement firstChild = parent.getFirstChild();
                return firstChild != null && (firstChild == ref || PsiTreeUtil.isAncestor(firstChild, ref, false));
            }
            if (isPrefixOrPostfixExpr(parent)) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /**
     * P3-C: Validate annotation member value types.
     * Resolves the annotation class, looks up each annotationElement key's declared type,
     * then infers the provided value's type via OceanTypeHelper.
     * Reports WARNING (not ERROR) to minimize false positives.
     */
    private void checkAnnotationValues(@NotNull PsiElement annotationElement, @NotNull AnnotationHolder holder) {
        // Find the annotation type name
        String annoTypeName = null;
        for (PsiElement child : annotationElement.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeName) || isRule(child, OceanParser.RULE_anyId)) {
                annoTypeName = child.getText().trim();
                break;
            }
        }
        if (annoTypeName == null) return;

        Project project = annotationElement.getProject();
        PsiElement annoClass = findClassInSameFileOrProject(project, annotationElement, annoTypeName);
        if (annoClass == null) {
            annoClass = OceanScopeDiscovery.findOceanClass(project, annoTypeName);
        }

        // 1. Duplicate Annotation Check (unless @Repeatable) [E0010]
        PsiElement parentOwner = annotationElement.getParent();
        if (parentOwner != null) {
            int annoCount = 0;
            boolean isRepeatable = false;
            if (annoClass != null) {
                isRepeatable = annoClass.getText().contains("@Repeatable");
            } else {
                PsiClass jClass = resolveJavaClassInContext(project, annotationElement, annoTypeName);
                if (jClass != null) {
                    isRepeatable = jClass.hasAnnotation("java.lang.annotation.Repeatable");
                }
            }
            if (!isRepeatable) {
                for (PsiElement sibling : parentOwner.getChildren()) {
                    if (isRule(sibling, OceanParser.RULE_annotation)) {
                        for (PsiElement c : sibling.getChildren()) {
                            if (isRule(c, OceanParser.RULE_typeName) && annoTypeName.equals(c.getText().trim())) {
                                annoCount++;
                                break;
                            }
                        }
                    }
                }
                if (annoCount > 1) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Annotation '" + annoTypeName + "' is not repeatable (requires @Repeatable)")
                            .range(annotationElement.getTextRange())
                            .create();
                }
            }

            // 2. Annotation Target Mismatch [E0010]
            if (annoClass != null && annoClass.getText().contains("@Target")) {
                String fullAnnoText = annoClass.getText();
                boolean targetAllowed = true;
                String targetKind = "UNKNOWN";
                if (isRule(parentOwner, OceanParser.RULE_classDeclaration)) {
                    targetKind = "TYPE";
                    targetAllowed = Pattern.compile("\\bTYPE\\b").matcher(fullAnnoText).find();
                } else if (isRule(parentOwner, OceanParser.RULE_methodDeclaration)) {
                    targetKind = "METHOD";
                    targetAllowed = Pattern.compile("\\bMETHOD\\b").matcher(fullAnnoText).find();
                } else if (isRule(parentOwner, OceanParser.RULE_fieldDeclaration)) {
                    targetKind = "FIELD";
                    targetAllowed = Pattern.compile("\\bFIELD\\b").matcher(fullAnnoText).find();
                } else if (isRule(parentOwner, OceanParser.RULE_parameter)) {
                    targetKind = "PARAMETER";
                    targetAllowed = Pattern.compile("\\bPARAMETER\\b").matcher(fullAnnoText).find();
                }
                if (!targetAllowed) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Annotation '" + annoTypeName + "' cannot be applied to target '" + targetKind + "'")
                            .range(annotationElement.getTextRange())
                            .create();
                }
            }
        }

        // Check each annotationElement (key = value pair) in this annotation for null value [E0010]
        for (PsiElement child : annotationElement.getChildren()) {
            if (isRule(child, OceanParser.RULE_annotationElement)) {
                String keyName = null;
                PsiElement valueExpr = null;
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_anyId) && keyName == null) {
                        keyName = c.getText().trim();
                    } else if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                        if (valueExpr == null) valueExpr = c;
                    }
                }
                if (valueExpr != null && "null".equals(valueExpr.getText().trim())) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Annotation '" + annoTypeName + "' attribute '" + keyName + "' cannot have 'null' value.")
                            .range(valueExpr.getTextRange())
                            .create();
                }
            }
        }

        if (annoClass == null) return; // Only check Ocean annotations; Java ones are handled by Java plugin

        // Build a map of memberName → declared type from the annotation declaration
        Map<String, String> memberTypes = new HashMap<>();
        for (PsiElement child : annoClass.getChildren()) {
            if (isRule(child, OceanParser.RULE_annotationMemberDeclaration)) {
                // annotationMemberDeclaration: modifiers? type anyId ('(' ')')? defaultValue? ';'
                PsiElement typeNode = null;
                PsiElement nameNode = null;
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_type) && typeNode == null) typeNode = c;
                    else if (isRule(c, OceanParser.RULE_anyId) && nameNode == null) nameNode = c;
                }
                if (typeNode != null && nameNode != null) {
                    memberTypes.put(nameNode.getText().trim(), typeNode.getText().trim());
                }
            }
        }
        if (memberTypes.isEmpty()) return;

        // Check each annotationElement (key = value pair) in this annotation
        for (PsiElement child : annotationElement.getChildren()) {
            if (isRule(child, OceanParser.RULE_annotationElement)) {
                String keyName = null;
                PsiElement valueExpr = null;
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_anyId) && keyName == null) {
                        keyName = c.getText().trim();
                    } else if (isRule(c, OceanParser.RULE_expression) || isRule(c, OceanParser.RULE_primary)) {
                        if (valueExpr == null) valueExpr = c;
                    }
                }
                if (valueExpr != null && "null".equals(valueExpr.getText().trim())) {
                    continue; // Already handled above
                }

                String declaredType = memberTypes.get(keyName);
                if (declaredType == null) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Attribute '" + keyName + "' is not defined in annotation '" + annoTypeName + "'")
                            .range(child.getTextRange())
                            .create();
                    continue;
                }
                if (valueExpr == null) continue;

                String actualType = OceanTypeHelper.guessType(valueExpr);
                if (actualType == null) continue;

                // Normalize both types for comparison
                String normalizedDeclared = OceanTypeHelper.boxType(declaredType);
                String normalizedActual = OceanTypeHelper.boxType(actualType);

                // Skip if types are compatible
                if (normalizedDeclared.equals(normalizedActual)) continue;
                if (normalizedActual.equals("java.lang.Object")) continue; // can't determine, skip
                if (normalizedDeclared.equals("java.lang.Object")) continue; // accepts anything

                // Special case: String annotations accept any literal
                if ("java.lang.String".equals(normalizedDeclared)) {
                    // Warn if not string
                    holder.newAnnotation(HighlightSeverity.WARNING,
                            "'" + keyName + "' expects a value of type String, but " + actualType + " was provided")
                            .range(valueExpr.getTextRange())
                            .create();
                    continue;
                }

                // Numeric narrowing: int can be assigned to long without warning
                if (isNumericCompatible(normalizedDeclared, normalizedActual)) continue;

                holder.newAnnotation(HighlightSeverity.WARNING,
                        "'" + keyName + "' expects a value of type " + declaredType + ", but " + actualType + " was provided")
                        .range(valueExpr.getTextRange())
                        .create();
            }
        }
    }

    /** P3-C: Returns true if actualType can be widened to declaredType without a warning. */
    private static boolean isNumericCompatible(String declared, String actual) {
        // int → long, int → double, long → double, float → double, int → float
        Set<String> intFamily = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte");
        if (!intFamily.contains(declared) || !intFamily.contains(actual)) return false;
        // Allow widening
        if ("java.lang.Integer".equals(actual)) return true; // int → any numeric
        if ("java.lang.Long".equals(actual) && !("java.lang.Integer".equals(declared) || "java.lang.Short".equals(declared) || "java.lang.Byte".equals(declared))) return true;
        return declared.equals(actual);
    }

    private void highlightImportOrPackageElement(PsiElement element, AnnotationHolder holder) {
        PsiElement importStmt = findEnclosingRule(element, OceanParser.RULE_importStatement);
        if (importStmt == null) {
            // It's in package declaration: package components are plain identifiers
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)
                    .create();
            return;
        }

        // Collect all anyId children in the importStatement
        List<PsiElement> anyIds = new ArrayList<>();
        boolean isStatic = false;
        boolean hasStar = false;
        for (PsiElement child = importStmt.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isRule(child, OceanParser.RULE_anyId)) {
                anyIds.add(child);
            } else if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti) {
                int tt = ti.getANTLRTokenType();
                if (tt == OceanLexer.STATIC) {
                    isStatic = true;
                } else if (tt == OceanLexer.STAR) {
                    hasStar = true;
                }
            } else {
                String txt = child.getText().trim();
                if ("static".equals(txt)) isStatic = true;
                else if ("*".equals(txt)) hasStar = true;
            }
        }

        if (anyIds.isEmpty()) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)
                    .create();
            return;
        }

        // Find which anyId corresponds to this element
        int anyIdIndex = -1;
        for (int i = 0; i < anyIds.size(); i++) {
            PsiElement anyId = anyIds.get(i);
            if (anyId == element || PsiTreeUtil.isAncestor(anyId, element, false)) {
                anyIdIndex = i;
                break;
            }
        }

        if (anyIdIndex == -1) {
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                    .range(element.getTextRange())
                    .textAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)
                    .create();
            return;
        }

        TextAttributesKey attr;
        Project project = element.getProject();

        if (isStatic) {
            if (!hasStar) {
                if (anyIdIndex == anyIds.size() - 1) {
                    // Static imported member: e.g. 'ONE' in 'import static java.math.BigDecimal.ONE;'
                    String memberName = anyIds.get(anyIdIndex).getText().trim();
                    String className = anyIds.size() >= 2 ? anyIds.get(anyIds.size() - 2).getText().trim() : "";
                    StringBuilder qClassSb = new StringBuilder();
                    for (int i = 0; i < anyIds.size() - 1; i++) {
                        if (i > 0) qClassSb.append(".");
                        qClassSb.append(anyIds.get(i).getText().trim());
                    }
                    String qualifiedClassName = qClassSb.toString();
                    attr = resolveStaticMemberHighlight(project, qualifiedClassName, className, memberName, element);
                } else if (anyIdIndex == anyIds.size() - 2) {
                    // Containing class: e.g. 'BigDecimal' in 'import static java.math.BigDecimal.ONE;'
                    StringBuilder qClassSb = new StringBuilder();
                    for (int i = 0; i <= anyIdIndex; i++) {
                        if (i > 0) qClassSb.append(".");
                        qClassSb.append(anyIds.get(i).getText().trim());
                    }
                    attr = resolveClassOrInterfaceHighlight(project, qClassSb.toString(), anyIds.get(anyIdIndex).getText().trim(), element);
                } else {
                    // Package prefix component: e.g. 'java', 'math'
                    attr = DefaultLanguageHighlighterColors.IDENTIFIER;
                }
            } else {
                // Wildcard static import: 'import static java.math.BigDecimal.*;'
                if (anyIdIndex == anyIds.size() - 1) {
                    StringBuilder qClassSb = new StringBuilder();
                    for (int i = 0; i <= anyIdIndex; i++) {
                        if (i > 0) qClassSb.append(".");
                        qClassSb.append(anyIds.get(i).getText().trim());
                    }
                    attr = resolveClassOrInterfaceHighlight(project, qClassSb.toString(), anyIds.get(anyIdIndex).getText().trim(), element);
                } else {
                    attr = DefaultLanguageHighlighterColors.IDENTIFIER;
                }
            }
        } else {
            // Normal import: 'import java.math.BigDecimal;' or 'import java.util.*;'
            if (!hasStar && anyIdIndex == anyIds.size() - 1) {
                // Class or Interface name
                StringBuilder qClassSb = new StringBuilder();
                for (int i = 0; i <= anyIdIndex; i++) {
                    if (i > 0) qClassSb.append(".");
                    qClassSb.append(anyIds.get(i).getText().trim());
                }
                attr = resolveClassOrInterfaceHighlight(project, qClassSb.toString(), anyIds.get(anyIdIndex).getText().trim(), element);
            } else {
                attr = DefaultLanguageHighlighterColors.IDENTIFIER;
            }
        }

        holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                .range(element.getTextRange())
                .textAttributes(attr)
                .create();
    }

    private TextAttributesKey resolveStaticMemberHighlight(Project project, String qualifiedClassName, String className, String memberName, PsiElement context) {
        if (project != null) {
            // 1. Try Java class
            PsiClass jc = JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, GlobalSearchScope.allScope(project));
            if (jc == null && className != null && !className.isEmpty()) {
                jc = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project));
            }
            if (jc != null) {
                PsiMethod[] methods = jc.findMethodsByName(memberName, true);
                if (methods.length > 0) {
                    return OceanSyntaxHighlighter.STATIC_METHOD;
                }
                PsiField field = jc.findFieldByName(memberName, true);
                if (field != null) {
                    return OceanSyntaxHighlighter.STATIC_FIELD;
                }
            }

            // 2. Try Ocean class
            PsiElement oceanClass = OceanScopeDiscovery.getProjectOceanClasses(project).get(className);
            if (oceanClass != null) {
                for (OceanMethodDeclaration m : PsiTreeUtil.findChildrenOfType(oceanClass, OceanMethodDeclaration.class)) {
                    if (memberName.equals(m.getName())) {
                        return OceanSyntaxHighlighter.STATIC_METHOD;
                    }
                }
                for (OceanFieldDeclaration f : PsiTreeUtil.findChildrenOfType(oceanClass, OceanFieldDeclaration.class)) {
                    if (memberName.equals(f.getName())) {
                        return OceanSyntaxHighlighter.STATIC_FIELD;
                    }
                }
            }
        }

        // 3. Fallback: In static imports, static fields/constants (e.g. ONE) are highlighted as STATIC_FIELD (purple & italic)
        return OceanSyntaxHighlighter.STATIC_FIELD;
    }

    private TextAttributesKey resolveClassOrInterfaceHighlight(Project project, String qualifiedClassName, String simpleName, PsiElement context) {
        if (project != null) {
            PsiClass jc = JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, GlobalSearchScope.allScope(project));
            if (jc == null && simpleName != null && !simpleName.isEmpty()) {
                jc = JavaPsiFacade.getInstance(project).findClass(simpleName, GlobalSearchScope.allScope(project));
            }
            if (jc != null) {
                return jc.isInterface() ? OceanSyntaxHighlighter.INTERFACE_NAME : OceanSyntaxHighlighter.CLASS_NAME;
            }

            PsiElement oceanElem = OceanScopeDiscovery.getProjectOceanClasses(project).get(simpleName);
            if (oceanElem instanceof OceanInterfaceDeclaration) {
                return OceanSyntaxHighlighter.INTERFACE_NAME;
            }
        }
        return OceanSyntaxHighlighter.CLASS_NAME;
    }

    private static PsiElement findEnclosingRule(PsiElement element, int ruleIdx) {
        PsiElement cur = element;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (cur.getNode() != null && cur.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIdx) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private void checkIfStatement(PsiElement element, AnnotationHolder holder) {
        PsiElement expr = directChildByRuleIndex(element, OceanParser.RULE_expression);
        if (expr != null) {
            String inferredType = OceanTypeHelper.guessType(expr);
            if (isKnownNonBoolean(inferredType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Condition in 'if' statement must be boolean, found '" + inferredType + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        }
    }

    private void checkLoopCondition(PsiElement element, AnnotationHolder holder) {
        PsiElement expr = directChildByRuleIndex(element, OceanParser.RULE_expression);
        if (expr != null) {
            String inferredType = OceanTypeHelper.guessType(expr);
            if (isKnownNonBoolean(inferredType)) {
                boolean isDoWhile = isRule(element, OceanParser.RULE_doWhileStatement);
                String loopKind = isDoWhile ? "do-while" : "while";
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Condition in '" + loopKind + "' loop must be boolean, found '" + inferredType + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        }
    }

    private void checkTryStatement(PsiElement element, AnnotationHolder holder) {
        boolean hasCatch = false;
        boolean hasFinally = false;
        boolean hasResource = false;

        for (PsiElement child : element.getChildren()) {
            if (isRule(child, OceanParser.RULE_resourceList)) {
                hasResource = true;
                for (PsiElement res : child.getChildren()) {
                    if (isRule(res, OceanParser.RULE_resource)) {
                        checkTryResource(res, holder);
                    }
                }
            } else if (isRule(child, OceanParser.RULE_catchClause)) {
                hasCatch = true;
            } else if (isFinallyBlock(child) || "finally".equals(child.getText().trim())) {
                hasFinally = true;
            }
        }
        if (!hasFinally && hasTokenChild(element, OceanLexer.FINALLY)) {
            hasFinally = true;
        }

        if (!hasCatch && !hasFinally && !hasResource) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'trying' statement must have at least one 'catch' or 'finally' block, or resource declaration")
                    .range(element.getTextRange())
                    .create();
        }

        // Subsumption & Duplicate check across catch clauses [E0006] and Catch variable shadowing [E0002]
        PsiElement encMethodOrCtor = null;
        PsiElement cur = element.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                encMethodOrCtor = cur;
                break;
            }
            if (isRule(cur, OceanParser.RULE_classDeclaration)) break;
            cur = cur.getParent();
        }

        Set<String> methodScopeVars = new HashSet<>();
        if (encMethodOrCtor != null) {
            for (PsiElement mChild : encMethodOrCtor.getChildren()) {
                if (isRule(mChild, OceanParser.RULE_parameterList)) {
                    for (PsiElement p : mChild.getChildren()) {
                        if (isRule(p, OceanParser.RULE_parameter)) {
                            PsiElement pId = directChildByRuleIndex(p, OceanParser.RULE_anyId);
                            if (pId != null && !"_".equals(pId.getText().trim())) {
                                methodScopeVars.add(pId.getText().trim());
                            }
                        }
                    }
                }
            }
            for (PsiElement varDecl : PsiTreeUtil.collectElements(encMethodOrCtor, e -> isRule(e, OceanParser.RULE_variableDeclarator))) {
                if (varDecl.getTextRange().getStartOffset() >= element.getTextRange().getStartOffset()) break;
                if (!isEnclosingScope(varDecl, element)) continue;
                PsiElement vId = directChildByRuleIndex(varDecl, OceanParser.RULE_anyId);
                if (vId != null && !"_".equals(vId.getText().trim())) {
                    methodScopeVars.add(vId.getText().trim());
                }
            }
        }

        List<String> seenPriorExceptions = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            if (isRule(child, OceanParser.RULE_catchClause)) {
                // Catch variable shadowing check [E0002]
                PsiElement catchVarId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (catchVarId != null) {
                    String catchVarName = catchVarId.getText().trim();
                    if (!"_".equals(catchVarName) && methodScopeVars.contains(catchVarName)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Catch variable '" + catchVarName + "' is already defined in this method scope.")
                                .range(catchVarId.getTextRange())
                                .create();
                    }
                }

                List<PsiElement> catchTypeNodes = new ArrayList<>();
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_type)) {
                        catchTypeNodes.add(c);
                    }
                }
                Set<String> seenInThisClause = new HashSet<>();
                for (PsiElement tn : catchTypeNodes) {
                    String excName = OceanScopeDiscovery.normalizeTypeName(tn.getText().trim());
                    // 1. Duplicate exception type in the same catch clause
                    if (!seenInThisClause.add(excName)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0006] Duplicate exception type in catch clause: '" + excName + "'")
                                .range(tn.getTextRange())
                                .create();
                        continue;
                    }
                    // 2. Subtype redundancy within same catch clause
                    boolean redundantInClause = false;
                    for (String sibling : seenInThisClause) {
                        if (!sibling.equals(excName) && isSubtypeOfException(element.getProject(), excName, sibling)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0006] Exception '" + excName + "' is already caught by alternative '" + sibling + "'")
                                    .range(tn.getTextRange())
                                    .create();
                            redundantInClause = true;
                            break;
                        }
                    }
                    if (redundantInClause) continue;

                    // 3. Subsumption across prior catch clauses
                    for (String prior : seenPriorExceptions) {
                        if (prior.equals(excName) || isSubtypeOfException(element.getProject(), excName, prior)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0006] Exception '" + excName + "' has already been caught by prior catch clause '" + prior + "'")
                                    .range(tn.getTextRange())
                                    .create();
                            break;
                        }
                    }
                }
                for (PsiElement tn : catchTypeNodes) {
                    seenPriorExceptions.add(OceanScopeDiscovery.normalizeTypeName(tn.getText().trim()));
                }
            }
        }
    }

    private boolean isSubtypeOfException(Project project, String subName, String superName) {
        if (subName == null || superName == null) return false;
        if (subName.equals(superName)) return true;
        if ("Throwable".equals(superName) || "java.lang.Throwable".equals(superName)) return true;
        if ("Exception".equals(superName) || "java.lang.Exception".equals(superName)) {
            return !"Throwable".equals(subName) && !"java.lang.Throwable".equals(subName);
        }
        PsiClass subCls = resolveJavaClassInContext(project, null, subName);
        PsiClass supCls = resolveJavaClassInContext(project, null, superName);
        if (subCls != null && supCls != null) {
            return subCls.isInheritor(supCls, true);
        }
        return false;
    }

    private void checkTryResource(PsiElement res, AnnotationHolder holder) {
        PsiElement expr = directChildByRuleIndex(res, OceanParser.RULE_expression);
        if (expr == null) {
            for (PsiElement c : res.getChildren()) {
                if (isRule(c, OceanParser.RULE_variableDeclaration)) {
                    for (PsiElement dec : c.getChildren()) {
                        if (isRule(dec, OceanParser.RULE_variableDeclarator)) {
                            expr = directChildByRuleIndex(dec, OceanParser.RULE_expression);
                            if (expr != null) break;
                        }
                    }
                    if (expr != null) break;
                }
            }
        }
        if (expr != null) {
            String inferredType = OceanTypeHelper.guessType(expr);
            if ((isPrimitiveType(inferredType) || "String".equals(inferredType) || "java.lang.String".equals(inferredType))) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible types: resource must implement java.lang.AutoCloseable, found '" + inferredType + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        }
    }

    private void checkLockStatement(PsiElement element, AnnotationHolder holder) {
        PsiElement expr = directChildByRuleIndex(element, OceanParser.RULE_expression);
        if (expr != null) {
            String text = expr.getText().trim();
            if ("null".equals(text)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Lock expression cannot be 'null'.")
                        .range(expr.getTextRange())
                        .create();
                return;
            }
            String inferredType = OceanTypeHelper.guessType(expr);
            if (isPrimitiveType(inferredType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Lock expression must be a reference type, found '" + inferredType + "'")
                        .range(expr.getTextRange())
                        .create();
            }
        }
    }

    private void checkStatement(PsiElement stmt, AnnotationHolder holder) {
        if (hasTokenChild(stmt, OceanLexer.THROW)) {
            checkThrowStatement(stmt, holder);
        }
        PsiElement ret = directChildByRuleIndex(stmt, OceanParser.RULE_returnStatement);
        if (ret != null) {
            checkReturnStatement(ret, holder);
        }
        PsiElement vStmt = directChildByRuleIndex(stmt, OceanParser.RULE_verifyStatement);
        if (vStmt != null) {
            checkVerifyStatement(vStmt, holder);
        }
        checkLabeledStatement(stmt, holder);
    }

    private void checkVerifyStatement(PsiElement verifyStmt, AnnotationHolder holder) {
        List<PsiElement> expressions = new ArrayList<>();
        for (PsiElement child : verifyStmt.getChildren()) {
            if (isRule(child, OceanParser.RULE_expression)) {
                expressions.add(child);
            }
        }
        if (expressions.isEmpty()) return;

        // Condition must be boolean [E0003]
        PsiElement condExpr = expressions.get(0);
        String condType = OceanTypeHelper.guessType(condExpr);
        if (isKnownNonBoolean(condType)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'verify' condition must be of type boolean: '" + condType + "'")
                    .range(condExpr.getTextRange())
                    .create();
        }

        // Optional detail message cannot be void [E0003]
        if (expressions.size() > 1) {
            PsiElement detailExpr = expressions.get(1);
            String detailType = OceanTypeHelper.guessType(detailExpr);
            if (isVoidType(detailType)) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'verify' detail message cannot be of type void")
                        .range(detailExpr.getTextRange())
                        .create();
            }
        }
    }

    private void checkLabeledStatement(PsiElement stmt, AnnotationHolder holder) {
        PsiElement colon = findTokenChild(stmt, OceanLexer.COLON);
        if (colon == null) return;
        PsiElement labelId = directChildByRuleIndex(stmt, OceanParser.RULE_anyId);
        if (labelId == null || labelId.getTextOffset() > colon.getTextOffset()) return;

        String label = labelId.getText().trim();
        // Check enclosing statements for duplicate label [E0002]
        PsiElement cur = stmt.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) ||
                isRule(cur, OceanParser.RULE_constructorDeclaration) ||
                isRule(cur, OceanParser.RULE_classDeclaration)) {
                break;
            }
            if (isRule(cur, OceanParser.RULE_statement)) {
                PsiElement pColon = findTokenChild(cur, OceanLexer.COLON);
                if (pColon != null) {
                    PsiElement pLabelId = directChildByRuleIndex(cur, OceanParser.RULE_anyId);
                    if (pLabelId != null && pLabelId.getTextOffset() < pColon.getTextOffset()) {
                        String pLabel = pLabelId.getText().trim();
                        if (label.equals(pLabel)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Label '" + label + "' is already defined in an enclosing scope")
                                    .range(labelId.getTextRange())
                                    .create();
                            break;
                        }
                    }
                }
            }
            cur = cur.getParent();
        }
    }

    private void checkThrowStatement(PsiElement stmt, AnnotationHolder holder) {
        PsiElement expr = directChildByRuleIndex(stmt, OceanParser.RULE_expression);
        if (expr != null) {
            String inferredType = OceanTypeHelper.guessType(expr);
            if ((isPrimitiveType(inferredType) || "String".equals(inferredType) || "java.lang.String".equals(inferredType))) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible types: throw expression must extend java.lang.Throwable, found '" + inferredType + "'")
                        .range(expr.getTextRange())
                        .create();
            } else if (inferredType != null) {
                checkExceptionHandledOrDeclared(stmt, inferredType, holder, expr.getTextRange());
            }
        }
    }

    private void checkVariableDeclaration(PsiElement decl, AnnotationHolder holder) {
        boolean isValue = hasTokenChild(decl, OceanLexer.VALUE) || hasModifierToken(decl, OceanLexer.FINAL);
        boolean isSync = hasModifierToken(decl, OceanLexer.SYNC) || hasTokenChild(decl, OceanLexer.SYNC);
        if (isValue && isSync) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Field cannot be declared both 'final' ('value') and 'sync' (volatile)")
                    .range(decl.getTextRange())
                    .create();
        }

        boolean isUntypedVar = hasTokenChild(decl, OceanLexer.VARIABLE);

        PsiElement typeElem = directChildByRuleIndex(decl, OceanParser.RULE_type);
        if (typeElem != null) {
            String typeText = typeElem.getText();
            int dimCount = 0;
            int idx = 0;
            while ((idx = typeText.indexOf("[]", idx)) != -1) {
                dimCount++;
                idx += 2;
            }
            if (dimCount > 255) {
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Array dimension limit exceeded (maximum 255 dimensions supported).")
                        .range(typeElem.getTextRange())
                        .create();
            }
        }

        PsiElement encMethodOrCtor = null;
        PsiElement cur = decl.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                encMethodOrCtor = cur;
                break;
            }
            if (isRule(cur, OceanParser.RULE_classDeclaration) || isRule(cur, OceanParser.RULE_interfaceDeclaration) || isRule(cur, OceanParser.RULE_enumDeclaration)) {
                break;
            }
            cur = cur.getParent();
        }

        for (PsiElement child : decl.getChildren()) {
            if (isRule(child, OceanParser.RULE_variableDeclarator)) {
                PsiElement idNode = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                String name = idNode != null ? idNode.getText().trim() : child.getText().trim();

                // Unreachable code check: if inside unreachable code in a block, suppress duplicate variable error
                if (isUnreachableInBlock(child)) {
                    continue;
                }

                // Method scope variable duplicate check [E0002]
                if (encMethodOrCtor != null && !name.isEmpty() && !"_".equals(name)) {
                    boolean isDuplicate = false;
                    // Check method parameters
                    for (PsiElement mChild : encMethodOrCtor.getChildren()) {
                        if (isRule(mChild, OceanParser.RULE_parameterList)) {
                            for (PsiElement p : mChild.getChildren()) {
                                if (isRule(p, OceanParser.RULE_parameter)) {
                                    PsiElement pId = directChildByRuleIndex(p, OceanParser.RULE_anyId);
                                    if (pId != null && name.equals(pId.getText().trim())) {
                                        isDuplicate = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    // Check prior variable declarations in the same method
                    if (!isDuplicate) {
                        for (PsiElement priorVar : PsiTreeUtil.collectElements(encMethodOrCtor, e -> isRule(e, OceanParser.RULE_variableDeclarator))) {
                            if (priorVar == child) {
                                break; // reached self in document order
                            }
                            PsiElement pId = directChildByRuleIndex(priorVar, OceanParser.RULE_anyId);
                            if (pId != null && name.equals(pId.getText().trim())) {
                                if (isEnclosingScope(priorVar, child)) {
                                    isDuplicate = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (isDuplicate) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Variable '" + name + "' is already defined in this method scope")
                                .range(idNode != null ? idNode.getTextRange() : child.getTextRange())
                                .create();
                    }
                }

                PsiElement init = directChildByRuleIndex(child, OceanParser.RULE_expression);
                if (init == null) {
                    if (isValue) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] 'value' (constant) declaration must have an initializer: '" + name + "'")
                                .range(child.getTextRange())
                                .withFix(new OceanInitializeVariableQuickFix(child))
                                .create();
                    } else if (isUntypedVar) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] 'variable' declaration of '" + name + "' must have an initializer")
                                .range(child.getTextRange())
                                .withFix(new OceanInitializeVariableQuickFix(child))
                                .create();
                    }
                } else {
                    // Check void expression initializer [E0002]
                    String initType = OceanTypeHelper.guessType(init);
                    if (isVoidType(initType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] 'void' expression cannot be assigned to a variable")
                                .range(init.getTextRange())
                                .create();
                    } else if (hasTokenChild(init, OceanLexer.ARROW)) {
                        // Lambda expression requires explicit functional interface target type [E0003]
                        if (isUntypedVar) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Lambda expression requires an explicit target type (functional interface)")
                                    .range(init.getTextRange())
                                    .create();
                        }
                    } else if (typeElem != null && !isUntypedVar) {
                        String declType = OceanTypeHelper.resolveDeclaredType(typeElem.getText().trim(), decl.getProject(), decl);
                        if (declType != null && initType != null && !"null".equals(initType) &&
                                !OceanTypeHelper.isAssignable(declType, initType, decl.getProject())) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Incompatible types: cannot convert '" + toHumanReadableType(initType) + "' to '" + toHumanReadableType(declType) + "'")
                                    .range(init.getTextRange())
                                    .create();
                        }
                    }
                }
            }
        }
    }

    private boolean isKnownNonBoolean(String type) {
        if (type == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(type);
        if (t.contains(".")) t = t.substring(t.lastIndexOf('.') + 1);
        return !"boolean".equalsIgnoreCase(t) && !"Boolean".equals(t) && !"Object".equals(t) && !"null".equals(t);
    }

    private boolean isPrimitiveType(String type) {
        if (type == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(type);
        if (t.contains(".")) t = t.substring(t.lastIndexOf('.') + 1);
        return switch (t) {
            case "int", "long", "short", "byte", "float", "double", "char", "boolean" -> true;
            default -> false;
        };
    }

    private boolean isBooleanType(String type) {
        if (type == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(type);
        if (t.contains(".")) t = t.substring(t.lastIndexOf('.') + 1);
        return "boolean".equalsIgnoreCase(t) || "Boolean".equals(t) || "Z".equals(t);
    }

    private boolean isVoidType(String type) {
        if (type == null) return false;
        String t = OceanScopeDiscovery.normalizeTypeName(type);
        if (t.contains(".")) t = t.substring(t.lastIndexOf('.') + 1);
        return "void".equalsIgnoreCase(t) || "Void".equals(t);
    }

    private boolean isAssignableTarget(PsiElement lhs) {
        if (lhs == null) return false;
        String text = lhs.getText().trim();
        if (text.isEmpty()) return false;
        if (text.startsWith("\"") || text.startsWith("$\"") || text.startsWith("'") ||
            "true".equals(text) || "false".equals(text) || "null".equals(text)) {
            return false;
        }
        if (Character.isDigit(text.charAt(0))) {
            return false;
        }
        if (text.endsWith(")") || hasTokenChild(lhs, OceanLexer.RPAREN)) {
            return false;
        }
        if (hasTokenChild(lhs, OceanLexer.LBRACK) && hasTokenChild(lhs, OceanLexer.RBRACK)) {
            return true;
        }
        if (hasTokenChild(lhs, OceanLexer.DOT)) {
            return true;
        }
        return lhs.getReference() != null ||
                directChildByRuleIndex(lhs, OceanParser.RULE_anyId) != null ||
                isRule(lhs, OceanParser.RULE_anyId) ||
                lhs instanceof OceanIdReference ||
                !PsiTreeUtil.findChildrenOfType(lhs, OceanIdReference.class).isEmpty() ||
                text.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    private String getFilePackage(PsiElement element) {
        if (element == null) return "";
        PsiFile file = element.getContainingFile();
        if (file != null) {
            for (PsiElement child : file.getChildren()) {
                if (isRule(child, OceanParser.RULE_packageDeclaration)) {
                    PsiElement qid = directChildByRuleIndex(child, OceanParser.RULE_identifierList);
                    if (qid != null) return qid.getText().trim();
                    for (PsiElement c : child.getChildren()) {
                        if (isRule(c, OceanParser.RULE_anyId) || isRule(c, OceanParser.RULE_expression)) {
                            return c.getText().trim();
                        }
                    }
                }
            }
        }
        return "";
    }

    private void checkForStatement(PsiElement forStmt, AnnotationHolder holder) {
        PsiElement control = directChildByRuleIndex(forStmt, OceanParser.RULE_forControl);
        if (control == null) return;

        // Loop variable already defined in this method scope [E0002]
        PsiElement encMethodOrCtor = null;
        PsiElement cur = forStmt.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                encMethodOrCtor = cur;
                break;
            }
            if (isRule(cur, OceanParser.RULE_classDeclaration)) break;
            cur = cur.getParent();
        }

        if (encMethodOrCtor != null) {
            Set<String> methodScopeVars = new HashSet<>();
            for (PsiElement mChild : encMethodOrCtor.getChildren()) {
                if (isRule(mChild, OceanParser.RULE_parameterList)) {
                    for (PsiElement p : mChild.getChildren()) {
                        if (isRule(p, OceanParser.RULE_parameter)) {
                            PsiElement pId = directChildByRuleIndex(p, OceanParser.RULE_anyId);
                            if (pId != null && !"_".equals(pId.getText().trim())) {
                                methodScopeVars.add(pId.getText().trim());
                            }
                        }
                    }
                }
            }
            for (PsiElement varDecl : PsiTreeUtil.collectElements(encMethodOrCtor, e -> isRule(e, OceanParser.RULE_variableDeclarator))) {
                if (varDecl.getTextRange().getStartOffset() >= forStmt.getTextRange().getStartOffset()) break;
                if (!isEnclosingScope(varDecl, forStmt)) continue;
                PsiElement vId = directChildByRuleIndex(varDecl, OceanParser.RULE_anyId);
                if (vId != null && !"_".equals(vId.getText().trim())) {
                    methodScopeVars.add(vId.getText().trim());
                }
            }

            // Find loop variable in forControl
            for (PsiElement c : control.getChildren()) {
                if (isRule(c, OceanParser.RULE_anyId)) {
                    String loopVarName = c.getText().trim();
                    if (!"_".equals(loopVarName) && methodScopeVars.contains(loopVarName)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Loop variable '" + loopVarName + "' is already defined in this method scope.")
                                .range(c.getTextRange())
                                .create();
                    }
                    break;
                }
            }
        }

        // Range-based for loop: from ... to ...
        if (hasTokenChild(control, OceanLexer.FROM) || hasTokenChild(control, OceanLexer.TO)) {
            boolean afterFrom = false;
            boolean afterTo = false;
            for (PsiElement c : control.getChildren()) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti) {
                    if (ti.getANTLRTokenType() == OceanLexer.FROM) {
                        afterFrom = true;
                        continue;
                    } else if (ti.getANTLRTokenType() == OceanLexer.TO) {
                        afterFrom = false;
                        afterTo = true;
                        continue;
                    } else if (ti.getANTLRTokenType() == OceanLexer.WITH) {
                        afterTo = false;
                    }
                }
                if (afterFrom && isRule(c, OceanParser.RULE_expression)) {
                    String fromType = OceanTypeHelper.guessType(c);
                    if (fromType != null && !isNumericType(fromType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Range 'from' expression must be a numeric type: '" + fromType + "'")
                                .range(c.getTextRange())
                                .create();
                    }
                }
                if (afterTo && isRule(c, OceanParser.RULE_expression)) {
                    String toType = OceanTypeHelper.guessType(c);
                    if (toType != null && !isNumericType(toType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Range 'to' expression must be a numeric type: '" + toType + "'")
                                .range(c.getTextRange())
                                .create();
                    }
                }
            }
        }

        if (hasTokenChild(control, OceanLexer.WITH)) {
            boolean afterWith = false;
            for (PsiElement c : control.getChildren()) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.WITH) {
                    afterWith = true;
                    continue;
                }
                if (afterWith && isRule(c, OceanParser.RULE_expression)) {
                    String stepText = c.getText().trim();
                    if ("0".equals(stepText) || "0.0".equals(stepText)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] For loop step value cannot be 0.")
                                .range(c.getTextRange())
                                .create();
                    } else {
                        String stepType = OceanTypeHelper.guessType(c);
                        if (stepType != null && !isNumericType(stepType)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Range 'step' expression must be a numeric type: '" + stepType + "'")
                                    .range(c.getTextRange())
                                    .create();
                        }
                    }
                    break;
                }
            }
        }

        if (hasTokenChild(control, OceanLexer.IN)) {
            boolean afterIn = false;
            for (PsiElement c : control.getChildren()) {
                if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.IN) {
                    afterIn = true;
                    continue;
                }
                if (afterIn && isRule(c, OceanParser.RULE_expression)) {
                    String colType = OceanTypeHelper.guessType(c);
                    if (isPrimitiveType(colType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Loop expression must be an array or java.lang.Iterable: found '" + colType + "'")
                                .range(c.getTextRange())
                                .create();
                    }
                    break;
                }
            }
        }
    }

    private boolean isEnclosingScope(PsiElement priorElement, PsiElement currentElement) {
        if (priorElement == null || currentElement == null) return false;
        // Find the scope block of priorElement (e.g. RULE_block, RULE_methodDeclaration, etc.)
        PsiElement priorScope = priorElement;
        while (priorScope != null) {
            if (isRule(priorScope, OceanParser.RULE_block) ||
                isRule(priorScope, OceanParser.RULE_methodDeclaration) ||
                isRule(priorScope, OceanParser.RULE_constructorDeclaration)) {
                break;
            }
            priorScope = priorScope.getParent();
        }
        if (priorScope == null) return false;

        // Check if priorScope is an ancestor of currentElement
        PsiElement cur = currentElement;
        while (cur != null) {
            if (cur == priorScope) {
                return true;
            }
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                break;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private void checkAwaitExpression(PsiElement awaitToken, AnnotationHolder holder) {
        PsiElement cur = awaitToken.getParent();
        boolean insideAsync = false;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_methodDeclaration)) {
                if (hasModifierToken(cur, OceanLexer.ASYNC) || hasTokenChild(cur, OceanLexer.ASYNC)) {
                    insideAsync = true;
                }
                break;
            }
            cur = cur.getParent();
        }
        boolean isMainMethod = false;
        if (cur != null) {
            for (PsiElement child : cur.getChildren()) {
                if (isRule(child, OceanParser.RULE_anyId) && "main".equals(child.getText().trim())) {
                    isMainMethod = true;
                    break;
                }
            }
        }
        if (!insideAsync) {
            var builder = holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] 'await' expression is only allowed in 'async' functions.")
                    .range(awaitToken.getTextRange());
            if (!isMainMethod) {
                builder = builder.withFix(new OceanMakeMethodAsyncQuickFix(awaitToken));
            }
            builder.create();
        }

        // Check if operand is Future or CompletableFuture
        if (cur != null) {
            PsiElement expr = directChildByRuleIndex(cur, OceanParser.RULE_expression);
            if (expr == null) {
                // awaitToken might be inside expression, look for sibling or child expression
                for (PsiElement c : cur.getChildren()) {
                    if (isRule(c, OceanParser.RULE_expression) && c != awaitToken) {
                        expr = c;
                        break;
                    }
                }
            }
            if (expr != null) {
                String operandType = OceanTypeHelper.guessType(expr);
                if (operandType != null && !operandType.isEmpty() && !"Object".equals(operandType) && !"java.lang.Object".equals(operandType)) {
                    boolean isFuture = operandType.contains("Future") || operandType.contains("CompletableFuture") || operandType.contains("Task");
                    if (!isFuture && (isPrimitiveType(operandType) || "String".equals(operandType) || "java.lang.String".equals(operandType) || !operandType.contains("Future"))) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0003] Right-hand side of 'await' expression must be a CompletableFuture or future type.")
                                .range(expr.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    // =========================================================================
    // [E0007] Nullable Dereference & Safe-Call Check
    // =========================================================================
    private void checkNullableAccess(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;

        // Check for member access with DOT (not SAFE_DOT)
        ASTNode node = expr.getNode();
        if (node == null) return;

        ASTNode dotNode = null;
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() instanceof TokenIElementType ti) {
                if (ti.getANTLRTokenType() == OceanLexer.DOT) {
                    dotNode = child;
                    break;
                }
            }
        }

        if (dotNode != null && dotNode.getTextRange() != null) {
            // Find receiver element before dot
            PsiElement receiver = null;
            for (PsiElement c : expr.getChildren()) {
                if (c.getTextRange() != null && c.getTextRange().getEndOffset() <= dotNode.getTextRange().getStartOffset()) {
                    receiver = c;
                }
            }
            if (receiver != null) {
                String receiverText = receiver.getText().trim();
                // Exclude class literal or package prefixes
                if (!"this".equals(receiverText) && !"super".equals(receiverText)) {
                    String rType = OceanTypeHelper.guessType(receiver);
                    if (rType != null && (rType.endsWith("?") || "null".equals(rType))) {
                        if (!isVariableGuardedByNonNullCheck(receiver, receiverText)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0007] Direct member access on nullable value is not allowed. Use safe-call (?.) or null check")
                                    .range(dotNode.getTextRange())
                                    .create();
                        }
                    }
                }
            }
        }

        // Also check array/indexer access on nullable: receiver[index]
        ASTNode lbrackNode = null;
        for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.LBRACK) {
                lbrackNode = child;
                break;
            }
        }
        if (lbrackNode != null && lbrackNode.getTextRange() != null) {
            PsiElement receiver = null;
            for (PsiElement c : expr.getChildren()) {
                if (c.getTextRange() != null && c.getTextRange().getEndOffset() <= lbrackNode.getTextRange().getStartOffset()) {
                    receiver = c;
                }
            }
            if (receiver != null) {
                String receiverText = receiver.getText().trim();
                String rType = OceanTypeHelper.guessType(receiver);
                if (rType != null && (rType.endsWith("?") || "null".equals(rType))) {
                    if (!isVariableGuardedByNonNullCheck(receiver, receiverText)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0007] Direct element access is not allowed on nullable array or object")
                                .range(lbrackNode.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private boolean isVariableGuardedByNonNullCheck(PsiElement receiver, String varName) {
        if (varName == null || varName.isEmpty() || !Character.isJavaIdentifierStart(varName.charAt(0))) {
            return false;
        }

        // 1. Walk up the AST to check short-circuit && expressions or enclosing conditionals
        PsiElement current = receiver;
        int receiverStart = receiver.getTextRange().getStartOffset();

        while (!(current instanceof PsiFile)) {
            PsiElement parent = current.getParent();
            if (parent == null) break;

            // Short-circuit AND (e.g. `(len != null) && len.toString().equals("1")`)
            if (isRule(parent, OceanParser.RULE_expression)) {
                boolean hasAnd = false;
                for (ASTNode childNode = parent.getNode().getFirstChildNode(); childNode != null; childNode = childNode.getTreeNext()) {
                    if (childNode.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.LOGICAL_AND) {
                        hasAnd = true;
                        break;
                    }
                }
                if (hasAnd) {
                    // Check if any left-hand sibling/sub-expression before current checks `varName != null`
                    for (PsiElement sibling : parent.getChildren()) {
                        if (sibling.getTextRange().getEndOffset() <= current.getTextRange().getStartOffset()) {
                            if (expressionGuaranteesNonNull(sibling, varName)) {
                                return true;
                            }
                        }
                    }
                }

                // Ternary conditional expression: `cond ? thenExpr : elseExpr`
                // If receiver is in thenExpr and cond guarantees non-null
                ASTNode questionNode = null;
                for (ASTNode cNode = parent.getNode().getFirstChildNode(); cNode != null; cNode = cNode.getTreeNext()) {
                    if (cNode.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.QUESTION) {
                        questionNode = cNode;
                        break;
                    }
                }
                if (questionNode != null) {
                    int qOffset = questionNode.getTextRange().getStartOffset();
                    if (current.getTextRange().getStartOffset() > qOffset) {
                        // Check if it's before colon (then-branch)
                        ASTNode colonNode = null;
                        for (ASTNode cNode = questionNode.getTreeNext(); cNode != null; cNode = cNode.getTreeNext()) {
                            if (cNode.getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.COLON) {
                                colonNode = cNode;
                                break;
                            }
                        }
                        if (colonNode != null && current.getTextRange().getEndOffset() <= colonNode.getTextRange().getStartOffset()) {
                            for (PsiElement c : parent.getChildren()) {
                                if (c.getTextRange().getEndOffset() <= qOffset) {
                                    if (expressionGuaranteesNonNull(c, varName)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Enclosing if / while statement:
            // 1. `if (len != null) { ... }` (then-branch)
            // 2. `if (len == null) { ... } else { ... len.foo() ... }` (else-branch)
            if (isRule(parent, OceanParser.RULE_ifStatement)) {
                PsiElement cond = directChildByRuleIndex(parent, OceanParser.RULE_expression);
                if (cond != null && cond.getTextRange() != null && current.getTextRange() != null &&
                    cond.getTextRange().getEndOffset() <= current.getTextRange().getStartOffset()) {

                    // Check if current is in else-branch
                    PsiElement elseElement = null;
                    for (PsiElement c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
                        if (c.getNode() != null && c.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.ELSE) {
                            elseElement = c;
                            break;
                        }
                        if ("else".equals(c.getText().trim())) {
                            elseElement = c;
                            break;
                        }
                    }

                    if (elseElement != null && elseElement.getTextRange() != null &&
                        current.getTextRange().getStartOffset() >= elseElement.getTextRange().getEndOffset()) {
                        // In else-branch: guaranteed non-null if condition checks `varName == null`
                        if (expressionGuaranteesNullOnTrue(cond, varName)) {
                            return true;
                        }
                    } else {
                        // In then-branch: guaranteed non-null if condition checks `varName != null`
                        if (expressionGuaranteesNonNull(cond, varName)) {
                            return true;
                        }
                    }
                }
            } else if (isRule(parent, OceanParser.RULE_whileStatement)) {
                PsiElement cond = directChildByRuleIndex(parent, OceanParser.RULE_expression);
                if (cond != null && cond.getTextRange() != null && current.getTextRange() != null &&
                    cond.getTextRange().getEndOffset() <= current.getTextRange().getStartOffset()) {
                    if (expressionGuaranteesNonNull(cond, varName)) {
                        return true;
                    }
                }
            }

            // Early-exit / guard clauses in preceding statements within the same block:
            // e.g. `if (len == null) return;` or `if (len == null) throw ...;`
            if (isRule(parent, OceanParser.RULE_block) || isRule(parent, OceanParser.RULE_statement)) {
                for (PsiElement stmt : parent.getChildren()) {
                    if (stmt.getTextRange() != null && stmt.getTextRange().getEndOffset() <= receiverStart) {
                        if (isGuardClauseExitingOnNull(stmt, varName)) {
                            return true;
                        }
                    }
                }
            }

            current = parent;
        }

        return false;
    }

    private boolean expressionGuaranteesNonNull(PsiElement expr, String varName) {
        if (expr == null) return false;
        String text = expr.getText();
        if (!text.contains(varName)) return false;

        // Check if expression is directly `(varName != null)` or `null != varName`
        String qVar = Pattern.quote(varName);
        if (text.matches(".*\\b" + qVar + "\\s*!=\\s*null\\b.*") ||
            text.matches(".*\\bnull\\s*!=\\s*" + qVar + "\\b.*") ||
            text.matches(".*\\b" + qVar + "\\s+instanceof\\b.*")) {
            return true;
        }

        // Subtree check for binary expressions with `!= null` or `instanceof`
        for (PsiElement child : expr.getChildren()) {
            if (expressionGuaranteesNonNull(child, varName)) {
                return true;
            }
        }
        return false;
    }

    private boolean expressionGuaranteesNullOnTrue(PsiElement expr, String varName) {
        if (expr == null) return false;
        String text = expr.getText();
        if (!text.contains(varName)) return false;

        // Check if expression checks `varName == null` or `null == varName`
        String clean = text.replaceAll("\\s+", "");
        if (clean.contains(varName + "==null") || clean.contains("null==" + varName)) {
            return true;
        }

        for (PsiElement child : expr.getChildren()) {
            if (expressionGuaranteesNullOnTrue(child, varName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGuardClauseExitingOnNull(PsiElement stmt, String varName) {
        if (!isRule(stmt, OceanParser.RULE_ifStatement) && !isRule(stmt, OceanParser.RULE_statement)) {
            return false;
        }
        PsiElement ifStmt = isRule(stmt, OceanParser.RULE_ifStatement) ? stmt : directChildByRuleIndex(stmt, OceanParser.RULE_ifStatement);
        if (ifStmt == null) {
            for (PsiElement child : stmt.getChildren()) {
                if (isRule(child, OceanParser.RULE_ifStatement)) {
                    ifStmt = child;
                    break;
                }
            }
        }
        if (ifStmt == null) {
            return false;
        }

        PsiElement cond = directChildByRuleIndex(ifStmt, OceanParser.RULE_expression);
        if (cond == null || !cond.getText().contains(varName)) {
            return false;
        }

        // Find thenBranch (the first statement child of ifStatement)
        PsiElement thenBranch = null;
        for (PsiElement c : ifStmt.getChildren()) {
            if (isRule(c, OceanParser.RULE_statement)) {
                thenBranch = c;
                break;
            }
        }
        if (thenBranch == null) {
            return false;
        }

        // Check if thenBranch terminates execution: return, throw, skip, stop
        if (!isStatementTerminatingEarly(thenBranch)) {
            return false;
        }

        // When thenBranch terminates, if condition guarantees varName is null when true,
        // then following code execution guarantees varName is non-null.
        return expressionGuaranteesNullOnTrue(cond, varName);
    }

    private boolean isStatementTerminatingEarly(PsiElement element) {
        if (element == null) return false;
        if (isRule(element, OceanParser.RULE_returnStatement)) return true;

        if (element.getNode() != null) {
            IElementType elType = element.getNode().getElementType();
            if (elType instanceof TokenIElementType ti) {
                int tt = ti.getANTLRTokenType();
                if (tt == OceanLexer.RETURN || tt == OceanLexer.THROW || tt == OceanLexer.SKIP_KW || tt == OceanLexer.STOP_KW) {
                    return true;
                }
            }
        }

        if (isRule(element, OceanParser.RULE_block)) {
            // Check if any top-level statement in the block unconditionally terminates
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_statement)) {
                    if (isStatementTerminating(child) || allPathsReturn(child)) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (isRule(element, OceanParser.RULE_statement)) {
            return isStatementTerminating(element) || allPathsReturn(element);
        }

        return false;
    }

    // =========================================================================
    // [E0006] Checked Exception & Unreported Exception Check
    // =========================================================================
    private void checkMethodCallExceptions(PsiElement expr, AnnotationHolder holder) {
        if (!isRule(expr, OceanParser.RULE_expression)) return;
        // Check if this expression is a method call or constructor invocation
        for (PsiElement child : expr.getChildren()) {
            if (child instanceof OceanIdReference idRef) {
                PsiElement resolved = idRef.resolve();
                if (resolved instanceof PsiMethod psiMethod) {
                    PsiClassType[] thrownTypes = psiMethod.getThrowsList().getReferencedTypes();
                    for (PsiClassType thrown : thrownTypes) {
                        String canonicalText = thrown.getCanonicalText();
                        if (isCheckedException(expr.getProject(), canonicalText)) {
                            checkExceptionHandledOrDeclared(expr, canonicalText, holder, child.getTextRange());
                        }
                    }
                }
            }
        }
    }

    private void checkExceptionHandledOrDeclared(PsiElement site, String exceptionType, AnnotationHolder holder, TextRange range) {
        if (!isCheckedException(site.getProject(), exceptionType)) return;

        // Traverse up to see if caught in a try-catch block
        PsiElement cur = site.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_tryStatement)) {
                // Check catch clauses
                for (PsiElement child : cur.getChildren()) {
                    if (isRule(child, OceanParser.RULE_catchClause)) {
                        PsiElement typeNode = directChildByRuleIndex(child, OceanParser.RULE_type);
                        if (typeNode != null) {
                            String catchType = OceanTypeHelper.resolveDeclaredType(typeNode.getText().trim(), site.getProject(), child);
                            if (catchType != null && OceanScopeDiscovery.isSubclassOf(site.getProject(), exceptionType, catchType)) {
                                return; // Handled!
                            }
                        }
                    }
                }
            }

            // If we hit method/constructor boundary, check if declared in throws clause
            if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                PsiElement throwsNode = directChildByRuleIndex(cur, OceanParser.RULE_typeList);
                if (throwsNode != null) {
                    for (PsiElement t : throwsNode.getChildren()) {
                        if (isRule(t, OceanParser.RULE_type)) {
                            String declaredThrow = OceanTypeHelper.resolveDeclaredType(t.getText().trim(), site.getProject(), cur);
                            if (declaredThrow != null && OceanScopeDiscovery.isSubclassOf(site.getProject(), exceptionType, declaredThrow)) {
                                return; // Declared in throws!
                            }
                        }
                    }
                }
                // Escaped method without handling or declaration
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0006] Unreported exception '" + exceptionType + "'; must be caught or declared to be thrown")
                        .range(range != null ? range : site.getTextRange())
                        .create();
                return;
            }

            cur = cur.getParent();
        }
    }

    private boolean isCheckedException(Project project, String typeName) {
        if (typeName == null) return false;
        String raw = OceanScopeDiscovery.normalizeTypeName(typeName);
        if (raw.endsWith("?")) raw = raw.substring(0, raw.length() - 1);
        if ("RuntimeException".equals(raw) || "java.lang.RuntimeException".equals(raw) ||
            "Error".equals(raw) || "java.lang.Error".equals(raw) ||
            "Throwable".equals(raw) || "java.lang.Throwable".equals(raw)) {
            return false;
        }

        // Subclass of RuntimeException or Error?
        if (OceanScopeDiscovery.isSubclassOf(project, raw, "java.lang.RuntimeException") ||
            OceanScopeDiscovery.isSubclassOf(project, raw, "java.lang.Error")) {
            return false;
        }

        // If it subclasses Exception or Throwable, it is a checked exception
        return OceanScopeDiscovery.isSubclassOf(project, raw, "java.lang.Exception") ||
                OceanScopeDiscovery.isSubclassOf(project, raw, "java.lang.Throwable") ||
                raw.endsWith("Exception");
    }

    // =========================================================================
    // [E0001] Uninitialized Final / Value Fields Check
    // =========================================================================
    private void checkClassFieldInitialization(PsiElement cls, AnnotationHolder holder) {
        if (!isRule(cls, OceanParser.RULE_classDeclaration)) return;

        // Collect constructors and static initializer blocks
        List<PsiElement> constructors = new ArrayList<>();
        List<PsiElement> staticBlocks = new ArrayList<>();
        List<PsiElement> fieldDecls = new ArrayList<>();

        for (PsiElement child : cls.getChildren()) {
            if (isRule(child, OceanParser.RULE_constructorDeclaration)) {
                constructors.add(child);
            } else if (isRule(child, OceanParser.RULE_memberDeclaration)) {
                for (PsiElement m : child.getChildren()) {
                    if (isRule(m, OceanParser.RULE_fieldDeclaration)) {
                        fieldDecls.add(m);
                    } else if (isRule(m, OceanParser.RULE_constructorDeclaration)) {
                        constructors.add(m);
                    } else if (isRule(m, OceanParser.RULE_block)) {
                        if (hasModifierToken(child, OceanLexer.STATIC) || hasTokenChild(child, OceanLexer.STATIC)) {
                            staticBlocks.add(m);
                        }
                    }
                }
            } else if (isRule(child, OceanParser.RULE_fieldDeclaration)) {
                fieldDecls.add(child);
            }
        }

        // Collect class type parameters for static field check [E0010]
        Set<String> classTpNames = new HashSet<>();
        for (PsiElement child : cls.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeParameter)) {
                PsiElement tpId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (tpId != null) classTpNames.add(tpId.getText().trim());
            }
        }

        for (PsiElement field : fieldDecls) {
            boolean isValueOrFinal = hasTokenChild(field, OceanLexer.VALUE) ||
                                     hasTokenChild(field, OceanLexer.FINAL) ||
                                     hasModifierToken(field, OceanLexer.FINAL);
            boolean isStatic = hasModifierToken(field, OceanLexer.STATIC) || hasTokenChild(field, OceanLexer.STATIC);
            boolean isSync = hasModifierToken(field, OceanLexer.SYNC) || hasTokenChild(field, OceanLexer.LOCK);

            // [E0010] Field cannot be declared both 'final' and 'sync' (volatile)
            if (isValueOrFinal && isSync) {
                PsiElement fId = directChildByRuleIndex(field, OceanParser.RULE_anyId);
                String fName = fId != null ? fId.getText().trim() : field.getText().trim();
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Field cannot be declared both 'final' and 'sync' (volatile): '" + fName + "'")
                        .range(field.getTextRange())
                        .create();
            }

            // [E0010] Static fields cannot reference class type parameter
            if (isStatic && !classTpNames.isEmpty()) {
                PsiElement fType = directChildByRuleIndex(field, OceanParser.RULE_type);
                if (fType != null) {
                    String fTypeText = fType.getText().trim();
                    for (String ctp : classTpNames) {
                        if (typeTextContainsParam(fTypeText, ctp)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Static fields cannot reference class type parameter ('" + ctp + "')")
                                    .range(fType.getTextRange())
                                    .create();
                            break;
                        }
                    }
                }
            }

            if (!isValueOrFinal) continue;

            for (PsiElement child : field.getChildren()) {
                if (isRule(child, OceanParser.RULE_variableDeclarator)) {
                    PsiElement init = directChildByRuleIndex(child, OceanParser.RULE_expression);
                    if (init != null) continue; // Already initialized at declaration!

                    PsiElement idNode = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                    String fieldName = idNode != null ? idNode.getText().trim() : child.getText().trim();
                    TextRange range = idNode != null ? idNode.getTextRange() : child.getTextRange();

                    if (isStatic) {
                        boolean assignedInStaticBlock = false;
                        for (PsiElement sBlock : staticBlocks) {
                            if (isFieldAssignedInBlock(sBlock, fieldName)) {
                                assignedInStaticBlock = true;
                                break;
                            }
                        }
                        if (!assignedInStaticBlock) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0001] Static final field '" + fieldName + "' might not have been initialized")
                                    .range(range)
                                    .create();
                        }
                    } else {
                        // Instance field: must be initialized in all constructors
                        if (constructors.isEmpty()) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0001] Final field '" + fieldName + "' might not have been initialized")
                                    .range(range)
                                    .create();
                        } else {
                            boolean assignedInAllCtors = true;
                            for (PsiElement ctor : constructors) {
                                PsiElement ctorBlock = directChildByRuleIndex(ctor, OceanParser.RULE_block);
                                if (ctorBlock != null) {
                                    // If constructor delegates to another constructor via this(...), skip direct assignment requirement
                                    boolean delegatesViaThis = false;
                                    for (PsiElement stmt : ctorBlock.getChildren()) {
                                        if (isRule(stmt, OceanParser.RULE_statement)) {
                                            String st = stmt.getText().trim();
                                            if (st.startsWith("this(") || st.startsWith("this (")) {
                                                delegatesViaThis = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (delegatesViaThis) continue;
                                }
                                if (!isFieldAssignedInBlock(ctorBlock, fieldName)) {
                                    assignedInAllCtors = false;
                                    break;
                                }
                            }
                            if (!assignedInAllCtors) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0001] Final field '" + fieldName + "' might not have been initialized")
                                        .range(range)
                                        .create();
                            }
                        }
                    }
                }
            }
        }

        // [E0002] Illegal Forward Reference in Field Initializers
        List<String> declaredFieldNames = new ArrayList<>();
        List<PsiElement> fieldsWithInit = new ArrayList<>();
        Map<PsiElement, String> fieldNameMap = new HashMap<>();

        for (PsiElement field : fieldDecls) {
            for (PsiElement child : field.getChildren()) {
                if (isRule(child, OceanParser.RULE_variableDeclarator)) {
                    PsiElement idNode = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                    String fieldName = idNode != null ? idNode.getText().trim() : child.getText().trim();
                    declaredFieldNames.add(fieldName);
                    fieldNameMap.put(child, fieldName);
                    PsiElement init = directChildByRuleIndex(child, OceanParser.RULE_expression);
                    if (init != null) {
                        fieldsWithInit.add(child);
                    }
                }
            }
        }

        for (PsiElement declarator : fieldsWithInit) {
            String currentFieldName = fieldNameMap.get(declarator);
            PsiElement initExpr = directChildByRuleIndex(declarator, OceanParser.RULE_expression);
            if (initExpr == null) continue;

            int currentFieldIdx = declaredFieldNames.indexOf(currentFieldName);
            Set<String> forwardFieldNames = new HashSet<>();
            for (int k = currentFieldIdx + 1; k < declaredFieldNames.size(); k++) {
                forwardFieldNames.add(declaredFieldNames.get(k));
            }

            for (PsiElement ref : PsiTreeUtil.collectElements(initExpr, e -> e instanceof OceanIdReference || isRule(e, OceanParser.RULE_anyId))) {
                String refName = ref.getText().trim();
                // Check if this reference is unqualified (no receiver)
                PsiElement parent = ref.getParent();
                if (parent != null && parent.getText().trim().startsWith("this.")) {
                    continue; // explicit 'this.' access bypasses forward reference rule
                }
                if (refName.equals(currentFieldName)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Illegal forward reference: field '" + refName + "' cannot be referenced in its own initializer")
                            .range(ref.getTextRange())
                            .create();
                } else if (forwardFieldNames.contains(refName)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0002] Illegal forward reference: field '" + refName + "' has not been declared yet")
                            .range(ref.getTextRange())
                            .create();
                }
            }
        }
    }

    private boolean isFieldAssignedInBlock(PsiElement block, String fieldName) {
        if (block == null || fieldName == null) return false;
        final boolean[] assigned = new boolean[]{false};
        block.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (assigned[0]) return;
                if (isRule(element, OceanParser.RULE_assignment)) {
                    PsiElement lhs = element.getFirstChild();
                    if (lhs != null) {
                        String lhsText = lhs.getText().trim();
                        if (lhsText.equals(fieldName) || lhsText.equals("this." + fieldName)) {
                            assigned[0] = true;
                            return;
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        return assigned[0];
    }

    // =========================================================================
    // [E0013] Compilation Unit Duplicate Types & Conflicting Imports
    // =========================================================================
    private void checkCompilationUnit(PsiElement cu, AnnotationHolder holder) {
        Set<String> declaredTypeNames = new HashSet<>();
        Map<String, String> importedSimpleNames = new HashMap<>();

        for (PsiElement child : cu.getChildren()) {
            if (isRule(child, OceanParser.RULE_classDeclaration) ||
                isRule(child, OceanParser.RULE_interfaceDeclaration) ||
                isRule(child, OceanParser.RULE_enumDeclaration) ||
                isRule(child, OceanParser.RULE_annotationDeclaration)) {

                PsiElement idNode = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (idNode != null) {
                    String typeName = idNode.getText().trim();
                    if (!declaredTypeNames.add(typeName)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Duplicate type declaration: '" + typeName + "' is already defined in this compilation unit")
                                .range(idNode.getTextRange())
                                .create();
                    }
                }
            } else if (isRule(child, OceanParser.RULE_importStatement)) {
                // Collect import path and simple name
                List<PsiElement> anyIds = new ArrayList<>();
                for (PsiElement c = child.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (isRule(c, OceanParser.RULE_anyId)) {
                        anyIds.add(c);
                    }
                }
                if (!anyIds.isEmpty() && !child.getText().contains("*")) {
                    PsiElement lastId = anyIds.get(anyIds.size() - 1);
                    String simpleName = lastId.getText().trim();
                    String fullImport = child.getText().trim();
                    if (importedSimpleNames.containsKey(simpleName)) {
                        String existing = importedSimpleNames.get(simpleName);
                        if (!existing.equals(fullImport)) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "[E0013] Conflicting import declaration: simple name '" + simpleName + "' is already imported")
                                    .range(lastId.getTextRange())
                                    .create();
                        }
                    } else {
                        importedSimpleNames.put(simpleName, fullImport);
                    }
                }
            }
        }
    }

    // =========================================================================
    // [E0010] Type Parameter Bound Checks (No primitive type bounds & Forward References)
    // =========================================================================
    private void checkTypeParameter(PsiElement typeParam, AnnotationHolder holder) {
        // Collect sibling type parameters from the enclosing declaration
        PsiElement parent = typeParam.getParent();
        List<String> declaredTpNames = new ArrayList<>();
        int currentTpIdx = -1;
        if (parent != null) {
            for (PsiElement sibling : parent.getChildren()) {
                if (isRule(sibling, OceanParser.RULE_typeParameter)) {
                    PsiElement sId = directChildByRuleIndex(sibling, OceanParser.RULE_anyId);
                    if (sId != null) {
                        String sName = sId.getText().trim();
                        if (sibling == typeParam) {
                            currentTpIdx = declaredTpNames.size();
                        }
                        declaredTpNames.add(sName);
                    }
                }
            }
        }

        PsiElement myId = directChildByRuleIndex(typeParam, OceanParser.RULE_anyId);
        String myName = myId != null ? myId.getText().trim() : "T";

        boolean afterLowerBound = false;
        for (PsiElement child : typeParam.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti) {
                if (ti.getANTLRTokenType() == OceanLexer.LOWER_BOUND) {
                    afterLowerBound = true;
                    continue;
                }
            }
            if (isRule(child, OceanParser.RULE_type)) {
                String boundText = child.getText().trim();
                if (afterLowerBound) {
                    if (isPrimitiveType(boundText)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Type parameter bound cannot be a primitive type: '" + boundText + "'")
                                .range(child.getTextRange())
                                .create();
                    }
                } else {
                    if (isPrimitiveType(boundText)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Type parameter bound cannot be a primitive type: '" + boundText + "'")
                                .range(child.getTextRange())
                                .create();
                    } else if (currentTpIdx >= 0) {
                        // Check if boundText matches a type parameter declared after this one
                        for (int k = currentTpIdx + 1; k < declaredTpNames.size(); k++) {
                            String forwardName = declaredTpNames.get(k);
                            if (forwardName.equals(boundText) || boundText.startsWith(forwardName + "<") || boundText.contains(" " + forwardName)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Type parameter '" + myName + "' bounds to '" + forwardName + "' which is declared after '" + myName + "'. Forward type parameter references in bounds are not allowed")
                                        .range(child.getTextRange())
                                        .create();
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkSafeVarargsAnnotation(PsiElement methodOrCtor, AnnotationHolder holder,
                                            boolean isStatic, boolean isPrivate, boolean isFinal, boolean isCtor) {
        PsiElement safeVarargsAnno = null;
        for (PsiElement child : methodOrCtor.getChildren()) {
            if (isRule(child, OceanParser.RULE_annotation)) {
                String text = child.getText();
                if (text.contains("SafeVarargs")) {
                    safeVarargsAnno = child;
                    break;
                }
            }
        }
        if (safeVarargsAnno == null) return;

        boolean hasVarargs = false;
        for (PsiElement child : methodOrCtor.getChildren()) {
            if (isRule(child, OceanParser.RULE_parameterList)) {
                for (PsiElement p : child.getChildren()) {
                    if (isRule(p, OceanParser.RULE_parameter) && p.getText().contains("...")) {
                        hasVarargs = true;
                        break;
                    }
                }
            }
        }

        if (!hasVarargs) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] @SafeVarargs annotation can only be applied to methods or constructors with varargs parameter")
                    .range(safeVarargsAnno.getTextRange())
                    .create();
        } else if (!isStatic && !isFinal && !isPrivate && !isCtor) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] @SafeVarargs annotation cannot be applied to overridable methods. Method must be 'static', 'final', 'private', or a constructor")
                    .range(safeVarargsAnno.getTextRange())
                    .create();
        }
    }

    // =========================================================================
    // [E0010] Interface Inheritance Conflicts (Diamond Defaults & Return Types)
    // =========================================================================
    private void checkInterfaceInheritanceConflicts(PsiElement typeDecl, AnnotationHolder holder,
                                                    String typeName, PsiElement nameId,
                                                    List<String> directInterfaceNames, boolean isInterface) {
        if (directInterfaceNames == null || directInterfaceNames.size() < 2) return;

        // Map method signature -> Map<InterfaceName, MethodReturnAndDefaultInfo>
        record MethodInfo(String ifaceName, String returnType, boolean isDefault) {}
        Map<String, List<MethodInfo>> methodSignatures = new LinkedHashMap<>();

        Project project = typeDecl.getProject();
        for (String ifaceName : directInterfaceNames) {
            PsiElement oIface = findClassInSameFileOrProject(project, typeDecl, ifaceName);
            if (isRule(oIface, OceanParser.RULE_interfaceDeclaration)) {
                for (PsiElement child : oIface.getChildren()) {
                    if (isRule(child, OceanParser.RULE_methodDeclaration)) {
                        PsiElement mId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                        if (mId == null) continue;
                        String mName = mId.getText().trim();
                        PsiElement pList = directChildByRuleIndex(child, OceanParser.RULE_parameterList);
                        int pCount = 0;
                        if (pList != null) {
                            for (PsiElement p : pList.getChildren()) {
                                if (isRule(p, OceanParser.RULE_parameter)) pCount++;
                            }
                        }
                        String sigKey = mName + "/" + pCount;
                        PsiElement retTypeElem = directChildByRuleIndex(child, OceanParser.RULE_type);
                        String retType = retTypeElem != null ? retTypeElem.getText().trim() : "void";
                        boolean isDefault = hasModifierToken(child, OceanLexer.DEFAULT);

                        methodSignatures.computeIfAbsent(sigKey, k -> new ArrayList<>())
                                .add(new MethodInfo(ifaceName, retType, isDefault));
                    }
                }
            } else {
                PsiClass jIface = resolveJavaClassInContext(project, typeDecl, ifaceName);
                if (jIface != null && jIface.isInterface()) {
                    for (PsiMethod jm : jIface.getMethods()) {
                        if (jm.isConstructor() || jm.hasModifierProperty(PsiModifier.STATIC)) continue;
                        String sigKey = jm.getName() + "/" + jm.getParameterList().getParametersCount();
                        String retType = jm.getReturnType() != null ? jm.getReturnType().getPresentableText() : "void";
                        boolean isDefault = jm.hasModifierProperty(PsiModifier.DEFAULT);
                        methodSignatures.computeIfAbsent(sigKey, k -> new ArrayList<>())
                                .add(new MethodInfo(ifaceName, retType, isDefault));
                    }
                }
            }
        }

        // Set of methods overridden directly in typeDecl
        Set<String> declaredMethodSigs = new HashSet<>();
        for (PsiElement child : typeDecl.getChildren()) {
            if (isRule(child, OceanParser.RULE_methodDeclaration)) {
                PsiElement mId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (mId != null) {
                    String mName = mId.getText().trim();
                    PsiElement pList = directChildByRuleIndex(child, OceanParser.RULE_parameterList);
                    int pCount = 0;
                    if (pList != null) {
                        for (PsiElement p : pList.getChildren()) {
                            if (isRule(p, OceanParser.RULE_parameter)) pCount++;
                        }
                    }
                    declaredMethodSigs.add(mName + "/" + pCount);
                }
            }
        }

        TextRange targetRange = nameId != null ? nameId.getTextRange() : typeDecl.getTextRange();

        for (Map.Entry<String, List<MethodInfo>> entry : methodSignatures.entrySet()) {
            String sigKey = entry.getKey();
            List<MethodInfo> list = entry.getValue();
            if (list.size() < 2) continue;

            String methodName = sigKey.substring(0, sigKey.indexOf('/'));

            // 1. Conflicting return types check
            MethodInfo first = list.get(0);
            for (int i = 1; i < list.size(); i++) {
                MethodInfo other = list.get(i);
                if (!first.ifaceName.equals(other.ifaceName)) {
                    if (!first.returnType.equals(other.returnType) &&
                        !isSubtypeOfException(project, first.returnType, other.returnType) &&
                        !isSubtypeOfException(project, other.returnType, first.returnType)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] '" + typeName + "' inherits conflicting return types for method '" + methodName + "' from interfaces '" + first.ifaceName + "' and '" + other.ifaceName + "' ('" + first.returnType + "' and '" + other.returnType + "')")
                                .range(targetRange)
                                .create();
                        return;
                    }
                }
            }

            // 2. Diamond default method conflict check
            List<MethodInfo> defaultProviders = new ArrayList<>();
            for (MethodInfo m : list) {
                if (m.isDefault) {
                    defaultProviders.add(m);
                }
            }

            if (defaultProviders.size() > 1 && !declaredMethodSigs.contains(sigKey)) {
                MethodInfo d1 = defaultProviders.get(0);
                MethodInfo d2 = defaultProviders.get(1);
                String entityKind = isInterface ? "Interface" : "Class";
                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] " + entityKind + " '" + typeName + "' inherits unrelated defaults for '" + methodName + "' from interfaces '" + d1.ifaceName + "' and '" + d2.ifaceName + "'. It must be overridden in '" + typeName + "'")
                        .range(targetRange)
                        .create();
            }
        }
    }

    // =========================================================================
    // [E0010] Ambiguous Interface Field Inheritance
    // =========================================================================
    private void checkAmbiguousInterfaceFieldReference(PsiElement element, PsiElement resolved, AnnotationHolder holder) {
        if (element == null || resolved == null) return;
        // Only unqualified references
        PsiElement prev = element.getPrevSibling();
        while (prev != null && prev.getText().trim().isEmpty()) prev = prev.getPrevSibling();
        if (prev != null && (".".equals(prev.getText().trim()) || "?.".equals(prev.getText().trim()) || "::".equals(prev.getText().trim()))) {
            return;
        }

        // Check if resolved element is a field or variableDeclarator
        boolean isField = resolved instanceof OceanFieldDeclaration || resolved instanceof PsiField;
        if (!isField && resolved instanceof OceanVariableDeclarator declarator && declarator.isField()) {
            isField = true;
        }
        if (!isField) return;

        // Find enclosing class of this reference
        PsiElement encClass = findEnclosingClass(element);
        if (encClass == null) return;

        // Check implemented interfaces
        List<String> ifaceNames = extractImplementsNames(encClass);
        if (ifaceNames.size() < 2) return;

        String fieldName = element.getText().trim();
        List<String> providingInterfaces = new ArrayList<>();
        Project project = element.getProject();

        for (String ifaceName : ifaceNames) {
            PsiElement oIface = findClassInSameFileOrProject(project, encClass, ifaceName);
            if (isRule(oIface, OceanParser.RULE_interfaceDeclaration)) {
                for (PsiElement child : oIface.getChildren()) {
                    if (isRule(child, OceanParser.RULE_fieldDeclaration)) {
                        for (PsiElement c : child.getChildren()) {
                            if (isRule(c, OceanParser.RULE_variableDeclarator)) {
                                PsiElement id = directChildByRuleIndex(c, OceanParser.RULE_anyId);
                                if (id != null && fieldName.equals(id.getText().trim())) {
                                    providingInterfaces.add(ifaceName);
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                PsiClass jIface = resolveJavaClassInContext(project, encClass, ifaceName);
                if (jIface != null && jIface.isInterface()) {
                    for (PsiField jf : jIface.getFields()) {
                        if (fieldName.equals(jf.getName())) {
                            providingInterfaces.add(ifaceName);
                            break;
                        }
                    }
                }
            }
        }

        if (providingInterfaces.size() > 1) {
            String if1 = providingInterfaces.get(0);
            String if2 = providingInterfaces.get(1);
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Ambiguous field reference: '" + fieldName + "' is inherited from interfaces '" + if1 + "' and '" + if2 + "'. Qualify field to disambiguate")
                    .range(element.getTextRange())
                    .create();
        }
    }

    // =========================================================================
    // [E0001] Local Variable Definite Assignment Check
    // =========================================================================
    private boolean isLhsOfAssignment(PsiElement element) {
        if (element == null) return false;
        PsiElement cur = element;
        while (!(cur instanceof PsiFile)) {
            PsiElement p = cur.getParent();
            if (isRule(p, OceanParser.RULE_assignment)) {
                for (PsiElement child : p.getChildren()) {
                    if (child == cur) return true;
                    String ct = child.getText();
                    if ("=".equals(ct) || ct.endsWith("=")) return false;
                }
            }
            if (p == null || isRule(p, OceanParser.RULE_statement) || isRule(p, OceanParser.RULE_block)) {
                break;
            }
            cur = p;
        }
        return false;
    }

    private void checkDefiniteAssignment(PsiElement element, PsiElement resolved, AnnotationHolder holder) {
        if (element == null || resolved == null || holder == null) return;
        if (isLhsOfAssignment(element)) return;

        // Check if resolved is a local variable declarator
        PsiElement declarator = isRule(resolved, OceanParser.RULE_variableDeclarator) ? resolved : resolved.getParent();
        if (!isRule(declarator, OceanParser.RULE_variableDeclarator)) {
            return;
        }

        // Must be in a method or constructor or block, not a class-level field
        PsiElement methodOrCtor = PsiTreeUtil.getParentOfType(declarator, OceanMethodDeclaration.class);
        if (methodOrCtor == null) {
            PsiElement cur = declarator.getParent();
            boolean insideMethod = false;
            while (cur != null && !(cur instanceof PsiFile)) {
                if (isRule(cur, OceanParser.RULE_methodDeclaration) || isRule(cur, OceanParser.RULE_constructorDeclaration)) {
                    insideMethod = true;
                    break;
                }
                cur = cur.getParent();
            }
            if (!insideMethod) return;
        }

        // If declarator has an initializer, it is definitely assigned
        if (directChildByRuleIndex(declarator, OceanParser.RULE_expression) != null) {
            return;
        }

        // Check if there is an assignment to this variable before element's offset
        String varName = element.getText().trim();
        PsiElement block = null;
        PsiElement cur = element.getParent();
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_block)) {
                block = cur;
                break;
            }
            cur = cur.getParent();
        }

        boolean assignedBefore = false;
        if (block != null) {
            int elemOffset = element.getTextOffset();
            int declOffset = declarator.getTextOffset();
            for (PsiElement child : block.getChildren()) {
                if (child.getTextOffset() > declOffset && child.getTextOffset() < elemOffset) {
                    if (isRule(child, OceanParser.RULE_statement)) {
                        String stText = child.getText().trim();
                        if (Pattern.compile("^\\b" + Pattern.quote(varName) + "\\s*=").matcher(stText).find()) {
                            assignedBefore = true;
                            break;
                        }
                    }
                }
            }
        }

        if (!assignedBefore) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0001] Variable '" + varName + "' might not have been initialized")
                    .range(element.getTextRange())
                    .create();
        }
    }

    // =========================================================================
    // [E0002] Helper to determine if element is a Try-With-Resources resource
    // =========================================================================
    private boolean isTryWithResourcesResource(PsiElement element) {
        if (element == null) return false;
        PsiElement cur = element;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isRule(cur, OceanParser.RULE_resource)) {
                return true;
            }
            if (isRule(cur, OceanParser.RULE_statement) || isRule(cur, OceanParser.RULE_block)) {
                break;
            }
            cur = cur.getParent();
        }
        return false;
    }

    // =========================================================================
    // [E0010] Static Method Class Type Parameter Reference Check
    // =========================================================================
    private void checkStaticMethodClassTypeParameter(PsiElement method, boolean isStatic, AnnotationHolder holder) {
        if (!isStatic) return;
        PsiElement encClass = findEnclosingClass(method);
        if (encClass == null) return;
        if (!isRule(encClass, OceanParser.RULE_classDeclaration) && !isRule(encClass, OceanParser.RULE_interfaceDeclaration)) {
            return;
        }

        // Collect class type parameters
        List<String> classTpNames = new ArrayList<>();
        for (PsiElement child : encClass.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeParameter)) {
                PsiElement id = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (id != null) {
                    classTpNames.add(id.getText().trim());
                }
            }
        }
        if (classTpNames.isEmpty()) return;

        // Collect method's own type parameters (shadowing class type parameters)
        Set<String> methodTpNames = new HashSet<>();
        for (PsiElement child : method.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeParameter)) {
                PsiElement id = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                if (id != null) {
                    methodTpNames.add(id.getText().trim());
                }
            }
        }

        // Check return type
        PsiElement returnType = directChildByRuleIndex(method, OceanParser.RULE_type);
        if (returnType != null) {
            String retText = returnType.getText().trim();
            for (String ctp : classTpNames) {
                if (!methodTpNames.contains(ctp) && typeTextContainsParam(retText, ctp)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Static methods cannot reference class type parameter ('" + ctp + "')")
                            .range(returnType.getTextRange())
                            .create();
                    break;
                }
            }
        }

        // Check method parameters
        PsiElement paramList = directChildByRuleIndex(method, OceanParser.RULE_parameterList);
        if (paramList != null) {
            for (PsiElement p : paramList.getChildren()) {
                if (isRule(p, OceanParser.RULE_parameter)) {
                    PsiElement pType = directChildByRuleIndex(p, OceanParser.RULE_type);
                    if (pType != null) {
                        String pTypeText = pType.getText().trim();
                        for (String ctp : classTpNames) {
                            if (!methodTpNames.contains(ctp) && typeTextContainsParam(pTypeText, ctp)) {
                                holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Static methods cannot reference class type parameter ('" + ctp + "')")
                                        .range(pType.getTextRange())
                                        .create();
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // [E0010] Variance Position Checks (+T in param, -T in return)
    // =========================================================================
    private void checkVariancePosition(PsiElement method, boolean isStatic, AnnotationHolder holder) {
        if (isStatic) return;
        PsiElement encClass = findEnclosingClass(method);
        if (encClass == null) return;
        if (!isRule(encClass, OceanParser.RULE_classDeclaration) && !isRule(encClass, OceanParser.RULE_interfaceDeclaration)) {
            return;
        }

        PsiElement clsId = directChildByRuleIndex(encClass, OceanParser.RULE_anyId);
        String clsName = clsId != null ? clsId.getText().trim() : "Class";

        PsiElement methodId = directChildByRuleIndex(method, OceanParser.RULE_anyId);
        String methodName = methodId != null ? methodId.getText().trim() : "method";

        for (PsiElement child : encClass.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeParameter)) {
                String fullTpText = child.getText().trim();
                boolean isCovariant = fullTpText.startsWith("+");
                boolean isContravariant = fullTpText.startsWith("-");
                if (!isCovariant && !isContravariant) continue;

                PsiElement tpId = directChildByRuleIndex(child, OceanParser.RULE_anyId);
                String tpName = tpId != null ? tpId.getText().trim() : (fullTpText.length() > 1 ? fullTpText.substring(1) : fullTpText);

                if (isCovariant) {
                    // +T cannot occur in method parameter (contravariant position)
                    PsiElement paramList = directChildByRuleIndex(method, OceanParser.RULE_parameterList);
                    if (paramList != null) {
                        for (PsiElement p : paramList.getChildren()) {
                            if (isRule(p, OceanParser.RULE_parameter)) {
                                PsiElement pType = directChildByRuleIndex(p, OceanParser.RULE_type);
                                if (pType != null && typeTextContainsParam(pType.getText().trim(), tpName)) {
                                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Covariant type parameter '" + tpName + "' cannot occur in contravariant position (method parameter) in method '" + methodName + "' of class '" + clsName + "'")
                                            .range(pType.getTextRange())
                                            .create();
                                }
                            }
                        }
                    }
                } else {
                    // -T cannot occur in method return type (covariant position)
                    PsiElement returnType = directChildByRuleIndex(method, OceanParser.RULE_type);
                    if (returnType != null && typeTextContainsParam(returnType.getText().trim(), tpName)) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Contravariant type parameter '" + tpName + "' cannot occur in covariant position (return type) in method '" + methodName + "' of class '" + clsName + "'")
                                .range(returnType.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    private boolean typeTextContainsParam(String typeText, String paramName) {
        if (typeText == null || paramName == null) return false;
        String clean = typeText.trim();
        if (clean.equals(paramName) || clean.equals(paramName + "?") || clean.equals(paramName + "[]")) return true;
        return Pattern.compile("\\b" + Pattern.quote(paramName) + "\\b").matcher(clean).find();
    }

    // =========================================================================
    // [E0010] Helper to check if type is or extends java.lang.Throwable
    // =========================================================================
    private boolean isThrowableType(Project project, PsiElement context, String typeName) {
        if (typeName == null) return false;
        String raw = extractRawType(typeName);
        if ("java.lang.Throwable".equals(raw) || "java.lang.Exception".equals(raw) || "java.lang.Error".equals(raw) ||
            "RuntimeException".equals(raw) || "java.lang.RuntimeException".equals(raw) ||
            raw.endsWith("Exception") || raw.endsWith("Error") || raw.endsWith("Throwable")) {
            return true;
        }
        PsiElement oceanCls = findClassInSameFileOrProject(project, context, raw);
        if (isRule(oceanCls, OceanParser.RULE_classDeclaration)) {
            String ext = extractExtendsName(oceanCls);
            if (ext != null) {
                return isThrowableType(project, oceanCls, ext);
            }
        }
        PsiClass javaCls = resolveJavaClassInContext(project, context, raw);
        if (javaCls != null) {
            return OceanScopeDiscovery.isSubclassOf(project, javaCls.getQualifiedName(), "java.lang.Throwable");
        }
        return false;
    }

    // =========================================================================
    // [E0010] Helper to check superclass default (no-arg) constructor
    // =========================================================================
    private void checkSuperclassNoArgConstructor(PsiElement ctor, PsiElement encClass, String superName, AnnotationHolder holder) {
        Project project = encClass.getProject();
        String rawSuper = extractRawType(superName);
        PsiElement oSuper = findClassInSameFileOrProject(project, encClass, rawSuper);
        if (isRule(oSuper, OceanParser.RULE_classDeclaration)) {
            List<PsiElement> ctors = new ArrayList<>();
            for (PsiElement child : oSuper.getChildren()) {
                if (isRule(child, OceanParser.RULE_constructorDeclaration)) {
                    ctors.add(child);
                }
            }
            if (!ctors.isEmpty()) {
                boolean hasNoArg = false;
                for (PsiElement c : ctors) {
                    PsiElement pList = directChildByRuleIndex(c, OceanParser.RULE_parameterList);
                    if (pList == null) {
                        hasNoArg = true;
                        break;
                    }
                    int pCount = 0;
                    for (PsiElement p : pList.getChildren()) {
                        if (isRule(p, OceanParser.RULE_parameter)) pCount++;
                    }
                    if (pCount == 0) {
                        hasNoArg = true;
                        break;
                    }
                }
                if (!hasNoArg) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] No default (no-arg) constructor found in superclass '" + superName + "'")
                            .range(ctor.getTextRange())
                            .create();
                    holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] No suitable constructor found in superclass '" + superName + "'.")
                            .range(ctor.getTextRange())
                            .create();
                }
            }
        } else {
            PsiClass jSuper = resolveJavaClassInContext(project, encClass, rawSuper);
            if (jSuper != null && !jSuper.isInterface()) {
                PsiMethod[] jCtors = jSuper.getConstructors();
                if (jCtors.length > 0) {
                    boolean hasNoArg = false;
                    for (PsiMethod jc : jCtors) {
                        if (jc.getParameterList().getParametersCount() == 0) {
                            hasNoArg = true;
                            break;
                        }
                    }
                    if (!hasNoArg) {
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] No default (no-arg) constructor found in superclass '" + superName + "'")
                                .range(ctor.getTextRange())
                                .create();
                        holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] No suitable constructor found in superclass '" + superName + "'.")
                                .range(ctor.getTextRange())
                                .create();
                    }
                }
            }
        }
    }

    // =========================================================================
    // [E0005] Flow-Sensitive Return Path Analysis (allPathsReturn)
    // =========================================================================
    private boolean allPathsReturn(PsiElement element) {
        if (element == null) return false;
        if (hasUnbrokenInfiniteLoop(element)) return true;

        if (isRule(element, OceanParser.RULE_returnStatement)) {
            return true;
        }
        if (hasTokenChild(element, OceanLexer.THROW)) {
            return true;
        }
        if (isRule(element, OceanParser.RULE_block)) {
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_statement)) {
                    if (allPathsReturn(child)) return true;
                }
            }
            return false;
        }
        if (isRule(element, OceanParser.RULE_tryStatement)) {
            PsiElement tryBlock = null;
            PsiElement finallyBlock = null;
            List<PsiElement> catchBlocks = new ArrayList<>();
            boolean seenFinally = false;

            for (PsiElement child : element.getChildren()) {
                if ("finally".equals(child.getText().trim())) {
                    seenFinally = true;
                } else if (child.getNode() != null && child.getNode().getElementType() instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.FINALLY) {
                    seenFinally = true;
                } else if (isRule(child, OceanParser.RULE_block)) {
                    if (tryBlock == null) {
                        tryBlock = child;
                    } else if (seenFinally) {
                        finallyBlock = child;
                    }
                } else if (isRule(child, OceanParser.RULE_catchClause)) {
                    for (PsiElement cc : child.getChildren()) {
                        if (isRule(cc, OceanParser.RULE_block)) {
                            catchBlocks.add(cc);
                        }
                    }
                }
            }

            if (allPathsReturn(finallyBlock)) {
                return true;
            }
            if (allPathsReturn(tryBlock)) {
                boolean allCatchesReturn = true;
                for (PsiElement cb : catchBlocks) {
                    if (!allPathsReturn(cb)) {
                        allCatchesReturn = false;
                        break;
                    }
                }
                return allCatchesReturn;
            }
            return false;
        }
        if (isRule(element, OceanParser.RULE_ifStatement)) {
            PsiElement cond = directChildByRuleIndex(element, OceanParser.RULE_expression);
            boolean isConstTrue = cond != null && ("true".equals(cond.getText().trim()) || "(true)".equals(cond.getText().trim()));

            PsiElement thenBranch = null;
            PsiElement elseBranch = null;
            boolean seenThen = false;
            for (PsiElement ic : element.getChildren()) {
                if (isRule(ic, OceanParser.RULE_statement)) {
                    if (!seenThen) {
                        thenBranch = ic;
                        seenThen = true;
                    } else {
                        elseBranch = ic;
                        break;
                    }
                }
            }
            if (isConstTrue && thenBranch != null) {
                return allPathsReturn(thenBranch);
            }
            if (thenBranch != null && elseBranch != null) {
                return allPathsReturn(thenBranch) && allPathsReturn(elseBranch);
            }
            return false;
        }
        if (isRule(element, OceanParser.RULE_lockBlockStatement)) {
            PsiElement body = directChildByRuleIndex(element, OceanParser.RULE_block);
            return allPathsReturn(body);
        }
        if (isRule(element, OceanParser.RULE_switchStatement)) {
            boolean hasDefault = false;
            boolean allCasesReturn = true;
            int caseCount = 0;
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_defaultCase)) {
                    hasDefault = true;
                    if (!allPathsReturn(child)) {
                        allCasesReturn = false;
                    }
                } else if (isRule(child, OceanParser.RULE_switchCase)) {
                    caseCount++;
                    if (!allPathsReturn(child)) {
                        allCasesReturn = false;
                    }
                }
            }

            // If no default case, check if selector is an enum/sealed type with matching cases
            boolean isExhaustiveWithoutDefault = false;
            if (!hasDefault && caseCount > 0) {
                PsiElement expr = directChildByRuleIndex(element, OceanParser.RULE_expression);
                if (expr != null) {
                    String exprType = OceanTypeHelper.guessType(expr);
                    if (exprType != null) {
                        PsiElement cls = OceanScopeDiscovery.findOceanClass(element.getProject(), exprType);
                        if (isRule(cls, OceanParser.RULE_enumDeclaration)) {
                            // Check if all enum constants are covered
                            Set<String> constants = new HashSet<>();
                            for (PsiElement c : cls.getChildren()) {
                                if (isRule(c, OceanParser.RULE_enumConstant)) {
                                    constants.add(c.getText().trim());
                                }
                            }
                            if (caseCount >= constants.size() && !constants.isEmpty()) {
                                isExhaustiveWithoutDefault = true;
                            }
                        }
                    }
                }
            }

            return (hasDefault || isExhaustiveWithoutDefault) && caseCount > 0 && allCasesReturn;
        }
        if (isRule(element, OceanParser.RULE_switchCase) || isRule(element, OceanParser.RULE_defaultCase)) {
            for (PsiElement scChild : element.getChildren()) {
                if (isRule(scChild, OceanParser.RULE_statement) || isRule(scChild, OceanParser.RULE_block)) {
                    if (allPathsReturn(scChild)) return true;
                }
            }
            return false;
        }
        if (isRule(element, OceanParser.RULE_statement)) {
            for (PsiElement child : element.getChildren()) {
                if (isRule(child, OceanParser.RULE_ifStatement) || isRule(child, OceanParser.RULE_lockBlockStatement) || isRule(child, OceanParser.RULE_switchStatement)) {
                    return allPathsReturn(child);
                }
                if (allPathsReturn(child)) return true;
            }
        }
        return false;
    }

    public boolean isStatementTerminating(PsiElement stmt) {
        if (stmt == null) return false;
        if (isRule(stmt, OceanParser.RULE_returnStatement)) return true;
        if (hasTokenChild(stmt, OceanLexer.THROW)) return true;
        if (hasTokenChild(stmt, OceanLexer.SKIP_KW) || hasTokenChild(stmt, OceanLexer.STOP_KW)) return true;
        if (isRule(stmt, OceanParser.RULE_statement)) {
            if (hasTokenChild(stmt, OceanLexer.THROW) || hasTokenChild(stmt, OceanLexer.SKIP_KW) || hasTokenChild(stmt, OceanLexer.STOP_KW)) {
                return true;
            }
            PsiElement ret = directChildByRuleIndex(stmt, OceanParser.RULE_returnStatement);
            if (ret != null) return true;
            PsiElement childIf = directChildByRuleIndex(stmt, OceanParser.RULE_ifStatement);
            if (childIf != null && isStatementTerminating(childIf)) return true;
            PsiElement childLock = directChildByRuleIndex(stmt, OceanParser.RULE_lockBlockStatement);
            if (childLock != null && isStatementTerminating(childLock)) return true;
            PsiElement childBlock = directChildByRuleIndex(stmt, OceanParser.RULE_block);
            if (allPathsReturn(childBlock)) return true;
            PsiElement childTry = directChildByRuleIndex(stmt, OceanParser.RULE_tryStatement);
            if (allPathsReturn(childTry)) return true;
            PsiElement childSwitch = directChildByRuleIndex(stmt, OceanParser.RULE_switchStatement);
            if (allPathsReturn(childSwitch)) return true;
        }
        if (hasUnbrokenInfiniteLoop(stmt)) return true;
        if (isRule(stmt, OceanParser.RULE_lockBlockStatement)) {
            PsiElement body = directChildByRuleIndex(stmt, OceanParser.RULE_block);
            return allPathsReturn(body);
        }
        if (isRule(stmt, OceanParser.RULE_switchStatement)) {
            return allPathsReturn(stmt);
        }
        if (isRule(stmt, OceanParser.RULE_ifStatement)) {
            PsiElement cond = directChildByRuleIndex(stmt, OceanParser.RULE_expression);
            boolean isConstTrue = cond != null && ("true".equals(cond.getText().trim()) || "(true)".equals(cond.getText().trim()));
            PsiElement thenBranch = null;
            PsiElement elseBranch = null;
            boolean seenThen = false;
            for (PsiElement ic : stmt.getChildren()) {
                if (isRule(ic, OceanParser.RULE_statement)) {
                    if (!seenThen) {
                        thenBranch = ic;
                        seenThen = true;
                    } else {
                        elseBranch = ic;
                        break;
                    }
                }
            }
            if (isConstTrue && thenBranch != null) {
                return isStatementTerminating(thenBranch);
            }
            if (thenBranch != null && elseBranch != null) {
                return isStatementTerminating(thenBranch) && isStatementTerminating(elseBranch);
            }
        }
        if (isRule(stmt, OceanParser.RULE_block) || isRule(stmt, OceanParser.RULE_tryStatement)) {
            return allPathsReturn(stmt);
        }
        return false;
    }

    private boolean isUnreachableInBlock(PsiElement element) {
        if (element == null) return false;
        PsiElement cur = element;
        while (cur != null && !(cur instanceof PsiFile)) {
            PsiElement parent = cur.getParent();
            if (isRule(parent, OceanParser.RULE_block)) {
                boolean foundTerm = false;
                for (PsiElement sibling : parent.getChildren()) {
                    if (sibling == cur) {
                        if (foundTerm) return true;
                        break;
                    }
                    if (isRule(sibling, OceanParser.RULE_statement)) {
                        if (foundTerm) return true;
                        if (isStatementTerminating(sibling)) {
                            foundTerm = true;
                        }
                    }
                }
            }
            cur = parent;
        }
        return false;
    }

    private void checkType(PsiElement typeElem, AnnotationHolder holder) {
        if (typeElem == null) return;
        List<PsiElement> typeArgNodes = new ArrayList<>();
        PsiElement typeNameElem = directChildByRuleIndex(typeElem, OceanParser.RULE_typeName);
        if (typeNameElem == null) {
            typeNameElem = directChildByRuleIndex(typeElem, OceanParser.RULE_anyId);
        }
        for (PsiElement child : typeElem.getChildren()) {
            if (isRule(child, OceanParser.RULE_typeArguments)) {
                for (PsiElement c : child.getChildren()) {
                    if (isRule(c, OceanParser.RULE_type)) {
                        typeArgNodes.add(c);
                    }
                }
            } else if (isRule(child, OceanParser.RULE_type)) {
                // Nested generic argument
                typeArgNodes.add(child);
            }
        }

        if (typeNameElem == null || typeArgNodes.isEmpty()) return;
        String typeName = typeNameElem.getText().trim();
        String rawName = extractRawType(typeName);

        Project project = typeElem.getProject();
        int expectedParams = -1;

        PsiElement oceanClass = findClassInSameFileOrProject(project, typeElem, rawName);
        if (oceanClass != null) {
            int count = 0;
            for (PsiElement child : oceanClass.getChildren()) {
                if (isRule(child, OceanParser.RULE_typeParameter)) count++;
            }
            if (count > 0) expectedParams = count;
        } else {
            PsiClass javaClass = resolveJavaClassInContext(project, typeElem, rawName);
            if (javaClass != null && javaClass.hasTypeParameters()) {
                expectedParams = javaClass.getTypeParameters().length;
            }
        }

        if (expectedParams > 0 && expectedParams != typeArgNodes.size()) {
            holder.newAnnotation(HighlightSeverity.ERROR, "[E0010] Wrong number of type arguments for '" + rawName + "': expected " + expectedParams + ", found " + typeArgNodes.size() + ".")
                    .range(typeElem.getTextRange())
                    .create();
        }
    }

    private boolean isDeclarationOrPatternContext(PsiElement element) {
        if (element instanceof OceanNamedElement) {
            return true;
        }
        for (PsiElement p = element.getParent(); p != null && !(p instanceof PsiFile); p = p.getParent()) {
            if (p instanceof OceanNamedElement) {
                return true;
            }
            if (isRule(p, OceanParser.RULE_switchPattern) ||
                isRule(p, OceanParser.RULE_instanceofPattern) ||
                isRule(p, OceanParser.RULE_variableDeclarator) ||
                isRule(p, OceanParser.RULE_variableDeclaration) ||
                isRule(p, OceanParser.RULE_catchClause) ||
                isRule(p, OceanParser.RULE_forControl) ||
                isRule(p, OceanParser.RULE_forStatement) ||
                isRule(p, OceanParser.RULE_parameter) ||
                isRule(p, OceanParser.RULE_parameterList) ||
                isRule(p, OceanParser.RULE_identifierList) ||
                isRule(p, OceanParser.RULE_resource) ||
                isRule(p, OceanParser.RULE_fieldDeclaration)) {
                return true;
            }
            if (isRule(p, OceanParser.RULE_expression)) {
                // Once we hit an expression boundary that is not inside a pattern declaration,
                // check if it's an instanceof or pattern container
                PsiElement parent = p.getParent();
                return (isRule(parent, OceanParser.RULE_switchPattern) || isRule(parent, OceanParser.RULE_instanceofPattern));
            }
            if (isRule(p, OceanParser.RULE_statement) || isRule(p, OceanParser.RULE_block)) {
                break;
            }
        }
        return false;
    }
}


