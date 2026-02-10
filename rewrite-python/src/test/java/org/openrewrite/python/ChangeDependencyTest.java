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
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.python.Assertions.pyproject;

class ChangeDependencyTest implements RewriteTest {

    @Test
    void changePackageName() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("pycrypto", "pycryptodome", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "pycrypto>=2.6",
                  "requests>=2.28.0",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "pycryptodome>=2.6",
                  "requests>=2.28.0",
              ]
              """
          )
        );
    }

    @Test
    void changePackageNameAndVersion() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("pycrypto", "pycryptodome", ">=3.15")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "pycrypto>=2.6",
                  "requests>=2.28.0",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "pycryptodome>=3.15",
                  "requests>=2.28.0",
              ]
              """
          )
        );
    }

    @Test
    void preservesExtrasAndMarkers() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("old-pkg", "new-pkg", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "old-pkg[security]>=1.0; python_version>='3.8'",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "new-pkg[security]>=1.0; python_version>='3.8'",
              ]
              """
          )
        );
    }

    @Test
    void changePackageNameWithNewVersionAndPreserveMarker() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("old-pkg", "new-pkg", ">=2.0")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "old-pkg>=1.0; python_version>='3.8'",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "new-pkg>=2.0; python_version>='3.8'",
              ]
              """
          )
        );
    }

    @Test
    void skipWhenNotPresent() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("nonexistent", "new-pkg", null)),
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
    void normalizeNameForMatching() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("typing_extensions", "typing-extensions-v2", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "typing-extensions>=4.0.0",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "typing-extensions-v2>=4.0.0",
              ]
              """
          )
        );
    }

    @Test
    void changePackageWithoutVersion() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("old-pkg", "new-pkg", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "old-pkg",
              ]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "new-pkg",
              ]
              """
          )
        );
    }

    @Test
    void changesAcrossAllScopes() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("old-pkg", "new-pkg", null)),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "old-pkg>=1.0",
                  "requests>=2.28.0",
              ]

              [project.optional-dependencies]
              security = ["old-pkg[crypto]>=1.0"]

              [dependency-groups]
              dev = ["old-pkg>=1.0"]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = [
                  "new-pkg>=1.0",
                  "requests>=2.28.0",
              ]

              [project.optional-dependencies]
              security = ["new-pkg[crypto]>=1.0"]

              [dependency-groups]
              dev = ["new-pkg>=1.0"]
              """
          )
        );
    }

    @Test
    void changeInInlineList() {
        rewriteRun(
          spec -> spec.recipe(new ChangeDependency("sklearn", "scikit-learn", ">=1.3")),
          pyproject(
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["sklearn>=0.24", "numpy>=1.20"]
              """,
            """
              [project]
              name = "myapp"
              version = "1.0.0"
              dependencies = ["scikit-learn>=1.3", "numpy>=1.20"]
              """
          )
        );
    }
}
