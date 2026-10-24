package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanParameter extends OceanNamedElementImpl {
    public OceanParameter(@NotNull ASTNode node) {
        super(node);
    }
}
