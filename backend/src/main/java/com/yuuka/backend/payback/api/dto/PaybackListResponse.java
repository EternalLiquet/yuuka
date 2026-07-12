package com.yuuka.backend.payback.api.dto;

import java.util.List;

public record PaybackListResponse(PaybackSummaryResponse summary, List<PaybackResponse> items) {}
