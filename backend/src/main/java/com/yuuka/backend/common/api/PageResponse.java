package com.yuuka.backend.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResponse<T>(
    List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {
  public static <T> PageResponse<T> from(Page<T> result) {
    return new PageResponse<>(
        result.getContent(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages(),
        result.hasNext());
  }

  public static <T> PageResponse<T> singlePage(List<T> items) {
    return new PageResponse<>(items, 0, items.size(), items.size(), items.isEmpty() ? 0 : 1, false);
  }
}
