/*
 * Copyright 2025 the original author or authors.
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
package org.openrewrite.rpc;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.moderne.jsonrpc.formatter.MessageFormatter;
import org.msgpack.jackson.dataformat.MessagePackFactory;

/**
 * A MessageFormatter that uses MsgPack encoding instead of JSON.
 * This provides more compact binary serialization compared to JSON,
 * typically resulting in 20-30% smaller payloads and faster serialization.
 */
public class MsgPackMessageFormatter implements MessageFormatter {
    private final ObjectMapper mapper;

    /**
     * Create a new MsgPackMessageFormatter with custom Jackson modules.
     *
     * @param modules Additional Jackson modules to register (e.g., for custom serializers)
     */
    public MsgPackMessageFormatter(Module... modules) {
        this.mapper = new ObjectMapper(new MessagePackFactory());

        // Register any custom modules
        for (Module module : modules) {
            mapper.registerModule(module);
        }
    }

    /**
     * Create a new MsgPackMessageFormatter with default configuration.
     */
    public MsgPackMessageFormatter() {
        this(new SimpleModule());
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    @Override
    public String getContentType() {
        return "application/msgpack";
    }
}
