package com.acme.orders.web;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.acme.orders.Order;
import com.acme.orders.OrderService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * Spring 4 MVC controller returning JSP views.
 *
 * Stores the current filter in the HTTP session, which is why this application cannot be
 * horizontally scaled without sticky sessions.
 */
@Controller
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @RequestMapping(method = RequestMethod.GET)
    public ModelAndView list(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String customerId = (String) session.getAttribute("currentCustomerId");
        if (customerId == null) {
            customerId = request.getParameter("customerId");
            session.setAttribute("currentCustomerId", customerId);
        }

        List<Order> orders = orderService.findOrdersByCustomer(customerId);

        ModelAndView mav = new ModelAndView("order-list");
        mav.addObject("orders", orders);
        mav.addObject("customerId", customerId);
        return mav;
    }

    @RequestMapping(value = "/view", method = RequestMethod.GET)
    public ModelAndView view(HttpServletRequest request) {
        String idParam = request.getParameter("id");
        Order order = orderService.getOrder(Long.valueOf(idParam));
        ModelAndView mav = new ModelAndView("order-detail");
        mav.addObject("order", order);
        return mav;
    }

    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public ModelAndView cancel(HttpServletRequest request) {
        String idParam = request.getParameter("id");
        String reason = request.getParameter("reason");
        boolean cancelled = orderService.cancelOrder(Long.valueOf(idParam), reason);
        ModelAndView mav = new ModelAndView("redirect:/orders");
        mav.addObject("cancelled", Boolean.valueOf(cancelled));
        return mav;
    }
}
