package ocean.plugin;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanVariableDeclarator extends OceanNamedElementImpl {
    public OceanVariableDeclarator(@NotNull ASTNode node) {
        super(node);
    }

    public boolean isField() {
        return getParent() instanceof OceanFieldDeclaration;
    }

    @Nullable
    public PsiElement getInitializer() {
        for (PsiElement child : getChildren()) {
            if (isRule(child, OceanParser.RULE_expression)) {
                return child;
            }
        }
        return null;
    }
}
