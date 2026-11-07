package br.com.erpadv.controllers;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.FinanceDtos.DelinquencyReport;
import br.com.erpadv.dto.FinanceDtos.FinanceSummary;
import br.com.erpadv.dto.FinanceDtos.PayRequest;
import br.com.erpadv.dto.FinanceDtos.ReasonRequest;
import br.com.erpadv.dto.FinanceDtos.TransactionFilter;
import br.com.erpadv.dto.FinanceDtos.TransactionRequest;
import br.com.erpadv.dto.FinanceDtos.TransactionResponse;
import br.com.erpadv.dto.FinanceDtos.View;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.FinanceReportService;
import br.com.erpadv.services.FinanceService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceService finance;
    private final FinanceReportService reports;

    public FinanceController(FinanceService finance, FinanceReportService reports) {
        this.finance = finance;
        this.reports = reports;
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public CursorPage<TransactionResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) FinanceScope scope,
                                                @RequestParam(required = false) FinanceDirection direction,
                                                @RequestParam(defaultValue = "ABERTOS") View view,
                                                @RequestParam(required = false) FinanceCategory category,
                                                @RequestParam(required = false) String clientId,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(required = false) String contractId,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(defaultValue = "30") int size) {
        return finance.list(user, new TransactionFilter(scope, direction, view, category, clientId, from, to, contractId), cursor, size);
    }

    @GetMapping("/transactions/export")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) FinanceScope scope,
                                         @RequestParam(required = false) FinanceDirection direction,
                                         @RequestParam(defaultValue = "TODOS") View view,
                                         @RequestParam(required = false) FinanceCategory category,
                                         @RequestParam(required = false) String clientId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] csv = finance.exportCsv(user, new TransactionFilter(scope, direction, view, category, clientId, from, to));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("lancamentos-" + LocalDate.now() + ".csv").build().toString())
                .body(csv);
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public TransactionResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return finance.get(user, id);
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public List<TransactionResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody TransactionRequest req) {
        List<TransactionResponse> result = finance.create(user, req);
        reports.invalidate(user.officeId());
        return result;
    }

    @PutMapping("/transactions/{id}")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public TransactionResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                      @Valid @RequestBody TransactionRequest req) {
        TransactionResponse result = finance.update(user, id, req);
        reports.invalidate(user.officeId());
        return result;
    }

    @PostMapping("/transactions/{id}/pay")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public TransactionResponse pay(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody PayRequest req) {
        TransactionResponse result = finance.pay(user, id, req);
        reports.invalidate(user.officeId());
        return result;
    }

    @PostMapping("/transactions/{id}/reopen")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public TransactionResponse reopen(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        TransactionResponse result = finance.reopen(user, id);
        reports.invalidate(user.officeId());
        return result;
    }

    @PostMapping("/transactions/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public TransactionResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                      @Valid @RequestBody ReasonRequest req) {
        TransactionResponse result = finance.cancel(user, id, req.reason());
        reports.invalidate(user.officeId());
        return result;
    }

    @PostMapping(value = "/transactions/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public TransactionResponse uploadReceipt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                             @RequestPart("file") MultipartFile file) {
        if (file.getSize() > FinanceService.MAX_RECEIPT_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE", "Arquivo excede o limite de 10 MB.");
        }
        try {
            return finance.attachReceipt(user, id, file.getBytes(), file.getOriginalFilename());
        } catch (IOException e) {
            throw new BusinessException("UPLOAD_FAILED", "Não foi possível ler o arquivo enviado.");
        }
    }

    @GetMapping("/transactions/{id}/receipt")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public ResponseEntity<byte[]> downloadReceipt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        FinanceService.Receipt receipt = finance.downloadReceipt(user, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(receipt.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(receipt.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(receipt.bytes());
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public FinanceSummary summary(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(required = false) FinanceScope scope,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(user, scope, from, to);
    }

    /** Indicadores financeiros do dashboard executivo (sempre escopo do escritório). */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('PERM_FINANCE_DASHBOARD_VIEW')")
    public FinanceSummary dashboard(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(user, FinanceScope.ESCRITORIO, from, to);
    }

    @GetMapping("/delinquency")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public DelinquencyReport delinquency(@AuthenticationPrincipal AuthenticatedUser user) {
        return reports.delinquency(user);
    }
}
