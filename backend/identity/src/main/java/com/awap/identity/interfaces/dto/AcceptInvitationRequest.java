package com.awap.identity.interfaces.dto;

/** Request to accept an invitation; fields are optional for existing users. */
public record AcceptInvitationRequest(String fullName, String password) {}
