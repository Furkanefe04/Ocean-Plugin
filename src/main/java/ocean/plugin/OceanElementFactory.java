package ocean.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;

public class OceanElementFactory {
    public static PsiElement createIdentifier(Project project, String name) {
        String dummyContent = "class " + name + " {}";
        OceanFile file = (OceanFile) PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.ocean", OceanFileType.INSTANCE, dummyContent);
        OceanClassDeclaration cls = PsiTreeUtil.findChildOfType(file, OceanClassDeclaration.class);
        if (cls != null && cls.getNameIdentifier() != null) {
            return cls.getNameIdentifier();
        }
        PsiElement[] ids = PsiTreeUtil.collectElements(file, e -> 
            e.getNode() != null && e.getNode().getElementType().toString().contains("anyId")
        );
        for (PsiElement id : ids) {
            if (name.equals(id.getText())) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    public static OceanClassDeclaration createClass(Project project, String name) {
        String dummyContent = "class " + name + " {}";
        OceanFile file = (OceanFile) PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.ocean", OceanFileType.INSTANCE, dummyContent);
        return PsiTreeUtil.findChildOfType(file, OceanClassDeclaration.class);
    }

    public static OceanAnnotationDeclaration createAnnotation(Project project, String name) {
        String dummyContent = "annotation " + name + " {}";
        OceanFile file = (OceanFile) PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.ocean", OceanFileType.INSTANCE, dummyContent);
        return PsiTreeUtil.findChildOfType(file, OceanAnnotationDeclaration.class);
    }

    public static OceanMethodDeclaration createMethod(Project project, String signature) {
        String dummyContent = "class Dummy { " + signature + " {} }";
        OceanFile file = (OceanFile) PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.ocean", OceanFileType.INSTANCE, dummyContent);
        return PsiTreeUtil.findChildOfType(file, OceanMethodDeclaration.class);
    }

    public static PsiElement createImport(Project project, String qualifiedName) {
        String dummyContent = "import " + qualifiedName + ";";
        OceanFile file = (OceanFile) PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.ocean", OceanFileType.INSTANCE, dummyContent);
        return file.getFirstChild();
    }
}
