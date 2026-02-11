package com.fintrack.dto;

import java.util.List;

public record EnableBankingAspspResponse(
    String name,
    String country,
    String bic,
    String logo,
    List<String> psuTypes
) {}
