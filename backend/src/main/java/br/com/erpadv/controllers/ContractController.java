package br.com.erpadv.controllers;

import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.dto.ContractDtos.BodyRequest;
import br.com.erpadv.dto.ContractDtos.ContractRequest;
import br.com.erpadv.dto.ContractDtos.ContractResponse;
import br.com.erpadv.dto.ContractDtos.FieldInfo;
import br.com.erpadv.dto.ContractDtos.GenerateRequest;
import br.com.erpadv.dto.ContractDtos.GenerateResponse;
import br.com.erpadv.dto.ContractDtos.SuccessRequest;
import br.com.erpadv.dto.ContractDtos.TemplateRequest;
import br.com.erpadv.dto.ContractDtos.TemplateResponse;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.FinanceDtos.ReasonRequest;
import br.com.erpadv.models.ContractEnums.ContractStatus;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.ContractService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService contracts;

    public ContractController(ContractService contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_READ')")
    public CursorPage<ContractResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam(required = false) ContractStatus status,
                                             @RequestParam(required = false) String clientId,
                                             @RequestParam(required = false) String search,
                                             @RequestParam(defaultValue = "false") boolean expiring,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(defaultValue = "30") int size) {
        return contracts.list(user, status, clientId, search, expiring, cursor, size);
    }

    @GetMapping("/fields")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_READ')")
    public List<FieldInfo> fields() {
        return ContractService.fieldCatalog();
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_READ')")
    public List<TemplateResponse> templates(@AuthenticationPrincipal AuthenticatedUser user) {
        return contracts.templates(user);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public TemplateResponse createTemplate(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody TemplateRequest req) {
        return contracts.saveTemplate(user, null, req);
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public TemplateResponse updateTemplate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                           @Valid @RequestBody TemplateRequest req) {
        return contracts.saveTemplate(user, id, req);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public void deleteTemplate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        contracts.deleteTemplate(user, id);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_READ')")
    public ContractResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return contracts.get(user, id);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_READ')")
    public CursorPage<AuditLog> history(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                        @RequestParam(required = false) String cursor) {
        return contracts.history(user, id, cursor, 50);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ContractRequest req) {
        return contracts.create(user, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody ContractRequest req) {
        return contracts.update(user, id, req);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse activate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return contracts.activate(user, id);
    }

    @PostMapping("/{id}/stages/{stageId}/bill")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse billStage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                      @PathVariable String stageId) {
        return contracts.billStage(user, id, stageId);
    }

    @PostMapping("/{id}/success")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse success(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                    @Valid @RequestBody SuccessRequest req) {
        return contracts.registerSuccess(user, id, req.obtainedCents(), req.dueDate(), req.note());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse close(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return contracts.close(user, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody ReasonRequest req) {
        return contracts.cancel(user, id, req.reason());
    }

    @PostMapping("/{id}/generate")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public GenerateResponse generate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @Valid @RequestBody GenerateRequest req) {
        return contracts.generate(user, id, req.templateId());
    }

    @PutMapping("/{id}/body")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse saveBody(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @Valid @RequestBody BodyRequest req) {
        return contracts.saveBody(user, id, req.body());
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_READ')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                         @RequestParam(defaultValue = "pdf") String format) {
        ContractService.Exported file = contracts.export(user, id, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }

    @PostMapping("/{id}/export-to-documents")
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public Map<String, String> exportToDocuments(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                 @RequestParam(defaultValue = "pdf") String format) {
        return Map.of("documentId", contracts.exportToDocuments(user, id, format));
    }

    @PostMapping(value = "/{id}/signed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_CONTRACTS_WRITE')")
    public ContractResponse uploadSigned(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                         @RequestPart("file") MultipartFile file) {
        return contracts.uploadSigned(user, id, DocumentController.bytes(file), file.getOriginalFilename());
    }
}
