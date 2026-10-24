package ocean.plugin.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import ocean.plugin.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OceanReference extends PsiReferenceBase<PsiElement> implements PsiPolyVariantReference {
    private final String key;

    public OceanReference(@NotNull PsiElement element, TextRange textRange) {
        super(element, textRange);
        key = element.getText().substring(textRange.getStartOffset(), textRange.getEndOffset());
    }

    @NotNull
    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        Project project = myElement.getProject();

        // 1. Search in current file (local variables, methods, classes)
        PsiFile currentFile = myElement.getContainingFile();
        List<ResolveResult> results = new ArrayList<>(findInFile(currentFile, key));
        
        // 2. Search in other project files if not found or if it could be a global class/method
        Collection<VirtualFile> virtualFiles = FileTypeIndex.getFiles(OceanFileType.INSTANCE, GlobalSearchScope.allScope(project));
        for (VirtualFile virtualFile : virtualFiles) {
            if (virtualFile.equals(currentFile.getVirtualFile())) continue;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile instanceof OceanFile) {
                results.addAll(findInFile(psiFile, key));
            }
        }
        
        return results.toArray(new ResolveResult[0]);
    }

    private List<ResolveResult> findInFile(PsiFile file, String name) {
        List<ResolveResult> results = new ArrayList<>();
        
        // Find Classes
        PsiTreeUtil.findChildrenOfType(file, OceanClassDeclaration.class).stream()
                .filter(c -> name.equals(c.getName()))
                .forEach(c -> results.add(new PsiElementResolveResult(c)));

        // Find Methods
        PsiTreeUtil.findChildrenOfType(file, OceanMethodDeclaration.class).stream()
                .filter(m -> name.equals(m.getName()))
                .forEach(m -> results.add(new PsiElementResolveResult(m)));

        // Find Fields/Variables (simplistic)
        PsiTreeUtil.findChildrenOfType(file, OceanVariableDeclaration.class).stream()
                .filter(v -> name.equals(v.getName()))
                .forEach(v -> results.add(new PsiElementResolveResult(v)));
        
        PsiTreeUtil.findChildrenOfType(file, OceanParameter.class).stream()
                .filter(p -> name.equals(p.getName()))
                .forEach(p -> results.add(new PsiElementResolveResult(p)));

        return results;
    }

    @Nullable
    @Override
    public PsiElement resolve() {
        ResolveResult[] resolveResults = multiResolve(false);
        return resolveResults.length == 1 ? resolveResults[0].getElement() : null;
    }

    @NotNull
    @Override
    public Object @NotNull [] getVariants() {
        // For code completion - we could add all visible symbols here
        return new Object[0];
    }
}
