package ocean.plugin;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

public class OceanColorSettingsPage implements ColorSettingsPage {

    private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
            new AttributesDescriptor("Keyword", OceanSyntaxHighlighter.KEYWORD),
            new AttributesDescriptor("Modifier", OceanSyntaxHighlighter.MODIFIER),
            new AttributesDescriptor("String", OceanSyntaxHighlighter.STRING),
            new AttributesDescriptor("Number", OceanSyntaxHighlighter.NUMBER),
            new AttributesDescriptor("Line/block comment", OceanSyntaxHighlighter.COMMENT),
            new AttributesDescriptor("Identifier", OceanSyntaxHighlighter.IDENTIFIER),
            new AttributesDescriptor("Class name", OceanSyntaxHighlighter.CLASS_NAME),
            new AttributesDescriptor("Interface name", OceanSyntaxHighlighter.INTERFACE_NAME),
            new AttributesDescriptor("Method declaration", OceanSyntaxHighlighter.METHOD_DECLARATION),
            new AttributesDescriptor("Method call", OceanSyntaxHighlighter.METHOD_CALL),
            new AttributesDescriptor("Static method call", OceanSyntaxHighlighter.STATIC_METHOD),
            new AttributesDescriptor("Parameter", OceanSyntaxHighlighter.PARAMETER),
            new AttributesDescriptor("Local variable", OceanSyntaxHighlighter.LOCAL_VARIABLE),
            new AttributesDescriptor("Instance field", OceanSyntaxHighlighter.INSTANCE_FIELD),
            new AttributesDescriptor("Static field", OceanSyntaxHighlighter.STATIC_FIELD),
            new AttributesDescriptor("Primitive type", OceanSyntaxHighlighter.PRIMITIVE_TYPE),
            new AttributesDescriptor("Built-in function", OceanSyntaxHighlighter.STDLIB_FUNCTION),
            new AttributesDescriptor("String escape", OceanSyntaxHighlighter.STRING_ESCAPE),
            new AttributesDescriptor("Operator", OceanSyntaxHighlighter.OPERATOR),
            new AttributesDescriptor("Braces", OceanSyntaxHighlighter.BRACES),
            new AttributesDescriptor("Parentheses", OceanSyntaxHighlighter.PARENTHESES),
            new AttributesDescriptor("Brackets", OceanSyntaxHighlighter.BRACKETS),
            new AttributesDescriptor("Semicolon", OceanSyntaxHighlighter.SEMICOLON),
            new AttributesDescriptor("Comma", OceanSyntaxHighlighter.COMMA),
            new AttributesDescriptor("Dot", OceanSyntaxHighlighter.DOT),
            new AttributesDescriptor("Annotation", OceanSyntaxHighlighter.ANNOTATION),
            new AttributesDescriptor("Type parameter", OceanSyntaxHighlighter.TYPE_PARAMETER)
    };

    @Nullable
    @Override
    public Icon getIcon() {
        return OceanIcons.ICON;
    }

    @NotNull
    @Override
    public SyntaxHighlighter getHighlighter() {
        return new OceanSyntaxHighlighter();
    }

    @NotNull
    @Override
    public String getDemoText() {
        return """
            package com.example.ocean;

            import java.lang.Math;
            import static java.math.BigDecimal.<static_field>ONE</static_field>;

            // Single line comment
            /* Multi-line block comment */

            <annotation>@Target</annotation>(ElementType.TYPE)
            public annotation <annotation>Service</annotation> {
                String value() default "";
            }

            public interface <interface>Repository</interface><<type_param>T</type_param>> {
                <type_param>T</type_param> function <method_decl>findById</method_decl>(int <param>id</param>);
            }

            public sealed interface <interface>Shape</interface> restricts <class>Circle</class> {
                double function <method_decl>area</method_decl>();
            }

            <annotation>@Service</annotation>("circleService")
            public data class <class>Circle</class>(double <param>radius</param>) implements <interface>Shape</interface> {
                public static final double <static_field>PI</static_field> = 3.14159;

                public double function <method_decl>area</method_decl>() {
                    return <static_field>PI</static_field> * <instance_field>radius</instance_field> * <instance_field>radius</instance_field>;
                }
            }

            public class <class>DemoService</class> {
                private String <instance_field>serviceName</instance_field>;

                public <class>DemoService</class>(String <param>name</param>) {
                    this.<instance_field>serviceName</instance_field> = <param>name</param>;
                }

                public async CompletableFuture<String> function <method_decl>fetchDataAsync</method_decl>(int <param>id</param>) {
                    variable <local_var>items</local_var> = [1, 2, 3];
                    <stdlib>OceanOutput</stdlib>($"Fetching data for id={id}");
                    variable <local_var>res</local_var> = await doFetch(<param>id</param>);
                    return <local_var>res</local_var>.<method_call>toUpperCase</method_call>();
                }

                public void function <method_decl>processShape</method_decl>(<interface>Shape</interface> <param>shape</param>) {
                    switch (<param>shape</param>) {
                        case <class>Circle</class> <local_var>c</local_var> -> <stdlib>OceanOutput</stdlib>($"Circle: {<local_var>c</local_var>.<method_call>area</method_call>()}");
                        default -> <stdlib>OceanOutput</stdlib>("Unknown shape");
                    }
                }
            }
            """;
    }

    @Nullable
    @Override
    public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        Map<String, TextAttributesKey> map = new HashMap<>();
        map.put("class", OceanSyntaxHighlighter.CLASS_NAME);
        map.put("interface", OceanSyntaxHighlighter.INTERFACE_NAME);
        map.put("method_decl", OceanSyntaxHighlighter.METHOD_DECLARATION);
        map.put("method_call", OceanSyntaxHighlighter.METHOD_CALL);
        map.put("static_method", OceanSyntaxHighlighter.STATIC_METHOD);
        map.put("param", OceanSyntaxHighlighter.PARAMETER);
        map.put("local_var", OceanSyntaxHighlighter.LOCAL_VARIABLE);
        map.put("instance_field", OceanSyntaxHighlighter.INSTANCE_FIELD);
        map.put("static_field", OceanSyntaxHighlighter.STATIC_FIELD);
        map.put("annotation", OceanSyntaxHighlighter.ANNOTATION);
        map.put("stdlib", OceanSyntaxHighlighter.STDLIB_FUNCTION);
        map.put("type_param", OceanSyntaxHighlighter.TYPE_PARAMETER);
        return map;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Ocean";
    }
}
