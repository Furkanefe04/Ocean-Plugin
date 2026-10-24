package ocean.plugin.run;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

public class OceanRunConfigurationOptions extends RunConfigurationOptions {
    private final StoredProperty<String> oceanFilePath = string("").provideDelegate(this, "oceanFilePath");

    public String getOceanFilePath() {
        return oceanFilePath.getValue(this);
    }

    public void setOceanFilePath(String path) {
        oceanFilePath.setValue(this, path);
    }
}
