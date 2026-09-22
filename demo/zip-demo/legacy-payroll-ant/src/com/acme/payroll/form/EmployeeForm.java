package com.acme.payroll.form;

import javax.servlet.http.HttpServletRequest;

import org.apache.struts.action.ActionErrors;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;

/**
 * Struts 1 ActionForm.
 *
 * Every field is a String because Struts 1 populates forms from request parameters by
 * reflection, so the type conversion and the validation both end up here, by hand.
 */
public class EmployeeForm extends ActionForm {

    private static final long serialVersionUID = 1L;

    private String employeeId;
    private String firstName;
    private String lastName;
    private String department;
    private String annualSalary;
    private String emailAddress;

    public void reset(ActionMapping mapping, HttpServletRequest request) {
        // Only some fields are reset, so a validation failure on one screen can carry
        // stale values into the next.
        this.employeeId = null;
        this.annualSalary = null;
    }

    public ActionErrors validate(ActionMapping mapping, HttpServletRequest request) {
        ActionErrors errors = new ActionErrors();

        if (lastName == null || lastName.trim().length() == 0) {
            errors.add("lastName", new ActionMessage("error.lastName.required"));
        }

        if (annualSalary != null && annualSalary.length() > 0) {
            try {
                double value = Double.parseDouble(annualSalary);
                if (value < 0) {
                    errors.add("annualSalary", new ActionMessage("error.salary.negative"));
                }
            } catch (NumberFormatException e) {
                errors.add("annualSalary", new ActionMessage("error.salary.notNumeric"));
            }
        }

        // Email "validation" that accepts anything with an at sign.
        if (emailAddress != null && emailAddress.length() > 0
                && emailAddress.indexOf('@') < 0) {
            errors.add("emailAddress", new ActionMessage("error.email.invalid"));
        }

        // department is never validated, and is concatenated directly into SQL downstream.
        return errors;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getAnnualSalary() {
        return annualSalary;
    }

    public void setAnnualSalary(String annualSalary) {
        this.annualSalary = annualSalary;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }
}
