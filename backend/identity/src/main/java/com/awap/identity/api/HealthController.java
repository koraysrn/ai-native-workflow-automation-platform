package com.awap.identity.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal scaffold endpoint used to verify the CI pipeline end to end. */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

  @GetMapping("/hello")
  public Map<String, String> hello() {
    return Map.of("message", "Hello from AWAP Identity", "status", "UP");
  }
}
