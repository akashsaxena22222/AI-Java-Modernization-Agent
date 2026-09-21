package com.acme.orders;

import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Hibernate 4 DAO. Manages sessions by hand and builds HQL with string concatenation.
 */
public class OrderDao {

    @Autowired
    private SessionFactory sessionFactory;

    public Order findById(Long orderId) {
        Session session = sessionFactory.getCurrentSession();
        return (Order) session.get(Order.class, orderId);
    }

    public List<Order> findByCustomer(String customerId) {
        Session session = sessionFactory.getCurrentSession();
        // String-concatenated HQL. Works because customerId happens to be validated upstream,
        // which is not a guarantee anyone should rely on.
        Query query = session.createQuery(
                "from Order where customerId = '" + customerId + "' order by createdDate desc");
        return query.list();
    }

    public List<Order> findPendingApproval() {
        Session session = sessionFactory.getCurrentSession();
        Query query = session.createQuery(
                "from Order where requiresApproval = true and status = 'SUBMITTED'");
        query.setMaxResults(500);
        return query.list();
    }

    public Long save(Order order) {
        Session session = sessionFactory.getCurrentSession();
        if (order.getId() == null) {
            return (Long) session.save(order);
        }
        session.update(order);
        return order.getId();
    }
}
