package ocean.plugin.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name = "ocean.plugin.settings.OceanSettingsState",
        storages = @Storage("OceanSettings.xml")
)
public class OceanSettingsState implements PersistentStateComponent<OceanSettingsState> {
    public String oceanHome = "";

    public static OceanSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(OceanSettingsState.class);
    }

    @Nullable
    @Override
    public OceanSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull OceanSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
