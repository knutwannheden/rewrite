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
import io.micrometer.core.instrument.Tags;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.jetbrains.annotations.NotNull;
import org.mapdb.*;
import org.openrewrite.internal.lang.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;

public final class CachingWorkspaceReader implements WorkspaceReader {
    private static final Map<File, CachingWorkspaceReader> READER_BY_CACHE_DIR = new ConcurrentHashMap<>();

    HTreeMap<Artifact, List<String>> versionsByArtifact;
    HTreeMap<Artifact, byte[]> artifactContentByArtifact;

    public static CachingWorkspaceReader forWorkspaceDir(@Nullable File workspace) {
        if (workspace != null) {
            return READER_BY_CACHE_DIR.computeIfAbsent(workspace, w -> {
                CachingWorkspaceReader reader = new CachingWorkspaceReader();

                DB localRepositoryDiskDb = DBMaker
                        .fileDB(workspace)
                        .transactionEnable()
                        .make();

                // big map populated with data expired from cache
                reader.versionsByArtifact = localRepositoryDiskDb
                        .hashMap("workspace.disk")
                        .keySerializer(ARTIFACT_SERIALIZER)
                        .valueSerializer(LIST_SERIALIZER)
                        .create();

                reader.artifactContentByArtifact = localRepositoryDiskDb
                        .hashMap("workspace.artifacts")
                        .keySerializer(ARTIFACT_SERIALIZER)
                        .valueSerializer(BYTE_ARRAY_SERIALIZER)
                        .create();

                Metrics.gaugeMapSize("rewrite.maven.workspace.cache.size", Tags.of("layer", "disk"),
                        reader.versionsByArtifact);

                return reader;
            });
        } else {
            CachingWorkspaceReader reader = new CachingWorkspaceReader();

            DB inMemoryDb = DBMaker
                    .heapDB()
                    .make();

            // fast in-memory collection with limited size
            reader.versionsByArtifact = inMemoryDb
                    .hashMap("workspace.inmem")
                    .keySerializer(ARTIFACT_SERIALIZER)
                    .valueSerializer(LIST_SERIALIZER)
                    .expireAfterCreate(10, TimeUnit.MINUTES)
                    .create();

            reader.artifactContentByArtifact = inMemoryDb
                    .hashMap("workspace.artifacts")
                    .keySerializer(ARTIFACT_SERIALIZER)
                    .valueSerializer(BYTE_ARRAY_SERIALIZER)
                    .expireAfterCreate(10, TimeUnit.MINUTES)
                    .create();

            Metrics.gaugeMapSize("rewrite.maven.workspace.cache.size", Tags.of("layer", "memory"),
                    reader.versionsByArtifact);

            return reader;
        }
    }

    private CachingWorkspaceReader() {

    }

    @Override
    public WorkspaceRepository getRepository() {
        return new WorkspaceRepository();
    }

    @Override
    public File findArtifact(Artifact artifact) {
        byte[] cacheEntry = artifactContentByArtifact.get(artifact);
        if(cacheEntry != null) {
            try {
                // The frustrating WorkspaceReader interface insists on this being a java.io.File, which is basically
                // impossible to mock. It's unfortunate to have to write bytes wy have in memory to disk, just so
                // they can be read back into memory. This could be sped up by using an in-memory filesystem
                File result = File.createTempFile(artifact.getGroupId() + "-" + artifact.getArtifactId(), "");
                Files.write(result.toPath(), cacheEntry);
                return result;
            } catch (IOException e) {
                // Maybe suppress and just return null instead
                throw new UncheckedIOException(e);
            }
        }
        return null;
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        return versionsByArtifact.getOrDefault(artifact, emptyList());
    }

    public void cacheVersions(Artifact artifact, List<String> versions) {
        if (!versionsByArtifact.containsKey(artifact)) {
            versionsByArtifact.put(artifact, versions);
        }
    }

    public void cacheArtifact(Artifact artifact, byte[] data) {
        if (!versionsByArtifact.containsKey(artifact)) {
            artifactContentByArtifact.put(artifact, data);
        }
    }

    private static final Serializer<Artifact> ARTIFACT_SERIALIZER = new Serializer<Artifact>() {
        @Override
        public void serialize(@NotNull DataOutput2 out, @NotNull Artifact value) throws IOException {
            out.writeUTF(value.getGroupId());
            out.writeUTF(value.getArtifactId());
            out.writeUTF(value.getClassifier());
            out.writeUTF(value.getExtension());
            out.writeUTF(value.getVersion());
        }

        @Override
        public Artifact deserialize(@NotNull DataInput2 input, int available) throws IOException {
            return new DefaultArtifact(input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF());
        }
    };

    private static final Serializer<List<String>> LIST_SERIALIZER = new Serializer<List<String>>() {
        @Override
        public void serialize(@NotNull DataOutput2 out, @NotNull List<String> value) throws IOException {
            out.writeShort(value.size());
            for (String s : value) {
                out.writeUTF(s);
            }

        }

        @Override
        public List<String> deserialize(@NotNull DataInput2 input, int available) throws IOException {
            short size = input.readShort();
            List<String> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(input.readUTF());
            }
            return list;
        }
    };

    private static final Serializer<byte[]> BYTE_ARRAY_SERIALIZER = new Serializer<byte[]>() {
        @Override
        public void serialize(@NotNull DataOutput2 out, @NotNull byte[] value) throws IOException {
            out.writeShort(value.length);
            for(byte b : value) {
                out.writeByte(b);
            }
        }

        @Override
        public byte[] deserialize(@NotNull DataInput2 input, int available) throws IOException {
            short size = input.readShort();
            byte[] result = new byte[size];
            for(int i = 0; i < size; i++) {
                result[i] = (byte) input.readUnsignedByte();
            }
            return result;
        }
    };
}
