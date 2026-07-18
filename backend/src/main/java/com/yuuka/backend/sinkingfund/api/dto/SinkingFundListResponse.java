package com.yuuka.backend.sinkingfund.api.dto;

import java.util.List;

public record SinkingFundListResponse(
    SinkingFundSummaryResponse summary, List<SinkingFundResponse> items) {}
