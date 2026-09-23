package com.graham.startbuild;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal reflection helpers. StartBuild calls Baritone and Flashback through reflection so it has
 * no compile-time dependency on either mod, stays buildable against Fabric API alone, and can load
 * and explain itself when one of them is missing.
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> find(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        return owner.getMethod(name, params);
    }

    static java.lang.reflect.Constructor<?> constructor(Class<?> owner, Class<?>... params) throws NoSuchMethodException {
        return owner.getConstructor(params);
    }

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        return owner.getField(name);
    }

    /** Unwraps InvocationTargetException so logs show the real cause. */
    static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
