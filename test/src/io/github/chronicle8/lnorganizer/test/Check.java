package io.github.chronicle8.lnorganizer.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Minimal assertions and a runner; the JDK alone runs the suite (Rule 9). */
final class Check {
    private Check() {}

    static final class Failure extends RuntimeException {
        Failure(String message) { super(message); }
    }

    interface Body {
        void run() throws Exception;
    }

    static int passed;
    static final List<String> failed = new ArrayList<>();
    static final List<String> skipped = new ArrayList<>();

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failed.add(name);
            System.out.println("  FAIL  " + name + ": " + t);
            if (!(t instanceof Failure)) t.printStackTrace(System.out);
        }
    }

    static void skip(String name, String why) {
        skipped.add(name + " (" + why + ")");
        System.out.println("  SKIP  " + name + ": " + why);
    }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new Failure(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void bytesEq(byte[] expected, byte[] actual, String what) {
        if (!Arrays.equals(expected, actual)) {
            throw new Failure(what + ": byte arrays differ (" + expected.length + " vs " + actual.length + " bytes)");
        }
    }

    static void yes(boolean cond, String what) {
        if (!cond) throw new Failure(what);
    }

    interface Thrower {
        void run() throws Exception;
    }

    /** Asserts that body throws an exception of type t whose message contains fragment. */
    static void throwsWith(Class<? extends Throwable> t, String fragment, Thrower body) {
        try {
            body.run();
        } catch (Throwable e) {
            if (!t.isInstance(e)) throw new Failure("expected " + t.getSimpleName() + " but got " + e);
            if (fragment != null && (e.getMessage() == null || !e.getMessage().contains(fragment))) {
                throw new Failure("message <" + e.getMessage() + "> lacks <" + fragment + ">");
            }
            return;
        }
        throw new Failure("expected " + t.getSimpleName() + " containing <" + fragment + ">, nothing was thrown");
    }
}
