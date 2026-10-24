package ocean.plugin;

import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanEnumConstant extends OceanNamedElementImpl {
    public OceanEnumConstant(@NotNull ASTNode node) {
        super(node);
    }
}
