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
package org.openrewrite.java.format;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.Space;

public class MinimumViableSpacingVisitor<P> extends JavaIsoVisitor<P> {
    @Override
    public J.ClassDecl visitClassDecl(J.ClassDecl classDecl, P p) {
        J.ClassDecl c = super.visitClassDecl(classDecl, p);

        boolean first = true;
        if (!c.getAnnotations().isEmpty()) {
            first = false;
        }
        if (!c.getModifiers().isEmpty()) {
            if (!first && Space.firstPrefix(c.getModifiers()).getWhitespace().isEmpty()) {
                c = c.withModifiers(Space.formatFirstPrefix(c.getModifiers(),
                        c.getModifiers().iterator().next().getPrefix().withWhitespace(" ")));
            }
            first = false;
        }

        J.ClassDecl.Padding padding = c.getPadding();
        JContainer<J.TypeParameter> typeParameters = padding.getTypeParameters();
        if (typeParameters != null && !typeParameters.getElems().isEmpty()) {
            if (!first && !typeParameters.getBefore().getWhitespace().isEmpty()) {
                c = padding.withTypeParameters(typeParameters.withBefore(typeParameters.getBefore().withWhitespace(" ")));
            }
        }

        return c.withName(c.getName().withPrefix(c.getName().getPrefix().withWhitespace(" ")));
    }

    @Override
    public J.MethodDecl visitMethod(J.MethodDecl method, P p) {
        J.MethodDecl m = super.visitMethod(method, p);

        boolean first = true;
        if (!m.getAnnotations().isEmpty()) {
            first = false;
        }
        if (!m.getModifiers().isEmpty()) {
            if (!first && Space.firstPrefix(m.getModifiers()).getWhitespace().isEmpty()) {
                m = m.withModifiers(Space.formatFirstPrefix(m.getModifiers(),
                        m.getModifiers().iterator().next().getPrefix().withWhitespace(" ")));
            }
            first = false;
        }
        J.MethodDecl.Padding padding = m.getPadding();
        JContainer<J.TypeParameter> typeParameters = padding.getTypeParameters();
        if (typeParameters != null && !typeParameters.getElems().isEmpty()) {
            if (!first && typeParameters.getBefore().getWhitespace().isEmpty()) {
                m = padding.withTypeParameters(typeParameters.withBefore(typeParameters.getBefore().withWhitespace(" ")));
            }
            first = false;
        }
        if (m.getReturnTypeExpr() != null && m.getReturnTypeExpr().getPrefix().getWhitespace().isEmpty()) {
            if (!first) {
                m = m.withReturnTypeExpr(m.getReturnTypeExpr()
                        .withPrefix(m.getReturnTypeExpr().getPrefix().withWhitespace(" ")));
            }
            first = false;
        }
        if (!first) {
            m = m.withName(m.getName().withPrefix(m.getName().getPrefix().withWhitespace(" ")));
        }

        return m;
    }

    @Override
    public J.Return visitReturn(J.Return retrn, P p) {
        J.Return r = super.visitReturn(retrn, p);
        if (r.getExpr() != null && r.getExpr().getPrefix().getWhitespace().isEmpty()) {
            r = r.withExpr(r.getExpr().withPrefix(r.getExpr().getPrefix().withWhitespace(" ")));
        }
        return r;
    }
}
