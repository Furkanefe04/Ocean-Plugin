package ocean.plugin.run;

import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import ocean.plugin.OceanIcons;
import org.jetbrains.annotations.NotNull;

public class OceanConfigurationType extends ConfigurationTypeBase {
    public static final String ID = "OceanConfigurationType";

    protected OceanConfigurationType() {
        super(ID, "Ocean", "Ocean run configuration", OceanIcons.ICON);
        addFactory(new OceanConfigurationFactory(this));
    }

    @NotNull
    public static OceanConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(OceanConfigurationType.class);
    }
}
