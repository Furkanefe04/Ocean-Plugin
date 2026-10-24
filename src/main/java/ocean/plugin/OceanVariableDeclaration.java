package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanVariableDeclaration extends OceanNamedElementImpl {
    public OceanVariableDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
