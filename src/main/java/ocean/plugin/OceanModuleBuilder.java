package ocean.plugin;

import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class OceanModuleBuilder extends ModuleBuilder {
    public String basePackage = "org.example";

    @Override
    public void setupRootModel(@NotNull ModifiableRootModel rootModel) {
        doAddContentEntry(rootModel);
        
        // Inherit the Project SDK (Java) so standard libraries are visible
        rootModel.inheritSdk();

        VirtualFile root = rootModel.getContentEntries()[0].getFile();
        if (root != null) {
            try {
                // 1. Create 'src' directory

                // 2. Create base package directory structure recursively
                VirtualFile deepestDir = root.createChildDirectory(this, "src");
                if (basePackage != null && !basePackage.trim().isEmpty()) {
                    String[] parts = basePackage.split("\\.");
                    for (String part : parts) {
                        deepestDir = deepestDir.createChildDirectory(this, part);
                    }
                }

                // 3. Create 'Main.ocean' file inside package
                VirtualFile mainFile = deepestDir.createChildData(this, "Main.ocean");
                StringBuilder sb = new StringBuilder();
                if (basePackage != null && !basePackage.trim().isEmpty()) {
                    sb.append("package ").append(basePackage).append(";\n\n");
                }
                sb.append("class Main {\n")
                  .append("    main() {\n")
                  .append("        OceanOutput(\"Hello Ocean!\");\n")
                  .append("    }\n")
                  .append("}\n");
                mainFile.setBinaryContent(sb.toString().getBytes(StandardCharsets.UTF_8));
                
                // 4. Create build.gradle
                VirtualFile buildGradle = root.createChildData(this, "build.gradle");
                String gradleContent = "plugins {\n" +
                                       "    id 'java'\n" +
                                       "}\n\n" +
                                       "group = '" + basePackage + "'\n" +
                                       "version = '1.0-SNAPSHOT'\n\n" +
                                       "repositories {\n" +
                                       "    mavenCentral()\n" +
                                       "}\n\n" +
                                       "dependencies {\n" +
                                       "    // Add external dependencies here\n" +
                                       "}\n\n" +
                                       "task copyOceanDeps(type: Copy) {\n" +
                                       "    from configurations.runtimeClasspath\n" +
                                       "    into 'build/ocean_deps'\n" +
                                       "}\n";
                buildGradle.setBinaryContent(gradleContent.getBytes(StandardCharsets.UTF_8));
                
                // 5. Create settings.gradle
                VirtualFile settingsGradle = root.createChildData(this, "settings.gradle");
                String settingsContent = "rootProject.name = '" + root.getName() + "'\n";
                settingsGradle.setBinaryContent(settingsContent.getBytes(StandardCharsets.UTF_8));

            } catch (IOException e) {
                // Log or handle error
            }
        }
    }

    @Override
    public ModuleType<?> getModuleType() {
        return OceanModuleType.getInstance();
    }

    @Override
    public ModuleWizardStep[] createWizardSteps(@NotNull WizardContext wizardContext, @NotNull ModulesProvider modulesProvider) {
        return new ModuleWizardStep[]{
            new ModuleWizardStep() {
                private final JPanel panel = new JPanel(new GridBagLayout());
                private final JTextField pkgField = new JTextField(basePackage);

                {
                    GridBagConstraints c = new GridBagConstraints();
                    c.fill = GridBagConstraints.HORIZONTAL;
                    c.weightx = 0.0;
                    c.gridx = 0;
                    c.gridy = 0;
                    c.insets = new Insets(10, 10, 10, 5);
                    panel.add(new JLabel("Base package:"), c);

                    c.weightx = 1.0;
                    c.gridx = 1;
                    c.insets = new Insets(10, 5, 10, 10);
                    panel.add(pkgField, c);
                }

                @Override
                public JComponent getComponent() {
                    return panel;
                }

                @Override
                public void updateDataModel() {
                    basePackage = pkgField.getText().trim();
                    if (basePackage.isEmpty()) basePackage = "org.example";
                }
            }
        };
    }
}
