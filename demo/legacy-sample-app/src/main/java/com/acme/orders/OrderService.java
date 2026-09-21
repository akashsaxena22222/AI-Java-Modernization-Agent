package com.acme.orders;

import java.util.List;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;

/**
 * SOAP contract for order management.
 *
 * Exposed over JAX-WS since 2011. Consumers are three internal systems and one
 * partner integration, all of which would prefer REST.
 */
@WebService(targetNamespace = "http://orders.acme.com/", name = "OrderServicePort")
public interface OrderService {

    @WebMethod(operationName = "getOrder")
    Order getOrder(@WebParam(name = "orderId") Long orderId);

    @WebMethod(operationName = "findOrdersByCustomer")
    List<Order> findOrdersByCustomer(@WebParam(name = "customerId") String customerId);

    @WebMethod(operationName = "submitOrder")
    Long submitOrder(@WebParam(name = "order") Order order);

    @WebMethod(operationName = "cancelOrder")
    boolean cancelOrder(@WebParam(name = "orderId") Long orderId,
                        @WebParam(name = "reason") String reason);
}
