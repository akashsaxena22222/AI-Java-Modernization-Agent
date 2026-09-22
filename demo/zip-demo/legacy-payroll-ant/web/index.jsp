<%--
  DELIBERATELY LEGACY. See ../README.md.
  The login screen. Posts over plain HTTP to a .do action.
--%>
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1" %>

<html>
<head><title>Acme Payroll - sign in</title></head>
<body bgcolor="#FFFFFF">

<h2>Acme Payroll</h2>

<%
    String error = request.getParameter("error");
    if (error != null) {
%>
<p><font color="#CC0000"><%= error %></font></p>
<%
    }
%>

<form method="post" action="login.do">
    <table border="0" cellpadding="3">
        <tr><td>User</td><td><input type="text" name="username" size="20"/></td></tr>
        <tr><td>Password</td><td><input type="password" name="password" size="20"/></td></tr>
        <%-- Role selected by the client, and trusted by PayrollSubmitAction. --%>
        <tr>
            <td>Role</td>
            <td>
                <select name="role">
                    <option value="PAYROLL_VIEWER">Viewer</option>
                    <option value="PAYROLL_ADMIN">Administrator</option>
                </select>
            </td>
        </tr>
        <tr><td colspan="2"><input type="submit" value="Sign in"/></td></tr>
    </table>
</form>

</body>
</html>
