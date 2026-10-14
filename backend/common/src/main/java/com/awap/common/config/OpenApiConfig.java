package com.awap.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI/Swagger configuration for the AWAP platform. */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI awapOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("AWAP API")
                .description("AI-Native Workflow Automation Platform API")
                .version("v1"));
  }
}
