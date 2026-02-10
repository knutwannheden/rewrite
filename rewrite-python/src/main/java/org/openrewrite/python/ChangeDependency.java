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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.python.internal.PyProjectHelper;
import org.openrewrite.python.marker.PythonResolutionResult;
import org.openrewrite.toml.TomlIsoVisitor;
import org.openrewrite.toml.tree.Toml;
import org.openrewrite.toml.tree.TomlType;

import java.util.*;

/**
 * Replace a Python dependency with a different package in pyproject.toml.
 * Useful when packages are renamed, forked, or superseded (e.g.,
 * {@code pycrypto} to {@code pycryptodome}, {@code sklearn} to {@code scikit-learn}).
 * <p>
 * Searches across all dependency scopes ({@code [project].dependencies},
 * {@code [project.optional-dependencies]}, {@code [dependency-groups]}) and replaces
 * matching entries. When uv is available, the uv.lock file is regenerated.
 */
@EqualsAndHashCode(callSuper = false)
@Value
public class ChangeDependency extends ScanningRecipe<ChangeDependency.Accumulator> {

    @Option(displayName = "Old package name",
            description = "The current PyPI package name to replace.",
            example = "pycrypto")
    String oldPackageName;

    @Option(displayName = "New package name",
            description = "The new PyPI package name.",
            example = "pycryptodome")
    String newPackageName;

    @Option(displayName = "New version",
            description = "The new PEP 508 version constraint. If not specified, the existing version constraint is preserved.",
            example = ">=3.15",
            required = false)
    @Nullable
    String newVersion;

    @Override
    public String getDisplayName() {
        return "Change Python dependency";
    }

    @Override
    public String getInstanceNameSuffix() {
        return String.format("`%s` to `%s`", oldPackageName, newPackageName);
    }

    @Override
    public String getDescription() {
        return "Replace a Python dependency with a different package in `pyproject.toml`. " +
                "Searches across all dependency scopes and replaces matching entries. " +
                "When `uv` is available, the `uv.lock` file is regenerated.";
    }

    static class Accumulator {
        final Set<String> projectsToUpdate = new HashSet<>();
        final Map<String, String> updatedLockFiles = new HashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.Document visitDocument(Toml.Document document, ExecutionContext ctx) {
                if (!document.getSourcePath().toString().endsWith("pyproject.toml")) {
                    return document;
                }
                Optional<PythonResolutionResult> resolution = document.getMarkers()
                        .findFirst(PythonResolutionResult.class);
                if (!resolution.isPresent()) {
                    return document;
                }

                PythonResolutionResult marker = resolution.get();

                // Check if the old dependency exists in any scope
                if (marker.findDependencyInAnyScope(oldPackageName) == null) {
                    return document;
                }

                acc.projectsToUpdate.add(document.getSourcePath().toString());
                return document;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.Document visitDocument(Toml.Document document, ExecutionContext ctx) {
                String sourcePath = document.getSourcePath().toString();

                if (sourcePath.endsWith("pyproject.toml") && acc.projectsToUpdate.contains(sourcePath)) {
                    return changeDependencyInPyproject(document, ctx, acc);
                }

                if (sourcePath.endsWith("uv.lock")) {
                    String pyprojectPath = PyProjectHelper.correspondingPyprojectPath(sourcePath);
                    String newContent = acc.updatedLockFiles.get(pyprojectPath);
                    if (newContent != null) {
                        return PyProjectHelper.reparseToml(document, newContent);
                    }
                }

                return document;
            }
        };
    }

    private Toml.Document changeDependencyInPyproject(Toml.Document document, ExecutionContext ctx, Accumulator acc) {
        String normalizedOld = PythonResolutionResult.normalizeName(oldPackageName);

        Toml.Document updated = (Toml.Document) new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.Literal visitLiteral(Toml.Literal literal, ExecutionContext ctx) {
                Toml.Literal l = super.visitLiteral(literal, ctx);
                if (l.getType() != TomlType.Primitive.String) {
                    return l;
                }

                Object val = l.getValue();
                if (!(val instanceof String)) {
                    return l;
                }

                String spec = (String) val;
                String depName = PyProjectHelper.extractPackageName(spec);
                if (depName == null || !PythonResolutionResult.normalizeName(depName).equals(normalizedOld)) {
                    return l;
                }

                // Build new PEP 508 string with the new package name
                String newSpec = buildNewSpec(spec, depName);
                return l.withSource("\"" + newSpec + "\"").withValue(newSpec);
            }

            private String buildNewSpec(String oldSpec, String oldName) {
                String extras = UpgradeDependencyVersion.extractExtras(oldSpec);
                String marker = UpgradeDependencyVersion.extractMarker(oldSpec);

                StringBuilder sb = new StringBuilder(newPackageName);
                if (extras != null) {
                    sb.append('[').append(extras).append(']');
                }

                if (newVersion != null) {
                    sb.append(newVersion);
                } else {
                    // Preserve existing version constraint
                    String existingVersion = extractVersionConstraint(oldSpec, oldName);
                    if (existingVersion != null) {
                        sb.append(existingVersion);
                    }
                }

                if (marker != null) {
                    sb.append("; ").append(marker);
                }
                return sb.toString();
            }

            /**
             * Extract the version constraint portion from a PEP 508 spec.
             * Given "requests[security]>=2.28.0; python_version>='3.8'",
             * returns ">=2.28.0".
             */
            private @Nullable String extractVersionConstraint(String spec, String name) {
                // Remove the name prefix
                String remainder = spec.substring(name.length()).trim();

                // Skip extras if present
                if (remainder.startsWith("[")) {
                    int closeBracket = remainder.indexOf(']');
                    if (closeBracket >= 0) {
                        remainder = remainder.substring(closeBracket + 1).trim();
                    }
                }

                // Remove marker if present
                int semicolonIdx = remainder.indexOf(';');
                if (semicolonIdx >= 0) {
                    remainder = remainder.substring(0, semicolonIdx).trim();
                }

                return remainder.isEmpty() ? null : remainder;
            }
        }.visitNonNull(document, ctx);

        if (updated != document) {
            updated = PyProjectHelper.regenerateLockAndRefreshMarker(updated, acc.updatedLockFiles);
        }

        return updated;
    }

}
