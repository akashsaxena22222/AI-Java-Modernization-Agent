<%--
  DELIBERATELY LEGACY. See the README.md in this fixture.

  The error page named by web.xml. It prints the stack trace to the browser, which
  discloses class names, file paths, and library versions to anyone who can trigger
  an exception.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" isErrorPage="true" isELIgnored="true" %>
<%@ page import="java.io.PrintWriter" %>

<html>
<head><title>Application error</title></head>
<body bgcolor="#FFFFFF">

<h1>Something went wrong</h1>

<p>Please contact support quoting the details below.</p>

<pre>
<%
    if (exception != null) {
        exception.printStackTrace(new PrintWriter(out));
    } else {
        out.print("No exception available.");
    }
%>
</pre>

<p><font size="1">
    Request: <%= request.getRequestURI() %><br/>
    Server: <%= application.getServerInfo() %><br/>
    Java: <%= System.getProperty("java.version") %>
</font></p>

</body>
</html>
