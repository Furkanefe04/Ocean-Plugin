package ocean.plugin;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanTargetElementEvaluator extends TargetElementEvaluatorEx2 {
    @Override
    public boolean isAcceptableNamedParent(@NotNull PsiElement parent) {
        // Do not automatically accept parent declarations for arbitrary child clicks.
        // Child clicks should only resolve to the declaration if they are the actual name identifier,
        // which is handled in getNamedElement().
        return false;
    }

    @Override
    public @Nullable PsiElement getNamedElement(@NotNull PsiElement element) {
        String text = element.getText();
        if (OceanKeywords.isKeyword(text)) {
            return null;
        }
        PsiElement parent = element.getParent();
        if (parent instanceof OceanNamedElement named) {
            PsiElement nameId = named.getNameIdentifier();
            if (nameId == element || (PsiTreeUtil.isAncestor(nameId, element, false))) {
                return named;
            }
        }
        if (element instanceof OceanNamedElement named) {
            String name = named.getName();
            if (name != null && !OceanKeywords.isKeyword(name)) {
                return named;
            }
        }
        return null;
    }

    @Override
    public @Nullable PsiElement getGotoDeclarationTarget(@NotNull PsiElement element, @Nullable PsiElement navElement) {
        String text = element.getText();
        if (OceanKeywords.isKeyword(text)) {
            return null;
        }
        if (navElement != null && OceanKeywords.isKeyword(navElement.getText())) {
            return null;
        }
        return null;
    }

    @Override
    public @Nullable PsiElement adjustTargetElement(Editor editor, int flags, int offset, @Nullable PsiElement targetElement) {
        if (editor != null && editor.getProject() != null) {
            editor.getDocument();
            PsiFile file = PsiDocumentManager.getInstance(editor.getProject()).getPsiFile(editor.getDocument());
            if (file != null) {
                PsiElement at = file.findElementAt(offset);
                if (at != null && OceanKeywords.isKeyword(at.getText())) {
                    return null;
                }
            }
        }
        if (targetElement != null && OceanKeywords.isKeyword(targetElement.getText())) {
            return null;
        }
        return targetElement;
    }

    @Override
    public @Nullable PsiElement adjustReferenceOrReferencedElement(@NotNull PsiFile file, @NotNull Editor editor, int offset, int flags, @Nullable PsiElement refElement) {
        PsiElement at = file.findElementAt(offset);
        if (at != null && OceanKeywords.isKeyword(at.getText())) {
            return null;
        }
        if (refElement != null && OceanKeywords.isKeyword(refElement.getText())) {
            return null;
        }
        return refElement;
    }

    @Override
    public @Nullable PsiElement adjustElement(Editor editor, int flags, @Nullable PsiElement element, @Nullable PsiElement contextElement) {
        if (element != null && OceanKeywords.isKeyword(element.getText())) {
            return null;
        }
        if (contextElement != null && OceanKeywords.isKeyword(contextElement.getText())) {
            return null;
        }
        return element;
    }

    @Override
    public @NotNull ThreeState isAcceptableReferencedElement(@Nullable PsiElement element, @Nullable PsiElement referenceOrReferencedElement) {
        if (element != null && OceanKeywords.isKeyword(element.getText())) {
            return ThreeState.NO;
        }
        if (referenceOrReferencedElement != null && OceanKeywords.isKeyword(referenceOrReferencedElement.getText())) {
            return ThreeState.NO;
        }
        return ThreeState.UNSURE;
    }
}
