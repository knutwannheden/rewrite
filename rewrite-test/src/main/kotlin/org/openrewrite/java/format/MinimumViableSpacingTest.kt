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
package org.openrewrite.java.format

import org.junit.jupiter.api.Test
import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.RecipeTest
import org.openrewrite.java.JavaParser
import org.openrewrite.java.JavaVisitor
import org.openrewrite.java.tree.Space

interface MinimumViableSpacingTest : RecipeTest {
    override val recipe: Recipe
        get() = object : JavaVisitor<ExecutionContext>() {
            override fun visitSpace(space: Space, loc: Space.Location, p: ExecutionContext): Space {
                return space.withWhitespace("")
            }
        }.toRecipe().doNext(MinimumViableSpacingVisitor<ExecutionContext>().toRecipe())

    @Test
    fun method(jp: JavaParser) = assertChanged(
        jp,
        before = """,
            class A {
                public <T> void foo() {
                }
            }
        """,
        after = """
            class A{public <T> void foo(){}}
        """
    )
}
