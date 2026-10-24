package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanAnnotationDeclaration extends OceanNamedElementImpl {
    public OceanAnnotationDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
