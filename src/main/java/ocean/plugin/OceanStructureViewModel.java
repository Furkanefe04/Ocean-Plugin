package ocean.plugin;

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanStructureViewModel extends StructureViewModelBase implements StructureViewModel.ElementInfoProvider {
    public OceanStructureViewModel(@NotNull PsiFile psiFile, @Nullable Editor editor) {
        super(psiFile, editor, new OceanStructureViewElement(psiFile));
        withSuitableClasses(OceanClassDeclaration.class, OceanInterfaceDeclaration.class,
                OceanEnumDeclaration.class, OceanAnnotationDeclaration.class,
                OceanMethodDeclaration.class, OceanFieldDeclaration.class,
                OceanVariableDeclarator.class, OceanEnumConstant.class,
                OceanAnnotationMemberDeclaration.class);
    }

    @NotNull
    @Override
    public Sorter @NotNull [] getSorters() {
        return new Sorter[]{Sorter.ALPHA_SORTER};
    }

    @Override
    public boolean isAlwaysLeaf(StructureViewTreeElement element) {
        Object val = element.getValue();
        return val instanceof OceanVariableDeclaration ||
                val instanceof OceanVariableDeclarator ||
                val instanceof OceanMethodDeclaration ||
                val instanceof OceanEnumConstant ||
                val instanceof OceanAnnotationMemberDeclaration;
    }

    @Override
    public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
        return false;
    }
}
