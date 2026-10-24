package ocean.plugin.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanRunConfiguration extends RunConfigurationBase<OceanRunConfigurationOptions> {
    protected OceanRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @NotNull
    @Override
    protected OceanRunConfigurationOptions getOptions() {
        return (OceanRunConfigurationOptions) super.getOptions();
    }

    public String getOceanFilePath() {
        return getOptions().getOceanFilePath();
    }

    public void setOceanFilePath(String path) {
        getOptions().setOceanFilePath(path);
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new OceanSettingsEditor();
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new OceanRunProfileState(environment, this);
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        // Compatibility with older versions if needed
    }

    @Override
    public void writeExternal(@NotNull Element element) throws WriteExternalException {
        super.writeExternal(element);
    }
}
