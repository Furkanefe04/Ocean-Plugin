package ocean.plugin;

import com.intellij.codeInsight.template.TemplateContextType;
import org.jetbrains.annotations.NotNull;

public class OceanTemplateContextType extends TemplateContextType {
    protected OceanTemplateContextType() {
        super("OCEAN");
    }

    @Override
    public String getPresentableName() {
        return "Ocean";
    }

    @Override
    public boolean isInContext(@NotNull com.intellij.codeInsight.template.TemplateActionContext context) {
        return context.getFile().getName().endsWith(".ocean");
    }
}
