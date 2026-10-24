package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanAnnotationMemberDeclaration extends OceanNamedElementImpl {
    public OceanAnnotationMemberDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
