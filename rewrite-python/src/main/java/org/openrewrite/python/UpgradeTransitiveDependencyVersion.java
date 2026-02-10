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
import org.openrewrite.marker.Markers;
import org.openrewrite.python.internal.PyProjectHelper;
import org.openrewrite.python.marker.PythonResolutionResult;
import org.openrewrite.python.marker.PythonResolutionResult.PackageManager;
import org.openrewrite.toml.TomlIsoVisitor;
import org.openrewrite.toml.tree.Space;
import org.openrewrite.toml.tree.Toml;
import org.openrewrite.toml.tree.TomlRightPadded;
import org.openrewrite.toml.tree.TomlType;

import java.util.*;

import static org.openrewrite.Tree.randomId;

/**
 * Upgrade the version of a transitive (indirect) dependency in pyproject.toml.
 * <p>
 * The strategy depends on the detected package manager:
 * <ul>
 *   <li><b>uv</b>: Adds or updates an entry in {@code [tool.uv].constraint-dependencies}</li>
 *   <li><b>pdm</b>: Adds or updates an entry in {@code [tool.pdm.overrides]}</li>
 *   <li><b>pip, poetry, pipenv, or unknown</b>: Adds the package as a direct dependency
 *       in {@code [project].dependencies}</li>
 * </ul>
 * <p>
 * If the package is already a direct dependency, this recipe does nothing &mdash; use
 * {@link UpgradeDependencyVersion} to upgrade direct dependencies instead.
 */
@EqualsAndHashCode(callSuper = false)
@Value
public class UpgradeTransitiveDependencyVersion extends ScanningRecipe<UpgradeTransitiveDependencyVersion.Accumulator> {

    @Option(displayName = "Package name",
            description = "The PyPI package name to constrain.",
            example = "certifi")
    String packageName;

    @Option(displayName = "Version",
            description = "The PEP 508 version constraint to apply (e.g., `>=2024.07.04`).",
            example = ">=2024.07.04")
    String version;

    @Override
    public String getDisplayName() {
        return "Upgrade transitive Python dependency version";
    }

    @Override
    public String getInstanceNameSuffix() {
        return String.format("`%s` to `%s`", packageName, version);
    }

    @Override
    public String getDescription() {
        return "Upgrade the version of a transitive dependency. " +
                "For uv projects, adds or updates `[tool.uv].constraint-dependencies`. " +
                "For pdm projects, adds or updates `[tool.pdm.overrides]`. " +
                "For other package managers, adds the dependency directly to `[project].dependencies`. " +
                "If the package is already a direct dependency, no changes are made.";
    }

    enum Action {
        NONE,
        ADD_CONSTRAINT,
        UPGRADE_CONSTRAINT,
        ADD_PDM_OVERRIDE,
        UPGRADE_PDM_OVERRIDE,
        ADD_DIRECT_DEPENDENCY
    }

    static class Accumulator {
        final Map<String, Action> projectActions = new HashMap<>();
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
                String sourcePath = document.getSourcePath().toString();

                // Skip if it's already a direct dependency
                if (marker.findDependency(packageName) != null) {
                    return document;
                }

                PackageManager pm = marker.getPackageManager();
                if (pm == PackageManager.Uv) {
                    scanForUv(marker, sourcePath, acc);
                } else if (pm == PackageManager.Pdm) {
                    scanForPdm(marker, sourcePath, acc);
                } else {
                    scanForFallback(marker, sourcePath, acc);
                }

                return document;
            }
        };
    }

    private void scanForUv(PythonResolutionResult marker, String sourcePath, Accumulator acc) {
        // For uv, require the package to be in the resolved dependency tree
        if (marker.getResolvedDependency(packageName) == null) {
            return;
        }

        PythonResolutionResult.Dependency existing =
                PyProjectHelper.findDependencyInScope(marker, packageName, "constraintDependencies", null);

        if (existing != null) {
            if (!version.equals(existing.getVersionConstraint())) {
                acc.projectActions.put(sourcePath, Action.UPGRADE_CONSTRAINT);
            }
        } else {
            acc.projectActions.put(sourcePath, Action.ADD_CONSTRAINT);
        }
    }

    private void scanForPdm(PythonResolutionResult marker, String sourcePath, Accumulator acc) {
        // For pdm, check resolved deps if available, but don't require them
        // (we don't have a pdm.lock parser yet)
        if (!marker.getResolvedDependencies().isEmpty() &&
                marker.getResolvedDependency(packageName) == null) {
            return;
        }

        PythonResolutionResult.Dependency existing =
                PyProjectHelper.findDependencyInScope(marker, packageName, "pdmOverrides", null);

        if (existing != null) {
            if (!version.equals(existing.getVersionConstraint())) {
                acc.projectActions.put(sourcePath, Action.UPGRADE_PDM_OVERRIDE);
            }
        } else {
            acc.projectActions.put(sourcePath, Action.ADD_PDM_OVERRIDE);
        }
    }

    private void scanForFallback(PythonResolutionResult marker, String sourcePath, Accumulator acc) {
        // For pip/poetry/pipenv/unknown, check resolved deps if available
        if (!marker.getResolvedDependencies().isEmpty() &&
                marker.getResolvedDependency(packageName) == null) {
            return;
        }

        // Add as a direct dependency
        acc.projectActions.put(sourcePath, Action.ADD_DIRECT_DEPENDENCY);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.Document visitDocument(Toml.Document document, ExecutionContext ctx) {
                String sourcePath = document.getSourcePath().toString();

                if (sourcePath.endsWith("pyproject.toml")) {
                    Action action = acc.projectActions.get(sourcePath);
                    if (action == null) {
                        return document;
                    }
                    switch (action) {
                        case ADD_CONSTRAINT:
                            return addToArray(document, ctx, acc, "constraintDependencies");
                        case UPGRADE_CONSTRAINT:
                            return upgradeConstraint(document, ctx, acc);
                        case ADD_PDM_OVERRIDE:
                            return addPdmOverride(document, ctx, acc);
                        case UPGRADE_PDM_OVERRIDE:
                            return upgradePdmOverride(document, ctx, acc);
                        case ADD_DIRECT_DEPENDENCY:
                            return addToArray(document, ctx, acc, null);
                        default:
                            return document;
                    }
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

    /**
     * Add a PEP 508 dependency string to a TOML array.
     *
     * @param scope the target scope (null or "dependencies" for [project].dependencies,
     *              "constraintDependencies" for [tool.uv].constraint-dependencies)
     */
    private Toml.Document addToArray(Toml.Document document, ExecutionContext ctx,
                                      Accumulator acc, @Nullable String scope) {
        String pep508 = packageName + version;

        Toml.Document updated = (Toml.Document) new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.Array visitArray(Toml.Array array, ExecutionContext ctx) {
                Toml.Array a = super.visitArray(array, ctx);

                if (!PyProjectHelper.isInsideDependencyArray(getCursor(), scope, null)) {
                    return a;
                }

                Toml.Literal newLiteral = new Toml.Literal(
                        randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        TomlType.Primitive.String,
                        "\"" + pep508 + "\"",
                        pep508
                );

                List<TomlRightPadded<Toml>> existingPadded = a.getPadding().getValues();
                List<TomlRightPadded<Toml>> newPadded = new ArrayList<>();

                boolean isEmpty = existingPadded.size() == 1 &&
                        existingPadded.get(0).getElement() instanceof Toml.Empty;
                if (existingPadded.isEmpty() || isEmpty) {
                    newPadded.add(new TomlRightPadded<>(newLiteral, Space.EMPTY, Markers.EMPTY));
                } else {
                    TomlRightPadded<Toml> lastPadded = existingPadded.get(existingPadded.size() - 1);
                    boolean hasTrailingComma = lastPadded.getElement() instanceof Toml.Empty;

                    if (hasTrailingComma) {
                        int lastRealIdx = existingPadded.size() - 2;
                        Toml lastRealElement = existingPadded.get(lastRealIdx).getElement();
                        Toml.Literal formattedLiteral = newLiteral.withPrefix(lastRealElement.getPrefix());
                        for (int i = 0; i <= lastRealIdx; i++) {
                            newPadded.add(existingPadded.get(i));
                        }
                        newPadded.add(new TomlRightPadded<>(formattedLiteral, Space.EMPTY, Markers.EMPTY));
                        newPadded.add(lastPadded);
                    } else {
                        Toml lastElement = lastPadded.getElement();
                        Space newPrefix = lastElement.getPrefix().getWhitespace().contains("\n")
                                ? lastElement.getPrefix()
                                : Space.SINGLE_SPACE;
                        Toml.Literal formattedLiteral = newLiteral.withPrefix(newPrefix);
                        for (int i = 0; i < existingPadded.size() - 1; i++) {
                            newPadded.add(existingPadded.get(i));
                        }
                        newPadded.add(lastPadded.withAfter(Space.EMPTY));
                        newPadded.add(new TomlRightPadded<>(formattedLiteral, lastPadded.getAfter(), Markers.EMPTY));
                    }
                }

                return a.getPadding().withValues(newPadded);
            }
        }.visitNonNull(document, ctx);

        if (updated != document) {
            updated = PyProjectHelper.regenerateLockAndRefreshMarker(updated, acc.updatedLockFiles);
        }

        return updated;
    }

    private Toml.Document upgradeConstraint(Toml.Document document, ExecutionContext ctx, Accumulator acc) {
        String normalizedName = PythonResolutionResult.normalizeName(packageName);

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

                if (!PyProjectHelper.isInsideDependencyArray(getCursor().getParentTreeCursor(),
                        "constraintDependencies", null)) {
                    return l;
                }

                String spec = (String) val;
                String depName = PyProjectHelper.extractPackageName(spec);
                if (depName == null || !PythonResolutionResult.normalizeName(depName).equals(normalizedName)) {
                    return l;
                }

                String extras = UpgradeDependencyVersion.extractExtras(spec);
                String marker = UpgradeDependencyVersion.extractMarker(spec);

                StringBuilder sb = new StringBuilder(depName);
                if (extras != null) {
                    sb.append('[').append(extras).append(']');
                }
                sb.append(version);
                if (marker != null) {
                    sb.append("; ").append(marker);
                }
                String newSpec = sb.toString();
                return l.withSource("\"" + newSpec + "\"").withValue(newSpec);
            }
        }.visitNonNull(document, ctx);

        if (updated != document) {
            updated = PyProjectHelper.regenerateLockAndRefreshMarker(updated, acc.updatedLockFiles);
        }

        return updated;
    }

    /**
     * Add a new key-value entry to the [tool.pdm.overrides] table.
     */
    private Toml.Document addPdmOverride(Toml.Document document, ExecutionContext ctx, Accumulator acc) {
        Toml.Document updated = (Toml.Document) new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.Table visitTable(Toml.Table table, ExecutionContext ctx) {
                Toml.Table t = super.visitTable(table, ctx);
                if (t.getName() == null || !"tool.pdm.overrides".equals(t.getName().getName())) {
                    return t;
                }

                // Build a new KeyValue: packageName = "version"
                Toml.Identifier key = new Toml.Identifier(
                        randomId(), Space.EMPTY, Markers.EMPTY, packageName, packageName
                );
                Toml.Literal value = new Toml.Literal(
                        randomId(), Space.SINGLE_SPACE, Markers.EMPTY,
                        TomlType.Primitive.String,
                        "\"" + version + "\"", version
                );
                Toml.KeyValue newEntry = new Toml.KeyValue(
                        randomId(), Space.EMPTY, Markers.EMPTY,
                        new TomlRightPadded<>(key, Space.SINGLE_SPACE, Markers.EMPTY),
                        value
                );

                // Determine formatting from existing entries
                List<TomlRightPadded<Toml>> existingValues = t.getPadding().getValues();
                Space entryPrefix;
                if (!existingValues.isEmpty()) {
                    entryPrefix = existingValues.get(existingValues.size() - 1).getElement().getPrefix();
                } else {
                    entryPrefix = Space.format("\n");
                }
                newEntry = newEntry.withPrefix(entryPrefix);

                List<TomlRightPadded<Toml>> newValues = new ArrayList<>(existingValues);
                newValues.add(new TomlRightPadded<>(newEntry, Space.EMPTY, Markers.EMPTY));

                return t.getPadding().withValues(newValues);
            }
        }.visitNonNull(document, ctx);

        if (updated != document) {
            updated = PyProjectHelper.regenerateLockAndRefreshMarker(updated, acc.updatedLockFiles);
        }

        return updated;
    }

    /**
     * Upgrade the value of an existing entry in the [tool.pdm.overrides] table.
     */
    private Toml.Document upgradePdmOverride(Toml.Document document, ExecutionContext ctx, Accumulator acc) {
        String normalizedName = PythonResolutionResult.normalizeName(packageName);

        Toml.Document updated = (Toml.Document) new TomlIsoVisitor<ExecutionContext>() {
            @Override
            public Toml.KeyValue visitKeyValue(Toml.KeyValue keyValue, ExecutionContext ctx) {
                Toml.KeyValue kv = super.visitKeyValue(keyValue, ctx);

                if (!PyProjectHelper.isInsidePdmOverridesTable(getCursor())) {
                    return kv;
                }

                if (!(kv.getKey() instanceof Toml.Identifier)) {
                    return kv;
                }
                String keyName = ((Toml.Identifier) kv.getKey()).getName();
                if (!PythonResolutionResult.normalizeName(keyName).equals(normalizedName)) {
                    return kv;
                }

                if (!(kv.getValue() instanceof Toml.Literal)) {
                    return kv;
                }

                Toml.Literal lit = (Toml.Literal) kv.getValue();
                return kv.withValue(lit.withSource("\"" + version + "\"").withValue(version));
            }
        }.visitNonNull(document, ctx);

        if (updated != document) {
            updated = PyProjectHelper.regenerateLockAndRefreshMarker(updated, acc.updatedLockFiles);
        }

        return updated;
    }

}
