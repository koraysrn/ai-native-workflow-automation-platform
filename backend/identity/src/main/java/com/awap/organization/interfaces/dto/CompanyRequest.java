package com.awap.organization.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Request to create a company. */
public record CompanyRequest(@NotBlank(message = "name is required") String name) {}
