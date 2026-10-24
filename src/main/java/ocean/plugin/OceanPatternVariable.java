package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanPatternVariable extends OceanNamedElementImpl {
    public OceanPatternVariable(@NotNull ASTNode node) {
        super(node);
    }
}
