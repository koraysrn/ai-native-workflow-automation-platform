package com.awap.identity.infrastructure.security;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
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
  private final EntityManager entityManager;

  public TenantContextTransactionAspect(DataSource dataSource, EntityManager entityManager) {
    this.dataSource = dataSource;
    this.entityManager = entityManager;
  }

  @Before(
      "execution(* *(..)) && (@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional))")
  public void applyTenantContext() {
    TenantContext.current().ifPresent(this::setLocalTenant);
  }

  private void setLocalTenant(TenantContext.Context context) {
    enableJpaFilter(context);
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

  /**
   * Enables the Hibernate tenantFilter as a defense-in-depth layer (RLS remains the hard
   * guarantee).
   */
  private void enableJpaFilter(TenantContext.Context context) {
    try {
      entityManager
          .unwrap(Session.class)
          .enableFilter("tenantFilter")
          .setParameter("tenantId", context.tenantId());
    } catch (RuntimeException ignored) {
      // The JPA filter is best-effort; RLS is the primary isolation mechanism.
    }
  }
}
