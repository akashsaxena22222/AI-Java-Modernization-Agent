<%--
  DELIBERATELY LEGACY. See ../README.md.
  Global error forward. Prints the stack trace and the server version to the browser.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" isErrorPage="true" %>
<%@ page import="java.io.PrintWriter" %>

<html>
<head><title>Error</title></head>
<body bgcolor="#FFFFFF">

<h2>An error occurred</h2>

<pre>
<%
    if (exception != null) {
        exception.printStackTrace(new PrintWriter(out));
    }
%>
</pre>

<font size="1">
    <%= application.getServerInfo() %> /
    JDK <%= System.getProperty("java.version") %> /
    <%= System.getProperty("user.name") %>@<%= System.getProperty("user.dir") %>
</font>

</body>
</html>
