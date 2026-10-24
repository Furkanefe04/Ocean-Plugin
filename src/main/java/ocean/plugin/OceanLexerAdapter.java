package ocean.plugin;

import com.intellij.psi.tree.IElementType;
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor;
import org.antlr.v4.runtime.Token;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;

public class OceanLexerAdapter extends ANTLRLexerAdaptor {

    private record SplitToken(IElementType type, int start, int end) {
    }

    private final Queue<SplitToken> pendingTokens = new ArrayDeque<>();
    private SplitToken currentSplitToken = null;

    public OceanLexerAdapter(OceanLexer lexer) {
        super(OceanLanguage.INSTANCE, lexer);
    }

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        pendingTokens.clear();
        currentSplitToken = null;
        super.start(buffer, startOffset, endOffset, initialState);
    }

    @Override
    public void advance() {
        if (!pendingTokens.isEmpty()) {
            currentSplitToken = pendingTokens.poll();
            return;
        }
        currentSplitToken = null;
        super.advance();
        checkAndSplitCurrentToken();
    }

    private void checkAndSplitCurrentToken() {
        Token token = getCurrentToken();
        if (token != null) {
            int tType = token.getType();
            if (tType == OceanLexer.RSHIFT || tType == OceanLexer.URSHIFT) {
                IElementType gtType = getTokenType(OceanLexer.GT);
                int start = token.getStartIndex();
                int count = (tType == OceanLexer.URSHIFT) ? 3 : 2;
                currentSplitToken = new SplitToken(gtType, start, start + 1);
                for (int i = 1; i < count; i++) {
                    pendingTokens.add(new SplitToken(gtType, start + i, start + i + 1));
                }
            }
        }
    }

    @Nullable
    @Override
    public IElementType getTokenType() {
        if (currentSplitToken != null) {
            return currentSplitToken.type;
        }
        return super.getTokenType();
    }

    @Override
    public int getTokenStart() {
        if (currentSplitToken != null) {
            return currentSplitToken.start;
        }
        return super.getTokenStart();
    }

    @Override
    public int getTokenEnd() {
        if (currentSplitToken != null) {
            return currentSplitToken.end;
        }
        return super.getTokenEnd();
    }
}
