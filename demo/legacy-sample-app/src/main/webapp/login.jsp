<%--
  DELIBERATELY LEGACY. See the README.md in this fixture.
  The login page named by spring-security.xml. Posts credentials over plain HTTP.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" isELIgnored="true" %>

<html>
<head><title>Acme Order Service - sign in</title></head>
<body bgcolor="#FFFFFF">

<img src="<%= request.getContextPath() %>/images/acme-logo.png" alt="Acme"/>

<h1>Sign in</h1>

<%
    // The failure reason is echoed back from the query string, unescaped.
    String error = request.getParameter("error");
    if (error != null) {
%>
<p><font color="#CC0000">Sign in failed (<%= error %>). Please try again.</font></p>
<%
    }
%>

<form method="post" action="<%= request.getContextPath() %>/j_spring_security_check">
    <table border="0" cellpadding="3">
        <tr><td>Username</td><td><input type="text" name="j_username" size="24"/></td></tr>
        <tr><td>Password</td><td><input type="password" name="j_password" size="24"/></td></tr>
        <tr>
            <td colspan="2">
                <input type="checkbox" name="_spring_security_remember_me"/> Remember me for 30 days
            </td>
        </tr>
        <tr><td colspan="2"><input type="submit" value="Sign in"/></td></tr>
    </table>
</form>

</body>
</html>
