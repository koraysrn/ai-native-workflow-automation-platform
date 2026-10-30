package com.awap.notification.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.notification.domain.InvalidNotificationTemplateException;
import com.awap.notification.domain.NotificationTemplate;
import com.awap.notification.domain.NotificationTemplateLocale;
import com.awap.notification.domain.NotificationTemplateValidator;
import com.awap.notification.domain.NotificationTemplateVersion;
import com.awap.notification.infrastructure.NotificationTemplateRepository;
import com.awap.notification.infrastructure.NotificationTemplateVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-managed notification templates: CRUD with optimistic concurrency, revision audit,
 * activation/archival and a preview that renders variables without dispatching a notification.
 */
@Service
public class NotificationTemplateService {

  private static final Logger log = LoggerFactory.getLogger(NotificationTemplateService.class);
  private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

  private final NotificationTemplateRepository repository;
  private final NotificationTemplateVersionRepository versionRepository;
  private final NotificationTemplateRenderer renderer;

  public NotificationTemplateService(
      NotificationTemplateRepository repository,
      NotificationTemplateVersionRepository versionRepository,
      NotificationTemplateRenderer renderer) {
    this.repository = repository;
    this.versionRepository = versionRepository;
    this.renderer = renderer;
  }

  @Transactional(readOnly = true)
  public List<NotificationTemplate> list(UUID tenantId, String templateKey) {
    if (templateKey == null || templateKey.isBlank()) {
      return repository.findByTenantIdOrderByTemplateKeyAscLocaleAsc(tenantId);
    }
    return repository.findByTenantIdAndTemplateKeyOrderByLocaleAsc(tenantId, templateKey);
  }

  @Transactional(readOnly = true)
  public NotificationTemplate get(UUID tenantId, UUID templateId) {
    return require(tenantId, templateId);
  }

  @Transactional(readOnly = true)
  public List<NotificationTemplateVersion> versions(UUID tenantId, UUID templateId) {
    require(tenantId, templateId);
    return versionRepository.findByTenantIdAndTemplateIdOrderByVersionDesc(tenantId, templateId);
  }

  @Transactional
  public NotificationTemplate create(
      UUID tenantId, String templateKey, String locale, String subject, String body, UUID actor) {
    String key = requireKey(templateKey);
    String normalizedLocale = requireLocale(locale);
    if (repository
        .findByTenantIdAndTemplateKeyAndLocale(tenantId, key, normalizedLocale)
        .isPresent()) {
      throw conflict("A template for " + key + "/" + normalizedLocale + " already exists.");
    }
    try {
      NotificationTemplate template =
          new NotificationTemplate(tenantId, key, normalizedLocale, subject, body, actor);
      repository.saveAndFlush(template);
      versionRepository.save(new NotificationTemplateVersion(template));
      renderer.invalidate(tenantId, key);
      log.info(
          "Created notification template tenant={} key={} locale={} version={}",
          tenantId,
          key,
          normalizedLocale,
          template.getVersion());
      return template;
    } catch (InvalidNotificationTemplateException ex) {
      throw invalid(ex);
    }
  }

  @Transactional
  public NotificationTemplate update(
      UUID tenantId,
      UUID templateId,
      long expectedVersion,
      String subject,
      String body,
      UUID actor) {
    NotificationTemplate template = require(tenantId, templateId);
    if (template.getVersion() != expectedVersion) {
      throw conflict(
          "Template "
              + templateId
              + " is at version "
              + template.getVersion()
              + " but the request expected "
              + expectedVersion
              + ".");
    }
    try {
      template.revise(subject, body, actor);
      repository.saveAndFlush(template);
      versionRepository.save(new NotificationTemplateVersion(template));
      renderer.invalidate(tenantId, template.getTemplateKey());
      log.info(
          "Revised notification template tenant={} id={} key={} locale={} version={}",
          tenantId,
          templateId,
          template.getTemplateKey(),
          template.getLocale(),
          template.getVersion());
      return template;
    } catch (InvalidNotificationTemplateException ex) {
      throw invalid(ex);
    } catch (OptimisticLockingFailureException ex) {
      throw conflict("Template " + templateId + " was modified by another request.");
    }
  }

  @Transactional
  public NotificationTemplate archive(UUID tenantId, UUID templateId, UUID actor) {
    return toggle(tenantId, templateId, actor, true);
  }

  @Transactional
  public NotificationTemplate activate(UUID tenantId, UUID templateId, UUID actor) {
    return toggle(tenantId, templateId, actor, false);
  }

  /** Renders a template (stored or inline) with the supplied variables without sending anything. */
  @Transactional(readOnly = true)
  public Preview preview(
      UUID tenantId,
      String templateKey,
      String locale,
      String subject,
      String body,
      String variablesJson) {
    String targetSubject = subject;
    String targetBody = body;
    if (targetSubject == null || targetBody == null) {
      String key = requireKey(templateKey);
      String normalizedLocale = requireLocale(locale == null ? "en" : locale);
      NotificationTemplate stored =
          repository
              .findByTenantIdAndTemplateKeyAndLocale(tenantId, key, normalizedLocale)
              .orElseThrow(
                  () ->
                      new AwapException(
                          ErrorCode.RESOURCE_NOT_FOUND,
                          HttpStatus.NOT_FOUND,
                          "Template " + key + "/" + normalizedLocale + " was not found."));
      if (targetSubject == null) {
        targetSubject = stored.getSubject();
      }
      if (targetBody == null) {
        targetBody = stored.getBody();
      }
    }
    try {
      NotificationTemplateValidator.validate(targetSubject, targetBody);
      Map<String, String> variables = renderer.parseVariables(variablesJson);
      Set<String> placeholders = NotificationTemplateValidator.placeholders(targetBody);
      String renderedSubject = renderer.renderText(targetSubject, variables);
      String renderedBody = renderer.renderText(targetBody, variables);
      return new Preview(renderedSubject, renderedBody, List.copyOf(placeholders));
    } catch (InvalidNotificationTemplateException ex) {
      throw invalid(ex);
    }
  }

  private NotificationTemplate toggle(UUID tenantId, UUID templateId, UUID actor, boolean archive) {
    NotificationTemplate template = require(tenantId, templateId);
    boolean changed = archive ? template.archive(actor) : template.activate(actor);
    if (!changed) {
      return template;
    }
    try {
      repository.saveAndFlush(template);
      versionRepository.save(new NotificationTemplateVersion(template));
      renderer.invalidate(tenantId, template.getTemplateKey());
      log.info(
          "Toggled notification template tenant={} id={} key={} locale={} active={} version={}",
          tenantId,
          templateId,
          template.getTemplateKey(),
          template.getLocale(),
          template.isActive(),
          template.getVersion());
      return template;
    } catch (OptimisticLockingFailureException ex) {
      throw conflict("Template " + templateId + " was modified by another request.");
    }
  }

  private NotificationTemplate require(UUID tenantId, UUID templateId) {
    return repository
        .findById(templateId)
        .filter(template -> tenantId.equals(template.getTenantId()))
        .orElseThrow(
            () ->
                new AwapException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "Template " + templateId + " was not found."));
  }

  private static String requireKey(String templateKey) {
    if (templateKey == null || !KEY_PATTERN.matcher(templateKey).matches()) {
      throw invalid("templateKey must match " + KEY_PATTERN.pattern() + " (lowercase key).");
    }
    return templateKey;
  }

  private static String requireLocale(String locale) {
    try {
      return NotificationTemplateLocale.canonicalize(locale);
    } catch (InvalidNotificationTemplateException ex) {
      throw invalid(ex);
    }
  }

  private static AwapException invalid(String detail) {
    return new AwapException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, detail);
  }

  private static AwapException invalid(InvalidNotificationTemplateException ex) {
    return invalid(ex.getMessage());
  }

  private static AwapException conflict(String detail) {
    return new AwapException(ErrorCode.RESOURCE_CONFLICT, HttpStatus.CONFLICT, detail);
  }

  /** Rendered preview payload. */
  public record Preview(String subject, String body, List<String> placeholders) {}
}
