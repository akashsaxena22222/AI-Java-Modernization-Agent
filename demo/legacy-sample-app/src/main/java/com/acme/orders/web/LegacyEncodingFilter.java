package com.acme.orders.web;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

/**
 * Hand-rolled character encoding filter, declared in web.xml.
 *
 * Spring has shipped CharacterEncodingFilter since 2004. This one was written anyway, and it
 * additionally logs every request line to stdout, which is where the access log comes from.
 */
public class LegacyEncodingFilter implements Filter {

    private String encoding = "ISO-8859-1";

    public void init(FilterConfig config) throws ServletException {
        String configured = config.getInitParameter("encoding");
        if (configured != null) {
            this.encoding = configured;
        }
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        request.setCharacterEncoding(encoding);
        response.setCharacterEncoding(encoding);

        if (request instanceof HttpServletRequest) {
            HttpServletRequest http = (HttpServletRequest) request;
            // Logs the full query string, so any credential passed as a parameter lands in
            // the access log in plaintext.
            System.out.println("[" + new java.util.Date() + "] "
                    + http.getMethod() + " " + http.getRequestURI()
                    + (http.getQueryString() == null ? "" : "?" + http.getQueryString()));
        }

        chain.doFilter(request, response);
    }

    public void destroy() {
    }
}
