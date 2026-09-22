package com.acme.payroll.ejb;

import java.rmi.RemoteException;

import javax.ejb.CreateException;
import javax.ejb.EJBHome;

/**
 * EJB 2.x remote home interface.
 *
 * Three files per bean - home, remote, implementation - none of which the compiler
 * checks against each other. A signature change that is applied to two of the three
 * fails at runtime on lookup, not at build time.
 */
public interface PayrollSessionHome extends EJBHome {

    PayrollSession create() throws RemoteException, CreateException;
}
