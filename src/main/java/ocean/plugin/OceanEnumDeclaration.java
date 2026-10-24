package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanEnumDeclaration extends OceanNamedElementImpl {
    public OceanEnumDeclaration(@NotNull ASTNode node) {
        super(node);
    }
}
