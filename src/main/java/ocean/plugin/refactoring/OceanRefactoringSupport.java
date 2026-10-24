package ocean.plugin.refactoring;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.RefactoringActionHandler;
import ocean.plugin.OceanNamedElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanRefactoringSupport extends RefactoringSupportProvider {
    @Override
    public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement elementToRename, @Nullable PsiElement context) {
        return elementToRename instanceof OceanNamedElement;
    }

    @Override
    public boolean isInplaceRenameAvailable(@NotNull PsiElement elementToRename, @Nullable PsiElement context) {
        return elementToRename instanceof OceanNamedElement;
    }

    @Override
    public RefactoringActionHandler getIntroduceVariableHandler() {
        return new OceanIntroduceVariableHandler();
    }

    @Override
    public RefactoringActionHandler getExtractMethodHandler() {
        return new OceanExtractMethodHandler();
    }
}
