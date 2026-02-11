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

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.python.internal.PythonDependencyParser;
import org.openrewrite.python.marker.PythonResolutionResult;
import org.openrewrite.python.marker.PythonResolutionResult.Dependency;
import org.openrewrite.python.marker.PythonResolutionResult.PackageManager;
import org.openrewrite.python.marker.PythonResolutionResult.ResolvedDependency;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.openrewrite.Tree.randomId;

/**
 * Parser for requirements.txt files that delegates to {@link PlainTextParser} and attaches a
 * {@link PythonResolutionResult} marker with dependency metadata.
 * <p>
 * When {@code uv} is available on the system, the parser creates a cached virtual environment,
 * installs the declared dependencies, and captures the full transitive closure via
 * {@code uv pip freeze}. If {@code uv} is not available, only the declared (direct)
 * dependencies are populated in the marker.
 */
public class RequirementsTxtParser implements Parser {

    private final PlainTextParser textParser = new PlainTextParser();

    @Override
    public Stream<SourceFile> parseInputs(Iterable<Input> sources, @Nullable Path relativeTo, ExecutionContext ctx) {
        return textParser.parseInputs(sources, relativeTo, ctx).map(sf -> {
            if (!(sf instanceof PlainText)) {
                return sf;
            }
            PlainText pt = (PlainText) sf;

            List<Dependency> declaredDeps = parseRequirementLines(pt.getText());
            if (declaredDeps.isEmpty()) {
                return sf;
            }

            List<ResolvedDependency> resolvedDeps = tryResolve(pt.getText(), pt.getSourcePath(), relativeTo);

            PythonResolutionResult marker = new PythonResolutionResult(
                    randomId(),
                    null,
                    null,
                    null,
                    null,
                    pt.getSourcePath().toString(),
                    null,
                    null,
                    Collections.emptyList(),
                    declaredDeps,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    resolvedDeps,
                    resolvedDeps.isEmpty() ? null : PackageManager.Pip,
                    null
            );

            if (!resolvedDeps.isEmpty()) {
                marker = marker.withDependencies(linkResolved(declaredDeps, resolvedDeps));
            }

            return pt.withMarkers(pt.getMarkers().addIfAbsent(marker));
        });
    }

    /**
     * Parse requirement lines from a requirements.txt file.
     * Skips blank lines, comments, and option lines ({@code -r}, {@code -c}, {@code --index-url}, etc.).
     */
    static List<Dependency> parseRequirementLines(String content) {
        List<Dependency> deps = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            Dependency dep = PythonDependencyParser.parsePep508(line);
            if (dep != null) {
                deps.add(dep);
            }
        }
        return deps;
    }

    private List<ResolvedDependency> tryResolve(String content, Path sourcePath, @Nullable Path relativeTo) {
        Path originalFile = null;
        if (relativeTo != null) {
            Path candidate = relativeTo.resolve(sourcePath);
            if (Files.isRegularFile(candidate)) {
                originalFile = candidate;
            }
        }

        Path workspaceDir = DependencyWorkspace.getOrCreateRequirementsWorkspace(content, originalFile);
        if (workspaceDir == null) {
            return Collections.emptyList();
        }

        String freezeOutput = DependencyWorkspace.readFreezeOutput(workspaceDir);
        if (freezeOutput == null) {
            return Collections.emptyList();
        }

        return parseFreezeOutput(freezeOutput);
    }

    /**
     * Parse the output of {@code uv pip freeze} into resolved dependencies.
     * Each line is expected to be in the format {@code name==version}.
     */
    static List<ResolvedDependency> parseFreezeOutput(String output) {
        List<ResolvedDependency> deps = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-e")) {
                continue;
            }
            int eqIdx = line.indexOf("==");
            if (eqIdx > 0) {
                String name = line.substring(0, eqIdx).trim();
                String version = line.substring(eqIdx + 2).trim();
                deps.add(new ResolvedDependency(name, version, null, null));
            }
        }
        return deps;
    }

    private List<Dependency> linkResolved(List<Dependency> deps, List<ResolvedDependency> resolved) {
        return deps.stream().map(dep -> {
            String normalizedName = PythonResolutionResult.normalizeName(dep.getName());
            ResolvedDependency found = resolved.stream()
                    .filter(r -> PythonResolutionResult.normalizeName(r.getName()).equals(normalizedName))
                    .findFirst()
                    .orElse(null);
            return found != null ? dep.withResolved(found) : dep;
        }).collect(Collectors.toList());
    }

    @Override
    public boolean accept(Path path) {
        return "requirements.txt".equals(path.getFileName().toString());
    }

    @Override
    public Path sourcePathFromSourceText(Path prefix, String sourceCode) {
        return prefix.resolve("requirements.txt");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends Parser.Builder {

        Builder() {
            super(PlainText.class);
        }

        @Override
        public RequirementsTxtParser build() {
            return new RequirementsTxtParser();
        }

        @Override
        public String getDslName() {
            return "requirements.txt";
        }
    }
}
