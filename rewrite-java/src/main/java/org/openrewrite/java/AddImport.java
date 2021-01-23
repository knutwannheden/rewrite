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
package org.openrewrite.java;

import lombok.EqualsAndHashCode;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.search.FindMethod;
import org.openrewrite.java.search.FindType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;

import static org.openrewrite.Tree.randomId;

/**
 * A Java refactoring visitor that can be used to add an import (or static import) to a given compilation unit.
 * This visitor can also be configured to only add the import if the imported class/method are referenced within the
 * compilation unit.
 * <P><P>
 * The {@link AddImport#type} must be supplied and represents a fully qualified class name.
 * <P><P>
 * The {@link AddImport#statik} is an optional method within the imported type. The staticMethod can be set to "*"
 * to represent a static wildcard import.
 * <P><P>
 * The {@link AddImport#onlyIfReferenced} is a flag (defaulted to true) to indicate if the import should only be added
 * if there is a reference to the imported class/method.
 */
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class AddImport<P> extends JavaIsoProcessor<P> {
    @EqualsAndHashCode.Include
    private final String type;

    @EqualsAndHashCode.Include
    @Nullable
    private final String statik;

    @EqualsAndHashCode.Include
    private final boolean onlyIfReferenced;

    private final JavaType.Class classType;

    public AddImport(String type, @Nullable String statik, boolean onlyIfReferenced) {
        this.type = type;
        this.classType = JavaType.Class.build(type);
        this.statik = statik;
        this.onlyIfReferenced = onlyIfReferenced;
    }

    @Override
    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, P p) {
        if (JavaType.Primitive.fromKeyword(classType.getFullyQualifiedName()) != null) {
            return cu;
        }

        if (onlyIfReferenced && !hasReference(cu)) {
            return cu;
        }

        if (classType.getPackageName().isEmpty()) {
            return cu;
        }

        if (cu.getImports().stream().map(JRightPadded::getElem).anyMatch(i -> {
            String ending = i.getQualid().getSimpleName();
            if (statik == null) {
                return !i.isStatic() && i.getPackageName().equals(classType.getPackageName()) &&
                        (ending.equals(classType.getClassName()) ||
                                ending.equals("*"));
            }
            return i.isStatic() && i.getTypeName().equals(classType.getFullyQualifiedName()) &&
                    (ending.equals(statik) ||
                            ending.equals("*"));
        })) {
            return cu;
        }

        J.Import importToAdd = new J.Import(randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                statik == null ? null : Space.format(" "),
                TypeTree.build(classType.getFullyQualifiedName() +
                        (statik == null ? "" : "." + statik)).withPrefix(Space.format(" ")));

        List<JRightPadded<J.Import>> imports = new ArrayList<>(cu.getImports());

        if (imports.isEmpty()) {
            importToAdd = cu.getPackageDecl() == null ?
                    importToAdd.withPrefix(cu.getClasses().get(0).getPrefix()) :
                    importToAdd.withPrefix(Space.format("\n\n"));
        }

        // Add just enough newlines to yield a blank line between imports and the first class declaration
        if (cu.getClasses().iterator().hasNext()) {
            while (true) {
                Space firstPrefix = Space.firstPrefix(cu.getClasses());
                if (firstPrefix.getWhitespace().chars().filter(c -> c == '\n').count() >= 2) {
                    break;
                }
                cu = cu.withClasses(Space.formatFirstPrefix(cu.getClasses(),
                        firstPrefix.withWhitespace("\n" + firstPrefix.getWhitespace())));
            }
        }

        imports.add(new JRightPadded<>(importToAdd, Space.EMPTY, Markers.EMPTY));
        cu = cu.withImports(imports);

        OrderImports orderImports = new OrderImports(false);
        doAfterVisit(orderImports);

        return cu;
    }

    /**
     * Returns true if there is at least one matching references for this associated import.
     * An import is considered a match if:
     * It is non-static and has a field reference
     * It is static, the static method is a wildcard, and there is at least on method invocation on the given import type.
     * It is static, the static method is explicitly defined, and there is at least on method invocation matching the type and method.
     *
     * @param compilationUnit The compilation passed to the visitCompilationUnit
     * @return true if the import is referenced by the class either explicitly or through a method reference.
     */

    //Note that using anyMatch when a stream is empty ends up returning true, which is not the behavior needed here!
    private boolean hasReference(J.CompilationUnit compilationUnit) {
        if (statik == null) {
            //Non-static imports, we just look for field accesses.
            for (NameTree t : FindType.find(compilationUnit, type)) {
                if (!(t instanceof J.FieldAccess) || !((J.FieldAccess) t).isFullyQualifiedClassReference(type)) {
                    return true;
                }
            }
            return false;
        }

        //For static imports, we are either looking for a specific method or a wildcard.
        for (J.MethodInvocation invocation : FindMethod.find(compilationUnit, type + " *(..)")) {
            if (invocation.getSelect() == null &&
                    (statik.equals("*") || invocation.getName().getSimpleName().equals(statik))) {
                return true;
            }
        }
        return false;
    }
}
