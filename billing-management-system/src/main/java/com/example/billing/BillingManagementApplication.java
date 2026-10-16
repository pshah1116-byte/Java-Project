package com.example.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Billing Management System.
 *
 * In IntelliJ IDEA: click the green Run arrow next to main(),
 * wait for "Started BillingManagementApplication" in the Run console,
 * then open http://localhost:8081 in your browser.
 *
 * MongoDB must already be running (default: mongodb://localhost:27017).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BillingManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingManagementApplication.class, args);
    }
}
