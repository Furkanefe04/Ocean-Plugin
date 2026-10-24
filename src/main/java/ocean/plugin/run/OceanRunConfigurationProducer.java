package ocean.plugin.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ocean.plugin.OceanFile;
import org.jetbrains.annotations.NotNull;

public class OceanRunConfigurationProducer extends LazyRunConfigurationProducer<OceanRunConfiguration> {
    @NotNull
    @Override
    public ConfigurationFactory getConfigurationFactory() {
        return OceanConfigurationType.getInstance().getConfigurationFactories()[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull OceanRunConfiguration configuration, @NotNull ConfigurationContext context, @NotNull Ref<PsiElement> sourceElement) {
        PsiElement location = context.getPsiLocation();
        if (location == null) return false;

        PsiFile psiFile = location.getContainingFile();
        if (!(psiFile instanceof OceanFile)) return false;

        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) return false;

        configuration.setOceanFilePath(virtualFile.getPath());
        configuration.setName(virtualFile.getName());
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull OceanRunConfiguration configuration, @NotNull ConfigurationContext context) {
        PsiElement location = context.getPsiLocation();
        if (location == null) return false;

        PsiFile psiFile = location.getContainingFile();
        if (!(psiFile instanceof OceanFile)) return false;

        VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile == null) return false;

        return virtualFile.getPath().equals(configuration.getOceanFilePath());
    }
}
