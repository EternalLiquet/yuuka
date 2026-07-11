package com.yuuka.backend.demo;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.config.OwnerProperties;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckFromTemplateRequest;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.api.dto.StatusChangeRequest;
import com.yuuka.backend.paycheck.application.PaycheckService;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.template.api.dto.CreateTemplateRequest;
import com.yuuka.backend.template.api.dto.TemplateEntryRequest;
import com.yuuka.backend.template.api.dto.TemplateResponse;
import com.yuuka.backend.template.application.TemplateService;
import com.yuuka.backend.template.infrastructure.JpaBudgetTemplateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
@Order(100)
public class DemoDataSeeder implements ApplicationRunner {
  private final OwnerProperties ownerProperties;
  private final JpaUserAccountRepository users;
  private final JpaPaycheckRepository paychecks;
  private final JpaBudgetTemplateRepository templates;
  private final TemplateService templateService;
  private final PaycheckService paycheckService;
  private final Clock clock;

  public DemoDataSeeder(
      OwnerProperties ownerProperties,
      JpaUserAccountRepository users,
      JpaPaycheckRepository paychecks,
      JpaBudgetTemplateRepository templates,
      TemplateService templateService,
      PaycheckService paycheckService,
      Clock clock) {
    this.ownerProperties = ownerProperties;
    this.users = users;
    this.paychecks = paychecks;
    this.templates = templates;
    this.templateService = templateService;
    this.paycheckService = paycheckService;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!ownerProperties.hasEmail()) {
      return;
    }
    UserAccount owner = users.findByEmailIgnoreCase(ownerProperties.normalizedEmail()).orElse(null);
    if (owner == null
        || paychecks.existsByOwnerId(owner.getId())
        || templates.existsByOwnerId(owner.getId())) {
      return;
    }

    TemplateResponse template =
        templateService.create(
            owner.getId(),
            new CreateTemplateRequest("UTILITIES 1/2", "Development demo data", demoEntries()));
    PaycheckResponse paycheck =
        templateService.createPaycheck(
            owner.getId(),
            new CreatePaycheckFromTemplateRequest(
                template.id(),
                "UTILITIES 1/2",
                193923,
                LocalDate.of(2026, 7, 10),
                "Demo income",
                "Development seed data only",
                null));

    Map<String, EntryStatus> statuses =
        Map.of(
            "State Farm", EntryStatus.POSTED,
            "Electricity", EntryStatus.PROCESSING,
            "BRZ", EntryStatus.POSTED,
            "Cayenne", EntryStatus.POSTED);
    for (EntryResponse entry : paycheck.entries()) {
      EntryStatus status = statuses.get(entry.name());
      if (status != null) {
        paycheckService.changeStatus(
            owner.getId(),
            entry.id(),
            new StatusChangeRequest(status, clock.instant(), "Demo status", entry.version()));
      }
    }
  }

  private List<TemplateEntryRequest> demoEntries() {
    return List.of(
        entry(EntryType.BILL, "State Farm", 23897),
        entry(EntryType.BILL, "Electricity", 13052),
        entry(EntryType.BILL, "QuickBooks", 4500),
        entry(EntryType.BILL, "BRZ", 46273),
        entry(EntryType.BILL, "Cayenne", 34158),
        entry(EntryType.BILL, "Fansly", 500),
        entry(EntryType.BILL, "Patreon", 535),
        entry(EntryType.BILL, "YouTube Membership", 499),
        entry(EntryType.BILL, "Microsoft", 1405),
        entry(EntryType.BILL, "Ride with GPS", 865),
        entry(EntryType.BILL, "YouTube Premium", 1399),
        entry(EntryType.BILL, "Clip Studio Paint", 523),
        entry(EntryType.BILL, "Verizon", 15261),
        entry(EntryType.SPENDING_BUCKET, "Groceries", 15000),
        entry(EntryType.SPENDING_BUCKET, "Work Food", 5000),
        entry(EntryType.SPENDING_BUCKET, "Normal Car Gas", 5000),
        entry(EntryType.SPENDING_BUCKET, "Misc/Life", 7500));
  }

  private TemplateEntryRequest entry(EntryType type, String name, long amountMinor) {
    return new TemplateEntryRequest(
        type, name, amountMinor, null, null, null, null, null, null, null);
  }
}
