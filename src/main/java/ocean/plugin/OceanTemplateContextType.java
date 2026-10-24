package ocean.plugin;

import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public class OceanTemplateContextType extends TemplateContextType {
    protected OceanTemplateContextType() {
        super("OCEAN", "Ocean");
    }

    @Override
    public boolean isInContext(@NotNull PsiFile file, int offset) {
        return file.getName().endsWith(".ocean");
    }
}
