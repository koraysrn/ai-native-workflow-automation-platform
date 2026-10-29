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
 * request-serving pool.
 *
 * <p>{@code awap.outbox.datasource.enabled=false} switches the relay to the JPA fallback on the
 * request-serving pool. That fallback has no tenant context, so PostgreSQL row-level security hides
 * every outbox row; it is therefore restricted to single-connection dev/test environments and
 * rejected at startup in {@code prod}/{@code staging} by {@link
 * com.awap.engine.application.OutboxRelayConfigurationGuard} (issue #122, runbook O8). The pool
 * name comes from {@code awap.outbox.datasource.name} so it is recognisable in Hikari metrics and
 * logs.
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
      @Value("${awap.outbox.datasource.name:awap-outbox-relay}") String poolName,
      @Value("${awap.outbox.datasource.maximum-pool-size:4}") int maximumPoolSize) {
    HikariConfig config = new HikariConfig();
    config.setPoolName(poolName);
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
