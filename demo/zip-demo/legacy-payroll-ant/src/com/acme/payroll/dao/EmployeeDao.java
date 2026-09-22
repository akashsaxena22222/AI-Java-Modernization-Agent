package com.acme.payroll.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Vector;

/**
 * Raw JDBC data access.
 *
 * Every query is built by string concatenation, including the ones that take user
 * input. Results are returned as Vectors of Object arrays, so every caller has to know
 * the column order by memory.
 */
public class EmployeeDao {

    private final Connection connection;

    public EmployeeDao(Connection connection) {
        this.connection = connection;
    }

    /**
     * SQL injection: department comes from a request parameter and is concatenated in.
     */
    public Vector findByDepartment(String department) throws Exception {
        Statement statement = connection.createStatement();
        String sql = "select employee_id, first_name, last_name, annual_salary, department "
                + "from employee "
                + "where department = '" + department + "' "
                + "and active_flag = 'Y' "
                + "order by last_name";

        ResultSet rs = statement.executeQuery(sql);
        Vector rows = new Vector();
        while (rs.next()) {
            Object[] row = new Object[5];
            row[0] = new Long(rs.getLong(1));
            row[1] = rs.getString(2);
            row[2] = rs.getString(3);
            row[3] = new Double(rs.getDouble(4));
            row[4] = rs.getString(5);
            rows.add(row);
        }
        // ResultSet and Statement are never closed. Cursors leak until the pool dies.
        return rows;
    }

    public Vector findAllActive() throws Exception {
        Statement statement = connection.createStatement();
        // Oracle-specific: ROWNUM and NVL do not exist in PostgreSQL or MySQL, which is
        // a direct cost of any move to a managed database.
        String sql = "select employee_id, first_name, last_name, nvl(annual_salary, 0) "
                + "from employee where active_flag = 'Y' and rownum <= 10000";

        ResultSet rs = statement.executeQuery(sql);
        Vector rows = new Vector();
        while (rs.next()) {
            Object[] row = new Object[4];
            row[0] = new Long(rs.getLong(1));
            row[1] = rs.getString(2);
            row[2] = rs.getString(3);
            row[3] = new Double(rs.getDouble(4));
            rows.add(row);
        }
        return rows;
    }

    public void insertPayrollRun(Date period, BigDecimal netAmount) throws Exception {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        Statement statement = connection.createStatement();

        String sql = "insert into payroll_run (run_date, net_amount, created_by) values ("
                + "to_date('" + fmt.format(period) + "', 'YYYY-MM-DD'), "
                + netAmount.toString() + ", "
                + "'BATCH')";

        statement.executeUpdate(sql);
        // No commit here: depends on the container's transaction attribute being right.
    }

    public String payslipSummary(long employeeId) throws Exception {
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
                "select first_name || ' ' || last_name || ',' || annual_salary "
                        + "from employee where employee_id = " + employeeId);
        if (rs.next()) {
            return rs.getString(1);
        }
        return "";
    }
}
