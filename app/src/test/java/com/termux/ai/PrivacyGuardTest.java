package com.termux.ai;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PrivacyGuardTest {

    @Test
    public void testFilter() {
        String input = "My api_key=1234567890123456 and my auth_token:abcdefghijklmnopqrst";
        String expected = "My api_key=[REDACTED] and my auth_token:[REDACTED]";
        assertEquals(expected, PrivacyGuard.filter(input));
    }
}
