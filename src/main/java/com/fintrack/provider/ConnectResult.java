package com.fintrack.provider;

import com.fintrack.model.ConnectionStatus;

public record ConnectResult(String redirectUrl, String externalId, ConnectionStatus status) {}
