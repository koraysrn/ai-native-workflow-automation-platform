package com.awap.engine.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dedicated, small connection pool for the outbox relay so polling/claiming cannot starve the
 * request-serving pool. Set {@code awap.outbox.datasource.enabled=false} to fall back to the shared
 * JPA repository (used by tests and single-connection deployments).
 */
@Configuration
public class OutboxRelayDataSourceConfig {

  @Bean(name = "outboxDataSource", destroyMethod = "close")
  @ConditionalOnProperty(
      name = "awap.outbox.datasource.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public DataSource outboxDataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password,
      @Value("${awap.outbox.datasource.maximum-pool-size:4}") int maximumPoolSize) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("awap-outbox-relay");
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(Math.max(1, maximumPoolSize));
    config.setMinimumIdle(1);
    config.setConnectionTimeout(10_000);
    return new HikariDataSource(config);
  }

  @Bean
  @ConditionalOnProperty(
      name = "awap.outbox.datasource.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public JdbcTemplate outboxJdbcTemplate(
      @Qualifier("outboxDataSource") DataSource outboxDataSource) {
    return new JdbcTemplate(outboxDataSource);
  }
}
