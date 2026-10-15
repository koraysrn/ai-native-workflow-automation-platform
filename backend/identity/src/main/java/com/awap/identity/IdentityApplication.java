package com.awap.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Entry point of the AWAP Backend application (modular monolith). */
@SpringBootApplication(scanBasePackages = "com.awap")
@EntityScan(basePackages = "com.awap")
@EnableJpaRepositories(basePackages = "com.awap")
public class IdentityApplication {

  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }
}
