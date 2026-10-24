package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanClassDeclaration extends OceanNamedElementImpl {
    public OceanClassDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
