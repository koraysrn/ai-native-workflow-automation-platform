package com.awap.identity.infrastructure.security;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Propagates the tenant context to PostgreSQL via {@code SET LOCAL awap.tenant_id}. */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantContextTransactionAspect {

  private final DataSource dataSource;

  public TenantContextTransactionAspect(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Before(
      "execution(* *(..)) && (@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional))")
  public void applyTenantContext() {
    TenantContext.current().ifPresent(this::setLocalTenant);
  }

  private void setLocalTenant(TenantContext.Context context) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
        return;
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL awap.tenant_id = '" + context.tenantId() + "'");
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("Failed to set tenant context on connection", ex);
    }
  }
}
