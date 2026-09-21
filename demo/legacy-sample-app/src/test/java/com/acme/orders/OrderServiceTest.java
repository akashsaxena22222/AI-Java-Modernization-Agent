package com.acme.orders;

import junit.framework.TestCase;

/**
 * The only test in the project. JUnit 3: extends TestCase, methods named testXxx, no annotations,
 * no mocking framework.
 *
 * Covers the two easiest methods and none of the pricing logic.
 */
public class OrderServiceTest extends TestCase {

    private OrderServiceImpl service;

    protected void setUp() throws Exception {
        super.setUp();
        service = new OrderServiceImpl();
    }

    public void testGetOrderRejectsNullId() {
        try {
            service.getOrder(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("orderId is null", expected.getMessage());
        }
    }

    public void testSubmitOrderRejectsNullOrder() {
        try {
            service.submitOrder(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("order is null", expected.getMessage());
        }
    }

    public void testSubmitOrderRequiresCustomerId() {
        Order order = new Order();
        order.setCustomerId("   ");
        try {
            service.submitOrder(order);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("customerId is required", expected.getMessage());
        }
    }
}
