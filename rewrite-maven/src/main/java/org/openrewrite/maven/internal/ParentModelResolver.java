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

import io.micrometer.core.instrument.Metrics;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.ModelSource2;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectModelResolver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class ParentModelResolver extends ProjectModelResolver {
    private static final Logger logger = LoggerFactory.getLogger(ParentModelResolver.class);

    private final CachingWorkspaceReader workspaceReader;
    private final File localRepository;

    public ParentModelResolver(RepositorySystemSession session,
                               RepositorySystem resolver,
                               RemoteRepositoryManager remoteRepositoryManager,
                               List<RemoteRepository> repositories,
                               CachingWorkspaceReader workspaceReader,
                               File localRepository) {
        super(session,
                new RequestTrace(null),
                resolver,
                remoteRepositoryManager,
                repositories.stream()
                        .map(ParentModelResolver::httpsFallback)
                        .collect(Collectors.toList()),
                ProjectBuildingRequest.RepositoryMerging.POM_DOMINANT,
                null);

        this.workspaceReader = workspaceReader;
        this.localRepository = localRepository;
    }

    /**
     * Most repositories, at least the big public ones, in the maven ecosystem now require HTTPS.
     * If a remote repository with an HTTP protocol comes in, check to see if the repository accepts HTTP connections
     * and switch to HTTPS if it does not
     */
    private static RemoteRepository httpsFallback(RemoteRepository repository) {
        RemoteRepository result = repository;
        try {
            if (repository.getProtocol().equals("http")) {
                URLConnection connection = URI.create(repository.getUrl()).toURL().openConnection();
                if (connection instanceof HttpURLConnection) {
                    HttpURLConnection httpConnection = (HttpURLConnection) connection;
                    httpConnection.setRequestMethod("HEAD");
                    if (httpConnection.getResponseCode() == 403) {
                        result = new RemoteRepository.Builder(repository)
                                .setUrl(repository.getUrl().replace("http:", "https:"))
                                .build();
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to add repository with URL: " + repository.getUrl(), e);
        }
        return result;
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        try {
            if (repository.getUrl().contains("http:")) {
                URLConnection connection = URI.create(repository.getUrl()).toURL().openConnection();
                if (connection instanceof HttpURLConnection) {
                    HttpURLConnection httpConnection = (HttpURLConnection) connection;
                    httpConnection.setRequestMethod("HEAD");
                    if (httpConnection.getResponseCode() == 403) {
                        repository.setUrl(repository.getUrl().replace("http:", "https:"));
                    }
                }
            }
            super.addRepository(repository, replace);
        } catch (IOException e) {
            logger.warn("Unable to add repository with URL: " + repository.getUrl(), e);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version) {
        return Metrics.timer("resolve.model", "group.id", groupId, "artifact.id", artifactId).record(() -> {
            logger.trace("resolving model for: {}:{}", groupId, artifactId);

            try {
                ModelSource modelSource = super.resolveModel(groupId, artifactId, version);
                if (Paths.get(modelSource.getLocation()).startsWith(localRepository.toPath())) {
                    // convert to inmem from cache
                }
                return modelSource;
            } catch (UnresolvableModelException e) {
                logger.error("unable to resolve model", e);
                return new UnresolvedModelSource(groupId, artifactId, version);
            }
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public ModelSource resolveModel(Parent parent) {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @SuppressWarnings("deprecation")
    @Override
    public ModelSource resolveModel(Dependency dependency) {
        return Metrics.timer("resolve.model", "group.id", dependency.getGroupId(), "artifact.id", dependency.getArtifactId()).record(() -> {
            logger.trace("resolving model for: {}:{}", dependency.getGroupId(), dependency.getArtifactId());

            try {
                return super.resolveModel(dependency);
            } catch (UnresolvableModelException e) {
                logger.error("unable to resolve model", e);
                return new UnresolvedModelSource(dependency.getGroupId(),
                        dependency.getArtifactId(),
                        dependency.getVersion());
            }
        });
    }

    @Override
    public ModelResolver newCopy() {
        return this;
    }

    public InMemModelSource cacheAndDeleteFile(Artifact artifact, FileModelSource fileModelSource) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        try {
            InputStream is = fileModelSource.getInputStream();
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            fileModelSource.getFile().delete();

            byte[] source = buffer.toByteArray();

            return new InMemModelSource(source, fileModelSource.getLocationURI(),
                    fileModelSource.getLocation());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class InMemModelSource implements ModelSource2 {
        private final byte[] source;
        private final URI locationUri;
        private final String location;

        InMemModelSource(byte[] source, URI locationUri, String location) {
            this.source = source;
            this.locationUri = locationUri;
            this.location = location;
        }

        @Override
        public ModelSource2 getRelatedSource(String relPath) {
            throw new UnsupportedOperationException("should not happen");
        }

        @Override
        public URI getLocationURI() {
            return locationUri;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(source);
        }

        @Override
        public String getLocation() {
            return location;
        }
    }

    private static class UnresolvedModelSource implements ModelSource2 {
        private final String groupId;
        private final String artifactId;
        private final String version;

        private UnresolvedModelSource(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        @Override
        public ModelSource2 getRelatedSource(String relPath) {
            return null;
        }

        @Override
        public URI getLocationURI() {
            return null;
        }

        @Override
        public InputStream getInputStream() {
            String syntheticPom = "<project>\n" +
                    "<modelVersion>4.0.0</modelVersion>\n" +
                    "<packaging>pom</packaging>\n" +
                    "<groupId>" + groupId + "</groupId>\n" +
                    "<artifactId>" + artifactId + "</artifactId>\n" +
                    "<version>" + version + "</version>\n" +
                    "</project>";

            return new ByteArrayInputStream(syntheticPom.getBytes());
        }

        @Override
        public String getLocation() {
            return null;
        }
    }
}
