<%--
  DELIBERATELY LEGACY. See ../README.md.
  Struts 1 taglibs mixed with scriptlets, and raw Vector iteration.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" %>
<%@ page import="java.util.Vector" %>
<%@ page import="com.acme.payroll.util.PayrollUtils" %>
<%@ taglib uri="/WEB-INF/struts-html.tld" prefix="html" %>
<%@ taglib uri="/WEB-INF/struts-bean.tld" prefix="bean" %>

<%
    Vector employees = (Vector) request.getAttribute("employees");
    String department = (String) request.getAttribute("department");

    // Totals computed in the view, again, with a different rounding rule from the EJB.
    double payrollTotal = 0.0d;
    if (employees != null) {
        for (int i = 0; i < employees.size(); i++) {
            Object[] row = (Object[]) employees.get(i);
            payrollTotal = payrollTotal + ((Double) row[3]).doubleValue();
        }
    }
%>

<html>
<head>
    <title>Employees - <%= department %></title>
</head>
<body bgcolor="#FFFFFF">

<table width="100%" border="0">
    <tr bgcolor="#003366">
        <td><font color="#FFFFFF" size="4"><b>Acme Payroll</b></font></td>
        <td align="right">
            <font color="#FFFFFF" size="1">
                <!-- Username written unescaped, straight out of the session. -->
                <%= session.getAttribute("username") %>
            </font>
        </td>
    </tr>
</table>

<h2>Employees in <%= department %></h2>

<html:form action="/employee">
    Department:
    <html:text property="department" size="20"/>
    <html:submit value="Search"/>
</html:form>

<%
    if (employees == null || employees.isEmpty()) {
%>
<p><i>No employees found.</i></p>
<%
    } else {
%>
<table border="1" cellpadding="3" cellspacing="0">
    <tr bgcolor="#DDDDDD">
        <th>Id</th><th>First</th><th>Last</th><th>Annual salary</th><th></th>
    </tr>
    <%
        for (int i = 0; i < employees.size(); i++) {
            Object[] row = (Object[]) employees.get(i);
    %>
    <tr>
        <td><%= row[0] %></td>
        <td><%= row[1] %></td>
        <td><%= row[2] %></td>
        <td align="right"><%= PayrollUtils.round(((Double) row[3]).doubleValue()) %></td>
        <td>
            <%-- Employee id in a GET link with no authorization check on the target. --%>
            <a href="employee.do?employeeId=<%= row[0] %>&amp;action=edit">edit</a>
        </td>
    </tr>
    <%
        }
    %>
    <tr bgcolor="#EEEEEE">
        <td colspan="3"><b>Total</b></td>
        <td align="right"><b><%= payrollTotal %></b></td>
        <td></td>
    </tr>
</table>
<%
    }
%>

<hr/>
<font size="1">Acme Payroll 2.7.1 &mdash; built 14/03/2014</font>

</body>
</html>
