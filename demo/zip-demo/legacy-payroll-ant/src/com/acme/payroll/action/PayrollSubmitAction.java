package com.acme.payroll.action;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.naming.InitialContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.apache.struts.action.ActionMessages;

import com.acme.payroll.ejb.PayrollSession;
import com.acme.payroll.ejb.PayrollSessionHome;

/**
 * Submits a payroll run.
 *
 * No CSRF token, no idempotency key, and no confirmation step: a double-submitted form
 * pays everyone twice. This has happened.
 */
public class PayrollSubmitAction extends Action {

    private static final Logger LOG = Logger.getLogger(PayrollSubmitAction.class);

    /** Not thread safe, and shared across every concurrent request. */
    private static final SimpleDateFormat PERIOD_FORMAT = new SimpleDateFormat("yyyyMM");

    public ActionForward execute(ActionMapping mapping,
                                 ActionForm form,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws Exception {

        String periodParam = request.getParameter("period");
        String approver = request.getParameter("approver");

        // Authorization by hidden form field. The role is whatever the browser sent.
        String role = request.getParameter("role");
        if (!"PAYROLL_ADMIN".equals(role)) {
            ActionMessages errors = new ActionMessages();
            errors.add(ActionMessages.GLOBAL_MESSAGE, new ActionMessage("error.notAuthorized"));
            saveErrors(request, errors);
            return mapping.findForward("failure");
        }

        Date period;
        try {
            period = PERIOD_FORMAT.parse(periodParam);
        } catch (Exception e) {
            LOG.error("Bad period " + periodParam, e);
            return mapping.findForward("failure");
        }

        InitialContext ctx = new InitialContext();
        PayrollSessionHome home = (PayrollSessionHome) javax.rmi.PortableRemoteObject
                .narrow(ctx.lookup("ejb/PayrollSession"), PayrollSessionHome.class);
        PayrollSession payroll = home.create();

        // Money as double, all the way through the calculation.
        double gross = payroll.calculateGrossForPeriod(period);
        double tax = gross * 0.2d;
        double net = gross - tax;

        // And converted to BigDecimal only at the very end, after the rounding errors.
        BigDecimal netAmount = new BigDecimal(net);

        LOG.info("Payroll submitted by " + approver + " period=" + periodParam
                + " gross=" + gross + " net=" + netAmount);

        payroll.commitPayrollRun(period, netAmount);
        payroll.remove();

        request.setAttribute("netAmount", netAmount);
        return mapping.findForward("success");
    }
}
