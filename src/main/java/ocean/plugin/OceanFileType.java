package ocean.plugin;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class OceanFileType extends LanguageFileType {

    public static final OceanFileType INSTANCE = new OceanFileType();

    private OceanFileType() {
        super(OceanLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "Ocean File";
    }

    @Override
    public @NotNull String getDescription() {
        return "Ocean language file";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "ocean";
    }

    @Override
    public Icon getIcon() {
        return OceanIcons.ICON;
    }
}