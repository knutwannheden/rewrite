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
package org.openrewrite.java.search;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.Set;

/**
 * This recipe will find all annotations matching the annotation pattern and mark those elements with a
 * {@link SearchResult} marker.
 *
 * The annotation pattern, expressed as a pointcut expression, is used to find matching annotations.
 * See {@link  AnnotationMatcher} for details on the expression's syntax.
 * 
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FindAnnotations extends Recipe {

    /**
     * An annotation pattern, expressed as a pointcut expression. See {@link FindAnnotations} for syntax.
     */
    private final String annotationPattern;

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new FindAnnotationsVisitor(annotationPattern);
    }

    public static Set<J.Annotation> find(J j, String clazz) {
        //noinspection ConstantConditions
        return new FindAnnotationsVisitor(clazz)
                .visit(j, ExecutionContext.builder().build())
                .findMarkedWith(SearchResult.class);
    }

    private static class FindAnnotationsVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final AnnotationMatcher matcher;

        public FindAnnotationsVisitor(String signature) {
            this.matcher = new AnnotationMatcher(signature);
        }

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);
            if (matcher.matches(annotation)) {
                a = a.withMarker(new SearchResult(null));
            }
            return a;
        }

    }
}
