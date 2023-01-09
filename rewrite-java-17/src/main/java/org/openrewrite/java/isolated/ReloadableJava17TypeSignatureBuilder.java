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
package org.openrewrite.java.isolated;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.util.List;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaTypeSignatureBuilder;
import org.openrewrite.java.tree.JavaType;

import javax.lang.model.type.NullType;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

@SuppressWarnings("UnusedReturnValue")
class ReloadableJava17TypeSignatureBuilder implements JavaTypeSignatureBuilder {
    @Nullable
    private Set<String> typeVariableNameStack;

    @Override
    public String signature(@Nullable Object t) {
        return appendSignature((Type) t, new StringBuilder()).toString();
    }

    private StringBuilder appendSignature(@Nullable Type type, StringBuilder builder) {
        if (type == null || type instanceof Type.UnknownType || type instanceof NullType) {
            return builder.append("{undefined}");
        } else if (type instanceof Type.ClassType) {
            Type.ClassType classType = (Type.ClassType) type;
            try {
                return classType.typarams_field != null && classType.typarams_field.length() > 0 ? appendParameterizedSignature(classType, builder) : appendClassSignature(classType, builder);
            } catch (Symbol.CompletionFailure ignored) {
                return classType.typarams_field != null && classType.typarams_field.length() > 0 ? appendParameterizedSignature(classType, builder) : appendClassSignature(classType, builder);
            }
        } else if (type instanceof Type.CapturedType) { // CapturedType must be evaluated before TypeVar
            return appendSignature(((Type.CapturedType) type).wildcard, builder);
        } else if (type instanceof Type.TypeVar) {
            return appendGenericSignature((Type.TypeVar) type, builder);
        } else if (type instanceof Type.JCPrimitiveType) {
            return appendPrimitiveSignature((Type.JCPrimitiveType) type, builder);
        } else if (type instanceof Type.JCVoidType) {
            return builder.append("void");
        } else if (type instanceof Type.ArrayType) {
            return appendArraySignature((Type.ArrayType) type, builder);
        } else if (type instanceof Type.WildcardType) {
            Type.WildcardType wildcard = (Type.WildcardType) type;
            builder.append("Generic{").append(wildcard.kind.toString());
            if (!type.isUnbound()) {
                appendSignature(wildcard.type, builder);
            }
            return builder.append('}');
        } else if (type instanceof Type.JCNoType) {
            return builder.append("{none}");
        }

        throw new IllegalStateException("Unexpected type " + type.getClass().getName());
    }

    private void completeClassSymbol(Symbol.ClassSymbol classSymbol) {
        try {
            classSymbol.complete();
        } catch (Symbol.CompletionFailure ignore) {
        }
    }

    @Override
    public String arraySignature(Object type) {
        return appendArraySignature((Type.ArrayType) type, new StringBuilder()).toString();
    }

    private StringBuilder appendArraySignature(Type.ArrayType type, StringBuilder builder) {
        return appendSignature(type.elemtype, builder).append("[]");
    }

    @Override
    public String classSignature(Object type) {
        return appendClassSignature((Type) type, new StringBuilder()).toString();
    }

    private StringBuilder appendClassSignature(Type type, StringBuilder builder) {
        if (type instanceof Type.JCVoidType) {
            return builder.append("void");
        } else if (type instanceof Type.JCPrimitiveType) {
            return appendPrimitiveSignature((Type.JCPrimitiveType) type, builder);
        } else if (type instanceof Type.JCNoType) {
            return builder.append("{undefined}");
        }

        Symbol.ClassSymbol sym = (Symbol.ClassSymbol) type.tsym;
        if (!sym.completer.isTerminal()) {
            completeClassSymbol(sym);
        }
        return builder.append(sym.flatName());
    }

    @Override
    public String genericSignature(Object type) {
        return appendGenericSignature((Type.TypeVar) type, new StringBuilder()).toString();
    }

    private StringBuilder appendGenericSignature(Type.TypeVar generic, StringBuilder builder) {
        String name = generic.tsym.name.toString();

        if (typeVariableNameStack == null) {
            typeVariableNameStack = new HashSet<>();
        }

        if (!typeVariableNameStack.add(name)) {
            return builder.append("Generic{").append(name).append('}');
        }

        builder.append("Generic{").append(name);

        int beforeExtends = builder.length();
        builder.append(" extends ");
        if (generic.getUpperBound() instanceof Type.IntersectionClassType) {
            Type.IntersectionClassType intersectionBound = (Type.IntersectionClassType) generic.getUpperBound();
            if (intersectionBound.supertype_field != null) {
                int index = builder.length();
                appendSignature(intersectionBound.supertype_field, builder);
                if ("java.lang.Object".equals(builder.substring(index))) {
                    builder.setLength(intersectionBound.interfaces_field.isEmpty() ? beforeExtends : index);
                } else if (!intersectionBound.interfaces_field.isEmpty()) {
                    builder.append(" & ");
                }
            }
            appendElements(intersectionBound.interfaces_field, this::appendSignature, " & ", "", "", builder);
        } else {
            int index = builder.length();
            appendSignature(generic.getUpperBound(), builder);
            if ("java.lang.Object".equals(builder.substring(index))) {
                builder.setLength(beforeExtends);
            }
        }

        typeVariableNameStack.remove(name);

        return builder.append('}');
    }

    @Override
    public String parameterizedSignature(Object type) {
        return appendParameterizedSignature((Type.ClassType) type, new StringBuilder()).toString();
    }

    private StringBuilder appendParameterizedSignature(Type.ClassType type, StringBuilder builder) {
        appendClassSignature(type, builder);
        appendElements(type.typarams_field, this::appendSignature, ", ", "<", ">", builder);
        return builder;
    }

    @Override
    public String primitiveSignature(Object type) {
        return appendPrimitiveSignature((Type.JCPrimitiveType) type, new StringBuilder()).toString();
    }

    private StringBuilder appendPrimitiveSignature(Type.JCPrimitiveType type, StringBuilder builder) {
        TypeTag tag = type.getTag();
        switch (tag) {
            case BOOLEAN:
                return builder.append(JavaType.Primitive.Boolean.getKeyword());
            case BYTE:
                return builder.append(JavaType.Primitive.Byte.getKeyword());
            case CHAR:
                return builder.append(JavaType.Primitive.Char.getKeyword());
            case DOUBLE:
                return builder.append(JavaType.Primitive.Double.getKeyword());
            case FLOAT:
                return builder.append(JavaType.Primitive.Float.getKeyword());
            case INT:
                return builder.append(JavaType.Primitive.Int.getKeyword());
            case LONG:
                return builder.append(JavaType.Primitive.Long.getKeyword());
            case SHORT:
                return builder.append(JavaType.Primitive.Short.getKeyword());
            case VOID:
                return builder.append(JavaType.Primitive.Void.getKeyword());
            case NONE:
                return builder.append(JavaType.Primitive.None.getKeyword());
            case CLASS:
                return builder.append(JavaType.Primitive.String.getKeyword());
            case BOT:
                return builder.append(JavaType.Primitive.Null.getKeyword());
            default:
                throw new IllegalArgumentException("Unknown type tag " + tag);
        }
    }

    public String methodSignature(Type selectType, Symbol.MethodSymbol symbol) {
        return appendMethodSignature(selectType, symbol, new StringBuilder()).toString();
    }

    private StringBuilder appendMethodSignature(Type selectType, Symbol.MethodSymbol symbol, StringBuilder builder) {
        int start = builder.length();
        appendClassSignature(symbol.owner.type, builder);

        if (symbol.isConstructor()) {
            int end = builder.length();
            builder.append("{name=<constructor>,return=").append(builder.substring(start, end));
        } else {
            builder.append("{name=").append(symbol.getSimpleName().toString()).append(",return=");
            appendSignature(selectType.getReturnType(), builder);
        }

        builder.append(",parameters=");
        appendMethodArgumentSignature(selectType, builder);
        return builder.append('}');
    }

    public String methodSignature(Symbol.MethodSymbol symbol) {
        return appendMethodSignature(symbol, new StringBuilder()).toString();
    }

    private StringBuilder appendMethodSignature(Symbol.MethodSymbol symbol, StringBuilder builder) {
        int start = builder.length();
        appendClassSignature(symbol.owner.type, builder);

        if (symbol.isConstructor()) {
            int end = builder.length();
            builder.append("{name=<constructor>,return=").append(builder.substring(start, end));
        } else {
            builder.append("{name=").append(symbol.getSimpleName().toString()).append(",return=");
            if (symbol.isStaticOrInstanceInit()) {
                builder.append("void");
            } else {
                appendSignature(symbol.getReturnType(), builder);
            }
        }

        builder.append(",parameters=");
        appendMethodArgumentSignature(symbol, builder);
        return builder.append('}');
    }

    private StringBuilder appendMethodArgumentSignature(Symbol.MethodSymbol sym, StringBuilder builder) {
        if (sym.isStaticOrInstanceInit()) {
            return builder.append("[]");
        }

        return appendElements(sym.getParameters(), (p, b) -> appendSignature(p.type, b), ",", "[", "]", builder);
    }

    private StringBuilder appendMethodArgumentSignature(Type selectType, StringBuilder builder) {
        if (selectType instanceof Type.MethodType) {
            Type.MethodType mt = (Type.MethodType) selectType;
            return appendElements(mt.argtypes, this::appendSignature, ",", "[", "]", builder);
        } else if (selectType instanceof Type.ForAll) {
            return appendMethodArgumentSignature(((Type.ForAll) selectType).qtype, builder);
        } else if (selectType instanceof Type.JCNoType || selectType instanceof Type.UnknownType) {
            return builder.append("{undefined}");
        }

        throw new UnsupportedOperationException("Unexpected method type " + selectType.getClass().getName());
    }

    public String variableSignature(Symbol symbol) {
        StringBuilder owner = new StringBuilder();
        if (symbol.owner instanceof Symbol.MethodSymbol) {
            appendMethodSignature((Symbol.MethodSymbol) symbol.owner, owner);
        } else {
            appendSignature(symbol.owner.type, owner);
            if (owner.indexOf("<") != -1) {
                owner.setLength(owner.indexOf("<"));
            }
        }

        owner.append("{name=").append(symbol.name.toString()).append(",type=");
        appendSignature(symbol.type, owner);
        return owner.append('}').toString();
    }

    private <T> StringBuilder appendElements(List<T> list, BiConsumer<T, StringBuilder> consumer,
                                             String delimiter, String prefix, String suffix, StringBuilder builder) {
        builder.append(prefix);
        for (int i = 0; i < list.size(); i++) {
            consumer.accept(list.get(i), builder);
            if (i < list.size() - 1) {
                builder.append(delimiter);
            }
        }
        builder.append(suffix);
        return builder;
    }
}
