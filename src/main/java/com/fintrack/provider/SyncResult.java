package com.fintrack.provider;

public record SyncResult(int accountsUpdated, int transactionsImported, String message) {}
