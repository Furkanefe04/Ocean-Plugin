package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanLabelDeclaration extends OceanNamedElementImpl {
    public OceanLabelDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
