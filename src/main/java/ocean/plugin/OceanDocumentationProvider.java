package ocean.plugin;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiWhiteSpace;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.jetbrains.annotations.Nullable;
import java.util.Map;

public class OceanDocumentationProvider extends AbstractDocumentationProvider {

    private static final Map<String, String> STDLIB_DOCS = Map.ofEntries(
            Map.entry("OceanList", "<b>OceanList&lt;T&gt;</b><br/>Ocean standard dynamic list collection (backed by <code>java.util.ArrayList</code>).<br/><pre>variable list = [1, 2, 3];\nlist.add(4);</pre>"),
            Map.entry("OceanMap", "<b>OceanMap&lt;K, V&gt;</b><br/>Ocean standard key-value map (backed by <code>java.util.HashMap</code>).<br/><pre>variable map = {\"key\": \"value\"};\nmap.put(\"a\", 1);</pre>"),
            Map.entry("OceanSet", "<b>OceanSet&lt;T&gt;</b><br/>Ocean standard unique elements set (backed by <code>java.util.HashSet</code>).<br/><pre>variable set = #{1, 2, 3};\nset.add(4);</pre>"),
            Map.entry("OceanResult", "<b>OceanResult&lt;T, E&gt;</b><br/>Result carrier for robust error handling and success/failure modeling.<br/><pre>OceanResult.ok(data)\nOceanResult.err(\"Error\")</pre>"),
            Map.entry("OceanOptional", "<b>OceanOptional&lt;T&gt;</b><br/>Null-safe optional value wrapper.<br/><pre>OceanOptional.of(value)\nOceanOptional.empty()</pre>"),
            Map.entry("OceanPair", "<b>OceanPair&lt;A, B&gt;</b><br/>Two-element tuple carrier.<br/><pre>variable pair = new OceanPair&lt;&gt;(\"key\", 42);</pre>"),
            Map.entry("OceanTriple", "<b>OceanTriple&lt;A, B, C&gt;</b><br/>Three-element tuple carrier.<br/><pre>variable trip = new OceanTriple&lt;&gt;(1, \"two\", 3.0);</pre>"),
            Map.entry("OceanFile", "<b>OceanFile</b><br/>File read, write, and I/O utility class."),
            Map.entry("OceanHttp", "<b>OceanHttp</b><br/>HTTP GET/POST request and client utilities."),
            Map.entry("OceanHttpResponse", "<b>OceanHttpResponse</b><br/>HTTP response containing status code (<code>status</code>) and response payload (<code>body</code>)."),
            Map.entry("OceanJson", "<b>OceanJson</b><br/>JSON serialization and parsing utilities."),
            Map.entry("OceanMath", "<b>OceanMath</b><br/>Advanced mathematical calculations utility class."),
            Map.entry("OceanRegex", "<b>OceanRegex</b><br/>Regular expression parsing, matching, and replacement utilities."),
            Map.entry("OceanString", "<b>OceanString</b><br/>Extended string manipulation and utility functions."),
            Map.entry("OceanOutput", "<b>OceanOutput(content)</b><br/>Prints text or expression to standard output (equivalent to <code>System.out.println</code>)."),
            Map.entry("OceanInput", "<b>OceanInput</b><br/>Built-in scanner type for reading console user input (backed by <code>Scanner</code>)."),
            Map.entry("Task", "<b>Task</b><br/>Java 21 Virtual Threads and structured concurrency utility class.<br/><pre>Task.run(() -> { ... });\nTask.supply(() -> value);</pre>"),
            Map.entry("ScopedValue", "<b>ScopedValue&lt;T&gt;</b><br/>Lightweight, immutable, and safely propagated scoped value (Java 21+ Scoped Values).<br/><pre>ScopedValue.where(KEY, value).run(() -> { ... });</pre>"),
            Map.entry("OceanStack", "<b>OceanStack&lt;T&gt;</b><br/>Ocean standard LIFO (Last-In-First-Out) stack collection.<br/><pre>variable stack = new OceanStack&lt;&gt;();\nstack.push(10);\nstack.pop();</pre>"),
            Map.entry("OceanQueue", "<b>OceanQueue&lt;T&gt;</b><br/>Ocean standard FIFO (First-In-First-Out) queue collection.<br/><pre>variable queue = new OceanQueue&lt;&gt;();\nqueue.enqueue(10);\nqueue.dequeue();</pre>"),
            Map.entry("OceanCounter", "<b>OceanCounter&lt;T&gt;</b><br/>Element frequency and count data structure.<br/><pre>variable counter = new OceanCounter&lt;&gt;();\ncounter.add(\"item\");</pre>"),
            Map.entry("OceanBitSet", "<b>OceanBitSet</b><br/>Dynamic bit set with logical bitwise operations (AND, OR, XOR)."),
            Map.entry("OceanDate", "<b>OceanDate</b><br/>Date and timestamp formatting and manipulation utilities."),
            Map.entry("OceanTime", "<b>OceanTime</b><br/>Time and instant utilities."),
            Map.entry("StrBuilder", "<b>StrBuilder</b><br/>High-performance dynamic character string builder."),
            Map.entry("OceanStringBuilder", "<b>OceanStringBuilder</b><br/>Chained fluent string builder wrapper."),
            Map.entry("OceanIntList", "<b>OceanIntList</b><br/>Zero-boxing primitive <code>int</code> array-backed list collection."),
            Map.entry("OceanLongList", "<b>OceanLongList</b><br/>Zero-boxing primitive <code>long</code> array-backed list collection."),
            Map.entry("OceanDoubleList", "<b>OceanDoubleList</b><br/>Zero-boxing primitive <code>double</code> array-backed list collection."),
            Map.entry("OceanFloatList", "<b>OceanFloatList</b><br/>Zero-boxing primitive <code>float</code> array-backed list collection."),
            Map.entry("OceanBooleanList", "<b>OceanBooleanList</b><br/>Zero-boxing primitive <code>boolean</code> array-backed list collection."),
            Map.entry("OceanByteList", "<b>OceanByteList</b><br/>Zero-boxing primitive <code>byte</code> array-backed list collection."),
            Map.entry("OceanShortList", "<b>OceanShortList</b><br/>Zero-boxing primitive <code>short</code> array-backed list collection."),
            Map.entry("OceanCharList", "<b>OceanCharList</b><br/>Zero-boxing primitive <code>char</code> array-backed list collection.")
    );

    private static final Map<String, String> KEYWORD_DOCS = Map.ofEntries(
            Map.entry("data", "<b>data class</b><br/>Data class that automatically generates <code>getters</code>, <code>setters</code>, <code>equals</code>, <code>hashCode</code>, and <code>toString</code>."),
            Map.entry("sealed", "<b>sealed</b><br/>Modifier that restricts inheritance only to classes specified in the <code>restricts</code> clause."),
            Map.entry("non-sealed", "<b>non-sealed</b><br/>Modifier that lifts inheritance restrictions in a sealed hierarchy."),
            Map.entry("restricts", "<b>restricts</b><br/>Specifies the allowed subclasses that may extend a sealed class or implement a sealed interface.<br/><pre>sealed class Shape restricts Circle, Rectangle</pre>"),
            Map.entry("async", "<b>async function</b><br/>Asynchronous function that runs cooperatively/asynchronously and returns a <code>CompletableFuture</code>."),
            Map.entry("await", "<b>await</b><br/>Awaits completion of an asynchronous task or future and resolves its result."),
            Map.entry("annotation", "<b>annotation</b><br/>Ocean user-defined annotation (metadata) declaration."),
            Map.entry("trying", "<b>trying</b><br/>Exception handling block.<br/><pre>trying {\n    // code\n} catch (Exception e) {\n    // handle\n}</pre>"),
            Map.entry("switch", "<b>switch</b><br/>Pattern matching and multi-branch control flow expression."),
            Map.entry("verify", "<b>verify</b><br/>Assertion statement for verifying invariant conditions.<br/><pre>verify x > 0 : \"x must be positive\";</pre>"),
            Map.entry("matches", "<b>matches</b><br/>Pattern matching operator for type checking and pattern binding.<br/><pre>if (value matches String s) { ... }</pre>"),
            Map.entry("as", "<b>as</b><br/>Explicit type cast operator.<br/><pre>variable s = value as String;</pre>"),
            Map.entry("@tailrec", "<b>@tailrec</b><br/>Marks recursive function for tail-call optimization verification by compiler."),
            Map.entry("@deprecated", "<b>@deprecated</b><br/>Marks a symbol as deprecated, warning callers of impending removal.")
    );

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        PsiElement target = element != null ? element : originalElement;
        if (target == null) return null;

        String text = target.getText().trim();
        if (STDLIB_DOCS.containsKey(text)) {
            return STDLIB_DOCS.get(text);
        }
        if (KEYWORD_DOCS.containsKey(text)) {
            return KEYWORD_DOCS.get(text);
        }

        if (target instanceof PsiClass psiClass) {
            String qName = psiClass.getQualifiedName();
            String kind = psiClass.isInterface() ? "interface" : (psiClass.isEnum() ? "enum" : "class");
            return "<b>" + escape(kind) + "</b> <code>" + escape(qName != null ? qName : text) + "</code><br/><i>Java Platform Type</i>";
        }

        if (target instanceof PsiNamedElement named) {
            String name = named.getName();
            String kind = kindOf(target);
            String signature = signatureOf(target);
            String docComment = findDocComment(target);

            StringBuilder html = new StringBuilder();
            html.append("<b>").append(escape(kind)).append("</b>");
            if (name != null) {
                html.append(" <code>").append(escape(name)).append("</code>");
            }
            if (signature != null && !signature.isBlank()) {
                html.append("<br/><pre>").append(escape(signature)).append("</pre>");
            }
            if (docComment != null && !docComment.isBlank()) {
                html.append("<div style='margin-top:8px;'>").append(docComment).append("</div>");
            }
            return html.toString();
        }

        return null;
    }

    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        PsiElement target = element != null ? element : originalElement;
        if (target == null) return null;

        String text = target.getText().trim();
        if (STDLIB_DOCS.containsKey(text)) {
            return text + " (Ocean Standard Library)";
        }
        if (KEYWORD_DOCS.containsKey(text)) {
            return text + " (Ocean Keyword)";
        }

        if (target instanceof PsiClass psiClass) {
            String qName = psiClass.getQualifiedName();
            return (qName != null ? qName : text) + " (Java Platform Type)";
        }

        if (target instanceof PsiNamedElement named) {
            String signature = signatureOf(target);
            if (signature != null && !signature.isBlank()) return signature;
            String name = named.getName();
            return name == null ? kindOf(target) : kindOf(target) + " " + name;
        }
        return null;
    }

    private String kindOf(PsiElement element) {
        String txt = element.getText();
        if (element instanceof OceanClassDeclaration || (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_classDeclaration)) {
            if (txt != null) {
                if (txt.startsWith("data class") || txt.contains("data class ")) return "data class";
                if (txt.startsWith("sealed class") || txt.contains("sealed class ")) return "sealed class";
            }
            return "class";
        }
        if (element instanceof OceanInterfaceDeclaration || (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_interfaceDeclaration)) {
            if (txt != null && (txt.startsWith("sealed interface") || txt.contains("sealed interface "))) {
                return "sealed interface";
            }
            return "interface";
        }
        if (element instanceof OceanEnumDeclaration) return "enum";
        if (element instanceof OceanEnumConstant) return "enum constant";
        if (element instanceof OceanAnnotationDeclaration) return "annotation";
        if (element instanceof OceanAnnotationMemberDeclaration) return "annotation member";
        if (element instanceof OceanMethodDeclaration || (element.getNode() != null && element.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_methodDeclaration)) {
            PsiElement extReceiver = OceanScopeDiscovery.getExtensionReceiverTypeNode(element);
            String extPrefix = extReceiver != null ? (extReceiver.getText().trim() + ".") : "";
            if (txt != null && (txt.startsWith("async ") || txt.contains("async function ") || txt.contains("async def "))) {
                return extReceiver != null ? ("extension async function on " + extReceiver.getText().trim()) : "async function";
            }
            return extReceiver != null ? ("extension function on " + extReceiver.getText().trim()) : "function";
        }
        if (element instanceof OceanFieldDeclaration) return "field";
        if (element instanceof OceanVariableDeclaration) return "variable";
        if (element instanceof OceanVariableDeclarator d) return d.isField() ? "field" : "variable";
        if (element instanceof OceanParameter) return "parameter";
        if (element instanceof OceanTypeParameter) return "type parameter";
        return "symbol";
    }

    private String signatureOf(PsiElement element) {
        PsiElement parent = (element instanceof OceanVariableDeclarator) ? element.getParent() : (element instanceof OceanNamedElement ? element : element.getParent());
        if (parent == null) return element.getText();
        String text = parent.getText();
        int brace = text.indexOf('{');
        if (brace >= 0) text = text.substring(0, brace).trim() + " { ... }";
        if (text.length() > 240) text = text.substring(0, 237) + "...";
        return text;
    }

    private String findDocComment(PsiElement element) {
        PsiElement parent = (element instanceof OceanVariableDeclarator) ? element.getParent() : element;
        if (parent == null) return null;

        PsiElement prev = parent.getPrevSibling();
        while (prev instanceof PsiWhiteSpace || (prev != null && prev.getNode() != null && prev.getNode().getElementType() instanceof RuleIElementType ri && ri.getRuleIndex() == OceanParser.RULE_annotation)) {
            prev = prev.getPrevSibling();
        }

        if (prev != null) {
            String commentText = prev.getText();
            if (commentText != null) {
                commentText = commentText.trim();
                if (commentText.startsWith("/**") && commentText.endsWith("*/")) {
                    return renderDocComment(commentText);
                } else if (commentText.startsWith("//")) {
                    return "<p>" + escape(commentText.substring(2).trim()) + "</p>";
                }
            }
        }
        return null;
    }

    private String renderDocComment(String rawDoc) {
        // Strip /** and */
        String inner = rawDoc.substring(3, rawDoc.length() - 2).trim();
        String[] lines = inner.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!trimmed.isEmpty()) {
                if (trimmed.startsWith("@param")) {
                    sb.append("<br/><b>Parameter:</b> ").append(escape(trimmed.substring(6).trim()));
                } else if (trimmed.startsWith("@return") || trimmed.startsWith("@returns")) {
                    int prefixLen = trimmed.startsWith("@returns") ? 8 : 7;
                    sb.append("<br/><b>Returns:</b> ").append(escape(trimmed.substring(prefixLen).trim()));
                } else if (trimmed.startsWith("@throws")) {
                    sb.append("<br/><b>Throws:</b> ").append(escape(trimmed.substring(7).trim()));
                } else {
                    if (sb.length() > 0 && !sb.toString().endsWith("<br/>") && !sb.toString().endsWith("<p>")) {
                        sb.append(" ");
                    }
                    sb.append(escape(trimmed));
                }
            }
        }
        return "<p>" + sb + "</p>";
    }

    private String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
