package com.acme.payroll.ejb;

import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.util.Date;
import java.util.Vector;

import javax.ejb.EJBObject;

/**
 * EJB 2.x remote business interface.
 *
 * Every method declares RemoteException, so every caller has to handle a network
 * failure even when the bean is co-located in the same JVM. Collections are returned
 * as raw Vectors because the interface has to be serializable across RMI.
 */
public interface PayrollSession extends EJBObject {

    Vector findEmployeesByDepartment(String department) throws RemoteException;

    double calculateGrossForPeriod(Date period) throws RemoteException;

    void commitPayrollRun(Date period, BigDecimal netAmount) throws RemoteException;

    /** Returns a comma-separated string because a value object would need another class. */
    String getPayslipSummary(long employeeId) throws RemoteException;
}
