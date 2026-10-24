package ocean.plugin;

import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import org.jetbrains.annotations.NotNull;

public class OceanCreateFileAction extends CreateFileFromTemplateAction {
    public OceanCreateFileAction() {
        super("Ocean File", "Creates a new Ocean file", OceanIcons.ICON);
    }

    @Override
    protected void buildDialog(@NotNull Project project, @NotNull PsiDirectory directory, CreateFileFromTemplateDialog.Builder builder) {
        builder.setTitle("New Ocean File")
               .addKind("Ocean Class", OceanIcons.ICON, "Ocean Class.ocean")
               .addKind("Ocean Interface", OceanIcons.ICON, "Ocean Interface.ocean")
               .addKind("Ocean Enum", OceanIcons.ICON, "Ocean Enum.ocean")
               .addKind("Ocean Annotation", OceanIcons.ICON, "Ocean Annotation.ocean")
               .addKind("Ocean Data Class", OceanIcons.ICON, "Ocean Data Class.ocean");
    }

    @Override
    protected String getActionName(PsiDirectory directory, @NotNull String newName, String templateName) {
        return "Create Ocean File " + newName;
    }
}
