package ocean.plugin.navigation;

import com.intellij.navigation.ChooseByNameContributorEx;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FindSymbolParameters;
import com.intellij.util.indexing.IdFilter;
import ocean.plugin.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Contributor that exposes Ocean classes, interfaces, enums, records, and symbols
 * to IntelliJ's "Go to Class" (Ctrl+N) and "Go to Symbol" (Ctrl+Alt+Shift+N).
 */
public class OceanChooseByNameContributor implements ChooseByNameContributorEx {

    @Override
    public void processNames(@NotNull Processor<? super String> processor,
                             @NotNull GlobalSearchScope scope,
                             @Nullable IdFilter filter) {
        Project project = scope.getProject();
        if (project == null || project.isDisposed()) return;

        // 1. Process project classes
        Map<String, PsiElement> classes = OceanScopeDiscovery.getProjectOceanClasses(project);
        for (String name : classes.keySet()) {
            if (!processor.process(name)) {
                return;
            }
        }

        // 2. Process methods and fields across Ocean files
        FileTypeIndex.processFiles(OceanFileType.INSTANCE, vf -> {
            PsiFile pf = PsiManager.getInstance(project).findFile(vf);
            if (pf instanceof OceanFile) {
                for (PsiElement el : PsiTreeUtil.collectElements(pf, e -> {
                    if (e.getNode() == null) return false;
                    return e instanceof OceanMethodDeclaration || e instanceof OceanFieldDeclaration;
                })) {
                    String symName = OceanScopeDiscovery.findDeclarationName(el);
                    if (symName != null && !symName.isEmpty()) {
                        if (!processor.process(symName)) return false;
                    }
                }
            }
            return true;
        }, scope);
    }

    @Override
    public void processElementsWithName(@NotNull String name,
                                        @NotNull Processor<? super NavigationItem> processor,
                                        @NotNull FindSymbolParameters parameters) {
        Project project = parameters.getProject();
        if (project.isDisposed()) return;

        // 1. Check classes
        Map<String, PsiElement> classes = OceanScopeDiscovery.getProjectOceanClasses(project);
        PsiElement cls = classes.get(name);
        if (cls instanceof NavigationItem navItem) {
            if (!processor.process(navItem)) return;
        }

        // 2. Check methods and fields
        FileTypeIndex.processFiles(OceanFileType.INSTANCE, vf -> {
            PsiFile pf = PsiManager.getInstance(project).findFile(vf);
            if (pf instanceof OceanFile) {
                for (PsiElement el : PsiTreeUtil.collectElements(pf, e -> {
                    if (e.getNode() == null) return false;
                    return e instanceof OceanMethodDeclaration || e instanceof OceanFieldDeclaration;
                })) {
                    String symName = OceanScopeDiscovery.findDeclarationName(el);
                    if (name.equals(symName) && el instanceof NavigationItem navItem) {
                        if (!processor.process(navItem)) return false;
                    }
                }
            }
            return true;
        }, parameters.getSearchScope());
    }
}
