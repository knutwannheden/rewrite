/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.openrewrite.Cursor
import org.openrewrite.ExecutionContext
import org.openrewrite.RecipeTest
import org.openrewrite.internal.ListUtils
import org.openrewrite.java.format.MinimumViableSpacingVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.Statement

interface JavaTemplateTest : RecipeTest {

    @Disabled
    @Test
    fun lamdaMethodParameterTest(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitMethodInvocation(method: J.MethodInvocation, p: ExecutionContext): J.MethodInvocation {
                var m = super.visitMethodInvocation(method, p)
                m = m.withArgs(JavaTemplate.builder("() -> \"test\"").build().generate(cursor));
                return m
            }
        }.toRecipe(),
        before = """
            import java.util.function.Supplier;
            class A {
                void test() {
                    printStuff("test");
                }
                void printStuff(String string) {}
                void printStuff(Supplier<String> stringSupplier) {}
            }
        """,
        after = """
            import java.util.function.Supplier;
            class A {
                void test() {
                    printStuff(() -> "test");
                }
                void printStuff(String string) {}
                void printStuff(Supplier<String> stringSupplier) {}
            } 
        """,
        afterConditions = { cu -> cu.classes }
    )

    @Test
    fun beforeMethodBodyStatement(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J {
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.MethodDecl) {
                    val template = JavaTemplate.builder("others.add(#{});").build()

                    //Test to make sure the template extraction is working correctly. Both of these calls should return
                    //a single template element and should have type attribution

                    //Test when statement is the insertion scope is before the first statement in the block
                    var generatedMethodInvocations = template
                        .generate<J.MethodInvocation>(
                            Cursor(cursor, block.statements[0]),
                            (parent.params[0] as J.VariableDecls).vars[0]
                        )
                    assertThat(generatedMethodInvocations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodInvocations[0].type).isNotNull

                    //Test when insertion scope is between two statements in a block
                    generatedMethodInvocations = template
                        .generate(
                            Cursor(cursor, block.statements[1]),
                            (parent.params[0] as J.VariableDecls).vars[0]
                        )
                    assertThat(generatedMethodInvocations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodInvocations[0].type).isNotNull

                    return block.withStatements(
                        ListUtils.concat(
                            generatedMethodInvocations[0],
                            block.statements
                        )
                    )
                }
                return super.visitBlock(block, p)
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    n++;
                    n++;
                }
            }
        """,
        after = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                    n++;
                    n++;
                }
            }
        """,
        afterConditions = { cu -> cu.classes }
    )

    @Test
    fun lastInMethodBodyStatement(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J {
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.MethodDecl) {
                    val template = JavaTemplate.builder("others.add(#{});").build()
                    //Test to make sure the template extraction is working correctly. Both of these calls should return
                    //a single template element and should have type attribution

                    //Test when insertion scope is between two statements in a block
                    var generatedMethodInvocations = generateLastInBlock<J.MethodInvocation>(template,
                        Cursor(cursor, block),
                        (parent.params[0] as J.VariableDecls).vars[0])
                    assertThat(generatedMethodInvocations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodInvocations[0].type).isNotNull

                    //Test when insertion scope is after the last statements in a block
                    generatedMethodInvocations = generateLastInBlock(template,
                        Cursor(cursor, block),
                        (parent.params[0] as J.VariableDecls).vars[0])
                    assertThat(generatedMethodInvocations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodInvocations[0].type).isNotNull

                    return block.withStatements(
                        ListUtils.concat(
                            block.statements,
                            generatedMethodInvocations[0]
                        )
                    )
                }
                return super.visitBlock(block, p)
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    n++;
                    n++;
                }
            }
        """,
        after = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    n++;
                    n++;
                    others.add(m);
                }
            }
        """
    )

    @Test
    fun addToEmptyMethodBody(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J {
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.MethodDecl) {
                    val template = JavaTemplate.builder("others.add(#{});").build()
                    //Test to make sure the template extraction is working correctly. Both of these calls should return
                    //a single template element and should have type attribution

                    //Test when insertion scope is between two statements in a block
                    var generatedMethodInvocations = generateLastInBlock<J.MethodInvocation>(template,
                        Cursor(cursor, block),
                        (parent.params[0] as J.VariableDecls).vars[0])
                    assertThat(generatedMethodInvocations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodInvocations[0].type).isNotNull

                    //Test when insertion scope is after the last statements in a block
                    generatedMethodInvocations = generateLastInBlock(template,
                        Cursor(cursor, block),
                        (parent.params[0] as J.VariableDecls).vars[0])
                    assertThat(generatedMethodInvocations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodInvocations[0].type).isNotNull

                    return block.withStatements(
                        ListUtils.concat(
                            block.statements,
                            generatedMethodInvocations[0]
                        )
                    )
                }
                return super.visitBlock(block, p)
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                }
            }
        """,
        after = """
            import java.util.List;
            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                }
            }
        """
    )

    @Test
    fun addToEmptyClassBody(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitClassDecl(classDecl: J.ClassDecl, p: ExecutionContext): J.ClassDecl {

                val c = super.visitClassDecl(classDecl, p)

                val template = JavaTemplate.builder("private String name = null;").build()

                //Test when insertion scope is between two statements in a block
                val generatedVariabelDecls = generateLastInBlock<J.VariableDecls>(template, Cursor(cursor, c.body))
                assertThat(generatedVariabelDecls).`as`("The list of generated statements should be 1.")
                    .hasSize(1)
                assertThat(generatedVariabelDecls[0].typeAsClass).isNotNull

                return c.withBody(c.body.withStatements(
                    ListUtils.concat(
                        c.body.statements,
                        generatedVariabelDecls[0]
                    )))
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            public class A {
            }
        """,
        after = """
            import java.util.List;
            public class A {
                private String name = null;
            }
        """
    )

    @Test
    fun addMethodToClass(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J.Block {
                var b = super.visitBlock(block, p)
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.ClassDecl) {
                    val template = JavaTemplate.builder(
                        """
                            char incrementCounterByListSize(List<String> list) {
                                n += list.size();
                                return 'f';
                            }
                        """
                    ).build()

                    //Test generating the method using generateAfter and make sure the extraction is correct and has
                    //type attribution.
                    var generatedMethodDeclarations = generateLastInBlock<J.MethodDecl>(template, Cursor(cursor, block))
                    assertThat(generatedMethodDeclarations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodDeclarations[0].type).isNotNull

                    //Test generating the method using generateBefore and make sure the extraction is correct and has
                    //type attribution.
                    generatedMethodDeclarations = template.generate(
                        Cursor(cursor, block.statements[0])
                    )
                    assertThat(generatedMethodDeclarations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodDeclarations[0].type).isNotNull

                    b = b.withStatements(
                        ListUtils.concat(
                            block.statements,
                            generatedMethodDeclarations[0]
                        )
                    )
                }
                return b
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            import static java.util.Collections.emptyList;

            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                }
            }
        """,
        after = """
            import java.util.List;
            import static java.util.Collections.emptyList;

            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                }
            
                char incrementCounterByListSize(List<String> list) {
                    n += list.size();
                    return 'f';
                }
            }
        """
    )

    @Test
    fun addStaticMethodToClass(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J.Block {
                var b = super.visitBlock(block, p)
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.ClassDecl) {
                    val template = JavaTemplate.builder(
                        """
                            static char incrementCounterByListSize(List<String> list) {
                                n += list.size();
                                return 'f';
                            }
                        """
                    ).build()

                    //Test generating the method using generateAfter and make sure the extraction is correct and has
                    //type attribution.
                    var generatedMethodDeclarations = generateLastInBlock<J.MethodDecl>(template, Cursor(cursor, block))
                    assertThat(generatedMethodDeclarations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodDeclarations[0].type).isNotNull

                    //Test generating the method using generateBefore and make sure the extraction is correct and has
                    //type attribution.
                    generatedMethodDeclarations = template.generate(
                        Cursor(cursor, block.statements[0])
                    )
                    assertThat(generatedMethodDeclarations).`as`("The list of generated statements should be 1.")
                        .hasSize(1)
                    assertThat(generatedMethodDeclarations[0].type).isNotNull

                    b = b.withStatements(
                        ListUtils.concat(
                            block.statements,
                            generatedMethodDeclarations[0]
                        )
                    )
                }
                return b
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            import static java.util.Collections.emptyList;

            public class A {
                static int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                }
            }
        """,
        after = """
            import java.util.List;
            import static java.util.Collections.emptyList;

            public class A {
                static int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                }
            
                static char incrementCounterByListSize(List<String> list) {
                    n += list.size();
                    return 'f';
                }
            }
        """
    )

    @Test
    fun changeMethodInvocations(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {

            val template: JavaTemplate = JavaTemplate.builder("withString(#{}).length();")
                .build()

            init {
                setCursoringOn()
            }

            override fun visitMethodInvocation(method: J.MethodInvocation, p: ExecutionContext): J.MethodInvocation {
                val m = super.visitMethodInvocation(method, p)
                if (m.name.ident.simpleName != "countLetters") return m
                val argument = m.args[0]
                val generatedMethodInvocations = template.generate<J.MethodInvocation>(cursor, argument)
                assertThat(generatedMethodInvocations).`as`("The list of generated invocations should be 1.")
                    .hasSize(1)
                assertThat(generatedMethodInvocations[0].type).isNotNull
                return generatedMethodInvocations[0].withPrefix(method.prefix)
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            import java.util.stream.Collectors;
            import java.util.Arrays;

            public class A {
                String name = "Jill";
                {
                    countLetters("fred");
                }
                int n = countLetters(name);
                void foo() {
                    if (countLetters("fred") == 4) {
                        System.out.println("Letter Count :" + countLetters(name));
                    }
                    int letterCount = countLetters("fred") == 4 ? countLetters("fred") : 0;
                    letterCount = countLetters(name) != 3 ? 0 : countLetters(name);
                    String sub = "Fred".substring(0, countLetters("fred"));
                    Integer count = Integer.valueOf(countLetters("fred"));
                    for (int index = 0; index < countLetters(name); index++) {
                    }
                    int index = 0;
                    while (index < countLetters(name)) {
                        index++;
                    }
                    switch (countLetters(name)) {
                        case 4:
                        default:
                            break;
                    }
                    List<String> names = Arrays.asList("fred", "joe", "jill");
                    List<Integer> counts = names.stream().map(this::countLetters).collect(Collectors.toList());
                }
                public int countLetters(String sourceString) {
                    return sourceString.length();
                }
                public StringStub withString(String source) {
                    return new StringStub(source);
                }
                public class StringStub {
                    String source;
                    private StringStub(String source) {
                        this.source = source;
                    }
                    public int length() {
                        return source.length();
                    }
                }
           }
        """,
        after = """
            import java.util.List;
            import java.util.stream.Collectors;
            import java.util.Arrays;

            public class A {
                String name = "Jill";
                {
                    withString("fred").length();
                }
                int n = withString(name).length();
                void foo() {
                    if (withString("fred").length() == 4) {
                        System.out.println("Letter Count :" + withString(name).length());
                    }
                    int letterCount = withString("fred").length() == 4 ? withString("fred").length() : 0;
                    letterCount = withString(name).length() != 3 ? 0 : withString(name).length();
                    String sub = "Fred".substring(0, withString("fred").length());
                    Integer count = Integer.valueOf(withString("fred").length());
                    for (int index = 0; index < withString(name).length(); index++) {
                    }
                    int index = 0;
                    while (index < withString(name).length()) {
                        index++;
                    }
                    switch (withString(name).length()) {
                        case 4:
                        default:
                            break;
                    }
                    List<String> names = Arrays.asList("fred", "joe", "jill");
                    List<Integer> counts = names.stream().map(this::countLetters).collect(Collectors.toList());
                }
                public int countLetters(String sourceString) {
                    return sourceString.length();
                }
                public StringStub withString(String source) {
                    return new StringStub(source);
                }
                public class StringStub {
                    String source;
                    private StringStub(String source) {
                        this.source = source;
                    }
                    public int length() {
                        return source.length();
                    }
                }
           }
        """
    )

    @Test
    fun addAnnotationToMethod(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitMethod(method: J.MethodDecl, p: ExecutionContext): J.MethodDecl {
                var m = super.visitMethod(method, p)
                val generatedElements = JavaTemplate.builder("@Deprecated").build()
                        .generate<J.Annotation>(Cursor(cursor, method))
                m = m.withAnnotations(
                    ListUtils.concat(
                        m.annotations,
                        generatedElements[0]
                    )
                )
                m = MinimumViableSpacingVisitor<ExecutionContext>().visitMethod(m, ExecutionContext.builder().build())
                return m
            }
        }.toRecipe(),
        before = """
            public class A {
                void foo() {
                }
            }
        """,
        after = """
            public class A {
                @Deprecated void foo() {
                }
            }
        """
    )

    @Test
    @Disabled
    fun addAnnotationToClass(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            val template = JavaTemplate.builder("@Deprecated").build()

            override fun visitClassDecl(clazz: J.ClassDecl, p: ExecutionContext): J.ClassDecl {
                var c = super.visitClassDecl(clazz, p)
                c = generate(template, c.coordinates().before())
                assertThat(c.annotations).`as`("The list of generated annotations should be 1.").hasSize(1)
                assertThat(c.annotations[0].type).isNotNull
                return c
            }
        }.toRecipe(),
        before = """
            public class A {
                void foo() {
                }
            }
        """,
        after = """
            @Deprecated public class A {
                void foo() {
                }
            }
        """
    )

    @Test
    fun addAnnotationToClassWithImports(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitClassDecl(clazz: J.ClassDecl, p: ExecutionContext): J.ClassDecl {
                var c = super.visitClassDecl(clazz, p)

                val generatedAnnotations = JavaTemplate.builder("@Deprecated").build()
                    .generate<J.Annotation>(Cursor(cursor, clazz))

                assertThat(generatedAnnotations).`as`("The list of generated annotations should be 1.").hasSize(1)
                assertThat(generatedAnnotations[0].type).isNotNull

                c = c.withAnnotations(ListUtils.concat(c.annotations, generatedAnnotations[0]))
                c = MinimumViableSpacingVisitor<ExecutionContext>().visitClassDecl(
                    c,
                    ExecutionContext.builder().build()
                )
                return c
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            
            public class A {
                void foo() {
                }
            }
        """,
        after = """
            import java.util.List;
            
            @Deprecated public class A {
                void foo() {
                }
            }
        """
    )

    @Test
    fun templateWithLocalMethodReference(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J.Block {
                var b = super.visitBlock(block, p)
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.MethodDecl && parent.name.ident.simpleName == "foo") {

                    val template = JavaTemplate.builder("\n#{};\n#{};").build()

                    //Test when statement is the insertion scope is before the first statement in the block
                    val generatedStatements = template
                        .generate<Statement>(
                            Cursor(cursor, b.statements[0]),
                            b.statements[1] as J,
                            b.statements[0] as J
                        )
                    //Make sure type attribution is valid on the generated method invocations.
                    assertThat(generatedStatements).`as`("The list of generated statements should be 2.").hasSize(2)
                    assertThat((generatedStatements[0] as J.MethodInvocation).type).`as`("The type information should be populated").isNotNull
                    assertThat((generatedStatements[1] as J.MethodInvocation).type).`as`("The type information should be populated").isNotNull

                    b = b.withStatements(generatedStatements)
                }
                return b
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            import static java.util.Collections.emptyList;

            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    incrementCounterByListSize(others);
                    others.add(m);
                }
                char incrementCounterByListSize(List<String> list) {
                    n += list.size();
                    return 'f';
                }
            }
        """,
        after = """
            import java.util.List;
            import static java.util.Collections.emptyList;

            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    others.add(m);
                    incrementCounterByListSize(others);
                }
                char incrementCounterByListSize(List<String> list) {
                    n += list.size();
                    return 'f';
                }
            }
        """
    )

    @Test
    fun templateWithSiblingClassMethodReference(jp: JavaParser) = assertChanged(
        jp,
        recipe = object : JavaIsoVisitor<ExecutionContext>() {

            //This test ensures that the source generation is working when a parameter contains a method invocation
            //to a method that exists in a sibling class. It
            init {
                setCursoringOn()
            }

            override fun visitBlock(block: J.Block, p: ExecutionContext): J.Block {
                var b = super.visitBlock(block, p)
                val parent = cursor.dropParentUntil { it is J }.getValue<J>()
                if (parent is J.If) {

                    val template = JavaTemplate.builder("#{};\n#{}").build()

                    //Test when statement is the insertion scope is before the first statement in the block
                    val generatedStatements = generateLastInBlock<Statement>(template,
                        cursor,
                        b.statements[1],
                        b.statements[0]
                    )

                    //Make sure type attribution is valid on the generated method invocations.
                    assertThat(generatedStatements).`as`("The list of generated statements should be 2.").hasSize(2)
                    assertThat((generatedStatements[0] as J.MethodInvocation).type).`as`("The type information should be populated").isNotNull
                    assertThat((generatedStatements[1] as J.MethodInvocation).type).`as`("The type information should be populated").isNotNull
                    b = b.withStatements(generatedStatements)
                }
                return b
            }
        }.toRecipe(),
        before = """
            import java.util.List;
            import java.util.ArrayList;
            import static java.util.Collections.emptyList;

            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    boolean flag = true;
                    List<String> clone;
                    if (flag) {
                        clone.add(m);
                        B.cloneList(others);
                    }
                    int fred = 8;
                }

                public static class B {
                    public static List<String> cloneList(List<String> list) {
                        return new ArrayList<>(list);
                    }
                }

                public static class C {

                    private int hello = 0;
                    private String nope = "nothing here";
                }
            }
        """,
        after = """
            import java.util.List;
            import java.util.ArrayList;
            import static java.util.Collections.emptyList;

            public class A {
                int n = 0;
                void foo(String m, List<String> others) {
                    boolean flag = true;
                    List<String> clone;
                    if (flag) {
                        B.cloneList(others);
                        clone.add(m);
                    }
                    int fred = 8;
                }

                public static class B {
                    public static List<String> cloneList(List<String> list) {
                        return new ArrayList<>(list);
                    }
                }

                public static class C {

                    private int hello = 0;
                    private String nope = "nothing here";
                }
            }
        """
    )
}
