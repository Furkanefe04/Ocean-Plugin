package ocean.plugin.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import ocean.plugin.OceanAnnotator;
import org.jetbrains.annotations.NotNull;

/**
 * Inspection that runs Ocean semantic and compiler error checks during batch inspection
 * (Analyze > Inspect Code...), grouping all errors under the "Ocean" category.
 */
public class OceanCompilerInspection extends LocalInspectionTool {

    @NotNull
    @Override
    public String getDisplayName() {
        return "Compiler and semantic errors";
    }

    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "Ocean";
    }

    @NotNull
    @Override
    public String getShortName() {
        return "OceanCompiler";
    }

    private static final OceanAnnotator SHARED_ANNOTATOR = new OceanAnnotator();

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        OceanInspectionAnnotationHolderAdapter adapter = new OceanInspectionAnnotationHolderAdapter(holder);
        AnnotationHolder annotationHolder = adapter.getHolder();

        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                try {
                    adapter.setCurrentElement(element);
                    SHARED_ANNOTATOR.annotate(element, annotationHolder);
                } catch (Throwable ignored) {
                    // Prevent single malformed AST element from breaking the rest of file inspection
                }
                super.visitElement(element);
            }
        };
    }
}
