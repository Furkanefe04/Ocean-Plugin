package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanMethodDeclaration extends OceanNamedElementImpl {
    public OceanMethodDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
