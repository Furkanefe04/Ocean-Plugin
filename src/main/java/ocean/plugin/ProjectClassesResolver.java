package ocean.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.Collection;
import java.util.List;

public class ProjectClassesResolver {
    public static void resolveClass(Project project, String name, List<ResolveResult> results) {
        if (name == null || name.isEmpty()) return;

        // Fast path: Search files named name.ocean
        for (VirtualFile virtualFile : FilenameIndex.getVirtualFilesByName(project, name + ".ocean", GlobalSearchScope.projectScope(project))) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile == null) continue;
            Collection<PsiElement> classes = PsiTreeUtil.findChildrenOfAnyType(psiFile,
                OceanClassDeclaration.class, OceanInterfaceDeclaration.class,
                OceanEnumDeclaration.class, OceanAnnotationDeclaration.class);
            for (PsiElement cls : classes) {
                if (name.equals(((OceanNamedElement)cls).getName())) {
                    results.add(new PsiElementResolveResult(cls));
                }
            }
        }

        // Fallback: Search Ocean Files only if nothing found via fast path
        if (results.isEmpty()) {
            FileTypeIndex.processFiles(OceanFileType.INSTANCE, virtualFile -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                if (psiFile != null) {
                    // Efficient search for class/interface/enum/annotation declarations
                    Collection<PsiElement> classes = PsiTreeUtil.findChildrenOfAnyType(psiFile,
                        OceanClassDeclaration.class, OceanInterfaceDeclaration.class,
                        OceanEnumDeclaration.class, OceanAnnotationDeclaration.class);
                    for (PsiElement cls : classes) {
                        if (name.equals(((OceanNamedElement)cls).getName())) {
                            results.add(new PsiElementResolveResult(cls));
                        }
                    }
                }
                return true;
            }, GlobalSearchScope.projectScope(project));
        }

        // 2. Search Java Classes
        if (results.isEmpty()) {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            
            // A. Try as a fully qualified name
            PsiClass javaClass = facade.findClass(name, GlobalSearchScope.allScope(project));
            if (javaClass != null) {
                results.add(new PsiElementResolveResult(javaClass));
                return;
            }

            // B. Try java.lang prefix
            javaClass = facade.findClass("java.lang." + name, GlobalSearchScope.allScope(project));
            if (javaClass != null) {
                results.add(new PsiElementResolveResult(javaClass));
                return;
            }

            // C. Try Short Name Cache
            PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
            PsiClass[] foundClasses = cache.getClassesByName(name, GlobalSearchScope.allScope(project));
            for (PsiClass cls : foundClasses) {
                results.add(new PsiElementResolveResult(cls));
            }
        }
    }
}
