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
/*
 * CopyLeft 2020 the original author or authors.
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
package org.openrewrite.java.tree;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.With;
import lombok.experimental.FieldDefaults;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.Markable;
import org.openrewrite.marker.Markers;

import java.util.function.UnaryOperator;

/**
 * A Java element that could have space preceding some delimiter.
 * For example an array dimension could have space before the opening
 * bracket, and the containing {@link #elem} could have a prefix that occurs
 * after the bracket.
 *
 * @param <T>
 */
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Data
public class JLeftPadded<T> implements Markable {
    @With
    Space before;

    @With
    T elem;

    @With
    Markers markers;

    public JLeftPadded<T> map(UnaryOperator<T> map) {
        return withElem(map.apply(elem));
    }

    public enum Location {
        ASSIGNMENT(Space.Location.ASSIGNMENT),
        ASSIGN_OP_OPERATOR(Space.Location.ASSIGN_OP_OPERATOR),
        BINARY_OPERATOR(Space.Location.BINARY_OPERATOR),
        CLASS_KIND(Space.Location.CLASS_KIND),
        EXTENDS(Space.Location.EXTENDS),
        FIELD_ACCESS_NAME(Space.Location.FIELD_ACCESS_NAME),
        MEMBER_REFERENCE_NAME(Space.Location.MEMBER_REFERENCE_NAME),
        METHOD_DECL_DEFAULT_VALUE(Space.Location.METHOD_DECL_DEFAULT_VALUE),
        STATIC_IMPORT(Space.Location.STATIC_IMPORT),
        TERNARY_TRUE(Space.Location.TERNARY_TRUE),
        TERNARY_FALSE(Space.Location.TERNARY_FALSE),
        TRY_FINALLY(Space.Location.TRY_FINALLY),
        UNARY_OPERATOR(Space.Location.UNARY_OPERATOR),
        VARIABLE_INITIALIZER(Space.Location.VARIABLE_INITIALIZER),
        WHILE_CONDITION(Space.Location.WHILE_CONDITION);

        private final Space.Location beforeLocation;

        Location(Space.Location beforeLocation) {
            this.beforeLocation = beforeLocation;
        }

        public Space.Location getBeforeLocation() {
            return beforeLocation;
        }
    }

    @Nullable
    public static <T> JLeftPadded<T> withElem(@Nullable JLeftPadded<T> before, @Nullable T elems) {
        if (before == null) {
            if (elems == null) {
                return null;
            }
            return new JLeftPadded<>(Space.EMPTY, elems, Markers.EMPTY);
        }
        if (elems == null) {
            return null;
        }
        return before.withElem(elems);
    }

    @Override
    public String toString() {
        return "JLeftPadded(before=" + before + ", elem=" + elem.getClass().getSimpleName() + ')';
    }
}
