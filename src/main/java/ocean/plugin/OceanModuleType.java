package ocean.plugin;

import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.ModuleTypeManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class OceanModuleType extends ModuleType<OceanModuleBuilder> {
    private static final String ID = "OCEAN_MODULE_TYPE";

    public OceanModuleType() {
        super(ID);
    }

    public static OceanModuleType getInstance() {
        return (OceanModuleType) ModuleTypeManager.getInstance().findByID(ID);
    }

    @NotNull
    @Override
    public OceanModuleBuilder createModuleBuilder() {
        return new OceanModuleBuilder();
    }

    @NotNull
    @Override
    public String getName() {
        return "Ocean Project";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Create a new Ocean language project";
    }

    @NotNull
    @Override
    public Icon getNodeIcon(@Deprecated boolean isOpened) {
        return OceanIcons.ICON;
    }
}
