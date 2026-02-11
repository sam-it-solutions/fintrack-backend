package com.fintrack.dto;

import java.util.UUID;

public record PasskeyStartResponse(UUID challengeId, Object options) {}
