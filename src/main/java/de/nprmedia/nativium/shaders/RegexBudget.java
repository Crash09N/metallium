/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.shaders;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-bounded regex matching over shader sources. Shader rewrites run on the render thread inside glCompileShader /
 * pipeline creation; a pattern that backtracks pathologically on some pack's source would freeze the game with no way
 * out. A matcher from here aborts with {@link Exceeded} (a RuntimeException: the compile fails, the caller falls back -
 * Voxy to its normal shader, Iris to "shader pack could not be started") once one match has run longer than the budget.
 */
public final class RegexBudget {
    /** Wall time per matched source; the slowest real pack rewrite takes milliseconds. */
    static final long BUDGET_NANOS = Long.getLong("nativium.shaders.regexBudgetMillis", 10_000L) * 1_000_000L;

    private RegexBudget() {
    }

    /** {@code pattern.matcher(source)}, aborting with {@link Exceeded} after the budget. */
    public static Matcher matcher(final Pattern pattern, final String source) {
        return matcher(pattern, source, BUDGET_NANOS);
    }

    static Matcher matcher(final Pattern pattern, final String source, final long budgetNanos) {
        return pattern.matcher(new Bounded(source, pattern, budgetNanos));
    }

    /** Thrown when a regex over a shader source exceeds the time budget. */
    public static final class Exceeded extends IllegalStateException {
        Exceeded(final Pattern pattern, final int length, final long budgetNanos) {
            super("shader source rewrite gave up after " + budgetNanos / 1_000_000 + " ms (regex /" + abbreviate(pattern.pattern())
                    + "/ over " + length + " chars)");
        }

        private static String abbreviate(final String s) {
            return s.length() <= 60 ? s : s.substring(0, 60) + "...";
        }
    }

    private static final class Bounded implements CharSequence {
        private final String source;
        private final Pattern pattern;
        private final long deadline;
        private final long budgetNanos;
        private int reads;

        Bounded(final String source, final Pattern pattern, final long budgetNanos) {
            this.budgetNanos = budgetNanos;
            this.source = source;
            this.pattern = pattern;
            this.deadline = System.nanoTime() + budgetNanos;
        }

        @Override
        public char charAt(final int index) {
            if ((++reads & 0xFFFFF) == 0 && System.nanoTime() - deadline > 0) {
                throw new Exceeded(pattern, source.length(), budgetNanos);
            }
            return source.charAt(index);
        }

        @Override
        public int length() {
            return source.length();
        }

        @Override
        public CharSequence subSequence(final int start, final int end) {
            return source.substring(start, end);
        }

        @Override
        public String toString() {
            return source;
        }
    }
}
