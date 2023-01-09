/*
 * Copyright 2021 the original author or authors.
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
package org.openrewrite.java.internal;

import lombok.Value;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import org.openrewrite.internal.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

public class JavaTypeCache implements Cloneable {
    @Value
    private static class BytesKey {
        boolean compressed;
        byte[] data;
    }

    LZ4Factory factory = LZ4Factory.fastestInstance();
    LZ4Compressor compressor = factory.fastCompressor();
    Map<BytesKey, Object> typeCache = new HashMap<>();

    @Nullable
    public <T> T get(String signature) {
        //noinspection unchecked
        return (T) typeCache.get(key(signature));
    }

    public <T> T computeIfAbsent(String signature, Supplier<? extends T> typeSupplier, Function<T, T> postInitializer) {
        AtomicBoolean newType = new AtomicBoolean(false);
        BytesKey bytesKey = key(signature);
        //noinspection unchecked
        T result = (T) typeCache.computeIfAbsent(bytesKey, k -> {
            newType.set(true);
            return typeSupplier.get();
        });
        if (newType.get()) {
            T initialized = postInitializer.apply(result);
            if (initialized != result) {
                typeCache.put(bytesKey, initialized);
            }
            return initialized;
        }
        return result;
    }

    public void put(String signature, Object o) {
        typeCache.put(key(signature), o);
    }

    private BytesKey key(String signature) {
        if (signature.length() < 50) {
            return new BytesKey(false, signature.getBytes(StandardCharsets.UTF_8));
        }
        return new BytesKey(true, compressor.compress(signature.getBytes(StandardCharsets.UTF_8)));
    }

    public void clear() {
        typeCache.clear();
    }

    public int size() {
        return typeCache.size();
    }

    @Override
    public JavaTypeCache clone() {
        try {
            JavaTypeCache clone = (JavaTypeCache) super.clone();
            clone.typeCache = new HashMap<>(this.typeCache);
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
