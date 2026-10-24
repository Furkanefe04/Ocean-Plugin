package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanInterfaceDeclaration extends OceanNamedElementImpl {
    public OceanInterfaceDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
