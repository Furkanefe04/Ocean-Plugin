package ocean.plugin;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public class OceanFile extends PsiFileBase {
    public OceanFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, OceanLanguage.INSTANCE);
    }

    @NotNull
    @Override
    public FileType getFileType() {
        return OceanFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "Ocean File";
    }
}
