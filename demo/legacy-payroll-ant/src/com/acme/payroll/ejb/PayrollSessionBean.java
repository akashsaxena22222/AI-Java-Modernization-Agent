package com.acme.payroll.ejb;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Date;
import java.util.Vector;

import javax.ejb.CreateException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import org.apache.log4j.Logger;

import com.acme.payroll.dao.EmployeeDao;

/**
 * EJB 2.x stateless session bean implementation.
 *
 * Does not implement PayrollSession: the container wires the two together at deploy
 * time by name, so a method that exists here but not in the interface simply never
 * gets called, and nothing warns anybody.
 */
public class PayrollSessionBean implements SessionBean {

    private static final Logger LOG = Logger.getLogger(PayrollSessionBean.class);

    private SessionContext context;

    /** Looked up once and held for the life of the bean instance. */
    private DataSource dataSource;

    public void ejbCreate() throws CreateException {
        try {
            InitialContext ctx = new InitialContext();
            this.dataSource = (DataSource) ctx.lookup("java:/PayrollDS");
        } catch (Exception e) {
            // CreateException loses the cause: JDK 1.4 chaining was never applied here.
            throw new CreateException("Could not look up java:/PayrollDS: " + e.getMessage());
        }
    }

    public void setSessionContext(SessionContext ctx) {
        this.context = ctx;
    }

    public void ejbRemove() {
    }

    public void ejbActivate() {
    }

    public void ejbPassivate() {
    }

    // --- Business methods -----------------------------------------------------------------

    public Vector findEmployeesByDepartment(String department) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            return new EmployeeDao(connection).findByDepartment(department);
        } catch (Exception e) {
            LOG.error("findEmployeesByDepartment failed", e);
            // Returns empty on failure, so the screen shows "no employees" rather than an error.
            return new Vector();
        } finally {
            // No try/finally around close, and no null check on the result of getConnection.
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
                // Connection leak if this throws, which under load it does.
            }
        }
    }

    public double calculateGrossForPeriod(Date period) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Vector employees = new EmployeeDao(connection).findAllActive();

            double total = 0.0d;
            for (int i = 0; i < employees.size(); i++) {
                Object[] row = (Object[]) employees.get(i);
                double salary = ((Number) row[3]).doubleValue();
                // Monthly gross. Floating point accumulation across 4000 employees.
                total = total + (salary / 12.0d);
            }
            return total;
        } catch (Exception e) {
            LOG.error("calculateGrossForPeriod failed", e);
            // Silently returns zero. A failed payroll calculation looks like a zero payroll.
            return 0.0d;
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void commitPayrollRun(Date period, BigDecimal netAmount) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            // No transaction demarcation in code; relies entirely on the deployment
            // descriptor's Required attribute being correct, which for this bean it is not.
            new EmployeeDao(connection).insertPayrollRun(period, netAmount);
        } catch (Exception e) {
            LOG.error("commitPayrollRun failed", e);
            // Marks the transaction for rollback only if the context happens to be set.
            if (context != null) {
                context.setRollbackOnly();
            }
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public String getPayslipSummary(long employeeId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            return new EmployeeDao(connection).payslipSummary(employeeId);
        } catch (Exception e) {
            LOG.error("getPayslipSummary failed", e);
            return "";
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
