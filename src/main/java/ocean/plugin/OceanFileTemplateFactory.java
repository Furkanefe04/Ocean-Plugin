package ocean.plugin;

import com.intellij.ide.fileTemplates.FileTemplateDescriptor;
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptor;
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptorFactory;

public class OceanFileTemplateFactory implements FileTemplateGroupDescriptorFactory {
    @Override
    public FileTemplateGroupDescriptor getFileTemplatesDescriptor() {
        FileTemplateGroupDescriptor group = new FileTemplateGroupDescriptor("Ocean", OceanIcons.ICON);
        group.addTemplate(new FileTemplateDescriptor("Ocean Class.ocean", OceanIcons.ICON));
        group.addTemplate(new FileTemplateDescriptor("Ocean Interface.ocean", OceanIcons.ICON));
        group.addTemplate(new FileTemplateDescriptor("Ocean Enum.ocean", OceanIcons.ICON));
        group.addTemplate(new FileTemplateDescriptor("Ocean Annotation.ocean", OceanIcons.ICON));
        group.addTemplate(new FileTemplateDescriptor("Ocean Data Class.ocean", OceanIcons.ICON));
        return group;
    }
}
