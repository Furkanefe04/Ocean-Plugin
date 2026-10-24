package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanFieldDeclaration extends OceanNamedElementImpl {
    public OceanFieldDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
