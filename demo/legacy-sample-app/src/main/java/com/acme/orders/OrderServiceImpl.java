package com.acme.orders;

import java.util.ArrayList;
import java.util.List;

import javax.jws.WebService;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@WebService(endpointInterface = "com.acme.orders.OrderService",
            targetNamespace = "http://orders.acme.com/")
public class OrderServiceImpl implements OrderService {

    private static final Logger LOG = Logger.getLogger(OrderServiceImpl.class);

    @Autowired
    private OrderDao orderDao;

    public Order getOrder(Long orderId) {
        LOG.info("getOrder " + orderId);
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is null");
        }
        return orderDao.findById(orderId);
    }

    public List<Order> findOrdersByCustomer(String customerId) {
        LOG.info("findOrdersByCustomer " + customerId);
        List<Order> results = orderDao.findByCustomer(customerId);
        if (results == null) {
            return new ArrayList<Order>();
        }
        return results;
    }

    @Transactional
    public Long submitOrder(Order order) {
        // Validation, pricing, tax, discount and fulfilment routing all inline.
        // This method has grown for eleven years and nobody wants to touch it.
        if (order == null) {
            throw new IllegalArgumentException("order is null");
        }
        if (order.getCustomerId() == null || order.getCustomerId().trim().length() == 0) {
            throw new IllegalArgumentException("customerId is required");
        }

        double total = 0.0;
        if (order.getQuantity() > 0 && order.getUnitPrice() != null) {
            total = order.getQuantity() * order.getUnitPrice().doubleValue();
            if (order.getQuantity() > 100) {
                total = total * 0.9;
            } else if (order.getQuantity() > 50) {
                total = total * 0.95;
            } else if (order.getQuantity() > 20) {
                total = total * 0.98;
            }
            if ("DE".equals(order.getCountryCode())) {
                total = total * 1.19;
            } else if ("FR".equals(order.getCountryCode())) {
                total = total * 1.20;
            } else if ("GB".equals(order.getCountryCode())) {
                total = total * 1.20;
            } else if ("US".equals(order.getCountryCode())) {
                total = total * 1.0;
            }
            if (total > 10000.0) {
                order.setRequiresApproval(true);
            }
        }
        order.setTotal(new java.math.BigDecimal(total));
        order.setStatus("SUBMITTED");
        order.setCreatedDate(DateUtilHolder.now());

        Long id = orderDao.save(order);
        LOG.info("submitted order " + id + " total " + total);
        return id;
    }

    @Transactional
    public boolean cancelOrder(Long orderId, String reason) {
        Order order = orderDao.findById(orderId);
        if (order == null) {
            return false;
        }
        if ("SHIPPED".equals(order.getStatus())) {
            return false;
        }
        order.setStatus("CANCELLED");
        order.setCancellationReason(reason);
        orderDao.save(order);
        return true;
    }

    /** Indirection that exists only because the original author feared static imports. */
    private static final class DateUtilHolder {
        static java.util.Date now() {
            return new java.util.Date();
        }
    }
}
