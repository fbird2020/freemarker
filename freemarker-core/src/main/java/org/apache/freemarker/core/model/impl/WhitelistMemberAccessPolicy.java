/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.freemarker.core.model.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.freemarker.core.model.ObjectWrapper;
import org.apache.freemarker.core.util._ClassUtils;
import org.apache.freemarker.core.util._NullArgumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whitelist-based member access policy, that is, only members that you have explicitly whitelisted will be accessible.
 * The whitelist content is application specific, and can be significant work to put together, but it's the only way
 * you can achieve any practical safety if you don't fully trust the users who can edit templates. Of course, this only
 * can deal with the {@link ObjectWrapper} aspect of safety; please check the Manual to see what else is needed. Also,
 * since this is related to security, read the documentation of {@link MemberAccessPolicy}, to know about the
 * pitfalls and edge cases related to {@link MemberAccessPolicy}-es in general.
 *
 * <p>There are two ways you can add members to the whitelist:
 * <ul>
 *     <li>Via a list of member selectors passed to the constructor
 *     <li>Via {@link TemplateAccessible} annotation
 * </ul>
 *
 * <p>When a member is whitelisted, it's identified by the following data (with the example of
 * {@code com.example.MyClass.myMethod(int, int)} being whitelisted):
 * <ul>
 *    <li>Upper bound class ({@code com.example.MyClass} in the example)
 *    <li>Member name ({@code myMethod} in the example), except for constructors where it's unused
 *    <li>Parameter types ({@code int, int} in the example), except for fields where it's unused
 * </ul>
 *
 * <p>Once you have whitelisted a member in the upper bound class, it will be automatically whitelisted in all
 * subclasses of that, even if the whitelisted member is a field or constructor (which doesn't support overriding, but
 * it will be treated as such if the field name or constructor parameter types match).
 * It's called "upper bound" class, because the member will only be whitelisted in classes that are {@code instanceof}
 * the upper bound class. That restriction stands even if the member was inherited from another class or
 * interface, and it wasn't even overridden in the upper bound class; the member won't be whitelisted in the
 * class/interface where it was inherited from, if that type is more generic than the upper bound class.
 *
 * <p>Note that the return type of methods aren't used in any way. So if you whitelist {@code myMethod(int, int)}, and
 * it has multiple variants with different return types (which is possible on the bytecode level), then you have
 * whitelisted all variants of it.
 */
public class WhitelistMemberAccessPolicy implements MemberAccessPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(WhitelistMemberAccessPolicy.class);

    private final MethodMatcher methodMatcher;
    private final ConstructorMatcher constructorMatcher;
    private final FieldMatcher fieldMatcher;

    /**
     * A condition that matches some type members. See {@link WhitelistMemberAccessPolicy} documentation for more.
     * Exactly one of these will be non-{@code null}:
     * {@link #getMethod()}, {@link #getConstructor()}, {@link #getField()}, {@link #getException()}.
     *
     * @since 2.3.30
     */
    public final static class MemberSelector {
        private final Class<?> upperBoundType;
        private final Method method;
        private final Constructor<?> constructor;
        private final Field field;
        private final Exception exception;
        private final String exceptionMemberSelectorString;

        /**
         * Use if you want to match methods similar to the specified one, in types that are {@code instanceof} of
         * the specified upper bound type. When methods are matched, only the name and the parameter types matter.
         */
        public MemberSelector(Class<?> upperBoundType, Method method) {
            _NullArgumentException.check("upperBoundType", upperBoundType);
            _NullArgumentException.check("method", method);
            this.upperBoundType = upperBoundType;
            this.method = method;
            this.constructor = null;
            this.field = null;
            this.exception = null;
            this.exceptionMemberSelectorString = null;
        }

        /**
         * Use if you want to match constructors similar to the specified one, in types that are {@code instanceof} of
         * the specified upper bound type. When constructors are matched, only the parameter types matter.
         */
        public MemberSelector(Class<?> upperBoundType, Constructor<?> constructor) {
            _NullArgumentException.check("upperBoundType", upperBoundType);
            _NullArgumentException.check("constructor", constructor);
            this.upperBoundType = upperBoundType;
            this.method = null;
            this.constructor = constructor;
            this.field = null;
            this.exception = null;
            this.exceptionMemberSelectorString = null;
        }

        /**
         * Use if you want to match fields similar to the specified one, in types that are {@code instanceof} of
         * the specified upper bound type. When fields are matched, only the name matters.
         */
        public MemberSelector(Class<?> upperBoundType, Field field) {
            _NullArgumentException.check("upperBoundType", upperBoundType);
            _NullArgumentException.check("field", field);
            this.upperBoundType = upperBoundType;
            this.method = null;
            this.constructor = null;
            this.field = field;
            this.exception = null;
            this.exceptionMemberSelectorString = null;
        }

        /**
         * Used to store the result of a parsing that's failed for a reason that we can skip on runtime (typically,
         * when a missing class or member was referred).
         *
         * @param upperBoundType {@code null} if resolving the upper bound type itself failed.
         * @param exception Not {@code null}
         * @param exceptionMemberSelectorString Not {@code null}; the selector whose resolution has failed, used in
         *      the log message.
         */
        public MemberSelector(Class<?> upperBoundType, Exception exception, String exceptionMemberSelectorString) {
            _NullArgumentException.check("exception", exception);
            _NullArgumentException.check("exceptionMemberSelectorString", exceptionMemberSelectorString);
            this.upperBoundType = upperBoundType;
            this.method = null;
            this.constructor = null;
            this.field = null;
            this.exception = exception;
            this.exceptionMemberSelectorString = exceptionMemberSelectorString;
        }

        /**
         * Maybe {@code null} if {@link #getException()} is non-{@code null}.
         */
        public Class<?> getUpperBoundType() {
            return upperBoundType;
        }

        /**
         * Maybe {@code null};
         * set if the selector matches methods similar to the returned one, and there was no exception.
         */
        public Method getMethod() {
            return method;
        }

        /**
         * Maybe {@code null};
         * set if the selector matches constructors similar to the returned one, and there was no exception.
         */
        public Constructor<?> getConstructor() {
            return constructor;
        }

        /**
         * Maybe {@code null};
         * set if the selector matches fields similar to the returned one, and there was no exception.
         */
        public Field getField() {
            return field;
        }

        /**
         * Maybe {@code null}
         */
        public Exception getException() {
            return exception;
        }

        /**
         * Maybe {@code null}
         */
        public String getExceptionMemberSelectorString() {
            return exceptionMemberSelectorString;
        }

        /**
         * Parses a member selector that was specified with a string.
         *
         * @param classLoader
         *      Used to resolve class names in the member selectors. Generally you want to pick a class that belongs to
         *      you application (not to a 3rd party library, like FreeMarker), and then call
         *      {@link Class#getClassLoader()} on that. Note that the resolution of the classes is not lazy, and so the
         *      {@link ClassLoader} won't be stored after this method returns.
         * @param memberSelectorString
         *      Describes the member (method, constructor, field) which you want to whitelist. Starts with the full
         *      qualified name of the member, like {@code com.example.MyClass.myMember}. Unless it's a field, the
         *      name is followed by comma separated list of the parameter types inside parentheses, like in
         *      {@code com.example.MyClass.myMember(java.lang.String, boolean)}. The parameter type names must be
         *      also full qualified names, except primitive type names. Array types must be indicated with one or
         *      more {@code []}-s after the type name. Varargs arguments shouldn't be marked with {@code ...}, but with
         *      {@code []}. In the member name, like {@code com.example.MyClass.myMember}, the class refers to the so
         *      called "upper bound class". Regarding that and inheritance rules see the class level documentation.
         *
         * @return The {@link MemberSelector}, which might has non-{@code null} {@link MemberSelector#exception}.
         */
        public static MemberSelector parse(String memberSelectorString, ClassLoader classLoader) {
            if (memberSelectorString.contains("<") || memberSelectorString.contains(">")
                    || memberSelectorString.contains("...") || memberSelectorString.contains(";")) {
                throw new IllegalArgumentException(
                        "Malformed whitelist entry (shouldn't contain \"<\", \">\", \"...\", or \";\"): "
                                + memberSelectorString);
            }
            String cleanedStr = memberSelectorString.trim().replaceAll("\\s*([\\.,\\(\\)\\[\\]])\\s*", "$1");

            int postMemberNameIdx;
            boolean hasArgList;
            {
                int openParenIdx = cleanedStr.indexOf('(');
                hasArgList = openParenIdx != -1;
                postMemberNameIdx = hasArgList ? openParenIdx : cleanedStr.length();
            }

            final int postClassDotIdx = cleanedStr.lastIndexOf('.', postMemberNameIdx);
            if (postClassDotIdx == -1) {
                throw new IllegalArgumentException("Malformed whitelist entry (missing dot): " + memberSelectorString);
            }

            Class<?> upperBoundClass;
            String upperBoundClassStr = cleanedStr.substring(0, postClassDotIdx);
            if (!isWellFormedClassName(upperBoundClassStr)) {
                throw new IllegalArgumentException("Malformed whitelist entry (malformed upper bound class name): "
                        + memberSelectorString);
            }
            try {
                upperBoundClass = classLoader.loadClass(upperBoundClassStr);
            } catch (ClassNotFoundException e) {
                return new MemberSelector(null, e, cleanedStr);
            }

            String memberName = cleanedStr.substring(postClassDotIdx + 1, postMemberNameIdx);
            if (!isWellFormedJavaIdentifier(memberName)) {
                throw new IllegalArgumentException(
                        "Malformed whitelist entry (malformed member name): " + memberSelectorString);
            }

            if (hasArgList) {
                if (cleanedStr.charAt(cleanedStr.length() - 1) != ')') {
                    throw new IllegalArgumentException("Malformed whitelist entry (missing closing ')'): "
                            + memberSelectorString);
                }
                String argsSpec = cleanedStr.substring(postMemberNameIdx + 1, cleanedStr.length() - 1);
                StringTokenizer tok = new StringTokenizer(argsSpec, ",");
                int argCount = tok.countTokens();
                Class<?>[] argTypes = new Class[argCount];
                for (int i = 0; i < argCount; i++) {
                    String argClassName = tok.nextToken();
                    int arrayDimensions = 0;
                    while (argClassName.endsWith("[]")) {
                        arrayDimensions++;
                        argClassName = argClassName.substring(0, argClassName.length() - 2);
                    }
                    Class<?> argClass;
                    Class<?> primArgClass = _ClassUtils.resolveIfPrimitiveTypeName(argClassName);
                    if (primArgClass != null) {
                        argClass = primArgClass;
                    } else {
                        if (!isWellFormedClassName(argClassName)) {
                            throw new IllegalArgumentException(
                                    "Malformed whitelist entry (malformed argument class name): " + memberSelectorString);
                        }
                        try {
                            argClass = classLoader.loadClass(argClassName);
                        } catch (ClassNotFoundException | SecurityException e) {
                            return new MemberSelector(upperBoundClass, e, cleanedStr);
                        }
                    }
                    argTypes[i] = _ClassUtils.getArrayClass(argClass, arrayDimensions);
                }
                try {
                    return memberName.equals(upperBoundClass.getSimpleName())
                            ? new MemberSelector(upperBoundClass, upperBoundClass.getConstructor(argTypes))
                            : new MemberSelector(upperBoundClass, upperBoundClass.getMethod(memberName, argTypes));
                } catch (NoSuchMethodException | SecurityException e) {
                    return new MemberSelector(upperBoundClass, e, cleanedStr);
                }
            } else {
                try {
                    return new MemberSelector(upperBoundClass, upperBoundClass.getField(memberName));
                } catch (NoSuchFieldException | SecurityException e) {
                    return new MemberSelector(upperBoundClass, e, cleanedStr);
                }
            }
        }

        /**
         * Convenience method to parse all member selectors in the collection; see {@link #parse(String, ClassLoader)}.
         */
        public static List<MemberSelector> parse(Collection<String> memberSelectors,
                ClassLoader classLoader) {
            List<MemberSelector> parsedMemberSelectors = new ArrayList<>(memberSelectors.size());
            for (String memberSelector : memberSelectors) {
                parsedMemberSelectors.add(parse(memberSelector, classLoader));
            }
            return parsedMemberSelectors;
        }
    }

    public WhitelistMemberAccessPolicy(Collection<MemberSelector> memberSelectors) {
        methodMatcher = new MethodMatcher();
        constructorMatcher = new ConstructorMatcher();
        fieldMatcher = new FieldMatcher();
        for (MemberSelector memberSelector : memberSelectors) {
            Class<?> upperBoundClass = memberSelector.upperBoundType;
            if (memberSelector.exception != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Member selector ignored due to error: " + memberSelector.getExceptionMemberSelectorString(),
                            memberSelector.exception);
                }
            } else if (memberSelector.constructor != null) {
                constructorMatcher.addMatching(upperBoundClass, memberSelector.constructor);
            } else if (memberSelector.method != null) {
                methodMatcher.addMatching(upperBoundClass, memberSelector.method);
            } else if (memberSelector.field != null) {
                fieldMatcher.addMatching(upperBoundClass, memberSelector.field);
            } else {
                throw new AssertionError();
            }
        }
    }

    @Override
    public ClassMemberAccessPolicy forClass(final Class<?> contextClass) {
        return new ClassMemberAccessPolicy() {
            @Override
            public boolean isMethodExposed(Method method) {
                return methodMatcher.matches(contextClass, method)
                        || _MethodUtils.getInheritableAnnotation(contextClass, method, TemplateAccessible.class) != null;
            }

            @Override
            public boolean isConstructorExposed(Constructor<?> constructor) {
                return constructorMatcher.matches(contextClass, constructor)
                        || _MethodUtils.getInheritableAnnotation(contextClass, constructor, TemplateAccessible.class)
                        != null;
            }

            @Override
            public boolean isFieldExposed(Field field) {
                return fieldMatcher.matches(contextClass, field)
                        || _MethodUtils.getInheritableAnnotation(contextClass, field, TemplateAccessible.class) != null;
            }
        };
    }

    private static boolean isWellFormedClassName(String s) {
        if (s.length() == 0) {
            return false;
        }
        int identifierStartIdx = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == identifierStartIdx) {
                if (!Character.isJavaIdentifierStart(c)) {
                    return false;
                }
            } else if (c == '.' && i != s.length() - 1) {
                identifierStartIdx = i + 1;
            } else {
                if (!Character.isJavaIdentifierPart(c)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isWellFormedJavaIdentifier(String s) {
        if (s.length() == 0) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

}