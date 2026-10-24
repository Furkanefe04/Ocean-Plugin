package ocean.plugin.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class OceanSettingsConfigurable implements Configurable {
    private final TextFieldWithBrowseButton oceanHomeField = new TextFieldWithBrowseButton();

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Ocean";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        oceanHomeField.addBrowseFolderListener("Select Ocean Home Directory", null, null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor());
        
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Ocean home directory:", oceanHomeField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    public boolean isModified() {
        OceanSettingsState settings = OceanSettingsState.getInstance();
        return !oceanHomeField.getText().equals(settings.oceanHome);
    }

    @Override
    public void apply() {
        OceanSettingsState settings = OceanSettingsState.getInstance();
        settings.oceanHome = oceanHomeField.getText();
    }

    @Override
    public void reset() {
        OceanSettingsState settings = OceanSettingsState.getInstance();
        oceanHomeField.setText(settings.oceanHome);
    }
}
