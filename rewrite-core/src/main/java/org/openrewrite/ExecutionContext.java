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
package org.openrewrite;

import org.openrewrite.internal.lang.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * Passes messages between individual TreeVisitor / Recipes in the chain
 * controls execution and lifecycle by providing a ForkJoinPool and max number of cycles
 */
public final class ExecutionContext {
    private volatile boolean needAnotherCycle = false;

    private final int maxCycles;

    @Nullable
    private final Consumer<Throwable> onError;

    private final ForkJoinPool forkJoinPool;

    private final Map<String, Object> messages = new ConcurrentHashMap<>();

    final Map<UUID, String> recipeThatModifiedSourceFile = new HashMap<>();

    private ExecutionContext(int maxCycles, @Nullable Consumer<Throwable> onError, ForkJoinPool forkJoinPool) {
        this.maxCycles = maxCycles;
        this.onError = onError;
        this.forkJoinPool = forkJoinPool;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void putMessage(String key, Object value) {
        needAnotherCycle = true;
        messages.put(key, value);
    }

    @Nullable
    public <T> T peekMessage(String key) {
        //noinspection unchecked
        return (T) messages.get(key);
    }

    @Nullable
    public <T> T pollMessage(String key) {
        //noinspection unchecked
        return (T) messages.remove(key);
    }

    boolean isNeedAnotherCycle() {
        return needAnotherCycle;
    }

    /**
     * Break the {@link org.openrewrite.Recipe} execution cycle for the current visitor
     */
    void nextCycle() {
        needAnotherCycle = false;
    }

    int getMaxCycles() {
        return maxCycles;
    }

    @Nullable
    Consumer<Throwable> getOnError() {
        return onError;
    }

    ForkJoinPool getForkJoinPool() {
        return forkJoinPool;
    }

    void recordSourceFileModification(SourceFile sourceFile, Recipe recipe) {
        recipeThatModifiedSourceFile.put(sourceFile.getId(), recipe.getName());
    }

    String getRecipeThatModifiedSourceFile(UUID sourceFileId) {
        return recipeThatModifiedSourceFile.get(sourceFileId);
    }

    public static class Builder {
        private int maxCycles = 3;

        @Nullable
        private Consumer<Throwable> onError;

        private ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();

        public Builder maxCycles(int maxCycles) {
            this.maxCycles = maxCycles;
            return this;
        }

        public Builder doOnError(@Nullable Consumer<Throwable> onError) {
            this.onError = onError;
            return this;
        }

        public Builder forkJoinPool(ForkJoinPool forkJoinPool) {
            this.forkJoinPool = forkJoinPool;
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(maxCycles, onError, forkJoinPool);
        }
    }
}
