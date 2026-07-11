package com.yuuka.backend.template.api;

import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckFromTemplateRequest;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.api.dto.VersionRequest;
import com.yuuka.backend.template.api.dto.CreateTemplateRequest;
import com.yuuka.backend.template.api.dto.DuplicateTemplateRequest;
import com.yuuka.backend.template.api.dto.ReorderTemplateEntriesRequest;
import com.yuuka.backend.template.api.dto.TemplateEntryRequest;
import com.yuuka.backend.template.api.dto.TemplateEntryResponse;
import com.yuuka.backend.template.api.dto.TemplateResponse;
import com.yuuka.backend.template.api.dto.UpdateTemplateRequest;
import com.yuuka.backend.template.application.TemplateService;
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
public class TemplateController {
  private final TemplateService templateService;

  public TemplateController(TemplateService templateService) {
    this.templateService = templateService;
  }

  @GetMapping("/templates")
  public PageResponse<TemplateResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean includeArchived) {
    return PageResponse.singlePage(
        templateService.list(AuthenticatedOwner.id(jwt), includeArchived));
  }

  @PostMapping("/templates")
  @ResponseStatus(HttpStatus.CREATED)
  public TemplateResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTemplateRequest request) {
    return templateService.create(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/templates/{templateId}")
  public TemplateResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID templateId) {
    return templateService.get(AuthenticatedOwner.id(jwt), templateId);
  }

  @PatchMapping("/templates/{templateId}")
  public TemplateResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID templateId,
      @Valid @RequestBody UpdateTemplateRequest request) {
    return templateService.update(AuthenticatedOwner.id(jwt), templateId, request);
  }

  @PostMapping("/templates/{templateId}/duplicate")
  @ResponseStatus(HttpStatus.CREATED)
  public TemplateResponse duplicate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID templateId,
      @Valid @RequestBody(required = false) DuplicateTemplateRequest request) {
    return templateService.duplicate(AuthenticatedOwner.id(jwt), templateId, request);
  }

  @PostMapping("/templates/{templateId}/archive")
  public TemplateResponse archive(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID templateId,
      @Valid @RequestBody VersionRequest request) {
    return templateService.archive(AuthenticatedOwner.id(jwt), templateId, request.version());
  }

  @PostMapping("/templates/{templateId}/restore")
  public TemplateResponse restore(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID templateId,
      @Valid @RequestBody VersionRequest request) {
    return templateService.restore(AuthenticatedOwner.id(jwt), templateId, request.version());
  }

  @PostMapping("/templates/{templateId}/entries")
  @ResponseStatus(HttpStatus.CREATED)
  public TemplateEntryResponse addEntry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID templateId,
      @Valid @RequestBody TemplateEntryRequest request) {
    return templateService.addEntry(AuthenticatedOwner.id(jwt), templateId, request);
  }

  @PatchMapping("/template-entries/{entryId}")
  public TemplateEntryResponse updateEntry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @Valid @RequestBody TemplateEntryRequest request) {
    return templateService.updateEntry(AuthenticatedOwner.id(jwt), entryId, request);
  }

  @DeleteMapping("/template-entries/{entryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteEntry(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID entryId, @RequestParam long version) {
    templateService.deleteEntry(AuthenticatedOwner.id(jwt), entryId, version);
  }

  @PostMapping("/templates/{templateId}/entries/reorder")
  public TemplateResponse reorder(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID templateId,
      @Valid @RequestBody ReorderTemplateEntriesRequest request) {
    return templateService.reorder(AuthenticatedOwner.id(jwt), templateId, request);
  }

  @PostMapping("/paychecks/from-template")
  @ResponseStatus(HttpStatus.CREATED)
  public PaycheckResponse createPaycheck(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreatePaycheckFromTemplateRequest request) {
    return templateService.createPaycheck(AuthenticatedOwner.id(jwt), request);
  }
}
