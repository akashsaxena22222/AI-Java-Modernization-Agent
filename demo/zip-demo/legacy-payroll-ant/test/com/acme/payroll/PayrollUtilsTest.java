package com.acme.payroll;

import junit.framework.TestCase;

import com.acme.payroll.util.PayrollUtils;

/**
 * The only test in the project, and it is not in the default build target.
 *
 * JUnit 3.8.1: extends TestCase, methods named testXxx, no annotations. Tests the two
 * string helpers and none of the payroll calculation.
 */
public class PayrollUtilsTest extends TestCase {

    public void testSplitReturnsTokens() {
        assertEquals(3, PayrollUtils.split("a,b,c", ",").size());
    }

    public void testSplitHandlesNull() {
        assertEquals(0, PayrollUtils.split(null, ",").size());
    }

    public void testJoinRoundTrips() {
        assertEquals("a|b", PayrollUtils.join(PayrollUtils.split("a|b", "|"), "|"));
    }

    public void testFormatDateHandlesNull() {
        assertEquals("", PayrollUtils.formatDate(null));
    }

    /**
     * Asserts the rounding helper is correct for one value that happens to work.
     * 0.615 rounds the other way, which is why the March 2013 payroll was out by
     * eleven pence per employee.
     */
    public void testRound() {
        assertEquals(1.23d, PayrollUtils.round(1.234d), 0.001d);
    }
}
