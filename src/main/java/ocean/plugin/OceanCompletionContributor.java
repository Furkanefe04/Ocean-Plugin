package ocean.plugin;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiFormatUtilBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ProcessingContext;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.adaptor.lexer.TokenIElementType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.*;

public class OceanCompletionContributor extends CompletionContributor {
    private static final List<String> KEYWORDS = List.of(
            "abstract", "bool", "case", "catch", "class", "decreasing", "default",
            "else", "enum", "extends", "false", "final", "finally", "for", "from", "function",
            "if", "implements", "import", "in", "increasing", "interface", "lock", "new", "null",
            "return", "skip", "static", "stop", "super", "switch", "sync", "this",
            "to", "true", "trying", "value", "variable", "void", "while", "with", "package",
            "int", "long", "float", "double", "char", "byte", "short", "String",
            "data", "result", "throw", "throws", "instanceof", "do", "public", "private", "protected",
            "annotation", "sealed", "non-sealed", "restricts", "async", "await", "native", "when", "verify");

    public OceanCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result) {
                PsiElement position = parameters.getPosition();
                PsiElement dot = PsiTreeUtil.prevVisibleLeaf(position);
                if (dot == null && (".".equals(position.getText()) || "?.".equals(position.getText()))) {
                    dot = position;
                }
                Project project = parameters.getOriginalFile().getProject();
                List<PsiFile> allFiles = new ArrayList<>();
                PsiFile curFile = parameters.getPosition().getContainingFile();
                if (curFile != null) allFiles.add(curFile);
                PsiFile origFile = parameters.getOriginalFile();
                if (!allFiles.contains(origFile)) allFiles.add(origFile);
                for (PsiFile f : getAllOceanFiles(project)) {
                    if (!allFiles.contains(f)) allFiles.add(f);
                }

                if (isInsideImportStatement(position, dot)) {
                    addImportCompletions(project, result, dot, position, allFiles);
                    return;
                }

                if (dot != null && (dot.getText().equals(".") || dot.getText().equals("?."))) {
                    addMemberCompletions(parameters, result, dot, allFiles);
                } else {
                    PsiElement prev = PsiTreeUtil.prevVisibleLeaf(position);
                    boolean isNewContext = prev != null && prev.getText().equals("new");
                    boolean isExtendsContext = prev != null && prev.getText().equals("extends");
                    boolean isImplementsContext = prev != null && prev.getText().equals("implements");
                    boolean isAnnotationContext = prev != null && prev.getText().equals("@");

                    if (isAnnotationContext) {
                        addProjectWideClasses(project, result, false, false, allFiles);
                        return;
                    }

                    String varDeclType = resolvePrecedingTypeName(position);
                    if (varDeclType != null) {
                        suggestVariableNamesForType(varDeclType, result);
                        return;
                    }

                    for (String keyword : getKeywordsForContext(position)) {
                        result.addElement(PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(keyword).withBoldness(true), 1));
                    }

                    // 1. Local scope symbols
                    for (PsiElement symbol : OceanScopeDiscovery.findVisibleSymbols(position)) {
                        if (symbol instanceof PsiNamedElement) {
                            String name = ((PsiNamedElement) symbol).getName();
                            if (name != null) {
                                LookupElementBuilder builder = LookupElementBuilder.create(name)
                                        .withIcon(symbol instanceof OceanMethodDeclaration ? PlatformIcons.METHOD_ICON
                                                : PlatformIcons.VARIABLE_ICON)
                                        .withTypeText(symbol instanceof OceanMethodDeclaration ? "Method" : "Variable")
                                        .withBoldness(true);
                                result.addElement(PrioritizedLookupElement.withPriority(builder, 5));
                            }
                        }
                    }

                    // 1.5 Special: 'args' inside main()
                    if (isInsideMain(position)) {
                        result.addElement(PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create("args")
                                        .withIcon(PlatformIcons.PARAMETER_ICON)
                                        .withTypeText("String[]")
                                        .withBoldness(true),
                                6));
                    }

                    // Built-in Ocean helpers
                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanOutput")
                                     .withIcon(PlatformIcons.FUNCTION_ICON)
                                    .withTailText("(content)", true)
                                    .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanInput")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (built-in scanner type)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanMap")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Ocean dynamic key-value map)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanList")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Ocean dynamic list collection)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanSet")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Ocean unique elements set)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("Task")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Structured concurrency & virtual threads)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("ScopedValue")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Scoped value carrier)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanResult")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Result<T, E> wrapper)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanOptional")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Optional<T> wrapper)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanPair")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Pair<A, B> tuple)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanTriple")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Triple<A, B, C> tuple)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanIntList")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Primitive int list)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanLongList")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Primitive long list)", true),
                            2));

                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("OceanDoubleList")
                                    .withIcon(PlatformIcons.CLASS_ICON)
                                    .withTailText(" (Primitive double list)", true),
                            2));

                    // Collection Literals
                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("[]")
                                    .withPresentableText("[]")
                                    .withTailText(" (List Literal)", true)
                                    .withTypeText("OceanList")
                                    .withBoldness(true),
                            3));
                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("{ key: value }")
                                    .withPresentableText("{ key: value }")
                                    .withTailText(" (Map Literal)", true)
                                    .withTypeText("OceanMap")
                                    .withBoldness(true),
                            3));
                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create("#{}")
                                    .withPresentableText("#{}")
                                    .withTailText(" (Set Literal)", true)
                                    .withTypeText("OceanSet")
                                    .withBoldness(true),
                            3));

                    addProjectWideClasses(project, result,
                            isNewContext, isExtendsContext || isImplementsContext, allFiles);

                    AllClassesGetter.processJavaClasses(parameters, result.getPrefixMatcher(), true, (psiClass) -> {
                        String qName = psiClass.getQualifiedName();
                        String className = psiClass.getName();
                        if (className == null) return;
                        LookupElementBuilder builder = LookupElementBuilder.create(className)
                                .withIcon(psiClass.getIcon(0));
                        if (qName != null)
                            builder = builder.withTailText(" (" + qName + ")", true);
                        if (isNewContext) {
                            final String qn = qName;
                            final String parentClassName = className;
                            final boolean isAbstract = psiClass.isInterface() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT);
                            builder = builder.withInsertHandler((insertionContext, item) -> {
                                autoInsertOceanImport(insertionContext, qn);
                                ParenthesesInsertHandler.NO_PARAMETERS.handleInsert(insertionContext, item);
                                if (isAbstract) {
                                    scheduleAnonymousClassStubInsertion(insertionContext, parentClassName, qn);
                                }
                            });
                        } else {
                            builder = builder.withInsertHandler((insertionContext, item) -> {
                                if (qName != null && !qName.startsWith("java.lang.") && !qName.startsWith("ocean.stdlib.")
                                        && !isImported(insertionContext.getFile(), qName))
                                    addImport(insertionContext.getFile(), qName, insertionContext.getProject());
                            });
                        }
                        result.addElement(builder);
                    });
                }
            }
        });
    }

    private void addMemberCompletions(CompletionParameters parameters, CompletionResultSet result, PsiElement dot, List<PsiFile> allFiles) {
        Project project = parameters.getOriginalFile().getProject();
        String receiverExpr = extractReceiverExpressionText(dot);
        if (receiverExpr == null || receiverExpr.isEmpty())
            return;

        if (!"this".equals(receiverExpr) && !"super".equals(receiverExpr)) {
            addPostfixTemplates(result, receiverExpr, dot);
        }

        // ---- this. ----
        if (receiverExpr.equals("this")) {
            PsiElement cls = findEnclosingClassOrInterface(dot);
            if (cls != null) {
                String className = findNameForElement(cls);
                if (className != null) {
                    addLocalClassMemberCompletions(project, className, result, allFiles);
                    return;
                }
            }
        }

        // ---- super. ----
        if (receiverExpr.equals("super")) {
            String superType = OceanTypeHelper.guessTypeFromTextOrElement("super", dot);
            if (superType != null) {
                addLocalClassMemberCompletions(project, superType, result, allFiles);
                return;
            }
        }

        // ---- Typed variable / Chained expression ----
        String guessedType = OceanTypeHelper.guessTypeFromTextOrElement(receiverExpr, dot);
        if (guessedType != null) {
            addExtensionMethodCompletions(allFiles, guessedType, result);
            PsiClass javaClass = resolveJavaClass(project, guessedType, dot);
            if (javaClass != null) {
                // P2-B: Pass the full guessedType (with generics) so return types can be resolved
                addJavaClassMembersToResult(javaClass, result, false, guessedType, project);
            } else {
                // Ocean class or interface → full hierarchy chain
                addLocalClassMemberCompletions(project, guessedType, result, allFiles, dot);
            }
            return;
        }

        // ---- Built-in static primitives (Task, ScopedValue) ----
        if ("Task".equals(receiverExpr)) {
            addTaskStaticCompletions(result);
            return;
        }
        if ("ScopedValue".equals(receiverExpr)) {
            addScopedValueStaticCompletions(result);
            return;
        }

        // ---- Static class name / Enum name / Fallback ----
        PsiClass staticClass = resolveJavaClass(project, receiverExpr, dot);
        if (staticClass != null) {
            addJavaClassMembersToResult(staticClass, result, true);
        } else {
            addLocalEnumCompletions(project, allFiles, receiverExpr, result);
            addLocalClassMemberCompletions(project, receiverExpr, result, allFiles, dot);
            addExtensionMethodCompletions(allFiles, receiverExpr, result);
            suggestObjectMethods(project, result);
        }
    }

    private void addTaskStaticCompletions(CompletionResultSet result) {
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("run")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(Runnable action)", true)
                        .withTypeText("CompletableFuture<Void>")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("supply")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(Supplier<T> task)", true)
                        .withTypeText("CompletableFuture<T>")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("start")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(Runnable task)", true)
                        .withTypeText("Thread")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("sleep")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(long millis)", true)
                        .withTypeText("void")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("all")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(CompletableFuture<?>... futures)", true)
                        .withTypeText("CompletableFuture<Void>")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("race")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(CompletableFuture<?>... futures)", true)
                        .withTypeText("CompletableFuture<Object>")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("gather")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(List<Supplier<T>> suppliers)", true)
                        .withTypeText("List<T>")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("isVirtual")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("()", true)
                        .withTypeText("boolean")
                        .withInsertHandler(ParenthesesInsertHandler.NO_PARAMETERS)
                        .withBoldness(true), 10));
    }

    private void addScopedValueStaticCompletions(CompletionResultSet result) {
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("newInstance")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("()", true)
                        .withTypeText("ScopedValue<T>")
                        .withInsertHandler(ParenthesesInsertHandler.NO_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("where")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(ScopedValue<T> key, T value)", true)
                        .withTypeText("Carrier")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("runWhere")
                        .withIcon(PlatformIcons.METHOD_ICON)
                        .withTailText("(ScopedValue<T> key, T value, Runnable op)", true)
                        .withTypeText("void")
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                        .withBoldness(true), 10));
    }


    private String extractReceiverExpressionText(PsiElement dot) {
        if (dot == null) return null;
        PsiFile file = dot.getContainingFile();
        if (file == null) return null;
        String text = file.getText();
        int dotOffset = dot.getTextOffset();
        if (dotOffset <= 0 || dotOffset > text.length()) return null;

        int i = dotOffset - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i < 0) return null;

        int end = i + 1;
        int depthParen = 0;
        int depthBracket = 0;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ')') {
                depthParen++;
            } else if (c == '(') {
                if (depthParen > 0) depthParen--;
                else break;
            } else if (c == ']') {
                depthBracket++;
            } else if (c == '[') {
                if (depthBracket > 0) depthBracket--;
                else break;
            } else if (depthParen == 0 && depthBracket == 0) {
                if (!Character.isJavaIdentifierPart(c) && c != '.' && c != '?' && c != ':') {
                    break;
                }
            }
            i--;
        }
        int start = i + 1;
        if (start < end) {
            return text.substring(start, end).trim();
        }
        return null;
    }

    private void addLocalEnumCompletions(Project project, List<PsiFile> allFiles, String enumName, CompletionResultSet result) {
        boolean found = false;
        for (PsiFile file : allFiles) {
            if ((enumName + ".ocean").equals(file.getName())) {
                PsiElement[] enums = PsiTreeUtil.collectElements(file, e -> isRule(e, "enumDeclaration"));
                for (PsiElement en : enums) {
                    if (enumName.equals(findNameForElement(en))) {
                        found = true;
                        collectEnumMembers(project, en, result, allFiles);
                    }
                }
            }
        }
        if (!found) {
            for (PsiFile file : allFiles) {
                if ((enumName + ".ocean").equals(file.getName())) continue;
                PsiElement[] enums = PsiTreeUtil.collectElements(file, e -> isRule(e, "enumDeclaration"));
                for (PsiElement en : enums) {
                    if (enumName.equals(findNameForElement(en))) {
                        collectEnumMembers(project, en, result, allFiles);
                    }
                }
            }
        }
    }

    private void collectEnumMembers(Project project, PsiElement en, CompletionResultSet result, List<PsiFile> allFiles) {
        PsiElement[] constants = PsiTreeUtil.collectElements(en, e -> isRule(e, "enumConstant"));
        for (PsiElement c : constants) {
            String name = findNameForElement(c);
            if (name != null && !name.isEmpty()) {
                result.addElement(PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name)
                                .withIcon(PlatformIcons.ENUM_ICON)
                                .withTypeText("Enum Constant")
                                .withBoldness(true),
                        10));
            }
        }
        // Also add methods defined in the enum
        addClassMembers(project, en, findNameForElement(en), result, new HashSet<>(), allFiles);
        // Add standard enum methods (name, ordinal, values)
        result.addElement(LookupElementBuilder.create("name()").withIcon(PlatformIcons.METHOD_ICON).withTypeText("String"));
        result.addElement(LookupElementBuilder.create("ordinal()").withIcon(PlatformIcons.METHOD_ICON).withTypeText("int"));
        result.addElement(LookupElementBuilder.create("values()").withIcon(PlatformIcons.METHOD_ICON).withTypeText("Array"));
    }

    // =========================================================================
    // Local Ocean class member completion — walks the full class hierarchy
    // =========================================================================

    private void addLocalClassMemberCompletions(Project project, String className,
            CompletionResultSet result, List<PsiFile> allFiles) {
        addLocalClassMemberCompletions(project, className, result, new HashSet<>(), allFiles, null);
    }

    private void addLocalClassMemberCompletions(Project project, String className,
            CompletionResultSet result, List<PsiFile> allFiles, PsiElement context) {
        addLocalClassMemberCompletions(project, className, result, new HashSet<>(), allFiles, context);
    }

    /**
     * Recursive, cycle-safe version that follows extends/implements up the chain.
     */
    private void addLocalClassMemberCompletions(Project project, String className,
            CompletionResultSet result,
            Set<String> visited,
            List<PsiFile> allFiles) {
        addLocalClassMemberCompletions(project, className, result, visited, allFiles, null);
    }

    private void addLocalClassMemberCompletions(Project project, String className,
            CompletionResultSet result,
            Set<String> visited,
            List<PsiFile> allFiles,
            PsiElement context) {
        if (className == null || !visited.add(className))
            return;

        className = cleanGeneric(className);
        boolean foundInOcean = false;
        Set<String> addedMemberNames = new HashSet<>();

        // 1. Check context file first (in-memory editor file or nearest scope)
        PsiFile contextFile = context != null ? context.getContainingFile() : null;
        if (contextFile != null) {
            // A. If className is explicitly imported from Java, prioritize Java members
            OceanIdReference.OceanFileInfo fileInfo = OceanIdReference.getFileInfo(contextFile);
            for (String explicit : fileInfo.explicitImports) {
                if (explicit.endsWith("." + className) || explicit.equals(className)) {
                    PsiClass javaClass = resolveJavaClass(project, explicit, context);
                    if (javaClass != null) {
                        addJavaClassMembersToResult(javaClass, result, false);
                        suggestObjectMethods(project, result);
                        return;
                    }
                }
            }

            // B. Local Ocean class in context file
            PsiElement localCls = OceanScopeDiscovery.findClassDeep(contextFile, className);
            if (localCls != null) {
                foundInOcean = true;
                addClassMembers(project, localCls, className, result, visited, allFiles, addedMemberNames);
            }
        }

        // 2. Check OceanScopeDiscovery.findOceanClass (supports imports, packages, project index)
        if (!foundInOcean) {
            PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, className, contextFile);
            if (oceanCls != null) {
                foundInOcean = true;
                addClassMembers(project, oceanCls, className, result, visited, allFiles, addedMemberNames);
            }
        }

        // 3. Search through allFiles (including matching file names)
        if (!foundInOcean) {
            for (PsiFile file : allFiles) {
                if ((className + ".ocean").equals(file.getName())) {
                    PsiElement[] declarations = PsiTreeUtil.collectElements(file, e ->
                            isRule(e, "classDeclaration") || isRule(e, "interfaceDeclaration") || isRule(e, "enumDeclaration"));
                    for (PsiElement decl : declarations) {
                        if (className.equals(findNameForElement(decl))) {
                            foundInOcean = true;
                            addClassMembers(project, decl, className, result, visited, allFiles, addedMemberNames);
                        }
                    }
                }
            }
        }

        if (!foundInOcean) {
            for (PsiFile file : allFiles) {
                if ((className + ".ocean").equals(file.getName())) continue;
                PsiElement[] declarations = PsiTreeUtil.collectElements(file, e ->
                        isRule(e, "classDeclaration") || isRule(e, "interfaceDeclaration") || isRule(e, "enumDeclaration"));
                for (PsiElement decl : declarations) {
                    if (className.equals(findNameForElement(decl))) {
                        foundInOcean = true;
                        addClassMembers(project, decl, className, result, visited, allFiles, addedMemberNames);
                    }
                }
            }
        }

        // 4. Robust text-based scan of contextFile or allFiles if AST was incomplete during editing
        if (!foundInOcean || addedMemberNames.isEmpty()) {
            if (contextFile != null) {
                addClassMembersFromText(contextFile.getText(), className, result, addedMemberNames);
            }
            if (addedMemberNames.isEmpty()) {
                for (PsiFile file : allFiles) {
                    addClassMembersFromText(file.getText(), className, result, addedMemberNames);
                    if (!addedMemberNames.isEmpty()) {
                        foundInOcean = true;
                        break;
                    }
                }
            } else {
                foundInOcean = true;
            }
        }

        // Not an Ocean class or interface → try as Java
        if (!foundInOcean) {
            PsiClass javaClass = resolveJavaClass(project, className, context);
            if (javaClass != null)
                addJavaClassMembersToResult(javaClass, result, false);
        }

        // Suggest Object methods (toString, equals, hashCode, getClass)
        suggestObjectMethods(project, result);
    }

    private void addClassMembers(Project project, PsiElement cls, String className, CompletionResultSet result, Set<String> visited, List<PsiFile> allFiles) {
        addClassMembers(project, cls, className, result, visited, allFiles, new HashSet<>());
    }

    private void addClassMembers(Project project, PsiElement cls, String className, CompletionResultSet result, Set<String> visited, List<PsiFile> allFiles, Set<String> addedMemberNames) {
        // ---- Check primary constructor parameters (e.g. data class Point(int x, int y)) ----
        PsiElement paramList = findChildByRule(cls, "parameterList");
        if (paramList == null) paramList = findChildByRule(cls, "formalParameters");
        if (paramList != null) {
            PsiElement[] params = PsiTreeUtil.collectElements(paramList, e -> isRule(e, "parameter"));
            for (PsiElement p : params) {
                String pName = findNameForElement(p);
                if (pName != null && !pName.isEmpty() && addedMemberNames.add(pName)) {
                    PsiElement pTypeNode = findChildByRule(p, "type");
                    String pType = pTypeNode != null ? pTypeNode.getText().trim() : "variable";
                    LookupElementBuilder builder = LookupElementBuilder.create(pName)
                            .withBoldness(true)
                            .withIcon(PlatformIcons.VARIABLE_ICON)
                            .withTypeText(pType);
                    result.addElement(builder);
                }
            }
        }

        // ---- Own fields and methods ----
        PsiElement[] items = PsiTreeUtil.collectElements(cls,
                e -> isAnyRule(e, "fieldDeclaration", "methodDeclaration", "annotationMemberDeclaration")
                        || e instanceof OceanFieldDeclaration || e instanceof OceanMethodDeclaration);
        for (PsiElement item : items) {
            if (isRule(item, "fieldDeclaration") || item instanceof OceanFieldDeclaration) {
                String typeText = extractVariableType(item);
                for (PsiElement child : item.getChildren()) {
                    if (isRule(child, "variableDeclarator") || child instanceof OceanVariableDeclarator) {
                        PsiElement anyId = findChildByRule(child, "anyId");
                        String name = (anyId != null) ? anyId.getText().trim() : null;
                        if (name == null && child instanceof OceanNamedElement named) {
                            name = named.getName();
                        }
                        if (name == null || name.equals(className) || !addedMemberNames.add(name)) continue;
                        LookupElementBuilder builder = LookupElementBuilder.create(name)
                                .withBoldness(true)
                                .withIcon(PlatformIcons.VARIABLE_ICON)
                                .withTypeText(typeText);
                        result.addElement(builder);
                    }
                }
            } else {
                // methodDeclaration, annotationMemberDeclaration
                String name = findNameForElement(item);
                if (name == null && item instanceof OceanNamedElement named) {
                    name = named.getName();
                }
                if (name == null || name.equals(className) || !addedMemberNames.add(name))
                    continue;
                String paramStr = extractParameters(item);
                boolean noParams = "()".equals(paramStr.trim()) || "( )".equals(paramStr.trim());
                LookupElementBuilder builder = LookupElementBuilder.create(name)
                        .withBoldness(true)
                        .withIcon(PlatformIcons.FUNCTION_ICON)
                        .withTailText(paramStr, true)
                        .withTypeText(extractReturnType(item))
                        .withInsertHandler(noParams ? ParenthesesInsertHandler.NO_PARAMETERS : ParenthesesInsertHandler.WITH_PARAMETERS);
                result.addElement(builder);
            }
        }

        // If AST parsing missed members (e.g. during live typing inside class), do text scan of class body
        String clsText = cls.getText();
        if (clsText != null) {
            addClassMembersFromText(clsText, className, result, addedMemberNames);
        }

        // ---- Walk up: extends (Ocean or Java) ----
        String superName = extractExtendsName(cls);
        if (superName != null) {
            addLocalClassMemberCompletions(project, superName, result, visited, allFiles, cls);
        }

        // ---- Walk up: implements (both Ocean and Java interfaces) ----
        for (String ifaceName : extractImplementsNames(cls)) {
            addLocalClassMemberCompletions(project, ifaceName, result, visited, allFiles, cls);
        }
    }

    private void addClassMembersFromText(String text, String targetClassName, CompletionResultSet result, Set<String> addedMemberNames) {
        if (text == null || text.isBlank()) return;

        // Isolate class body if targetClassName is provided
        String body = text;
        if (targetClassName != null && !targetClassName.isEmpty()) {
            Pattern classPat = Pattern.compile("(?:class|interface|record|data\\s+class)\\s+" + Pattern.quote(targetClassName) + "[^{]*\\{([\\s\\S]*?)(?:\\n\\s*}|$)");
            Matcher classMat = classPat.matcher(text);
            if (classMat.find()) {
                body = classMat.group(1);
            }
        }

        // Find fields: Type name [= value]; or var name [= value];
        Pattern fieldPat = Pattern.compile("(?<=^|[;{}\\n])\\s*(?:(?:public|private|protected|static|final)\\s+)*([a-zA-Z_][a-zA-Z0-9_<>\\[\\]]*)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=;]");
        Matcher fm = fieldPat.matcher(body);
        while (fm.find()) {
            String type = fm.group(1).trim();
            String name = fm.group(2).trim();
            if (!KEYWORDS.contains(name) && !name.equals(targetClassName) && addedMemberNames.add(name)) {
                LookupElementBuilder builder = LookupElementBuilder.create(name)
                        .withBoldness(true)
                        .withIcon(PlatformIcons.VARIABLE_ICON)
                        .withTypeText(type);
                result.addElement(builder);
            }
        }

        // Find methods: [Type] name(...) [ { ... } ]
        Pattern methodPat = Pattern.compile("(?<=^|[;{}\\n])\\s*(?:(?:public|private|protected|static|final|async|override)\\s+)*(?:([a-zA-Z_][a-zA-Z0-9_<>\\[\\]]*)\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*(?:\\{|=>|;)");
        Matcher mm = methodPat.matcher(body);
        while (mm.find()) {
            String returnType = mm.group(1) != null ? mm.group(1).trim() : "void";
            String name = mm.group(2).trim();
            String params = mm.group(3) != null ? "(" + mm.group(3).trim() + ")" : "()";
            if (!KEYWORDS.contains(name) && !"main".equals(name) && !name.equals(targetClassName) && addedMemberNames.add(name)) {
                LookupElementBuilder builder = LookupElementBuilder.create(name)
                        .withBoldness(true)
                        .withIcon(PlatformIcons.FUNCTION_ICON)
                        .withTailText(params, true)
                        .withTypeText(returnType)
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS);
                result.addElement(builder);
            }
        }
    }

    private void addExtensionMethodCompletions(List<PsiFile> allFiles, String receiverType, CompletionResultSet result) {
        if (receiverType == null)
            return;
        String cleanReceiver = cleanGeneric(receiverType);

        for (PsiFile file : allFiles) {
            String text = file.getText();
            if (text == null || !text.contains("function") || !text.contains(cleanReceiver)) {
                continue;
            }
            PsiElement[] methods = PsiTreeUtil.collectElements(file, e -> isRule(e, "methodDeclaration"));
            for (PsiElement m : methods) {
                // Extension methods look like: (Type self).methodName(...)
                // They must be static to be used as extensions usually, but compiler allows them.
                // We check for the (Type DOT) prefix in methodDeclaration.
                PsiElement extType = extractExtensionType(m);
                if (extType != null && cleanGeneric(extType.getText()).equals(cleanReceiver)) {
                    String name = findNameForElement(m);
                    if (name == null)
                        continue;

                    String extParams = extractParameters(m);
                    boolean noExtParams = "()".equals(extParams.trim()) || "( )".equals(extParams.trim());
                    LookupElementBuilder builder = LookupElementBuilder.create(name)
                            .withIcon(PlatformIcons.FUNCTION_ICON)
                            .withItemTextItalic(true)
                            .withTailText(extParams + " (extension)", true)
                            .withTypeText(extractReturnType(m))
                            .withInsertHandler(noExtParams ? ParenthesesInsertHandler.NO_PARAMETERS : ParenthesesInsertHandler.WITH_PARAMETERS);

                    result.addElement(PrioritizedLookupElement.withPriority(builder, 10)); // Higher priority for extensions
                }
            }
        }
    }

    private PsiElement extractExtensionType(PsiElement methodNode) {
        // We need to look for a 'type' node that is immediately followed by a DOT '.'
        // and precedes the 'FUNCTION' or 'anyId' (name).
        PsiElement[] children = methodNode.getChildren();
        for (int i = 0; i < children.length - 1; i++) {
            if (isRule(children[i], "type") && children[i + 1].getText().equals(".")) {
                return children[i];
            }
        }
        return null;
    }

    // =========================================================================
    // Java class member additions (methods + fields)
    // =========================================================================

    private void addJavaClassMembersToResult(PsiClass javaClass, CompletionResultSet result,
            boolean onlyStatic) {
        // Backward-compatible call — no generic receiver type info
        addJavaClassMembersToResult(javaClass, result, onlyStatic, null, null);
    }

    /**
     * P2-B: Generic-aware overload.
     * When {@code guessedReceiverType} is non-null (e.g. "OceanList<String>"),
     * we call {@link OceanTypeHelper#findMemberType} to obtain the substituted
     * return type (String instead of E) and show it in the completion type-text.
     */
    private void addJavaClassMembersToResult(PsiClass javaClass, CompletionResultSet result,
            boolean onlyStatic, String guessedReceiverType, Project project) {
        for (PsiMethod m : javaClass.getAllMethods()) {
            if (m.isConstructor())
                continue;
            if (onlyStatic && !m.hasModifierProperty(PsiModifier.STATIC))
                continue;
            String params = PsiFormatUtil.formatMethod(m, PsiSubstitutor.EMPTY,
                    PsiFormatUtilBase.SHOW_PARAMETERS, PsiFormatUtilBase.SHOW_TYPE | PsiFormatUtilBase.SHOW_NAME);
            // P2-B: try generic-substituted return type first
            String retType = null;
            if (guessedReceiverType != null && project != null) {
                retType = OceanTypeHelper.findMemberType(guessedReceiverType, m.getName(), project);
            }
            if (retType == null) {
                retType = PsiFormatUtil.formatMethod(m, PsiSubstitutor.EMPTY, PsiFormatUtilBase.SHOW_TYPE, 0);
            }
            boolean noParams = m.getParameterList().isEmpty();
            result.addElement(LookupElementBuilder.createWithIcon(m)
                    .withTailText(params, true)
                    .withTypeText(retType)
                    .withInsertHandler(noParams ? ParenthesesInsertHandler.NO_PARAMETERS : ParenthesesInsertHandler.WITH_PARAMETERS));
        }
        for (PsiField f : javaClass.getAllFields()) {
            if (onlyStatic && !f.hasModifierProperty(PsiModifier.STATIC))
                continue;
            String fType = null;
            if (guessedReceiverType != null && project != null) {
                fType = OceanTypeHelper.findMemberType(guessedReceiverType, f.getName(), project);
            }
            if (fType == null) {
                fType = PsiFormatUtil.formatVariable(f, PsiFormatUtilBase.SHOW_TYPE, PsiSubstitutor.EMPTY);
            }
            result.addElement(LookupElementBuilder.createWithIcon(f).withTypeText(fType));
        }
    }


    // =========================================================================
    // Project-wide class listing
    // =========================================================================

    private void addProjectWideClasses(Project project, CompletionResultSet result,
            boolean isNewContext, boolean isExtendsContext, List<PsiFile> allFiles) {
        for (PsiFile file : allFiles) {
            String filePackage = extractOceanPackage(file);
            PsiElement[] classes = PsiTreeUtil.collectElements(file, e -> isRule(e, "classDeclaration"));
            for (PsiElement cls : classes) {
                String name = findNameForElement(cls);
                if (name == null)
                    continue;

                // Build qualified import path: package.ClassName
                final String qualifiedName = (filePackage != null) ? filePackage + "." + name : null;
                String tailText = (filePackage != null)
                        ? " (" + filePackage + " - " + file.getName() + ")"
                        : " (local: " + file.getName() + ")";

                LookupElementBuilder builder = LookupElementBuilder.create(name)
                        .withIcon(OceanIcons.ICON)
                        .withTailText(tailText, true)
                        .withBoldness(true);

                if (isNewContext) {
                    final String parentClassName = name;
                    final boolean isAbstract = isOceanAbstractClass(cls);
                    builder = builder.withInsertHandler((insertionContext, item) -> {
                        autoInsertOceanImport(insertionContext, qualifiedName);
                        ParenthesesInsertHandler.NO_PARAMETERS.handleInsert(insertionContext, item);
                        if (isAbstract) {
                            scheduleAnonymousClassStubInsertion(insertionContext, parentClassName, qualifiedName);
                        }
                    });
                } else if (isExtendsContext && isOceanAbstractClass(cls)) {
                    final String parentClassName = name;
                    builder = builder.withInsertHandler((insertionContext, item) -> {
                        autoInsertOceanImport(insertionContext, qualifiedName);
                        scheduleMethodStubInsertion(insertionContext, parentClassName);
                    });
                } else {
                    builder = builder.withInsertHandler((insertionContext, item) -> autoInsertOceanImport(insertionContext, qualifiedName));
                }
                result.addElement(builder);
            }

            // Also add interface and enum declarations with auto-import
            PsiElement[] interfaces = PsiTreeUtil.collectElements(file, e -> isRule(e, "interfaceDeclaration"));
            for (PsiElement iface : interfaces) {
                String name = findNameForElement(iface);
                if (name == null)
                    continue;
                final String qualifiedName2 = (filePackage != null) ? filePackage + "." + name : null;
                LookupElementBuilder builder = LookupElementBuilder.create(name)
                        .withIcon(PlatformIcons.INTERFACE_ICON)
                        .withTailText((filePackage != null) ? " (" + filePackage + ")" : " (local)", true)
                        .withBoldness(true);
                if (isNewContext) {
                    final String parentClassName = name;
                    builder = builder.withInsertHandler((insertionContext, item) -> {
                        autoInsertOceanImport(insertionContext, qualifiedName2);
                        ParenthesesInsertHandler.NO_PARAMETERS.handleInsert(insertionContext, item);
                        scheduleAnonymousClassStubInsertion(insertionContext, parentClassName, qualifiedName2);
                    });
                } else {
                    builder = builder.withInsertHandler((insertionContext, item) -> autoInsertOceanImport(insertionContext, qualifiedName2));
                }
                result.addElement(builder);
            }
        }
    }

    /**
     * Extracts the package name from an Ocean file by looking for
     * packageDeclaration rule.
     * Returns "math" for "package math;", "com.example" for "package com.example;",
     * or null.
     */
    public static String extractOceanPackage(PsiFile file) {
        PsiElement[] pkgDecls = PsiTreeUtil.collectElements(file, e -> isRule(e, "packageDeclaration"));
        if (pkgDecls.length == 0)
            return null;
        PsiElement pkgDecl = pkgDecls[0];
        StringBuilder sb = new StringBuilder();
        for (PsiElement child : pkgDecl.getChildren()) {
            if (isRule(child, "anyId")) {
                if (!sb.isEmpty())
                    sb.append(".");
                sb.append(child.getText().trim());
            }
        }
        return !sb.isEmpty() ? sb.toString() : null;
    }

    /**
     * Auto-inserts an Ocean import statement if the class is from a different
     * package.
     */
    private void autoInsertOceanImport(InsertionContext ctx, String qualifiedName) {
        if (qualifiedName == null)
            return;

        PsiFile file = ctx.getFile();
        Project project = ctx.getProject();

        // Check if current file is in the same package
        String currentPackage = extractOceanPackage(file);
        String targetPackage = qualifiedName.contains(".")
                ? qualifiedName.substring(0, qualifiedName.lastIndexOf("."))
                : null;

        // Skip import if target package is java.lang or ocean.stdlib
        if ("java.lang".equals(targetPackage) || "ocean.stdlib".equals(targetPackage))
            return;

        // Same package or no package - no import needed
        if (Objects.equals(currentPackage, targetPackage))
            return;

        // Check if already imported
        if (isImported(file, qualifiedName))
            return;

        // Insert the import
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc != null) {
                OceanImportClassQuickFix.insertImport(doc, file, qualifiedName, project);
            }
        });
    }

    /**
     * Checks if an Ocean class has the 'abstract' modifier or has any abstract
     * methods.
     */
    private boolean isOceanAbstractClass(PsiElement cls) {
        // Check for abstract modifier
        for (PsiElement child : cls.getChildren()) {
            if (isRuleIndex(child, OceanParser.RULE_modifier) && child.getText().contains("abstract"))
                return true;
        }
        // Check if any method has no block (abstract method)
        for (PsiElement child : cls.getChildren()) {
            if (!isRuleIndex(child, OceanParser.RULE_memberDeclaration))
                continue;
            for (PsiElement member : child.getChildren()) {
                if (isRuleIndex(member, OceanParser.RULE_methodDeclaration) && !hasBlockChild(member))
                    return true;
            }
        }
        return false;
    }

    /**
     * After selecting a class name after 'extends', this finds the enclosing
     * class's
     * closing brace and inserts the required abstract method stubs.
     */
    private void scheduleMethodStubInsertion(InsertionContext ctx, String parentClassName) {
        Project project = ctx.getProject();
        Editor editor = ctx.getEditor();
        Document doc = editor.getDocument();

        // We need to commit the document first so PSI is up-to-date
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        // Collect required abstract methods from the parent (deduplicate by name)
        Set<String> seen = new LinkedHashSet<>();
        List<OceanImplementMethodsAction.MethodStub> stubs = new ArrayList<>();
        for (OceanImplementMethodsAction.MethodStub s : collectOceanAbstractStubs(project, parentClassName,
                new HashSet<>())) {
            if (seen.add(s.name()))
                stubs.add(s);
        }
        if (stubs.isEmpty()) {
            for (OceanImplementMethodsAction.MethodStub s : collectJavaAbstractStubs(project, parentClassName)) {
                if (seen.add(s.name()))
                    stubs.add(s);
            }
        }
        if (stubs.isEmpty())
            return;

        // Find the closing brace of the enclosing class
        String text = doc.getText();
        int caretOffset = editor.getCaretModel().getOffset();
        // Walk forward to find '{', then find the matching '}'
        int openBrace = text.indexOf('{', caretOffset);
        if (openBrace == -1)
            return;
        int closeBrace = findMatchingBrace(text, openBrace);
        if (closeBrace == -1)
            return;

        // Build the stub code
        StringBuilder sb = new StringBuilder();
        for (OceanImplementMethodsAction.MethodStub s : stubs) {
            String ret = s.returnType() != null && !s.returnType().isBlank() ? s.returnType() + " " : "void ";
            sb.append("\n\n    @Override\n");
            sb.append("    public ").append(ret).append("function ").append(s.name());
            sb.append("(").append(s.params()).append(") {\n");
            sb.append("        // TODO: implement\n");
            sb.append("    }");
        }

        String code = sb.toString();
        // just before the }
        WriteCommandAction.runWriteCommandAction(project, () -> doc.insertString(closeBrace, code));
    }

    private int findMatchingBrace(String text, int openBracePos) {
        int depth = 0;
        for (int i = openBracePos; i < text.length(); i++) {
            if (text.charAt(i) == '{')
                depth++;
            else if (text.charAt(i) == '}') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return -1;
    }

    private List<OceanImplementMethodsAction.MethodStub> collectOceanAbstractStubs(
            Project project, String className, Set<String> visited) {
        return collectOceanAbstractStubs(project, className, visited, getAllOceanFiles(project));
    }

    private List<OceanImplementMethodsAction.MethodStub> collectOceanAbstractStubs(
            Project project, String className, Set<String> visited, List<PsiFile> allFiles) {
        if (className == null || !visited.add(className))
            return List.of();
        List<OceanImplementMethodsAction.MethodStub> result = new ArrayList<>();

        for (PsiFile file : allFiles) {
            // 1. Classes
            PsiElement[] classes = PsiTreeUtil.collectElements(file, e -> isRule(e, "classDeclaration"));
            for (PsiElement cls : classes) {
                String name = findNameForElement(cls);
                if (!className.equals(name))
                    continue;

                for (PsiElement child : cls.getChildren()) {
                    if (!isRuleIndex(child, OceanParser.RULE_memberDeclaration))
                        continue;
                    for (PsiElement member : child.getChildren()) {
                        if (!isRuleIndex(member, OceanParser.RULE_methodDeclaration))
                            continue;
                        if (!hasBlockChild(member)) {
                            PsiElement mid = directChildByRuleIndex(member, OceanParser.RULE_anyId);
                            if (mid == null)
                                continue;
                            result.add(new OceanImplementMethodsAction.MethodStub(
                                    mid.getText().trim(),
                                    getMethodReturnType(member),
                                    getMethodParamSignature(member)));
                        }
                    }
                }

                // Recurse to parent class or implemented interfaces
                String header = cls.getText();
                int brace = header.indexOf('{');
                if (brace > 0)
                    header = header.substring(0, brace);
                Matcher ext = Pattern.compile("\\bextends\\s+(\\w+)").matcher(header);
                if (ext.find())
                    result.addAll(collectOceanAbstractStubs(project, ext.group(1), visited, allFiles));

                Matcher imp = Pattern.compile("\\bimplements\\s+([A-Za-z0-9_,\\s]+)").matcher(header);
                if (imp.find()) {
                    String[] ifaces = imp.group(1).split(",");
                    for (String iface : ifaces) {
                        String ifaceTrimmed = iface.trim();
                        if (!ifaceTrimmed.isEmpty()) {
                            result.addAll(collectOceanAbstractStubs(project, ifaceTrimmed, visited, allFiles));
                        }
                    }
                }
            }

            // 2. Interfaces
            PsiElement[] interfaces = PsiTreeUtil.collectElements(file, e -> isRule(e, "interfaceDeclaration"));
            for (PsiElement iface : interfaces) {
                String name = findNameForElement(iface);
                if (!className.equals(name))
                    continue;

                for (PsiElement child : iface.getChildren()) {
                    if (!isRuleIndex(child, OceanParser.RULE_memberDeclaration))
                        continue;
                    for (PsiElement member : child.getChildren()) {
                        if (!isRuleIndex(member, OceanParser.RULE_methodDeclaration))
                            continue;
                        if (!hasBlockChild(member)) {
                            PsiElement mid = directChildByRuleIndex(member, OceanParser.RULE_anyId);
                            if (mid == null)
                                continue;
                            result.add(new OceanImplementMethodsAction.MethodStub(
                                    mid.getText().trim(),
                                    getMethodReturnType(member),
                                    getMethodParamSignature(member)));
                        }
                    }
                }

                // Recurse to extended interfaces
                String header = iface.getText();
                int brace = header.indexOf('{');
                if (brace > 0)
                    header = header.substring(0, brace);
                Matcher ext = Pattern.compile("\\bextends\\s+([A-Za-z0-9_,\\s]+)").matcher(header);
                if (ext.find()) {
                    String[] ifaces = ext.group(1).split(",");
                    for (String ifc : ifaces) {
                        String ifcTrimmed = ifc.trim();
                        if (!ifcTrimmed.isEmpty()) {
                            result.addAll(collectOceanAbstractStubs(project, ifcTrimmed, visited, allFiles));
                        }
                    }
                }
            }
        }
        return result;
    }

    private List<OceanImplementMethodsAction.MethodStub> collectJavaAbstractStubs(
            Project project, String className) {
        PsiClass cls = resolveJavaClass(project, className);
        if (cls == null)
            return List.of();
        List<OceanImplementMethodsAction.MethodStub> result = new ArrayList<>();
        boolean isIface = cls.isInterface();
        for (PsiMethod m : (isIface ? cls.getMethods() : cls.getAllMethods())) {
            if (m.isConstructor())
                continue;
            boolean needsImpl = isIface
                    ? (!m.hasModifierProperty(PsiModifier.DEFAULT) && !m.hasModifierProperty(PsiModifier.STATIC))
                    : m.hasModifierProperty(PsiModifier.ABSTRACT);
            if (!needsImpl)
                continue;
            result.add(new OceanImplementMethodsAction.MethodStub(
                    m.getName(), javaTypeToOcean(m.getReturnType()), buildJavaParamStr(m)));
        }
        return result;
    }

    private String javaTypeToOcean(PsiType type) {
        if (type == null)
            return "void";
        return switch (type.getCanonicalText()) {
            case "void" -> "void";
            case "int" -> "int";
            case "long" -> "long";
            case "double" -> "double";
            case "float" -> "float";
            case "boolean" -> "bool";
            case "char" -> "char";
            case "byte" -> "byte";
            case "short" -> "short";
            case "java.lang.String" -> "String";
            default -> {
                String r = type.getCanonicalText();
                int d = r.lastIndexOf('.');
                yield d >= 0 ? r.substring(d + 1) : r;
            }
        };
    }

    private String buildJavaParamStr(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(javaTypeToOcean(params[i].getType())).append(" ").append(params[i].getName());
        }
        return sb.toString();
    }

    private boolean hasBlockChild(PsiElement method) {
        for (PsiElement child : method.getChildren()) {
            if (isRuleIndex(child, OceanParser.RULE_block))
                return true;
        }
        return false;
    }

    public static boolean isRuleIndex(PsiElement e, int ruleIndex) {
        if (e == null || e.getNode() == null)
            return false;
        IElementType type = e.getNode().getElementType();
        return type instanceof RuleIElementType ri && ri.getRuleIndex() == ruleIndex;
    }

    private PsiElement directChildByRuleIndex(PsiElement e, int ruleIndex) {
        for (PsiElement c : e.getChildren())
            if (isRuleIndex(c, ruleIndex))
                return c;
        return null;
    }

    private String getMethodReturnType(PsiElement method) {
        return OceanScopeDiscovery.getMethodReturnType(method);
    }

    private String getMethodParamSignature(PsiElement method) {
        PsiElement pl = directChildByRuleIndex(method, OceanParser.RULE_parameterList);
        if (pl == null)
            return "";
        StringBuilder sb = new StringBuilder();
        List<PsiElement> params = new ArrayList<>();
        for (PsiElement c : pl.getChildren())
            if (isRuleIndex(c, OceanParser.RULE_parameter))
                params.add(c);
        for (int i = 0; i < params.size(); i++) {
            if (i > 0)
                sb.append(", ");
            PsiElement typeN = directChildByRuleIndex(params.get(i), OceanParser.RULE_type);
            PsiElement nameN = directChildByRuleIndex(params.get(i), OceanParser.RULE_anyId);
            sb.append(typeN != null ? typeN.getText().trim() : "variable")
                    .append(" ").append(nameN != null ? nameN.getText().trim() : "param" + i);
        }
        return sb.toString();
    }

    // =========================================================================
    // Hierarchy helpers
    // =========================================================================

    /** Extracts the name after `extends` using regex on the class text. */
    private String extractExtendsName(PsiElement cls) {
        Matcher m = Pattern.compile("\\bextends\\s+(\\w+)").matcher(cls.getText());
        return m.find() ? m.group(1) : null;
    }

    /** Extracts names after `implements` (comma-separated list). */
    private List<String> extractImplementsNames(PsiElement cls) {
        List<String> names = new ArrayList<>();
        if (cls == null) return names;
        Matcher m = Pattern.compile("\\bimplements\\s+([^;{]+)").matcher(cls.getText());
        if (m.find()) {
            for (String s : OceanScopeDiscovery.splitTopLevelComma(m.group(1))) {
                String t = OceanScopeDiscovery.normalizeTypeName(s);
                if (t != null && !t.isEmpty())
                    names.add(t);
            }
        }
        return names;
    }

    /**
     * Returns true if a class with this name exists in any Ocean source file in the
     * project.
     */
    private boolean isOceanClass(String className, List<PsiFile> allFiles) {
        for (PsiFile file : allFiles) {
            if ((className + ".ocean").equals(file.getName())) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Type inference and extraction helpers (unchanged from previous version)
    // =========================================================================

    private String extractParameters(PsiElement methodNode) {
        StringBuilder sb = new StringBuilder("(");
        PsiElement[] params = PsiTreeUtil.collectElements(methodNode, e -> isRule(e, "parameter"));
        for (int i = 0; i < params.length; i++) {
            String pName = findNameForElement(params[i]);
            PsiElement pTypeNode = findChildByRule(params[i], "type");
            String pType = pTypeNode != null ? pTypeNode.getText().trim() : "any";
            sb.append(pType).append(" ").append(pName);
            if (i < params.length - 1)
                sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }

    private String extractReturnType(PsiElement methodNode) {
        PsiElement typeNode = findChildByRule(methodNode, "type");
        if (typeNode != null)
            return typeNode.getText().trim();
        String text = methodNode.getText();
        if (text.contains("void"))
            return "void";
        if (text.contains("static"))
            return "static";
        return "void";
    }

    private String extractVariableType(PsiElement fieldNode) {
        PsiElement typeNode = findChildByRule(fieldNode, "type");
        if (typeNode != null)
            return typeNode.getText().trim();
        return "variable";
    }

    private String findNameForElement(PsiElement element) {
        PsiElement anyId = findChildByRule(element, "anyId");
        if (anyId != null) {
            String text = anyId.getText();
            int angle = text.indexOf('<');
            return (angle > 0) ? text.substring(0, angle).trim() : text.trim();
        }
        PsiElement id = findIdentifierLeafRecursive(element);
        return id != null ? id.getText().trim() : null;
    }

    private String guessTypeInProject(String varName, PsiFile currentFile, Project project) {
        return guessTypeInProject(varName, currentFile, project, getAllOceanFiles(project));
    }

    private String guessTypeInProject(String varName, PsiFile currentFile, Project project, List<PsiFile> allFiles) {
        String type = guessTypeInFile(varName, currentFile);
        if (type != null)
            return type;
        for (PsiFile file : allFiles) {
            if (file.equals(currentFile))
                continue;
            type = guessTypeInFile(varName, file);
            if (type != null)
                return type;
        }
        return null;
    }

    private String guessTypeInFile(String varName, PsiFile file) {
        PsiElement[] decls = PsiTreeUtil.collectElements(file,
                e -> isAnyRule(e, "variableDeclaration", "parameter", "fieldDeclaration"));
        for (PsiElement decl : decls) {
            if (isRule(decl, "fieldDeclaration")) {
                // fieldDeclaration → variableDeclarator → anyId: descend one level
                PsiElement typeNode = findChildByRule(decl, "type");
                String typeText = typeNode != null ? cleanGeneric(typeNode.getText()) : null;
                for (PsiElement child : decl.getChildren()) {
                    if (!isRule(child, "variableDeclarator")) continue;
                    PsiElement anyId = findChildByRule(child, "anyId");
                    if (anyId != null && varName.equals(anyId.getText().trim())) {
                        if (typeText != null) return typeText;
                        PsiElement init = findRuleNodeRecursive(child, "expression");
                        if (init != null) {
                            String t = extractTypeFromText(init.getText());
                            if (t != null) return t;
                        }
                    }
                }
            } else {
                // variableDeclaration, parameter — direct anyId child works correctly
                if (varName.equals(findNameForElement(decl))) {
                    PsiElement typeNode = findChildByRule(decl, "type");
                    if (typeNode != null)
                        return cleanGeneric(typeNode.getText());
                    PsiElement init = findRuleNodeRecursive(decl, "expression");
                    if (init != null) {
                        String t = extractTypeFromText(init.getText());
                        if (t != null)
                            return t;
                    }
                }
            }
        }
        String content = file.getText();
        if (content != null) {
            Matcher m = Pattern.compile("(?<=^|[\\s;{}])([a-zA-Z_][a-zA-Z0-9_<>\\[\\]]*)\\s+" + Pattern.quote(varName) + "\\s*=").matcher(content);
            while (m.find()) {
                String grp = m.group(1).trim();
                if (!"value".equals(grp) && !"variable".equals(grp) && !"final".equals(grp) && !"return".equals(grp)) {
                    return cleanGeneric(grp);
                }
            }
            Matcher m2 = Pattern.compile(Pattern.quote(varName) + "\\s*=\\s*new\\s+([a-zA-Z_][a-zA-Z0-9_<>]*)").matcher(content);
            if (m2.find()) {
                return cleanGeneric(m2.group(1).trim());
            }
        }
        return null;
    }

    // =========================================================================
    // PSI util
    // =========================================================================

    public static boolean isRule(PsiElement e, String ruleName) {
        if (!(e instanceof OceanPsiNode))
            return false;
        IElementType type = e.getNode().getElementType();
        if (type instanceof RuleIElementType ri) {
            int idx = ri.getRuleIndex();
            if (idx >= 0 && idx < OceanParser.ruleNames.length) {
                String name = OceanParser.ruleNames[idx];
                return name != null && name.equals(ruleName);
            }
        }
        return false;
    }

    private boolean isAnyRule(PsiElement e, String... names) {
        for (String n : names)
            if (isRule(e, n))
                return true;
        return false;
    }

    private PsiElement findChildByRule(PsiElement element, String ruleName) {
        for (PsiElement child : element.getChildren())
            if (isRule(child, ruleName))
                return child;
        return null;
    }

    private PsiElement findRuleNodeRecursive(PsiElement element, String ruleName) {
        if (isRule(element, ruleName))
            return element;
        for (PsiElement child : element.getChildren()) {
            PsiElement res = findRuleNodeRecursive(child, ruleName);
            if (res != null)
                return res;
        }
        return null;
    }

    private boolean isInsideMain(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (isRule(cur, "methodDeclaration")) {
                PsiElement mainToken = findTokenChild(cur, OceanLexer.MAIN);
                if (mainToken != null)
                    return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private int tokenType(PsiElement e) {
        if (e == null || e.getNode() == null)
            return -1;
        IElementType t = e.getNode().getElementType();
        return t instanceof TokenIElementType ti ? ti.getANTLRTokenType() : -1;
    }

    private PsiElement findTokenChild(PsiElement element, int tokenType) {
        if (element == null)
            return null;
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (tokenType(child) == tokenType)
                return child;
        }
        return null;
    }

    private PsiElement findEnclosingClass(PsiElement element) {
        return findEnclosingClassOrInterface(element);
    }

    private PsiElement findEnclosingClassOrInterface(PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (isRule(cur, "classDeclaration") || isRule(cur, "interfaceDeclaration") || isRule(cur, "enumDeclaration"))
                return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private PsiClass resolveJavaClass(Project project, String name) {
        return OceanScopeDiscovery.resolveJavaClass(project, name, null);
    }

    private PsiClass resolveJavaClass(Project project, String name, PsiElement context) {
        return OceanScopeDiscovery.resolveJavaClass(project, name, context);
    }

    private List<PsiFile> getAllOceanFiles(Project project) {
        List<PsiFile> files = new ArrayList<>();
        FileTypeIndex.processFiles(OceanFileType.INSTANCE, virtualFile -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile != null)
                files.add(psiFile);
            return true;
        }, GlobalSearchScope.projectScope(project));
        return files;
    }

    private void suggestObjectMethods(Project project, CompletionResultSet result) {
        PsiClass obj = JavaPsiFacade.getInstance(project)
                .findClass("java.lang.Object", GlobalSearchScope.allScope(project));
        if (obj != null)
            addJavaClassMembersToResult(obj, result, false);
    }

    private String extractTypeFromText(String text) {
        if (text == null)
            return null;
        if (text.contains("new ")) {
            int start = text.indexOf("new ") + 4;
            int end = text.indexOf("(", start);
            if (end > start)
                return cleanGeneric(text.substring(start, end));
        }
        return null;
    }

    private String cleanGeneric(String t) {
        if (t == null)
            return null;
        t = t.trim();
        int i = t.indexOf('<');
        return (i > 0) ? t.substring(0, i).trim() : t;
    }

    private boolean isImported(PsiFile f, String q) {
        return f.getText().contains("import " + q + ";");
    }

    private void addImport(PsiFile f, String q, Project p) {
        WriteCommandAction.runWriteCommandAction(p, () -> {
            Document d = PsiDocumentManager.getInstance(p).getDocument(f);
            if (d != null) {
                OceanImportClassQuickFix.insertImport(d, f, q, p);
            }
        });
    }

    private PsiElement findIdentifierLeafRecursive(PsiElement e) {
        if (isIdentifierToken(e))
            return e;
        for (PsiElement child : e.getChildren()) {
            PsiElement res = findIdentifierLeafRecursive(child);
            if (res != null)
                return res;
        }
        return null;
    }

    private boolean isIdentifierToken(PsiElement e) {
        if (e == null || e.getNode() == null)
            return false;
        IElementType t = e.getNode().getElementType();
        return t instanceof TokenIElementType ti && ti.getANTLRTokenType() == OceanLexer.IDENTIFIER;
    }

    private boolean isAbstractOrInterface(Project project, String className) {
        PsiElement oceanCls = OceanScopeDiscovery.findOceanClass(project, className);
        if (oceanCls != null) {
            if (isRule(oceanCls, "interfaceDeclaration")) return true;
            String cText = oceanCls.getText();
            int brace = cText.indexOf('{');
            if (brace > 0) cText = cText.substring(0, brace);
            return cText.contains("interface") || cText.contains("abstract");
        }
        PsiClass javaCls = resolveJavaClass(project, className);
        if (javaCls != null) {
            return javaCls.isInterface() || javaCls.hasModifierProperty(PsiModifier.ABSTRACT);
        }
        return false;
    }

    private void scheduleAnonymousClassStubInsertion(InsertionContext ctx, String parentClassName, String qualifiedName) {
        Project project = ctx.getProject();
        Editor editor = ctx.getEditor();
        Document doc = editor.getDocument();
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        Set<String> seen = new LinkedHashSet<>();
        List<OceanImplementMethodsAction.MethodStub> stubs = new ArrayList<>();
        for (OceanImplementMethodsAction.MethodStub s : collectOceanAbstractStubs(project, parentClassName, new HashSet<>())) {
            if (seen.add(s.name())) stubs.add(s);
        }
        if (stubs.isEmpty()) {
            String lookupKey = qualifiedName != null && !qualifiedName.isEmpty() ? qualifiedName : parentClassName;
            for (OceanImplementMethodsAction.MethodStub s : collectJavaAbstractStubs(project, lookupKey)) {
                if (seen.add(s.name())) stubs.add(s);
            }
        }
        if (stubs.isEmpty()) return;

        // Build the stub code
        StringBuilder sb = new StringBuilder();
        sb.append("() {\n");
        for (int i = 0; i < stubs.size(); i++) {
            if (i > 0) sb.append("\n");
            OceanImplementMethodsAction.MethodStub s = stubs.get(i);
            String ret = s.returnType() != null && !s.returnType().isBlank() ? s.returnType() + " " : "void ";
            sb.append("    @Override\n")
              .append("    public ").append(ret).append("function ").append(s.name())
              .append("(").append(s.params()).append(") {\n")
              .append("        // TODO: implement\n")
              .append("    }\n");
        }
        sb.append("}");

        String code = sb.toString();
        
        int caretOffset = editor.getCaretModel().getOffset();
        String text = doc.getText();

        if (caretOffset < text.length() && text.charAt(caretOffset) == '(') {
            int closeP = text.indexOf(')', caretOffset);
            if (closeP != -1) {
                final int startP = caretOffset;
                final int endP = closeP + 1;
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    doc.replaceString(startP, endP, code);
                    int todoOffset = code.indexOf("// TODO: implement");
                    if (todoOffset != -1) {
                        editor.getCaretModel().moveToOffset(startP + todoOffset);
                    }
                });
                return;
            }
        }
        if (caretOffset > 0 && text.charAt(caretOffset - 1) == ')') {
            String bodyOnly = code.substring(2); // strip "()"
            final int insPos = caretOffset;
            WriteCommandAction.runWriteCommandAction(project, () -> {
                doc.insertString(insPos, bodyOnly);
                int todoOffset = bodyOnly.indexOf("// TODO: implement");
                if (todoOffset != -1) {
                    editor.getCaretModel().moveToOffset(insPos + todoOffset);
                }
            });
            return;
        }

        final int finalInsertAt = caretOffset;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            doc.insertString(finalInsertAt, code);
            int todoOffset = code.indexOf("// TODO: implement");
            if (todoOffset != -1) {
                editor.getCaretModel().moveToOffset(finalInsertAt + todoOffset);
            }
        });
    }

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "int", "long", "float", "double", "char", "byte", "short", "bool", "String"
    );

    private static final Set<String> UNTYPED_VAR_KEYWORDS = Set.of(
            "variable", "value"
    );

    private static final Set<String> NON_TYPE_PRECEDING_KEYWORDS = Set.of(
            "class", "interface", "enum", "annotation", "extends", "implements",
            "package", "import", "new", "@", "throws", "restricts"
    );

    private String resolvePrecedingTypeName(PsiElement position) {
        if (position == null) return null;
        PsiElement prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (prev == null) return null;
        String text = prev.getText().trim();
        if (text.isEmpty()) return null;

        if (UNTYPED_VAR_KEYWORDS.contains(text)) {
            return text;
        }

        if (PRIMITIVE_TYPES.contains(text)) {
            return text;
        }

        if (text.equals(">")) {
            // e.g. ArrayList<String> <caret> or Map<String, int> <caret>
            int depth = 0;
            PsiElement curr = prev;
            while (curr != null) {
                String ct = curr.getText().trim();
                if (ct.equals(">")) {
                    depth++;
                } else if (ct.equals("<")) {
                    depth--;
                    if (depth == 0) {
                        PsiElement beforeAngle = PsiTreeUtil.prevVisibleLeaf(curr);
                        if (beforeAngle != null) {
                            String baseType = beforeAngle.getText().trim();
                            if (!baseType.isEmpty() && Character.isUpperCase(baseType.charAt(0))) {
                                return baseType;
                            }
                        }
                        break;
                    }
                }
                curr = PsiTreeUtil.prevVisibleLeaf(curr);
            }
            return null;
        }

        if (text.equals("[]")) {
            PsiElement beforeBracket = PsiTreeUtil.prevVisibleLeaf(prev);
            if (beforeBracket != null) {
                String elType = beforeBracket.getText().trim();
                if (PRIMITIVE_TYPES.contains(elType) || (!elType.isEmpty() && Character.isUpperCase(elType.charAt(0)))) {
                    return elType + "[]";
                }
            }
            return null;
        }

        if (text.matches("[A-Z][A-Za-z0-9_]*")) {
            PsiElement beforePrev = PsiTreeUtil.prevVisibleLeaf(prev);
            if (beforePrev != null) {
                String bpText = beforePrev.getText().trim();
                if (NON_TYPE_PRECEDING_KEYWORDS.contains(bpText)) {
                    return null;
                }
            }
            return text;
        }

        return null;
    }

    private void suggestVariableNamesForType(String typeName, CompletionResultSet result) {
        if (typeName == null || typeName.isEmpty()) return;
        Set<String> suggestions = new LinkedHashSet<>();

        if (UNTYPED_VAR_KEYWORDS.contains(typeName)) {
            suggestions.addAll(List.of("item", "value", "result", "temp", "data", "res"));
        } else if (typeName.equals("int") || typeName.equals("long") || typeName.equals("short") || typeName.equals("byte")) {
            suggestions.addAll(List.of("i", "count", "index", "num", "n", "size", "total", "value"));
        } else if (typeName.equals("float") || typeName.equals("double")) {
            suggestions.addAll(List.of("d", "value", "amount", "rate", "score", "price", "result"));
        } else if (typeName.equals("bool")) {
            suggestions.addAll(List.of("flag", "isValid", "isSuccess", "hasResult", "enabled", "found", "b"));
        } else if (typeName.equals("char")) {
            suggestions.addAll(List.of("c", "ch", "character"));
        } else if (typeName.equals("String")) {
            suggestions.addAll(List.of("s", "str", "string", "name", "text", "msg", "key", "value"));
        } else if (typeName.endsWith("[]")) {
            String base = typeName.substring(0, typeName.length() - 2);
            String baseDecap = decapitalize(base);
            suggestions.add(baseDecap + "s");
            suggestions.add(baseDecap + "Array");
            suggestions.add("array");
            suggestions.add("elements");
            suggestions.add("items");
        } else {
            // Special collections & stdlib classes
            if (typeName.equalsIgnoreCase("ArrayList") || typeName.equalsIgnoreCase("List") || typeName.equalsIgnoreCase("OceanList") || typeName.equalsIgnoreCase("LinkedList") || typeName.equalsIgnoreCase("Vector")) {
                suggestions.addAll(List.of("list", "arrayList", "items", "elements"));
            } else if (typeName.equalsIgnoreCase("HashMap") || typeName.equalsIgnoreCase("Map") || typeName.equalsIgnoreCase("OceanMap") || typeName.equalsIgnoreCase("TreeMap") || typeName.equalsIgnoreCase("LinkedHashMap")) {
                suggestions.addAll(List.of("map", "hashMap", "entries"));
            } else if (typeName.equalsIgnoreCase("HashSet") || typeName.equalsIgnoreCase("Set") || typeName.equalsIgnoreCase("OceanSet") || typeName.equalsIgnoreCase("TreeSet")) {
                suggestions.addAll(List.of("set", "hashSet", "uniqueElements"));
            } else if (typeName.equalsIgnoreCase("Queue") || typeName.equalsIgnoreCase("OceanQueue") || typeName.equalsIgnoreCase("Deque")) {
                suggestions.addAll(List.of("queue", "q"));
            } else if (typeName.equalsIgnoreCase("Stack") || typeName.equalsIgnoreCase("OceanStack")) {
                suggestions.add("stack");
            } else if (typeName.equalsIgnoreCase("Counter") || typeName.equalsIgnoreCase("OceanCounter")) {
                suggestions.addAll(List.of("counter", "counts"));
            } else if (typeName.equalsIgnoreCase("BitSet") || typeName.equalsIgnoreCase("OceanBitSet")) {
                suggestions.addAll(List.of("bitSet", "bits"));
            } else if (typeName.equalsIgnoreCase("Task")) {
                suggestions.addAll(List.of("task", "future"));
            } else if (typeName.equalsIgnoreCase("OceanFile") || typeName.equalsIgnoreCase("File")) {
                suggestions.add("file");
            } else if (typeName.equalsIgnoreCase("OceanHttp") || typeName.equalsIgnoreCase("Http") || typeName.equalsIgnoreCase("HttpClient")) {
                suggestions.addAll(List.of("http", "client"));
            } else if (typeName.equalsIgnoreCase("OceanDate") || typeName.equalsIgnoreCase("Date") || typeName.equalsIgnoreCase("LocalDate") || typeName.equalsIgnoreCase("LocalDateTime")) {
                suggestions.add("date");
            } else if (typeName.equalsIgnoreCase("OceanTime") || typeName.equalsIgnoreCase("Time") || typeName.equalsIgnoreCase("LocalTime")) {
                suggestions.add("time");
            } else if (typeName.equalsIgnoreCase("OceanRegex") || typeName.equalsIgnoreCase("Pattern") || typeName.equalsIgnoreCase("Regex")) {
                suggestions.addAll(List.of("pattern", "regex"));
            } else if (typeName.equalsIgnoreCase("OceanResult") || typeName.equalsIgnoreCase("Result")) {
                suggestions.addAll(List.of("result", "res"));
            } else if (typeName.equalsIgnoreCase("OceanOptional") || typeName.equalsIgnoreCase("Optional")) {
                suggestions.addAll(List.of("optional", "opt"));
            } else if (typeName.equalsIgnoreCase("OceanPair") || typeName.equalsIgnoreCase("Pair")) {
                suggestions.add("pair");
            } else if (typeName.equalsIgnoreCase("OceanTriple") || typeName.equalsIgnoreCase("Triple")) {
                suggestions.add("triple");
            } else if (typeName.equalsIgnoreCase("Exception") || typeName.equalsIgnoreCase("Throwable") || typeName.equalsIgnoreCase("Error")) {
                suggestions.addAll(List.of("e", "ex", "exception"));
            } else if (typeName.equalsIgnoreCase("StrBuilder") || typeName.equalsIgnoreCase("StringBuilder") || typeName.equalsIgnoreCase("OceanStringBuilder")) {
                suggestions.addAll(List.of("builder", "sb"));
            }

            // CamelCase decomposition
            String cleanType = typeName.startsWith("Ocean") && typeName.length() > 5 ? typeName.substring(5) : typeName;
            String decap = decapitalize(cleanType);
            if (!decap.isEmpty()) {
                suggestions.add(decap);
            }

            // Suffix words (e.g. CustomerService -> [customerService, service])
            List<String> words = splitPascalCase(cleanType);
            if (words.size() > 1) {
                String lastWord = decapitalize(words.get(words.size() - 1));
                suggestions.add(lastWord);
                if (words.size() >= 3) {
                    String lastTwo = decapitalize(words.get(words.size() - 2) + words.get(words.size() - 1));
                    suggestions.add(lastTwo);
                }
            }
        }

        for (String name : suggestions) {
            LookupElementBuilder builder = LookupElementBuilder.create(name)
                    .withIcon(PlatformIcons.VARIABLE_ICON)
                    .withTypeText(typeName)
                    .withBoldness(true);
            result.addElement(PrioritizedLookupElement.withPriority(builder, 100));
        }
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> splitPascalCase(String s) {
        List<String> words = new ArrayList<>();
        if (s == null || s.isEmpty()) return words;
        Matcher m = Pattern.compile("[A-Z][a-z0-9]*").matcher(s);
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    // =========================================================================
    // Context-Aware Keywords
    // =========================================================================

    public static List<String> getKeywordsForContext(PsiElement position) {
        if (position == null) return KEYWORDS;

        boolean insideMethod = false;
        boolean insideClass = false;

        PsiElement cur = position;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (cur instanceof OceanMethodDeclaration || isRule(cur, "methodDeclaration") || isRuleIndex(cur, OceanParser.RULE_methodDeclaration)) {
                insideMethod = true;
                break;
            }
            if (cur instanceof OceanClassDeclaration || isRule(cur, "classDeclaration") || isRuleIndex(cur, OceanParser.RULE_classDeclaration)
                    || cur instanceof OceanInterfaceDeclaration || isRule(cur, "interfaceDeclaration") || isRuleIndex(cur, OceanParser.RULE_interfaceDeclaration)) {
                insideClass = true;
            }
            cur = cur.getParent();
        }

        if (insideMethod) {
            return List.of(
                    "if", "else", "while", "for", "from", "to", "with", "in", "increasing", "decreasing",
                    "switch", "case", "default", "result", "trying", "catch", "finally", "throw", "return",
                    "stop", "skip", "lock", "sync", "await", "verify", "variable", "value", "this", "super",
                    "true", "false", "null", "new", "int", "long", "float", "double", "char", "byte", "short",
                    "String", "bool", "void"
            );
        } else if (insideClass) {
            return List.of(
                    "function", "variable", "value", "public", "private", "protected", "static", "final",
                    "abstract", "native", "async", "new", "void", "int", "long", "float", "double",
                    "char", "byte", "short", "String", "bool", "data", "class", "interface", "enum", "annotation"
            );
        } else {
            // Top-level file context
            return List.of(
                    "package", "import", "class", "interface", "enum", "data", "public", "annotation",
                    "sealed", "non-sealed"
            );
        }
    }

    // =========================================================================
    // Postfix Templates (.out, .null, .notnull, .return, .var, .for)
    // =========================================================================

    private void addPostfixTemplates(CompletionResultSet result, String receiverExpr, PsiElement dot) {
        if (receiverExpr == null || receiverExpr.isBlank()) return;

        // 1. .out / .sout -> OceanOutput(expr);
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("out")
                        .withPresentableText("out")
                        .withTailText(" -> OceanOutput(" + receiverExpr + ");", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "OceanOutput(" + receiverExpr + ");")), 15));

        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("sout")
                        .withPresentableText("sout")
                        .withTailText(" -> OceanOutput(" + receiverExpr + ");", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "OceanOutput(" + receiverExpr + ");")), 15));

        // 2. .null -> if (expr == null) { ... }
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("null")
                        .withPresentableText("null")
                        .withTailText(" -> if (" + receiverExpr + " == null)", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "if (" + receiverExpr + " == null) {\n    \n}")), 14));

        // 3. .notnull / .nn -> if (expr != null) { ... }
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("notnull")
                        .withPresentableText("notnull")
                        .withTailText(" -> if (" + receiverExpr + " != null)", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "if (" + receiverExpr + " != null) {\n    \n}")), 14));

        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("nn")
                        .withPresentableText("nn")
                        .withTailText(" -> if (" + receiverExpr + " != null)", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "if (" + receiverExpr + " != null) {\n    \n}")), 14));

        // 4. .return -> return expr;
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("return")
                        .withPresentableText("return")
                        .withTailText(" -> return " + receiverExpr + ";", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "return " + receiverExpr + ";")), 14));

        // 5. .var -> variable x = expr;
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("var")
                        .withPresentableText("var")
                        .withTailText(" -> variable x = " + receiverExpr + ";", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "variable x = " + receiverExpr + ";")), 14));

        // 6. .for -> for (variable item in expr) { ... }
        result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("for")
                        .withPresentableText("for")
                        .withTailText(" -> for (variable item in " + receiverExpr + ")", true)
                        .withTypeText("postfix")
                        .withBoldness(true)
                        .withInsertHandler((ctx, item) -> replaceWithPostfixTemplate(ctx, dot, receiverExpr, "for (variable item in " + receiverExpr + ") {\n    \n}")), 14));
    }

    public static void replaceWithPostfixTemplate(InsertionContext ctx, PsiElement dot, String receiverExpr, String replacementCode) {
        Document document = ctx.getDocument();
        int tailOffset = ctx.getTailOffset();
        int dotOffset = (dot != null) ? dot.getTextRange().getStartOffset() : (ctx.getStartOffset() - 1);
        int exprStart = dotOffset - receiverExpr.length();
        if (exprStart < 0) exprStart = 0;

        document.replaceString(exprStart, tailOffset, replacementCode);

        // Position caret inside braces if applicable
        if (replacementCode.contains("{\n    \n}")) {
            int braceOffset = exprStart + replacementCode.indexOf("{\n    \n}") + 6;
            ctx.getEditor().getCaretModel().moveToOffset(braceOffset);
        } else {
            ctx.getEditor().getCaretModel().moveToOffset(exprStart + replacementCode.length());
        }
    }

    // =========================================================================
    // Import Package and Class Completion
    // =========================================================================

    public static boolean isInsideImportStatement(PsiElement position, PsiElement dot) {
        PsiElement target = (dot != null) ? dot : position;
        if (target == null) return false;

        // 1. PSI rule check
        PsiElement cur = target;
        while (cur != null) {
            if (isRule(cur, "importStatement")) return true;
            if (cur instanceof PsiFile) break;
            cur = cur.getParent();
        }

        // 2. Line text fallback (when grammar is in recovery state during live editing)
        PsiFile file = target.getContainingFile();
        if (file != null) {
            Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
            if (doc != null) {
                int offset = target.getTextOffset();
                if (offset >= 0 && offset <= doc.getTextLength()) {
                    int lineNum = doc.getLineNumber(offset);
                    int lineStart = doc.getLineStartOffset(lineNum);
                    String lineText = doc.getText().substring(lineStart, offset).trim();
                    return lineText.startsWith("import ") || lineText.equals("import") || lineText.startsWith("import\t");
                }
            }
        }
        return false;
    }

    private void addImportCompletions(Project project, CompletionResultSet result, PsiElement dot, PsiElement position, List<PsiFile> allFiles) {
        if (dot != null && dot.getText().equals(".")) {
            // Dotted import path: e.g. "import java." or "import java.util." or "import ocean."
            String prefix = extractReceiverExpressionText(dot);
            if (prefix == null || prefix.isEmpty()) return;

            addImportPackageAndClassCompletions(project, result, prefix, allFiles);
        } else {
            // Initial root packages after "import ": e.g. "import "
            boolean isStatic = false;
            PsiFile targetFile = position != null ? position.getContainingFile() : null;
            if (targetFile != null) {
                Document doc = PsiDocumentManager.getInstance(targetFile.getProject()).getDocument(targetFile);
                if (doc != null) {
                    int offset = position.getTextOffset();
                    if (offset >= 0 && offset <= doc.getTextLength()) {
                        int lineNum = doc.getLineNumber(offset);
                        int lineStart = doc.getLineStartOffset(lineNum);
                        String lineText = doc.getText().substring(lineStart, offset).trim();
                        if (lineText.contains("static")) {
                            isStatic = true;
                        }
                    }
                }
            }

            result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create("java").withIcon(PlatformIcons.PACKAGE_ICON).withBoldness(true), 20));
            result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create("javax").withIcon(PlatformIcons.PACKAGE_ICON).withBoldness(true), 20));
            result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create("ocean").withIcon(PlatformIcons.PACKAGE_ICON).withBoldness(true), 20));
            if (!isStatic) {
                result.addElement(PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create("static").withBoldness(true), 15));
            }

            // Add root package segments from project Ocean files
            Set<String> projectRootPackages = new HashSet<>();
            for (PsiFile f : allFiles) {
                String pkg = extractOceanPackage(f);
                if (pkg != null && !pkg.isEmpty()) {
                    int firstDot = pkg.indexOf('.');
                    String rootPkg = (firstDot > 0) ? pkg.substring(0, firstDot) : pkg;
                    projectRootPackages.add(rootPkg);
                }
            }
            for (String rootPkg : projectRootPackages) {
                if (!rootPkg.equals("java") && !rootPkg.equals("javax") && !rootPkg.equals("ocean")) {
                    result.addElement(PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(rootPkg).withIcon(PlatformIcons.PACKAGE_ICON).withBoldness(true), 20));
                }
            }
        }
    }

    public static void addImportPackageAndClassCompletions(Project project, CompletionResultSet result, String prefix, List<PsiFile> allFiles) {
        for (LookupElement elem : computeImportCompletions(project, prefix, allFiles)) {
            result.addElement(elem);
        }
    }

    public static List<LookupElement> computeImportCompletions(Project project, String prefix, List<PsiFile> allFiles) {
        List<LookupElement> elements = new ArrayList<>();
        if (prefix == null || prefix.isBlank()) return elements;
        prefix = prefix.trim();

        // 1. Special: Ocean Stdlib & Ocean root package
        if ("ocean".equals(prefix)) {
            elements.add(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create("stdlib")
                            .withIcon(PlatformIcons.PACKAGE_ICON)
                            .withTypeText("Package")
                            .withBoldness(true), 30));
        } else if ("ocean.stdlib".equals(prefix)) {
            // Suggest all built-in Ocean standard library classes
            List<String> stdlibClasses = List.of(
                    "OceanList", "OceanMap", "OceanSet", "OceanIntList", "OceanDoubleList",
                    "OceanLongList", "OceanFloatList", "OceanShortList", "OceanByteList", "OceanCharList",
                    "OceanBooleanList", "OceanPair", "OceanTriple", "OceanResult", "OceanOptional",
                    "OceanFile", "OceanHttp", "OceanHttpResponse", "OceanJson", "OceanMath",
                    "OceanRegex", "OceanString", "OceanStringBuilder", "OceanCounter", "OceanStack",
                    "OceanQueue", "OceanBitSet", "OceanDate", "OceanTime", "StrBuilder", "Task", "ScopedValue"
            );
            for (String stdlibCls : stdlibClasses) {
                elements.add(PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(stdlibCls)
                                .withIcon(OceanIcons.ICON)
                                .withTypeText("ocean.stdlib")
                                .withBoldness(true), 35));
            }
        }

        // 2. Java Packages and Classes via JavaPsiFacade
        if (project != null) {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            PsiPackage pkg = facade.findPackage(prefix);
            if (pkg != null) {
                // Add Java subpackages
                for (PsiPackage subPkg : pkg.getSubPackages()) {
                    String subName = subPkg.getName();
                    if (subName != null) {
                        elements.add(PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(subName)
                                        .withIcon(PlatformIcons.PACKAGE_ICON)
                                        .withTypeText("Package")
                                        .withBoldness(true), 25));
                    }
                }

                // Add Java classes in this package
                for (PsiClass cls : pkg.getClasses()) {
                    String clsName = cls.getName();
                    if (clsName != null && !clsName.isEmpty()) {
                        elements.add(PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(clsName)
                                        .withIcon(cls.getIcon(0))
                                        .withTypeText(prefix)
                                        .withTailText(" (" + prefix + ")", true), 20));
                    }
                }
            } else {
                // Not a package: Check if prefix is a Java class for static member import!
                PsiClass targetClass = facade.findClass(prefix, GlobalSearchScope.allScope(project));
                if (targetClass != null) {
                    for (PsiField f : targetClass.getAllFields()) {
                        if (f.hasModifierProperty(PsiModifier.STATIC) && f.hasModifierProperty(PsiModifier.PUBLIC)) {
                            elements.add(PrioritizedLookupElement.withPriority(
                                    LookupElementBuilder.create(f.getName())
                                            .withIcon(PlatformIcons.FIELD_ICON)
                                            .withTypeText(f.getType().getPresentableText())
                                            .withTailText(" (" + targetClass.getName() + ")", true)
                                            .withBoldness(true), 40));
                        }
                    }
                    Set<String> addedMethods = new HashSet<>();
                    for (PsiMethod m : targetClass.getAllMethods()) {
                        if (m.hasModifierProperty(PsiModifier.STATIC) && m.hasModifierProperty(PsiModifier.PUBLIC)) {
                            String mName = m.getName();
                            if (addedMethods.add(mName)) {
                                String params = PsiFormatUtil.formatMethod(m, PsiSubstitutor.EMPTY,
                                        PsiFormatUtilBase.SHOW_PARAMETERS, PsiFormatUtilBase.SHOW_TYPE | PsiFormatUtilBase.SHOW_NAME);
                                String retType = m.getReturnType() != null ? m.getReturnType().getPresentableText() : "void";
                                elements.add(PrioritizedLookupElement.withPriority(
                                        LookupElementBuilder.create(mName)
                                                .withIcon(PlatformIcons.METHOD_ICON)
                                                .withTailText(params, true)
                                                .withTypeText(retType)
                                                .withBoldness(true), 40));
                            }
                        }
                    }
                }
            }
        }

        // Fallback for built-in Ocean standard library static members
        if (prefix.startsWith("ocean.stdlib.")) {
            addOceanStdlibStaticMemberCompletions(prefix, elements);
        }

        // 3. Project Ocean packages and classes
        if (allFiles != null) {
            Set<String> addedSubPackages = new HashSet<>();
            String prefixDot = prefix + ".";

            for (PsiFile f : allFiles) {
                String pkgName = extractOceanPackage(f);
                if (pkgName == null || pkgName.isEmpty()) continue;

                if (pkgName.equals(prefix)) {
                    // This file belongs directly to the target package: suggest its classes/interfaces/enums
                    PsiElement[] decls = PsiTreeUtil.collectElements(f, e -> {
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
                    for (PsiElement decl : decls) {
                        String declName = OceanScopeDiscovery.findDeclarationName(decl);
                        if (declName != null && !declName.isEmpty()) {
                            elements.add(PrioritizedLookupElement.withPriority(
                                    LookupElementBuilder.create(declName)
                                            .withIcon(OceanIcons.ICON)
                                            .withTypeText(prefix)
                                            .withTailText(" (" + f.getName() + ")", true)
                                            .withBoldness(true), 28));
                        }
                    }
                } else if (pkgName.startsWith(prefixDot)) {
                    // Subpackage match: e.g. prefix="com", pkgName="com.example.service" -> suggest "example"
                    String rest = pkgName.substring(prefixDot.length());
                    int nextDot = rest.indexOf('.');
                    String sub = (nextDot > 0) ? rest.substring(0, nextDot) : rest;
                    if (addedSubPackages.add(sub)) {
                        elements.add(PrioritizedLookupElement.withPriority(
                                LookupElementBuilder.create(sub)
                                        .withIcon(PlatformIcons.PACKAGE_ICON)
                                        .withTypeText("Package")
                                        .withBoldness(true), 25));
                    }
                }
            }
        }
        return elements;
    }

    private static void addOceanStdlibStaticMemberCompletions(String prefix, List<LookupElement> elements) {
        String simpleName = prefix.substring("ocean.stdlib.".length());
        switch (simpleName) {
            case "OceanMath" -> {
                elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create("PI").withIcon(PlatformIcons.FIELD_ICON).withTypeText("double").withBoldness(true), 40));
                elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create("E").withIcon(PlatformIcons.FIELD_ICON).withTypeText("double").withBoldness(true), 40));
                for (String m : List.of("abs", "max", "min", "factorial", "gcd", "isPrime", "sqrt", "pow", "round", "floor", "ceil", "sin", "cos", "tan", "clamp", "random", "randomInt")) {
                    elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(m).withIcon(PlatformIcons.METHOD_ICON).withTailText("(...) (OceanMath)", true).withBoldness(true), 40));
                }
            }
            case "OceanString" -> {
                for (String m : List.of("capitalize", "uncapitalize", "isBlank", "isNotBlank", "reverse", "truncate", "countMatches", "repeat", "join")) {
                    elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(m).withIcon(PlatformIcons.METHOD_ICON).withTailText("(...) (OceanString)", true).withBoldness(true), 40));
                }
            }
            case "OceanFile" -> {
                for (String m : List.of("read", "write", "append", "exists", "delete", "lines", "size", "copy", "move")) {
                    elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(m).withIcon(PlatformIcons.METHOD_ICON).withTailText("(...) (OceanFile)", true).withBoldness(true), 40));
                }
            }
            case "OceanJson" -> {
                for (String m : List.of("parse", "stringify")) {
                    elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(m).withIcon(PlatformIcons.METHOD_ICON).withTailText("(...) (OceanJson)", true).withBoldness(true), 40));
                }
            }
            case "OceanHttp" -> {
                for (String m : List.of("get", "post", "put", "delete", "request")) {
                    elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(m).withIcon(PlatformIcons.METHOD_ICON).withTailText("(...) (OceanHttp)", true).withBoldness(true), 40));
                }
            }
            case "Task" -> {
                for (String m : List.of("run", "supply", "start", "sleep", "all", "race", "gather", "isVirtual")) {
                    elements.add(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(m).withIcon(PlatformIcons.METHOD_ICON).withTailText("(...) (Task)", true).withBoldness(true), 40));
                }
            }
        }
    }
}