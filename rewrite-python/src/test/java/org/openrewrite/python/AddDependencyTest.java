/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Path;

import static org.openrewrite.python.Assertions.pyproject;
import static org.openrewrite.python.Assertions.uv;

class AddDependencyTest implements RewriteTest {

    @Test
    void addDependencyWithResolvedProject(@TempDir Path tempDir) {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("flask", ">=2.0", null, null)),
          uv(tempDir,
            pyproject(
              """
                [project]
                name = "myapp"
                version = "1.0.0"
                dependencies = [
                    "requests>=2.28.0",
                ]
                """,
              """
                [project]
                name = "myapp"
                version = "1.0.0"
                dependencies = [
                    "requests>=2.28.0",
                    "flask>=2.0",
                ]
                """
            )
          )
        );
    }

    @Test
    void addDependencyToExistingList() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("flask", null, null, null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "requests>=2.28.0",
                  "click>=8.0",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "requests>=2.28.0",
                  "click>=8.0",
                  "flask",
              ]
              """
          )
        );
    }

    @Test
    void addDependencyWithVersion() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("flask", ">=2.0", null, null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "requests>=2.28.0",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "requests>=2.28.0",
                  "flask>=2.0",
              ]
              """
          )
        );
    }

    @Test
    void skipWhenAlreadyPresent() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("requests", null, null, null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "requests>=2.28.0",
              ]
              """
          )
        );
    }

    @Test
    void addToEmptyDependencyList() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("flask", ">=2.0", null, null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = []
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["flask>=2.0"]
              """
          )
        );
    }

    @Test
    void addToInlineDependencyList() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("flask", null, null, null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0", "flask"]
              """
          )
        );
    }

    @Test
    void addToOptionalDependencies() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("coverage", ">=7.0", "optionalDependencies", "test")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [project.optional-dependencies]
              test = ["pytest>=7.0"]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [project.optional-dependencies]
              test = ["pytest>=7.0", "coverage>=7.0"]
              """
          )
        );
    }

    @Test
    void addToDependencyGroups() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("coverage", ">=7.0", "dependencyGroups", "dev")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [dependency-groups]
              dev = ["pytest>=7.0"]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [dependency-groups]
              dev = ["pytest>=7.0", "coverage>=7.0"]
              """
          )
        );
    }

    @Test
    void skipWhenAlreadyInOptionalDependencies() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("pytest", null, "optionalDependencies", "test")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [project.optional-dependencies]
              test = ["pytest>=7.0"]
              """
          )
        );
    }

    @Test
    void addToDependencyGroupDoesNotAffectMainDeps() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("ruff", ">=0.1", "dependencyGroups", "lint")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [dependency-groups]
              lint = [
                  "mypy>=1.0",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [dependency-groups]
              lint = [
                  "mypy>=1.0",
                  "ruff>=0.1",
              ]
              """
          )
        );
    }

    @Test
    void addToConstraintDependencies() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("certifi", ">=2024.07.04", "constraintDependencies", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [tool.uv]
              constraint-dependencies = ["urllib3>=2.0"]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [tool.uv]
              constraint-dependencies = ["urllib3>=2.0", "certifi>=2024.07.04"]
              """
          )
        );
    }

    @Test
    void addToOverrideDependencies() {
        rewriteRun(
          spec -> spec.recipe(new AddDependency("urllib3", ">=2.0", "overrideDependencies", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [tool.uv]
              override-dependencies = [
                  "certifi>=2024.07.04",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["requests>=2.28.0"]

              [tool.uv]
              override-dependencies = [
                  "certifi>=2024.07.04",
                  "urllib3>=2.0",
              ]
              """
          )
        );
    }
}
