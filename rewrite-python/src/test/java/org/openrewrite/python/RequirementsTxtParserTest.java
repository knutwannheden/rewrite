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
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.python.marker.PythonResolutionResult;
import org.openrewrite.python.marker.PythonResolutionResult.Dependency;
import org.openrewrite.python.marker.PythonResolutionResult.ResolvedDependency;
import org.openrewrite.text.PlainText;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementsTxtParserTest {

    @Test
    void acceptsRequirementsTxt() {
        RequirementsTxtParser parser = new RequirementsTxtParser();
        assertThat(parser.accept(Paths.get("requirements.txt"))).isTrue();
        assertThat(parser.accept(Paths.get("src/requirements.txt"))).isTrue();
        assertThat(parser.accept(Paths.get("requirements-dev.txt"))).isFalse();
        assertThat(parser.accept(Paths.get("pyproject.toml"))).isFalse();
    }

    @Test
    void parsesRequirementsTxtWithMarker() {
        String requirementsTxt = """
                requests>=2.28.0
                click>=8.0
                flask==2.3.0
                """;

        RequirementsTxtParser parser = new RequirementsTxtParser();
        Parser.Input input = Parser.Input.fromString(
                Paths.get("requirements.txt"),
                requirementsTxt
        );
        List<SourceFile> parsed = parser.parseInputs(
                Collections.singletonList(input),
                null,
                new InMemoryExecutionContext(Throwable::printStackTrace)
        ).collect(Collectors.toList());

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0)).isInstanceOf(PlainText.class);

        PlainText pt = (PlainText) parsed.get(0);
        PythonResolutionResult marker = pt.getMarkers().findFirst(PythonResolutionResult.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getName()).isNull();
        assertThat(marker.getVersion()).isNull();

        assertThat(marker.getDependencies()).hasSize(3);
        assertThat(marker.getDependencies().get(0).getName()).isEqualTo("requests");
        assertThat(marker.getDependencies().get(0).getVersionConstraint()).isEqualTo(">=2.28.0");
        assertThat(marker.getDependencies().get(1).getName()).isEqualTo("click");
        assertThat(marker.getDependencies().get(2).getName()).isEqualTo("flask");
        assertThat(marker.getDependencies().get(2).getVersionConstraint()).isEqualTo("==2.3.0");
    }

    @Test
    void skipsCommentsAndOptions() {
        String requirementsTxt = """
                # This is a comment
                requests>=2.28.0

                # Another comment
                -r base.txt
                --index-url https://pypi.org/simple
                click>=8.0
                """;

        RequirementsTxtParser parser = new RequirementsTxtParser();
        Parser.Input input = Parser.Input.fromString(
                Paths.get("requirements.txt"),
                requirementsTxt
        );
        List<SourceFile> parsed = parser.parseInputs(
                Collections.singletonList(input),
                null,
                new InMemoryExecutionContext(Throwable::printStackTrace)
        ).collect(Collectors.toList());

        PlainText pt = (PlainText) parsed.get(0);
        PythonResolutionResult marker = pt.getMarkers().findFirst(PythonResolutionResult.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getDependencies()).hasSize(2);
        assertThat(marker.getDependencies().get(0).getName()).isEqualTo("requests");
        assertThat(marker.getDependencies().get(1).getName()).isEqualTo("click");
    }

    @Test
    void parsesEmptyRequirementsTxt() {
        String requirementsTxt = """
                # Only comments
                # No actual dependencies
                """;

        RequirementsTxtParser parser = new RequirementsTxtParser();
        Parser.Input input = Parser.Input.fromString(
                Paths.get("requirements.txt"),
                requirementsTxt
        );
        List<SourceFile> parsed = parser.parseInputs(
                Collections.singletonList(input),
                null,
                new InMemoryExecutionContext(Throwable::printStackTrace)
        ).collect(Collectors.toList());

        PlainText pt = (PlainText) parsed.get(0);
        PythonResolutionResult marker = pt.getMarkers().findFirst(PythonResolutionResult.class).orElse(null);
        assertThat(marker).isNull();
    }

    @Test
    void builderCreatesDslName() {
        RequirementsTxtParser.Builder builder = RequirementsTxtParser.builder();
        assertThat(builder.getDslName()).isEqualTo("requirements.txt");
        assertThat(builder.build()).isInstanceOf(RequirementsTxtParser.class);
    }

    @Test
    void parseFreezeOutput() {
        String freezeOutput = """
                certifi==2024.2.2
                charset-normalizer==3.3.2
                idna==3.6
                requests==2.31.0
                urllib3==2.2.1
                """;

        List<ResolvedDependency> deps = RequirementsTxtParser.parseFreezeOutput(freezeOutput);
        assertThat(deps).hasSize(5);
        assertThat(deps.get(0).getName()).isEqualTo("certifi");
        assertThat(deps.get(0).getVersion()).isEqualTo("2024.2.2");
        assertThat(deps.get(3).getName()).isEqualTo("requests");
        assertThat(deps.get(3).getVersion()).isEqualTo("2.31.0");
    }

    @Test
    void parseFreezeOutputSkipsEditableAndComments() {
        String freezeOutput = """
                # This file was generated by uv
                -e /path/to/project
                requests==2.31.0
                certifi==2024.2.2
                """;

        List<ResolvedDependency> deps = RequirementsTxtParser.parseFreezeOutput(freezeOutput);
        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).getName()).isEqualTo("requests");
        assertThat(deps.get(1).getName()).isEqualTo("certifi");
    }

    @Test
    void parseRequirementLines() {
        String content = """
                requests>=2.28.0
                click>=8.0
                # comment
                -r other.txt
                flask==2.3.0
                """;

        List<Dependency> deps = RequirementsTxtParser.parseRequirementLines(content);
        assertThat(deps).hasSize(3);
        assertThat(deps.get(0).getName()).isEqualTo("requests");
        assertThat(deps.get(1).getName()).isEqualTo("click");
        assertThat(deps.get(2).getName()).isEqualTo("flask");
    }

    @Test
    void parseRequirementLinesWithExtras() {
        String content = """
                requests[security]>=2.28.0
                celery[redis,auth]>=5.0
                """;

        List<Dependency> deps = RequirementsTxtParser.parseRequirementLines(content);
        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).getName()).isEqualTo("requests");
        assertThat(deps.get(0).getExtras()).containsExactly("security");
        assertThat(deps.get(1).getName()).isEqualTo("celery");
        assertThat(deps.get(1).getExtras()).containsExactly("redis", "auth");
    }
}
