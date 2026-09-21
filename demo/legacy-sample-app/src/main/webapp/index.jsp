<%--
  DELIBERATELY LEGACY. See the README.md in this fixture.
  The welcome file named by web.xml. Redirects with a scriptlet.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" isELIgnored="true" %>
<%
    response.sendRedirect(request.getContextPath() + "/orders");
%>
