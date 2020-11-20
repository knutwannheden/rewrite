package org.openrewrite.maven.internal;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.DependencyDescriptor;
import org.openrewrite.maven.tree.DependencyManagementDependency;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.tree.Pom;
import org.openrewrite.maven.tree.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.*;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class RawMavenResolver {
    private static final Logger logger = LoggerFactory.getLogger(RawMavenResolver.class);

    // https://maven.apache.org/ref/3.6.3/maven-model-builder/super-pom.html
    private static final List<RawPom.Repository> SUPER_POM_REPOSITORY = singletonList(new RawPom.Repository("https://repo.maven.apache.org/maven2",
            new RawPom.ArtifactPolicy(true), new RawPom.ArtifactPolicy(false)));

    // This is used to keep track of what versions have been seen further up the tree so we don't unnecessarily
    // resolve subtrees that have no chance of being selected by conflict resolution.
    private final NavigableMap<Scope, Map<GroupArtifact, RequestedVersion>> versionSelection;

    // The breadth-first queue of resolution tasks.
    private final Queue<ResolutionTask> workQueue = new LinkedList<>();

    private final Map<PartialTreeKey, Optional<Maven>> resolved = new HashMap<>();
    private final Map<ResolutionTask, PartialMaven> partialResults = new HashMap<>();

    private final RawPomDownloader downloader;

    /**
     * Is this resolver being used for resolving parents or import BOMs? Just a flag for logging purposes alone...
     */
    private final boolean forParent;

    private final boolean resolveOptional;

    public RawMavenResolver(RawPomDownloader downloader, boolean forParent, boolean resolveOptional) {
        this.versionSelection = new TreeMap<>();
        for (Scope scope : Scope.values()) {
            versionSelection.putIfAbsent(scope, new HashMap<>());
        }
        this.downloader = downloader;
        this.forParent = forParent;
        this.resolveOptional = resolveOptional;
    }

    /**
     * Resolution is performed breadth-first because the default conflict resolution algorithm
     * for Maven prefers nearer versions. By proceeding breadth-first we can avoid even attempting
     * to resolve subtrees that have no chance of being selected by conflict resolution in the end.
     *
     * @param rawMaven The shell of the POM to resolve.
     * @return A transitively resolved POM model.
     */
    @Nullable
    public Maven resolve(RawMaven rawMaven) {
        ResolutionTask rootTask = new ResolutionTask(Scope.Compile, rawMaven, emptySet(),
                false, null, SUPER_POM_REPOSITORY);

        workQueue.add(rootTask);

        while (!workQueue.isEmpty()) {
            processTask(workQueue.poll());
        }

        return assembleResults(rootTask, new Stack<>());
    }

    private void processTask(ResolutionTask task) {
        RawMaven rawMaven = task.getRawMaven();
        RawPom pom = rawMaven.getPom();

        List<RawPom.Repository> repositories = new ArrayList<>();
        if (pom.getRepositories() != null) {
            repositories.addAll(pom.getRepositories());
        }
        repositories.addAll(task.getRepositories());

        PartialMaven partialMaven = new PartialMaven(rawMaven);
        processProperties(task, partialMaven);
        processParent(task, partialMaven);
        processDependencyManagement(task, partialMaven, task.getRepositories());
        processLicenses(task, partialMaven);
        processRepositories(task, partialMaven);
        processDependencies(task, partialMaven, repositories);

        partialResults.put(task, partialMaven);
    }

    private void processProperties(ResolutionTask task, PartialMaven partialMaven) {
        partialMaven.setProperties(task.getRawMaven().getActiveProperties());
    }

    private void processDependencyManagement(ResolutionTask task, PartialMaven partialMaven, List<RawPom.Repository> repositories) {
        RawPom pom = task.getRawMaven().getPom();
        List<DependencyManagementDependency> managedDependencies = new ArrayList<>();

        RawPom.DependencyManagement dependencyManagement = pom.getDependencyManagement();
        if (dependencyManagement != null) {
            for (RawPom.Dependency d : dependencyManagement.getDependencies()) {
                assert d.getVersion() != null;

                String groupId = partialMaven.getGroupId(d.getGroupId());
                String artifactId = partialMaven.getArtifactId(d.getArtifactId());
                String version = partialMaven.getVersion(d.getVersion());

                // for debugging...
                if (groupId == null || artifactId == null || version == null) {
                    assert groupId != null;
                    assert artifactId != null;
                    //noinspection ConstantConditions
                    assert version != null;
                }

                // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#importing-dependencies
                if (Objects.equals(d.getType(), "pom") && Objects.equals(d.getScope(), "import")) {
                    RawMaven rawMaven = downloader.download(groupId, artifactId, version, null, null, null,
                            repositories);
                    if (rawMaven != null) {
                        Maven maven = new RawMavenResolver(downloader, true, resolveOptional).resolve(rawMaven);

                        if (maven != null) {
                            managedDependencies.add(new DependencyManagementDependency.Imported(maven));
                        }
                    }
                } else {
                    managedDependencies.add(new DependencyManagementDependency.Defined(
                            groupId, artifactId, version,
                            d.getScope() == null ? null : Scope.fromName(d.getScope()),
                            d.getClassifier(), d.getExclusions()));
                }
            }
        }

        partialMaven.setDependencyManagement(new Pom.DependencyManagement(managedDependencies));
    }

    private void processDependencies(ResolutionTask task, PartialMaven partialMaven, List<RawPom.Repository> repositories) {
        RawMaven rawMaven = task.getRawMaven();

        // Parent dependencies wind up being part of the subtree rooted at "task", so affect conflict resolution further down tree.
        if (partialMaven.getParent() != null) {
            for (Pom.Dependency dependency : partialMaven.getParent().getModel().getDependencies()) {
                RequestedVersion requestedVersion = selectVersion(rawMaven.getURI(), dependency.getScope(), dependency.getGroupId(),
                        dependency.getArtifactId(), dependency.getVersion());
                versionSelection.get(dependency.getScope()).put(new GroupArtifact(dependency.getGroupId(), dependency.getArtifactId()), requestedVersion);
            }
        }

        partialMaven.setDependencyTasks(rawMaven.getActiveDependencies().stream()
                .filter(dep -> {
                    // we don't care about test-jar, etc.
                    return dep.getType() == null || dep.getType().equals("jar");
                })
                .filter(dep -> resolveOptional || dep.getOptional() == null || !dep.getOptional())
                .map(dep -> {
                    // replace property references, source versions from dependency management sections, etc.
                    String groupId = partialMaven.getGroupId(dep.getGroupId());
                    String artifactId = partialMaven.getArtifactId(dep.getArtifactId());

                    // for debugging...
                    if (groupId == null || artifactId == null) {
                        assert groupId != null;
                        //noinspection ConstantConditions
                        assert artifactId != null;
                    }

                    String version = null;
                    String last;

                    // loop so that when dependencyManagement refers to a property that we take another pass to resolve the property.
                    int i = 0;
                    do {
                        last = version;
                        version = ofNullable(last)
                                .map(partialMaven::getVersion)
                                .orElseGet(() -> partialMaven.getDependencyManagement().getDependencies().stream()
                                        .flatMap(managed -> managed.getDependencies().stream())
                                        .filter(managed -> groupId.equals(partialMaven.getGroupId(managed.getGroupId())) &&
                                                artifactId.equals(partialMaven.getArtifactId(managed.getArtifactId())))
                                        .findAny()
                                        .map(DependencyDescriptor::getVersion)
                                        .orElseGet(() -> partialMaven.getParent() == null ? null : partialMaven.getParent().getModel()
                                                .getManagedVersion(groupId, artifactId))
                                );
                    } while (i++ < 2 || !Objects.equals(version, last));

                    // dependencyManagement takes precedence over the version specified on the dependency
                    if (version == null) {
                        version = ofNullable(dep.getVersion()).map(partialMaven::getVersion).orElse(null);
                    }

                    // for debugging...
                    if (version == null) {
                        logger.error("Failed to determine version for {}:{}. Initial value was {}. Including POM is at {}",
                                groupId, artifactId, dep.getVersion(),
                                rawMaven.getURI());

                        //noinspection ConstantConditions
                        assert version != null;
                    }

                    Scope requestedScope = Scope.fromName(partialMaven.getScope(dep.getScope()));
                    Scope effectiveScope = requestedScope.transitiveOf(task.getScope());

                    if (effectiveScope == null) {
                        return null;
                    }

                    RequestedVersion requestedVersion = selectVersion(rawMaven.getURI(), effectiveScope, groupId, artifactId, version);
                    versionSelection.get(effectiveScope).put(new GroupArtifact(groupId, artifactId), requestedVersion);

                    version = requestedVersion.resolve(downloader, repositories);

                    // for debugging
                    //noinspection RedundantIfStatement
                    if (version.contains("${")) {
                        assert !version.contains("${");
                    }

                    return new RawPom.Dependency(
                            groupId,
                            artifactId,
                            version,
                            requestedScope.toString().toLowerCase(),
                            dep.getType(),
                            dep.getClassifier(),
                            dep.getOptional() != null && dep.getOptional(),
                            dep.getExclusions()
                    );
                })
                .filter(Objects::nonNull)
                .filter(dep -> task.getExclusions().stream().noneMatch(e -> dep.getGroupId().matches(e.getGroupId()) &&
                        dep.getArtifactId().matches(e.getArtifactId())))
                .map(dep -> {
                    assert dep.getVersion() != null;

                    RawMaven download = downloader.download(dep.getGroupId(), dep.getArtifactId(),
                            dep.getVersion(), dep.getClassifier(), null, rawMaven,
                            task.getRepositories());

                    if (download == null) {
                        logger.warn("Unable to download {}:{}:{}. Including POM is at {}",
                                dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
                                rawMaven.getURI());
                        return null;
                    }

                    ResolutionTask resolutionTask = new ResolutionTask(
                            Scope.fromName(dep.getScope()),
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
                            repositories
                    );

                    if (!partialResults.containsKey(resolutionTask)) {
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
        Maven parent = null;
        if (pom.getParent() != null) {
            // TODO would it help to limit lookups of parents here by pre-caching RawMaven by RawPom.Parent?
            RawPom.Parent rawParent = pom.getParent();
            RawMaven rawParentModel = downloader.download(rawParent.getGroupId(), rawParent.getArtifactId(),
                    rawParent.getVersion(), null, rawParent.getRelativePath(), rawMaven,
                    SUPER_POM_REPOSITORY);
            if (rawParentModel != null) {
                PartialTreeKey parentKey = new PartialTreeKey(rawParent.getGroupId(), rawParent.getArtifactId(), rawParent.getVersion());
                Optional<Maven> maybeParent = resolved.get(parentKey);

                //noinspection OptionalAssignedToNull
                if (maybeParent == null) {
                    parent = new RawMavenResolver(downloader, true, resolveOptional).resolve(rawParentModel);
                    resolved.put(parentKey, Optional.ofNullable(parent));
                } else {
                    parent = maybeParent.orElse(null);
                }
            }
        }

        partialMaven.setParent(parent);
    }

    private void processRepositories(ResolutionTask task, PartialMaven partialMaven) {
        List<RawPom.Repository> repositories = task.getRawMaven().getPom().getRepositories();
        if (repositories != null) {
            List<Pom.Repository> parsed = new ArrayList<>();
            for (RawPom.Repository repo : repositories) {
                try {
                    parsed.add(new Pom.Repository(URI.create(repo.getUrl()).toURL(),
                            repo.getReleases() == null || repo.getReleases().isEnabled(),
                            repo.getSnapshots() == null || repo.getSnapshots().isEnabled()));
                } catch (MalformedURLException e) {
                    logger.debug("Malformed repository URL '{}'", repo.getUrl());
                }
            }
            partialMaven.setRepositories(parsed);
        }
    }

    private void processLicenses(ResolutionTask task, PartialMaven partialMaven) {
        List<RawPom.License> licenses = task.getRawMaven().getPom().getLicenses();
        if (licenses != null) {
            partialMaven.setLicenses(licenses.stream()
                    .map(license -> Pom.License.fromName(license.getName()))
                    .collect(toList()));
        }
    }

    @Nullable
    private Maven assembleResults(ResolutionTask task, Stack<ResolutionTask> assemblyStack) {
        if (assemblyStack.contains(task)) {
            return null; // cut cycles
        }
        RawMaven rawMaven = task.getRawMaven();
        RawPom rawPom = rawMaven.getPom();
        PartialTreeKey taskKey = new PartialTreeKey(rawPom.getGroupId(), rawPom.getArtifactId(), rawPom.getVersion());

        Optional<Maven> result = resolved.get(taskKey);

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

                            if (logger.isDebugEnabled() && !forParent) {
                                String indent = CharBuffer.allocate(assemblyStack.size()).toString().replace('\0', ' ');
                                RawPom depPom = depTask.getRawMaven().getPom();
                                logger.debug(
                                        "{}{}:{}:{}{} {}",
                                        indent,
                                        depPom.getGroupId(),
                                        depPom.getArtifactId(),
                                        depPom.getVersion(),
                                        optional ? " (optional) " : "",
                                        depTask.getRawMaven().getURI()
                                );
                            }

                            Maven resolved = assembleResults(depTask, nextAssemblyStack);
                            if (resolved == null) {
                                return null;
                            }

                            return new Pom.Dependency(
                                    depTask.getScope(),
                                    depTask.getClassifier(),
                                    optional,
                                    resolved,
                                    depTask.getExclusions()
                            );
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(ArrayList::new));

                for (Maven ancestor = partial.getParent(); ancestor != null; ancestor = ancestor.getModel().getParent()) {
                    for (Pom.Dependency ancestorDep : ancestor.getModel().getDependencies()) {
                        // the ancestor's dependency might be overridden by another version by conflict resolution
                        Scope scope = ancestorDep.getScope();
                        String groupId = ancestorDep.getGroupId();
                        String artifactId = ancestorDep.getArtifactId();

                        String conflictResolvedVersion = selectVersion(rawMaven.getURI(), scope, groupId, artifactId, ancestorDep.getVersion())
                                .resolve(downloader, task.getRepositories());

                        if (!conflictResolvedVersion.equals(ancestorDep.getVersion())) {
                            RawMaven conflictResolvedRaw = downloader.download(groupId, artifactId, conflictResolvedVersion,
                                    ancestorDep.getClassifier(), null, null, task.getRepositories());

                            Maven conflictResolved = assembleResults(new ResolutionTask(scope, conflictResolvedRaw, ancestorDep.getExclusions(),
                                    ancestorDep.isOptional(), ancestorDep.getClassifier(), task.getRepositories()), nextAssemblyStack);

                            // for debugging
                            //noinspection RedundantIfStatement
                            if (conflictResolved == null) {
                                //noinspection ConstantConditions
                                assert conflictResolved != null;
                            }

                            dependencies.add(new Pom.Dependency(
                                    scope,
                                    ancestorDep.getClassifier(),
                                    ancestorDep.isOptional(),
                                    conflictResolved,
                                    ancestorDep.getExclusions()
                            ));
                        } else {
                            dependencies.add(ancestorDep);
                        }
                    }
                }

                String groupId = rawPom.getGroupId();
                if (groupId == null) {
                    groupId = partial.getParent().getModel().getGroupId();
                }

                String version = rawPom.getVersion();
                if (version == null) {
                    version = partial.getParent().getModel().getVersion();
                }

                result = Optional.of(new Maven(
                        rawMaven.getDocument(),
                        Pom.build(
                                groupId,
                                rawPom.getArtifactId(),
                                version,
                                "jar",
                                null,
                                partial.getParent(),
                                dependencies,
                                partial.getDependencyManagement(),
                                partial.getLicenses(),
                                partial.getRepositories(),
                                partial.getProperties()
                        )
                ));
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
        Set<GroupArtifact> exclusions;

        @EqualsAndHashCode.Include
        boolean optional;

        @EqualsAndHashCode.Include
        @Nullable
        String classifier;

        List<RawPom.Repository> repositories;

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
    static class PartialMaven {
        @EqualsAndHashCode.Include
        final RawMaven rawMaven;

        Maven parent;
        Pom.DependencyManagement dependencyManagement;
        Collection<ResolutionTask> dependencyTasks = emptyList();
        Collection<Pom.License> licenses = emptyList();
        Collection<Pom.Repository> repositories = emptyList();
        Map<String, String> properties = emptyMap();

        @Nullable
        String getGroupId(String g) {
            if (g.equals("${project.groupId}") || g.equals("${pom.groupId}")) {
                return ofNullable(rawMaven.getPom().getGroupId())
                        .orElse(parent == null ? null : parent.getModel().getGroupId());
            } else if (g.equals("${project.parent.groupId}")) {
                return parent != null ? parent.getModel().getGroupId() : null;
            }
            return getValue(g);
        }

        @Nullable
        String getArtifactId(String a) {
            if (a.equals("${project.artifactId}") || a.equals("${pom.artifactId}")) {
                return rawMaven.getPom().getArtifactId(); // cannot be inherited from parent
            } else if (a.equals("${project.parent.artifactId}")) {
                return parent != null ? parent.getModel().getArtifactId() : null;
            }
            return getValue(a);
        }

        @Nullable
        String getVersion(@Nullable String v) {
            if (v == null) {
                return null;
            } else if (v.equals("${project.version}") || v.equals("${pom.version}")) {
                return ofNullable(rawMaven.getPom().getVersion())
                        .orElse(parent == null ? null : parent.getModel().getVersion());
            } else if (v.equals("${project.parent.version}")) {
                return parent != null ? parent.getModel().getVersion() : null;
            }
            return getValue(v);
        }

        @Nullable
        String getScope(@Nullable String s) {
            return s == null ? null : getValue(s);
        }

        private String getValue(String v) {
            if (v.startsWith("${") && v.endsWith("}")) {
                String key = v.replace("${", "").replace("}", "");

                String value = rawMaven.getActiveProperties().get(key);
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

                for (Maven ancestor = parent; ancestor != null; ancestor = ancestor.getModel().getParent()) {
                    value = ancestor.getModel().getProperty(key);
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
    private RequestedVersion selectVersion(URI containingPomUri, @Nullable Scope scope, String groupId, String artifactId, String version) {
        GroupArtifact groupArtifact = new GroupArtifact(groupId, artifactId);

        if (scope == null) {
            return new RequestedVersion(containingPomUri, groupArtifact, null, version);
        }

        RequestedVersion nearer = versionSelection.headMap(scope, true).values().stream()
                .map(nearerInScope -> nearerInScope.get(groupArtifact))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return versionSelection.get(scope)
                .getOrDefault(groupArtifact, new RequestedVersion(containingPomUri, groupArtifact, nearer, version));
    }
}