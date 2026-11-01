package ocean.plugin.run;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class OceanSettingsEditor extends SettingsEditor<OceanRunConfiguration> {
    private final JPanel myPanel;
    private final TextFieldWithBrowseButton oceanFileField = new TextFieldWithBrowseButton();

    public OceanSettingsEditor() {
        oceanFileField.addBrowseFolderListener(new TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor("ocean")));
        myPanel = new JPanel(new BorderLayout());
        JPanel inner = FormBuilder.createFormBuilder()
                .addLabeledComponent("Ocean main file:", oceanFileField)
                .getPanel();
        myPanel.add(inner, BorderLayout.NORTH);
    }

    @Override
    protected void resetEditorFrom(@NotNull OceanRunConfiguration s) {
        oceanFileField.setText(s.getOceanFilePath());
    }

    @Override
    protected void applyEditorTo(@NotNull OceanRunConfiguration s) {
        s.setOceanFilePath(oceanFileField.getText());
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return myPanel;
    }
}
