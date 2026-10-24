package ocean.plugin.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class OceanConfigurationFactory extends ConfigurationFactory {
    protected OceanConfigurationFactory(@NotNull ConfigurationType type) {
        super(type);
    }

    @Override
    public @NotNull String getId() {
        return "OceanConfigurationFactory";
    }

    @Override
    public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new OceanRunConfiguration(project, this, "Ocean");
    }

    @Override
    public Class<? extends BaseState> getOptionsClass() {
        return OceanRunConfigurationOptions.class;
    }
}
