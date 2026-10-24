package ocean.plugin;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class OceanPsiNode extends ASTWrapperPsiElement {
    public OceanPsiNode(@NotNull ASTNode node) {
        super(node);
    }
}
