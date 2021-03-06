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
package org.openrewrite.maven.internal;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.openrewrite.internal.PropertyPlaceholderHelper;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.MavenSettings;
import org.openrewrite.maven.tree.*;
import org.openrewrite.xml.tree.Xml;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Collections.*;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class RawMavenResolver {
    private static final PropertyPlaceholderHelper placeholderHelper = new PropertyPlaceholderHelper("${", "}", null);

    // This is used to keep track of what versions have been seen further up the tree so we don't unnecessarily
    // resolve subtrees that have no chance of being selected by conflict resolution.
    private final NavigableMap<Scope, Map<GroupArtifact, RequestedVersion>> versionSelection;

    // The breadth-first queue of resolution tasks.
    private final Queue<ResolutionTask> workQueue = new LinkedList<>();

    private final Map<PartialTreeKey, Optional<Pom>> resolved = new HashMap<>();
    private final Map<ResolutionTask, PartialMaven> partialResults = new HashMap<>();

    private final MavenDownloader downloader;

    /**
     * Is this resolver being used for resolving parents or import BOMs? Just a flag for logging purposes alone...
     */
    private final boolean forParent;

    private final Collection<String> activeProfiles;
    private final boolean resolveOptional;
    private final boolean continueOnError;

    @Nullable
    private final MavenSettings mavenSettings;
    @Nullable
    private final Consumer<Throwable> onError;

    public RawMavenResolver(MavenDownloader downloader, boolean forParent, Collection<String> activeProfiles,
                            @Nullable MavenSettings mavenSettings, boolean resolveOptional, boolean continueOnError, @Nullable Consumer<Throwable> onError) {
        this.onError = onError;
        this.versionSelection = new TreeMap<>();
        for (Scope scope : Scope.values()) {
            versionSelection.putIfAbsent(scope, new HashMap<>());
        }
        this.downloader = downloader;
        this.forParent = forParent;
        this.activeProfiles = activeProfiles;
        this.mavenSettings = mavenSettings;
        this.resolveOptional = resolveOptional;
        this.continueOnError = continueOnError;
    }

    @Nullable
    public Xml.Document resolve(RawMaven rawMaven) {
        Pom pom = resolve(rawMaven, Scope.None, rawMaven.getPom().getVersion(),
                mavenSettings == null ? emptyList() : mavenSettings.getActiveRepositories(activeProfiles));
        assert pom != null;
        return rawMaven.getDocument().withMarkers(rawMaven.getDocument().getMarkers()
                .compute(pom, (old, n) -> n));
    }

    /**
     * Resolution is performed breadth-first because the default conflict resolution algorithm
     * for Maven prefers nearer versions. By proceeding breadth-first we can avoid even attempting
     * to resolve subtrees that have no chance of being selected by conflict resolution in the end.
     *
     * @param rawMaven     The shell of the POM to resolve.
     * @param repositories The set of repositories to resolve with.
     * @return A transitively resolved POM model.
     */
    @Nullable
    public Pom resolve(RawMaven rawMaven, Scope scope, @Nullable String requestedVersion, List<RawRepositories.Repository> repositories) {
        return _resolve(rawMaven, scope, requestedVersion, repositories, null);
    }

    @Nullable
    private Pom _resolve(RawMaven rawMaven, Scope scope, @Nullable String requestedVersion, List<RawRepositories.Repository> repositories,
                         @Nullable LinkedHashSet<PartialTreeKey> seenParentPoms) {
        ResolutionTask rootTask = new ResolutionTask(scope, rawMaven, emptySet(),
                false, null, requestedVersion, repositories, seenParentPoms);

        workQueue.add(rootTask);

        while (!workQueue.isEmpty()) {
            processTask(workQueue.poll());
        }

        return assembleResults(rootTask, new Stack<>());
    }

    private void processTask(ResolutionTask task) {
        RawMaven rawMaven = task.getRawMaven();

        if (partialResults.containsKey(task)) {
            return; // already processed
        }

        PartialMaven partialMaven = new PartialMaven(rawMaven.getDocument().getSourcePath(), rawMaven.getPom());
        processProperties(task, partialMaven);
        processRepositories(task, partialMaven);
        processParent(task, partialMaven);
        processDependencyManagement(task, partialMaven);
        processLicenses(task, partialMaven);
        processDependencies(task, partialMaven);

        partialResults.put(task, partialMaven);
    }

    private void processProperties(ResolutionTask task, PartialMaven partialMaven) {
        partialMaven.setProperties(task.getRawMaven().getActiveProperties(activeProfiles));
    }

    /**
     * Runs a piece of potentially exception-generating code, returning "false" to indicate that the code did not throw.
     * If the Runnable does throw, the behavior depends on whether continueOnError is set.
     * If the Runnable throws and continueOnError is not set, the exception will be rethrown.
     * If the Runnable throws and continueOnError is set, the exception will be suppressed and doesThrow will return true.
     *
     * The idea is to be able to succinctly write continueOnError-aware code in a form like:
     * if(doesThrow(() -> {
     *     // Some code which might throw
     *  }) {
     *     // Handle continuing execution here, knowing that the code did try to throw an error
     * }
     *
     */
    private boolean doesThrow(Runnable fun) {
        try {
            fun.run();
            return false;
        } catch (Throwable t) {
            if(onError != null) {
                onError.accept(t);
            }
            if(continueOnError) {
                return true;
            }
            if(t instanceof MavenParsingException) {
                throw t;
            }
            throw new MavenParsingException(t);
        }
    }

    private void processDependencyManagement(ResolutionTask task, PartialMaven partialMaven) {
        RawPom pom = task.getRawMaven().getPom();
        List<DependencyManagementDependency> managedDependencies = new ArrayList<>();

        RawPom.DependencyManagement dependencyManagement = pom.getDependencyManagement();
        if (dependencyManagement != null && dependencyManagement.getDependencies() != null) {
            for (RawPom.Dependency d : dependencyManagement.getDependencies().getDependencies()) {
                if (doesThrow(() -> {
                    if(d.getVersion() == null) {
                        throw new MavenParsingException(
                                "Problem with dependencyManagement section of %s:%s:%s. Unable to determine version of managed dependency %s:%s",
                                pom.getGroupId(), pom.getArtifactId(), pom.getVersion(), d.getGroupId(), d.getArtifactId());
                    }
                })) {
                    continue;
                }
                assert d.getVersion() != null;

                String groupId = partialMaven.getGroupId(d.getGroupId());
                String artifactId = partialMaven.getArtifactId(d.getArtifactId());
                String version = partialMaven.getVersion(d.getVersion());

                if(doesThrow(() -> {
                    if (groupId == null || artifactId == null || version == null) {
                        throw new MavenParsingException(
                                "Problem with dependencyManagement section of %s:%s:%s. Unable to determine groupId, " +
                                        "artifactId, or version of managed dependency %s:%s.",
                                pom.getGroupId(), pom.getArtifactId(), pom.getVersion(), d.getGroupId(), d.getArtifactId());
                    }
                })) {
                    continue;
                }
                assert groupId != null;
                assert artifactId != null;
                assert version != null;

                // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#importing-dependencies
                doesThrow(() -> {
                    if (Objects.equals(d.getType(), "pom") && Objects.equals(d.getScope(), "import")) {

                        RawMaven rawMaven = downloader.download(groupId, artifactId, version, null, null, null,
                                partialMaven.getRepositories());
                        if (rawMaven != null) {
                            Pom maven = new RawMavenResolver(downloader, true, activeProfiles, mavenSettings, resolveOptional, continueOnError, onError)
                                    .resolve(rawMaven, Scope.Compile, d.getVersion(), partialMaven.getRepositories());

                            if (maven != null) {
                                managedDependencies.add(new DependencyManagementDependency.Imported(groupId, artifactId,
                                        version, d.getVersion(), maven));
                            }
                        }
                    } else {
                        managedDependencies.add(new DependencyManagementDependency.Defined(
                                groupId, artifactId, version, d.getVersion(),
                                d.getScope() == null ? null : Scope.fromName(d.getScope()),
                                d.getClassifier(), d.getExclusions()));
                    }
                });
            }
        }

        partialMaven.setDependencyManagement(new Pom.DependencyManagement(managedDependencies));
    }

    private void processDependencies(ResolutionTask task, PartialMaven partialMaven) {
        RawMaven rawMaven = task.getRawMaven();

        // Parent dependencies wind up being part of the subtree rooted at "task", so affect conflict resolution further down tree.
        if (partialMaven.getParent() != null) {
            for (Pom.Dependency dependency : partialMaven.getParent().getDependencies()) {
                RequestedVersion requestedVersion = selectVersion(dependency.getScope(), dependency.getGroupId(),
                        dependency.getArtifactId(), dependency.getVersion());
                versionSelection.get(dependency.getScope()).put(new GroupArtifact(dependency.getGroupId(), dependency.getArtifactId()), requestedVersion);
            }
        }

        partialMaven.setDependencyTasks(rawMaven.getActiveDependencies(activeProfiles).stream()
                .filter(dep -> {
                    // we don't care about test-jar, etc.
                    return dep.getType() == null || dep.getType().equals("jar");
                })
                .filter(dep -> resolveOptional || dep.getOptional() == null || !dep.getOptional())
                .map(dep -> {
                    // replace property references, source versions from dependency management sections, etc.
                    String groupId = partialMaven.getGroupId(dep.getGroupId());
                    String artifactId = partialMaven.getArtifactId(dep.getArtifactId());

                    if (doesThrow(() -> {
                        if(groupId == null) {
                            throw new MavenParsingException(
                                    "Problem resolving dependency of %s:%s:%s. Unable to determine groupId.",
                                    rawMaven.getPom().getGroupId(), rawMaven.getPom().getArtifactId(), rawMaven.getPom().getVersion());
                        }
                        if(artifactId == null) {
                            throw new MavenParsingException(
                                    "Problem resolving dependency of %s:%s:%s. Unable to determine artifactId.",
                                    rawMaven.getPom().getGroupId(), rawMaven.getPom().getArtifactId(), rawMaven.getPom().getVersion());
                        }
                    })) {
                        return null;
                    }
                    assert groupId != null;
                    assert artifactId != null;

                    // Handle dependency exclusions
                    for (GroupArtifact e : task.getExclusions()) {
                        try {
                            if (dep.getGroupId().matches(e.getGroupId()) &&
                                    dep.getArtifactId().matches(e.getArtifactId())) {
                                return null;
                            }
                        } catch (Exception exception) {
                                continue;
                        }
                    }

                    ResolutionTask[] resolutionTaskContainer = new ResolutionTask[1];
                    if(doesThrow(() -> {
                        String version = null;
                        String last;
                        // loop so that when dependencyManagement refers to a property that we take another pass to resolve the property.
                        int i = 0;
                        do {
                            last = version;
                            String result = null;
                            if (last != null) {
                                String partialMavenVersion = partialMaven.getVersion(last);
                                if (partialMavenVersion != null) {
                                    result = partialMavenVersion;
                                }
                            }
                            if (result == null) {
                                OUTER:
                                for (DependencyManagementDependency managed : partialMaven.getDependencyManagement().getDependencies()) {
                                    for (DependencyDescriptor dependencyDescriptor : managed.getDependencies()) {
                                        if (groupId.equals(partialMaven.getGroupId(dependencyDescriptor.getGroupId())) &&
                                                artifactId.equals(partialMaven.getArtifactId(dependencyDescriptor.getArtifactId()))) {
                                            result = dependencyDescriptor.getVersion();
                                            break OUTER;
                                        }
                                    }
                                }

                                if (result == null && partialMaven.getParent() != null) {
                                    result = partialMaven.getParent().getManagedVersion(groupId, artifactId);
                                }
                            }
                            version = result;
                        } while (i++ < 2 || !Objects.equals(version, last));

                        // dependencyManagement takes precedence over the version specified on the dependency
                        if (version == null) {
                            String depVersion = dep.getVersion();
                            if (depVersion != null) {
                                version = partialMaven.getVersion(depVersion);
                            }
                        }

                        if (version == null) {
                            throw new MavenParsingException("Failed to determine version for %s:%s. Initial value was %s. Including POM is at %s",
                                    groupId, artifactId, dep.getVersion(), rawMaven.getSourcePath());
                        }

                        Scope requestedScope;
                        requestedScope = Scope.fromName(partialMaven.getScope(dep.getScope()));
                        Scope effectiveScope = requestedScope.transitiveOf(task.getScope());

                        if (effectiveScope == null) {
                            return;
                        }


                        RequestedVersion requestedVersion = selectVersion(effectiveScope, groupId, artifactId, version);
                        versionSelection.get(effectiveScope).put(new GroupArtifact(groupId, artifactId), requestedVersion);
                        version = requestedVersion.resolve(downloader, partialMaven.getRepositories());


                        if (version.contains("${")) {
                            throw new MavenParsingException("Unable to download %s:%s:%s. Including POM is at %s", groupId, artifactId, version, rawMaven.getSourcePath());
                        }

                        RawMaven download = downloader.download(groupId, artifactId,
                                version, dep.getClassifier(), null, rawMaven,
                                partialMaven.getRepositories());

                        if (download == null) {
                            throw new MavenParsingException("Unable to download %s:%s:%s. Including POM is at %s",
                                    groupId, artifactId, version, rawMaven.getSourcePath());
                        }

                        resolutionTaskContainer[0] = new ResolutionTask(
                                requestedScope,
                                download,
                                dep.getExclusions() == null ?
                                        emptySet() :
                                        dep.getExclusions().stream()
                                                .map(ex -> new GroupArtifact(
                                                        ex.getGroupId().replace("*", ".*"),
                                                        ex.getArtifactId().replace("*", ".*")
                                                ))
                                                .collect(Collectors.toSet()),
                                dep.getOptional() != null && dep.getOptional(),
                                dep.getClassifier(),
                                dep.getVersion(),
                                partialMaven.getRepositories(),
                                null
                        );
                    })) {
                        return null;
                    }

                    ResolutionTask resolutionTask = resolutionTaskContainer[0];
                    if (resolutionTask != null && !partialResults.containsKey(resolutionTask)) {
                        // otherwise we've already resolved this subtree previously!
                        workQueue.add(resolutionTask);
                    }

                    return resolutionTask;
                })
                .filter(Objects::nonNull)
                .collect(toList()));
    }

    private void processParent(ResolutionTask task, PartialMaven partialMaven) {
        RawMaven rawMaven = task.getRawMaven();
        RawPom pom = rawMaven.getPom();
        if (pom.getParent() != null) {
            RawPom.Parent rawParent = pom.getParent();
            // With "->" indicating a "has parent" relationship, this code is meant to detect cycles like
            // A -> B -> A
            // And cut them off early with a clearer, more actionable error than a stack overflow
            LinkedHashSet<PartialTreeKey> parentPomSightings;
            if (task.getSeenParentPoms() == null) {
                parentPomSightings = new LinkedHashSet<>();
            } else {
                parentPomSightings = new LinkedHashSet<>(task.getSeenParentPoms());
            }

            PartialTreeKey gav = new PartialTreeKey(rawParent.getGroupId(), rawParent.getArtifactId(), rawParent.getVersion());
            if(doesThrow(() -> {
                if (parentPomSightings.contains(gav)) {
                    throw new MavenParsingException("Cycle in parent poms detected: " + gav.getGroupId() + ":" + gav.getArtifactId() + ":" + gav.getVersion() + " is its own parent by way of these poms:\n" + parentPomSightings.stream()
                            .map(it -> it.groupId + ":" + it.getArtifactId() + ":" + it.getVersion())
                            .collect(joining("\n")));
                }
            })) {
                return;
            }
            parentPomSightings.add(gav);

            doesThrow(() -> {
                Pom parent = null;
                RawMaven rawParentModel = downloader.download(rawParent.getGroupId(), rawParent.getArtifactId(),
                        rawParent.getVersion(), null, rawParent.getRelativePath(), rawMaven,
                        partialMaven.getRepositories());
                if (rawParentModel != null) {
                    PartialTreeKey parentKey = new PartialTreeKey(rawParent.getGroupId(), rawParent.getArtifactId(), rawParent.getVersion());
                    Optional<Pom> maybeParent = resolved.get(parentKey);

                    //noinspection OptionalAssignedToNull
                    if (maybeParent == null) {
                        parent = new RawMavenResolver(downloader, true, activeProfiles, mavenSettings, resolveOptional, continueOnError, onError)
                                ._resolve(rawParentModel, Scope.Compile, rawParent.getVersion(), partialMaven.getRepositories(), parentPomSightings);
                        resolved.put(parentKey, Optional.ofNullable(parent));
                    } else {
                        parent = maybeParent.orElse(null);
                    }
                }
                partialMaven.setParent(parent);
            });
        }
    }

    private void processRepositories(ResolutionTask task, PartialMaven partialMaven) {
        List<RawRepositories.Repository> repositories = new ArrayList<>();
        List<RawRepositories.Repository> repositoriesFromPom = task.getRawMaven().getPom().getActiveRepositories(activeProfiles);
        if (mavenSettings != null) {
            repositoriesFromPom = mavenSettings.applyMirrors(repositoriesFromPom);
        }

        for (RawRepositories.Repository repository : repositoriesFromPom) {
            doesThrow(() -> {
                String url = repository.getUrl().trim();
                if (repository.getUrl().contains("${")) {
                    url = placeholderHelper.replacePlaceholders(url, k -> partialMaven.getProperties().get(k));
                }
                // Prevent malformed URLs from being used
                //noinspection ResultOfMethodCallIgnored
                URI.create(url);
                repositories.add(new RawRepositories.Repository(repository.getId(), url, repository.getReleases(), repository.getSnapshots()));
            });
        }

        repositories.addAll(task.getRepositories());
        partialMaven.setRepositories(repositories);
    }

    private void processLicenses(ResolutionTask task, PartialMaven partialMaven) {
        List<RawPom.License> licenses = task.getRawMaven().getPom().getInnerLicenses();
        List<Pom.License> list = new ArrayList<>();
        for (RawPom.License license : licenses) {
            Pom.License fromName = Pom.License.fromName(license.getName());
            list.add(fromName);
        }
        partialMaven.setLicenses(list);
    }

    @Nullable
    private Pom assembleResults(ResolutionTask task, Stack<ResolutionTask> assemblyStack) {
        if (assemblyStack.contains(task)) {
            return null; // cut cycles
        }

        RawMaven rawMaven = task.getRawMaven();
        RawPom rawPom = rawMaven.getPom();
        PartialTreeKey taskKey = new PartialTreeKey(rawPom.getGroupId(), rawPom.getArtifactId(), rawPom.getVersion());

        Optional<Pom> result = resolved.get(taskKey);

        Stack<ResolutionTask> nextAssemblyStack = new Stack<>();
        nextAssemblyStack.addAll(assemblyStack);
        nextAssemblyStack.push(task);

        //noinspection OptionalAssignedToNull
        if (result == null) {
            PartialMaven partial = partialResults.get(task);
            if (partial != null) {
                List<Pom.Dependency> dependencies = partial.getDependencyTasks().stream()
                        .map(depTask -> {
                            boolean optional = depTask.isOptional() ||
                                    assemblyStack.stream().anyMatch(ResolutionTask::isOptional);

                            Pom resolved = assembleResults(depTask, nextAssemblyStack);
                            if (resolved == null) {
                                return null;
                            }

                            return new Pom.Dependency(
                                    depTask.getScope(),
                                    depTask.getClassifier(),
                                    optional,
                                    resolved,
                                    depTask.getRequestedVersion(),
                                    depTask.getExclusions()
                            );
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(ArrayList::new));

                for (Pom ancestor = partial.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                    for (Pom.Dependency ancestorDep : ancestor.getDependencies()) {
                        // the ancestor's dependency might be overridden by another version by conflict resolution
                        Scope scope = ancestorDep.getScope();
                        String groupId = ancestorDep.getGroupId();
                        String artifactId = ancestorDep.getArtifactId();

                        String conflictResolvedVersion = selectVersion(scope, groupId, artifactId, ancestorDep.getVersion())
                                .resolve(downloader, task.getRepositories());

                        if (!conflictResolvedVersion.equals(ancestorDep.getVersion())) {
                            RawMaven conflictResolvedRaw = downloader.download(groupId, artifactId, conflictResolvedVersion,
                                    ancestorDep.getClassifier(), null, null, task.getRepositories());

                            Pom conflictResolved = assembleResults(new ResolutionTask(scope, conflictResolvedRaw,
                                    ancestorDep.getExclusions(), ancestorDep.isOptional(), ancestorDep.getRequestedVersion(),
                                    ancestorDep.getClassifier(), task.getRepositories(), null), nextAssemblyStack);

                            if (conflictResolved == null) {
                                dependencies.add(ancestorDep);
                            } else {
                                dependencies.add(new Pom.Dependency(
                                        scope,
                                        ancestorDep.getClassifier(),
                                        ancestorDep.isOptional(),
                                        conflictResolved,
                                        ancestorDep.getRequestedVersion(),
                                        ancestorDep.getExclusions()
                                ));
                            }
                        } else {
                            dependencies.add(ancestorDep);
                        }
                    }
                }

                String groupId = rawPom.getGroupId();
                if (groupId == null) {
                    groupId = partial.getParent().getGroupId();
                }

                String version = rawPom.getVersion();
                if (version == null) {
                    version = partial.getParent().getVersion();
                }

                List<Pom.Repository> repositories = new ArrayList<>();
                for (RawRepositories.Repository repo : partial.getRepositories()) {
                    doesThrow(() -> {
                        try {
                            repositories.add(new Pom.Repository(URI.create(repo.getUrl()).toURL(),
                                    repo.getReleases() == null || repo.getReleases().isEnabled(),
                                    repo.getSnapshots() == null || repo.getSnapshots().isEnabled()));
                        } catch (MalformedURLException e) {
                            throw new MavenParsingException("Malformed repository URL '%s'", repo.getUrl());
                        }
                    });
                }

                result = Optional.of(
                        new Pom(
                                partial.getSourcePath(),
                                groupId,
                                rawPom.getArtifactId(),
                                version,
                                rawPom.getSnapshotVersion(),
                                null,
                                null,
                                partial.getParent(),
                                dependencies,
                                partial.getDependencyManagement(),
                                partial.getLicenses(),
                                repositories,
                                partial.getProperties()
                        )
                );
            } else {
                result = Optional.empty();
            }

            resolved.put(taskKey, result);
        }

        return result.orElse(null);
    }

    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    @Data
    private static class ResolutionTask {
        @EqualsAndHashCode.Include
        Scope scope;

        @EqualsAndHashCode.Include
        RawMaven rawMaven;

        @EqualsAndHashCode.Include
        @Nullable
        Set<GroupArtifact> exclusions;

        @EqualsAndHashCode.Include
        boolean optional;

        @EqualsAndHashCode.Include
        @Nullable
        String classifier;

        @EqualsAndHashCode.Include
        @Nullable
        String requestedVersion;

        List<RawRepositories.Repository> repositories;

        @Nullable
        LinkedHashSet<PartialTreeKey> seenParentPoms;

        public Set<GroupArtifact> getExclusions() {
            return exclusions == null ? emptySet() : exclusions;
        }
    }

    // FIXME may be able to eliminate this and go straight to ResolutionTask as the key
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @Data
    static class PartialTreeKey {
        String groupId;
        String artifactId;
        String version;
    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    @RequiredArgsConstructor
    @Getter
    @Setter
    class PartialMaven {
        @EqualsAndHashCode.Include
        final Path sourcePath;

        @EqualsAndHashCode.Include
        final RawPom rawPom;

        Pom parent;
        Pom.DependencyManagement dependencyManagement;
        Collection<ResolutionTask> dependencyTasks = emptyList();
        Collection<Pom.License> licenses = emptyList();
        List<RawRepositories.Repository> repositories = emptyList();
        Map<String, String> properties = emptyMap();

        @Nullable
        String getGroupId(@Nullable String g) {
            if (g == null) {
                return null;
            }
            if (g.equals("${project.groupId}") || g.equals("${pom.groupId}")) {
                String groupId = rawPom.getGroupId();
                if (groupId != null) {
                    return groupId;
                }
                return parent == null ? null : parent.getGroupId();
            } else if (g.equals("${project.parent.groupId}")) {
                return parent != null ? parent.getGroupId() : null;
            }
            return getValue(g);
        }

        @Nullable
        String getArtifactId(@Nullable String a) {
            if (a == null) {
                return null;
            }
            if (a.equals("${project.artifactId}") || a.equals("${pom.artifactId}")) {
                return rawPom.getArtifactId(); // cannot be inherited from parent
            } else if (a.equals("${project.parent.artifactId}")) {
                return parent != null ? parent.getArtifactId() : null;
            }
            return getValue(a);
        }

        @Nullable
        String getVersion(@Nullable String v) {
            String last = null;
            String version;
            for (version = v; version != null && !version.equals(last); ) {
                last = version;
                if (version.equals("${project.version}") || version.equals("${pom.version}")) {
                    String rawVersion = rawPom.getVersion();
                    if (rawVersion != null) {
                        version = rawVersion;
                        continue;
                    }
                    version = parent == null ? null : parent.getVersion();
                } else if (v.equals("${project.parent.version}")) {
                    version = parent != null ? parent.getVersion() : null;
                } else {
                    version = getValue(version);
                }
            }
            return version;
        }

        @Nullable
        String getScope(@Nullable String s) {
            return s == null ? null : getValue(s);
        }

        private String getValue(String v) {
            if (v.startsWith("${") && v.endsWith("}")) {
                String key = v.replace("${", "").replace("}", "");

                String value = rawPom.getActiveProperties(activeProfiles).get(key);
                if (value != null) {
                    return value;
                }

                // will be null when processing dependencyManagement itself...
                if (dependencyManagement != null) {
                    for (DependencyManagementDependency managedDependency : dependencyManagement.getDependencies()) {
                        value = managedDependency.getProperties().get(key);
                        if (value != null) {
                            return value;
                        }
                    }
                }

                for (Pom ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
                    value = ancestor.getProperty(key);
                    if (value != null) {
                        return value;
                    }
                }

                value = System.getProperty(key);
                if (value != null) {
                    return value;
                }

                return v;
            }
            return v;
        }
    }

    /**
     * Perform version conflict resolution on a dependency
     *
     * @param scope      The dependency's scope.
     * @param groupId    The dependency's group.
     * @param artifactId The dependency's artifact id.
     * @param version    The dependency's recommended version, if any.
     * @return The version selected by conflict resolution.
     */
    private RequestedVersion selectVersion(@Nullable Scope scope, String groupId, String artifactId, String version) {
        GroupArtifact groupArtifact = new GroupArtifact(groupId, artifactId);

        if (scope == null) {
            return new RequestedVersion(groupArtifact, null, version);
        }

        RequestedVersion nearer = null;
        for (Map<GroupArtifact, RequestedVersion> nearerInScope : versionSelection.headMap(scope, true).values()) {
            RequestedVersion requestedVersion = nearerInScope.get(groupArtifact);
            if (requestedVersion != null) {
                nearer = requestedVersion;
                break;
            }
        }

        return versionSelection.get(scope)
                .getOrDefault(groupArtifact, new RequestedVersion(groupArtifact, nearer, version));
    }
}
