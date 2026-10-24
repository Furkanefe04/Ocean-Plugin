package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanCatchVariable extends OceanNamedElementImpl {
    public OceanCatchVariable(@NotNull ASTNode node) {
        super(node);
    }
}
