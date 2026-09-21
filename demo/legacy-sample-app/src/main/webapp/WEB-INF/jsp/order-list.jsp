<%--
  DELIBERATELY LEGACY. See the README.md in this fixture.

  Business logic in scriptlets, unescaped output, and a hand-rolled table. This is
  the view layer a modernization effort has to replace, and the reason "just add a
  REST API" is not a small change.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" isELIgnored="true" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Iterator" %>
<%@ page import="java.text.SimpleDateFormat" %>
<%@ page import="com.acme.orders.Order" %>
<%@ page import="com.acme.orders.util.DateUtil" %>

<%
    // Pricing rules live in the view. They also live in OrderServiceImpl, and the two
    // have drifted apart.
    List orders = (List) request.getAttribute("orders");
    String customerId = (String) request.getAttribute("customerId");
    double grandTotal = 0.0d;
    int cancelledCount = 0;
    if (orders != null) {
        for (Iterator it = orders.iterator(); it.hasNext(); ) {
            Order o = (Order) it.next();
            if ("CANCELLED".equals(o.getStatus())) {
                cancelledCount++;
                continue;
            }
            double lineTotal = o.getTotalAmount();
            // Volume discount, duplicated from the service layer.
            if (lineTotal > 1000.0d) {
                lineTotal = lineTotal * 0.95d;
            }
            grandTotal = grandTotal + lineTotal;
        }
    }
%>

<html>
<head>
    <title>Orders for <%= customerId %></title>
    <link rel="stylesheet" type="text/css" href="<%= request.getContextPath() %>/css/legacy.css"/>
</head>
<body bgcolor="#FFFFFF">

<img src="<%= request.getContextPath() %>/images/acme-logo.png" alt="Acme"/>

<!-- customerId comes straight from a request parameter and is written unescaped. -->
<h1>Orders for customer <%= customerId %></h1>

<%
    if (orders == null || orders.isEmpty()) {
%>
<p>No orders found.</p>
<%
    } else {
%>

<table border="1" cellpadding="4" cellspacing="0">
    <tr bgcolor="#CCCCCC">
        <th>Id</th>
        <th>Date</th>
        <th>Status</th>
        <th>Total</th>
        <th>Actions</th>
    </tr>
    <%
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy");
        for (Iterator it = orders.iterator(); it.hasNext(); ) {
            Order o = (Order) it.next();
    %>
    <tr>
        <td><%= o.getId() %></td>
        <td><%= DateUtil.format(o.getOrderDate()) %></td>
        <td><%= o.getStatus() %></td>
        <td align="right"><%= o.getTotalAmount() %></td>
        <td>
            <a href="<%= request.getContextPath() %>/orders/view?id=<%= o.getId() %>">view</a>
            <%
                if (!"CANCELLED".equals(o.getStatus()) && !"SHIPPED".equals(o.getStatus())) {
            %>
            <form method="post" action="<%= request.getContextPath() %>/orders/cancel">
                <input type="hidden" name="id" value="<%= o.getId() %>"/>
                <input type="text" name="reason" size="20"/>
                <input type="submit" value="cancel"/>
            </form>
            <%
                }
            %>
        </td>
    </tr>
    <%
        }
    %>
    <tr bgcolor="#EEEEEE">
        <td colspan="3"><b>Total (<%= cancelledCount %> cancelled excluded)</b></td>
        <td align="right"><b><%= grandTotal %></b></td>
        <td></td>
    </tr>
</table>

<%
    }
%>

<hr/>
<p><font size="1">Acme Order Service 1.4.2 &mdash; session <%= session.getId() %></font></p>

</body>
</html>
