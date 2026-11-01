package ocean.plugin.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import ocean.plugin.OceanParser;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OceanRunLineMarkerContributor extends RunLineMarkerContributor {
    @Nullable
    @Override
    public Info getInfo(@NotNull PsiElement element) {
        // If element is the "main" keyword or identifier and it is the direct name of a method declaration
        if (element instanceof LeafPsiElement && "main".equals(element.getText())) {
            PsiElement parent = element.getParent();
            if (parent != null && parent.getNode() != null && parent.getNode().getElementType() instanceof RuleIElementType pri && pri.getRuleIndex() == OceanParser.RULE_anyId) {
                PsiElement methodNode = parent.getParent();
                if (methodNode != null && methodNode.getNode() != null && methodNode.getNode().getElementType() instanceof RuleIElementType mri && mri.getRuleIndex() == OceanParser.RULE_methodDeclaration) {
                    // Ensure this anyId is the actual method name identifier (direct child of methodDeclaration)
                    if (isDirectMethodName(methodNode, parent)) {
                        return new Info(AllIcons.Actions.Execute, ExecutorAction.getActions(0), psiElement -> "Run 'main()'");
                    }
                }
            }
        }
        return null;
    }

    private static boolean isDirectMethodName(PsiElement methodNode, PsiElement anyIdNode) {
        for (PsiElement child : methodNode.getChildren()) {
            if (child.getNode() != null && child.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_anyId) {
                return child == anyIdNode;
            }
        }
        return false;
    }
}
