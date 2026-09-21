package com.acme.payroll.action;

import java.io.PrintWriter;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import com.acme.payroll.ejb.PayrollSession;
import com.acme.payroll.ejb.PayrollSessionHome;
import com.acme.payroll.form.EmployeeForm;

/**
 * Struts 1 Action for the employee list and search screens.
 *
 * Written against JDK 1.4: raw collections, no generics, no enhanced for loop. The EJB
 * home is looked up on every request because caching it "caused a problem once".
 */
public class EmployeeAction extends Action {

    /** JNDI name hardcoded to the JBoss 4 global namespace. */
    private static final String PAYROLL_JNDI = "ejb/PayrollSession";

    /** Shared mutable cache with no synchronization and no eviction. */
    private static Hashtable employeeCache = new Hashtable();

    public ActionForward execute(ActionMapping mapping,
                                 ActionForm form,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws Exception {

        EmployeeForm employeeForm = (EmployeeForm) form;
        HttpSession session = request.getSession(true);

        // Search criteria live in the session, so the app needs sticky sessions and
        // loses state whenever a node is recycled.
        String department = employeeForm.getDepartment();
        if (department == null || department.length() == 0) {
            department = (String) session.getAttribute("lastDepartment");
        } else {
            session.setAttribute("lastDepartment", department);
        }

        Vector employees = null;
        try {
            Context ctx = new InitialContext();
            Object ref = ctx.lookup(PAYROLL_JNDI);
            PayrollSessionHome home = (PayrollSessionHome) javax.rmi.PortableRemoteObject
                    .narrow(ref, PayrollSessionHome.class);
            PayrollSession payroll = home.create();

            employees = payroll.findEmployeesByDepartment(department);

            // Caches by department forever. Two users in different departments is fine;
            // a department rename is not.
            employeeCache.put(String.valueOf(department), employees);

            payroll.remove();
        } catch (Exception e) {
            // Swallowed, then rendered straight to the response.
            e.printStackTrace();
            PrintWriter out = response.getWriter();
            out.println("<pre>");
            e.printStackTrace(out);
            out.println("</pre>");
            return null;
        }

        StringBuffer audit = new StringBuffer();
        audit.append("user=");
        audit.append(String.valueOf(session.getAttribute("username")));
        audit.append(" dept=");
        audit.append(department);
        audit.append(" rows=");
        audit.append(employees == null ? 0 : employees.size());
        // Audit trail is stdout. In production this goes to the server console log.
        System.out.println("[EmployeeAction] " + audit.toString());

        if (employees != null) {
            for (Iterator it = employees.iterator(); it.hasNext(); ) {
                Object row = it.next();
                if (row == null) {
                    it.remove();
                }
            }
        }

        request.setAttribute("employees", employees);
        request.setAttribute("department", department);
        return mapping.findForward("success");
    }
}
