/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.shaders;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RegexBudgetTest {
    @Test
    void runawayMatchAbortsInsteadOfHanging() {
        // The pre-fix GlslProgramTranslator.GLOBAL_IO: cubic on long blank-line runs.
        Pattern catastrophic = Pattern.compile("^(\\s*)((?:flat|smooth|\\s)*)\\b(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;", Pattern.MULTILINE);
        String source = "\n".repeat(30_000) + "uniform float x;\n";
        RegexBudget.Exceeded e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(RegexBudget.Exceeded.class, () -> RegexBudget.matcher(catastrophic, source, 200_000_000L).find()));
        assertTrue(e.getMessage().contains("gave up after 200 ms"), e.getMessage());
    }

    @Test
    void normalMatchingIsUnchanged() {
        var m = RegexBudget.matcher(Pattern.compile("out (\\w+);"), "in a;\nout b;\nout c;");
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "out x_" + m.group(1) + ";");
        }
        m.appendTail(sb);
        assertEquals("in a;\nout x_b;\nout x_c;", sb.toString());
    }
}
