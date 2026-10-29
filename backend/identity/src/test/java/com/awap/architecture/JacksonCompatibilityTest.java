package com.awap.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Serialization compatibility guard for the Jackson BOM override (issue #136). These tests exercise
 * the behaviour the override must preserve: UUID/Instant round-trips, tolerant reads of unknown
 * fields and no polymorphic default typing that could instantiate arbitrary classes.
 */
class JacksonCompatibilityTest {

  // Mirrors the Spring Boot ObjectMapper: ISO-8601 timestamps, registered JavaTime module.
  private final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void apiDtoRoundTripsUuidAndInstant() throws Exception {
    SampleDto original =
        new SampleDto(
            UUID.randomUUID(), Instant.parse("2026-02-01T10:15:30Z"), "Ada", Map.of("k", "v"));

    String json = mapper.writeValueAsString(original);
    SampleDto restored = mapper.readValue(json, SampleDto.class);

    assertThat(restored).isEqualTo(original);
    assertThat(json).contains("2026-02-01T10:15:30Z");
  }

  @Test
  void unknownFieldsAreIgnoredWhenConfigured() throws Exception {
    ObjectMapper tolerant =
        mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    SampleDto restored =
        tolerant.readValue(
            "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"createdAt\":\"2026-02-01T10:15:30Z\","
                + "\"name\":\"Ada\",\"attributes\":{},\"futureField\":\"ignored\"}",
            SampleDto.class);

    assertThat(restored.name()).isEqualTo("Ada");
  }

  @Test
  void strictMapperStillRejectsUnknownFieldsByDefault() {
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    "{\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"createdAt\":\"2026-02-01T10:15:30Z\",\"name\":\"Ada\","
                        + "\"attributes\":{},\"unknown\":1}",
                    SampleDto.class))
        .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
  }

  @Test
  void noPolymorphicTypingMeansClassNamesCannotBeInstantiated() {
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    "{\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"createdAt\":\"2026-02-01T10:15:30Z\",\"name\":\"Ada\","
                        + "\"attributes\":{},\"@class\":\"java.lang.Runtime\"}",
                    SampleDto.class))
        .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
  }

  @Test
  void eventPayloadShapeFromTheAvroSchemaRoundTrips() throws Exception {
    Map<String, Object> event =
        Map.of(
            "tenantId", UUID.randomUUID().toString(),
            "recipient", "user@example.com",
            "channel", "EMAIL",
            "templateKey", "welcome",
            "locale", "en",
            "variablesJson", "{\"name\":\"Ada\"}");

    String json = mapper.writeValueAsString(event);
    Map<String, Object> restored =
        mapper.readValue(
            json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

    assertThat(restored.keySet()).containsExactlyInAnyOrderElementsOf(event.keySet());
    assertThat(restored.get("templateKey")).isEqualTo("welcome");
  }

  /** Representative DTO shape: UUID, Instant, String and a nested map. */
  record SampleDto(UUID id, Instant createdAt, String name, Map<String, String> attributes) {}
}
