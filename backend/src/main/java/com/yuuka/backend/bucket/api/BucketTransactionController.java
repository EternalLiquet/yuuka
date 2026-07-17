package com.yuuka.backend.bucket.api;

import com.yuuka.backend.bucket.api.dto.BucketTransactionResponse;
import com.yuuka.backend.bucket.api.dto.CreateBucketTransactionRequest;
import com.yuuka.backend.bucket.api.dto.UpdateBucketTransactionRequest;
import com.yuuka.backend.bucket.application.BucketTransactionService;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BucketTransactionController {
  private final BucketTransactionService service;

  public BucketTransactionController(BucketTransactionService service) {
    this.service = service;
  }

  @GetMapping("/entries/{entryId}/bucket-transactions")
  public PageResponse<BucketTransactionResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.list(AuthenticatedOwner.id(jwt), entryId, page, size);
  }

  @PostMapping("/entries/{entryId}/bucket-transactions")
  @ResponseStatus(HttpStatus.CREATED)
  public BucketTransactionResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @Valid @RequestBody CreateBucketTransactionRequest request) {
    return service.create(AuthenticatedOwner.id(jwt), entryId, request);
  }

  @PatchMapping("/bucket-transactions/{transactionId}")
  public BucketTransactionResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transactionId,
      @Valid @RequestBody UpdateBucketTransactionRequest request) {
    return service.update(AuthenticatedOwner.id(jwt), transactionId, request);
  }

  @DeleteMapping("/bucket-transactions/{transactionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transactionId,
      @RequestParam long version) {
    service.delete(AuthenticatedOwner.id(jwt), transactionId, version);
  }
}
