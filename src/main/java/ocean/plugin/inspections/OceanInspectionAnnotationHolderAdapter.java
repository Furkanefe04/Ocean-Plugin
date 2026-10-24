package ocean.plugin.inspections;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.AnnotationSession;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter that dynamically proxies AnnotationHolder, routing error annotations
 * and quick-fixes into ProblemsHolder for display under the Ocean inspection category.
 */
public class OceanInspectionAnnotationHolderAdapter {

    private final ProblemsHolder problemsHolder;
    private AnnotationSession session;
    private final AnnotationHolder proxy;
    private PsiElement currentElement;
    private final Set<String> seenProblems = new HashSet<>();

    public OceanInspectionAnnotationHolderAdapter(@NotNull ProblemsHolder problemsHolder) {
        this.problemsHolder = problemsHolder;
        this.proxy = (AnnotationHolder) Proxy.newProxyInstance(
                OceanInspectionAnnotationHolderAdapter.class.getClassLoader(),
                new Class<?>[]{AnnotationHolder.class, OceanInspectionHolder.class},
                createHolderHandler()
        );
    }

    private AnnotationSession getOrCreateSession() {
        if (session != null) return session;
        PsiFile file = null;
        try {
            file = problemsHolder.getFile();
        } catch (Throwable ignored) {
        }
        if (file == null && currentElement != null) {
            try {
                file = currentElement.getContainingFile();
            } catch (Throwable ignored) {
            }
        }
        if (file != null) {
            try {
                session = new AnnotationSession(file);
            } catch (Throwable ignored) {
            }
        }
        return session;
    }

    public void setCurrentElement(@NotNull PsiElement currentElement) {
        this.currentElement = currentElement;
    }

    public AnnotationHolder getHolder() {
        return proxy;
    }

    public static AnnotationHolder createProxy(@NotNull ProblemsHolder problemsHolder, @NotNull PsiElement currentElement) {
        OceanInspectionAnnotationHolderAdapter adapter = new OceanInspectionAnnotationHolderAdapter(problemsHolder);
        adapter.setCurrentElement(currentElement);
        return adapter.getHolder();
    }

    private InvocationHandler createHolderHandler() {
        return (proxy, method, args) -> {
            String methodName = method.getName();

            switch (methodName) {
                case "isBatchMode" -> {
                    return Boolean.FALSE;
                }
                case "getCurrentAnnotationSession" -> {
                    return getOrCreateSession();
                }
                case "newAnnotation", "newSilentAnnotation" -> {
                    HighlightSeverity severity = (args != null && args.length > 0 && args[0] instanceof HighlightSeverity)
                            ? (HighlightSeverity) args[0]
                            : HighlightSeverity.INFORMATION;
                    String message = (args != null && args.length > 1 && args[1] instanceof String)
                            ? (String) args[1]
                            : "";

                    PsiElement targetEl = currentElement;
                    if (targetEl == null) {
                        try {
                            targetEl = problemsHolder.getFile();
                        } catch (Throwable ignored) {
                        }
                    }
                    return createAnnotationBuilderProxy(problemsHolder, targetEl, severity, message, seenProblems);
                }
                case "toString" -> {
                    return "OceanInspectionHolderProxy";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                }
            }

            return null;
        };
    }

    private static Object createAnnotationBuilderProxy(
            @NotNull ProblemsHolder problemsHolder,
            @NotNull PsiElement fallbackElement,
            @NotNull HighlightSeverity severity,
            @NotNull String message,
            @NotNull Set<String> seenProblems) {

        final PsiElement[] targetElement = new PsiElement[]{fallbackElement};
        final TextRange[] targetRange = new TextRange[]{null};
        final List<LocalQuickFix> fixes = new ArrayList<>();

        InvocationHandler builderHandler = (proxy, method, args) -> {
            String name = method.getName();

            if ("range".equals(name) && args != null && args.length == 1) {
                if (args[0] instanceof PsiElement psi) {
                    targetElement[0] = psi;
                } else if (args[0] instanceof ASTNode ast) {
                    targetElement[0] = ast.getPsi();
                } else if (args[0] instanceof TextRange tr) {
                    targetRange[0] = tr;
                }
                return proxy;
            }

            if (("withFix".equals(name) || "newFix".equals(name)) && args != null && args.length > 0) {
                if (args[0] instanceof IntentionAction intention) {
                    fixes.add(asQuickFix(intention));
                }
                return proxy;
            }

            if ("newLocalQuickFix".equals(name) && args != null && args.length > 0) {
                if (args[0] instanceof LocalQuickFix fix) {
                    fixes.add(fix);
                }
                return proxy;
            }

            if ("create".equals(name) || "createAnnotation".equals(name)) {
                boolean isProblem = severity == HighlightSeverity.ERROR || severity == HighlightSeverity.WARNING || "ERROR".equalsIgnoreCase(severity.getName()) || "WARNING".equalsIgnoreCase(severity.getName()) || severity.myVal >= HighlightSeverity.WARNING.myVal;

                if (isProblem && !message.isEmpty()) {
                    boolean isError = (severity == HighlightSeverity.ERROR ||
                            "ERROR".equalsIgnoreCase(severity.getName()) ||
                            severity.myVal >= HighlightSeverity.ERROR.myVal);
                    ProblemHighlightType highlightType = isError
                            ? ProblemHighlightType.GENERIC_ERROR
                            : ProblemHighlightType.GENERIC_ERROR_OR_WARNING;

                    PsiElement target = (targetElement[0] != null && targetElement[0].isValid())
                            ? targetElement[0]
                            : fallbackElement;

                    if (target.isValid()) {
                        try {
                            int offset = 0;
                            if (targetRange[0] != null) {
                                offset = targetRange[0].getStartOffset();
                            } else {
                                try {
                                    TextRange tr = target.getTextRange();
                                    if (tr != null) {
                                        offset = tr.getStartOffset();
                                    } else {
                                        offset = target.getTextOffset();
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            String dedupKey = offset + ":" + message.trim();
                            if (!seenProblems.add(dedupKey)) {
                                return null;
                            }

                            LocalQuickFix[] fixesArray = fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
                            TextRange range = targetRange[0];

                            if (range != null) {
                                PsiFile file = target.getContainingFile();
                                if (file == null) {
                                    try {
                                        file = problemsHolder.getFile();
                                    } catch (Throwable ignored) {}
                                }

                                PsiElement exactTarget = target;
                                if (file != null && file.isValid()) {
                                    PsiElement found = file.findElementAt(range.getStartOffset());
                                    if (found != null && found.isValid()) {
                                        exactTarget = found;
                                    }
                                }

                                TextRange elRange = exactTarget.getTextRange();
                                if (elRange != null &&
                                        range.getStartOffset() >= elRange.getStartOffset() &&
                                        range.getEndOffset() <= elRange.getEndOffset()) {
                                    TextRange relativeRange = range.shiftRight(-elRange.getStartOffset());
                                    if (isError) {
                                        problemsHolder.registerProblem(
                                                exactTarget,
                                                relativeRange,
                                                message,
                                                fixesArray
                                        );
                                    } else {
                                        problemsHolder.registerProblem(
                                                exactTarget,
                                                message,
                                                highlightType,
                                                relativeRange,
                                                fixesArray
                                        );
                                    }
                                } else {
                                    problemsHolder.registerProblem(
                                            exactTarget,
                                            message,
                                            highlightType,
                                            fixesArray
                                    );
                                }
                            } else {
                                problemsHolder.registerProblem(
                                        target,
                                        message,
                                        highlightType,
                                        fixesArray
                                );
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return null;
            }

            if ("toString".equals(name)) {
                return "OceanAnnotationBuilderProxy";
            }

            // Any chaining method returning AnnotationBuilder or FixBuilder
            if (method.getReturnType().isAssignableFrom(proxy.getClass()) ||
                    method.getReturnType().equals(AnnotationBuilder.class) ||
                    method.getReturnType().getName().contains("FixBuilder")) {
                return proxy;
            }

            return null;
        };

        return Proxy.newProxyInstance(
                OceanInspectionAnnotationHolderAdapter.class.getClassLoader(),
                new Class<?>[]{AnnotationBuilder.class},
                builderHandler
        );
    }

    private static LocalQuickFix asQuickFix(IntentionAction action) {
        if (action instanceof LocalQuickFix lqf) {
            return lqf;
        }
        return new LocalQuickFix() {
            @NotNull
            @Override
            public String getName() {
                return action.getText();
            }

            @NotNull
            @Override
            public String getFamilyName() {
                return action.getFamilyName();
            }

            @Override
            public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
                PsiElement element = descriptor.getPsiElement();
                if (element != null && element.isValid()) {
                    action.invoke(project, null, element.getContainingFile());
                }
            }
        };
    }
}
