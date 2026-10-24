package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanLoopVariable extends OceanNamedElementImpl {
    public OceanLoopVariable(@NotNull ASTNode node) {
        super(node);
    }
}
