<%--
  DELIBERATELY LEGACY. See the README.md in this fixture.
  The view rendered by OrderController.view().
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" isELIgnored="true" %>
<%@ page import="com.acme.orders.Order" %>
<%@ page import="com.acme.orders.util.DateUtil" %>

<%
    Order order = (Order) request.getAttribute("order");
%>

<html>
<head><title>Order <%= order == null ? "?" : String.valueOf(order.getId()) %></title></head>
<body bgcolor="#FFFFFF">

<%
    if (order == null) {
%>
<p>Order not found.</p>
<%
    } else {
%>
<h1>Order <%= order.getId() %></h1>
<table border="0" cellpadding="3">
    <tr><td>Customer</td><td><%= order.getCustomerId() %></td></tr>
    <tr><td>Date</td><td><%= DateUtil.format(order.getOrderDate()) %></td></tr>
    <tr><td>Status</td><td><%= order.getStatus() %></td></tr>
    <tr><td>Total</td><td><%= order.getTotalAmount() %></td></tr>
</table>
<%
    }
%>

<p><a href="<%= request.getContextPath() %>/orders">back to list</a></p>

</body>
</html>
