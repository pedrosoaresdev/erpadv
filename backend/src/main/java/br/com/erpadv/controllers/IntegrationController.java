package br.com.erpadv.controllers;

import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.IntegrationRun;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.FinanceService;
import br.com.erpadv.services.integrations.CaseImportService;
import br.com.erpadv.services.integrations.DataJudService;
import br.com.erpadv.services.integrations.IntegrationService;
import br.com.erpadv.services.integrations.IntegrationService.ConnectorStatus;
import br.com.erpadv.services.integrations.IntegrationService.SettingsRequest;
import br.com.erpadv.services.integrations.PublicationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Central de Integrações e Central Judicial. Configurar conectores exige SETTINGS_MANAGE. */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final IntegrationService integrations;
    private final DataJudService dataJud;
    private final PublicationService publications;
    private final CaseImportService imports;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.integrations.GoogleCalendarService google;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.integrations.SignatureService signatureService;

    public IntegrationController(IntegrationService integrations, DataJudService dataJud, PublicationService publications,
                                 CaseImportService imports) {
        this.integrations = integrations;
        this.dataJud = dataJud;
        this.publications = publications;
        this.imports = imports;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CASES_READ') or hasAuthority('PERM_SETTINGS_MANAGE')")
    public List<ConnectorStatus> catalog(@AuthenticationPrincipal AuthenticatedUser user) {
        return integrations.catalog(user);
    }

    @PutMapping("/{provider}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ConnectorStatus save(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable IntegrationProvider provider,
                                @RequestBody SettingsRequest req) {
        return integrations.save(user, provider, req);
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('PERM_CASES_READ') or hasAuthority('PERM_SETTINGS_MANAGE')")
    public List<IntegrationRun> runs(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) IntegrationProvider provider) {
        return integrations.runs(user, provider);
    }

    @PostMapping("/datajud/cases/{caseId}")
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public DataJudService.SyncResult syncCase(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String caseId) {
        return dataJud.syncCase(user, caseId);
    }

    @PostMapping("/datajud/sync")
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public IntegrationRun syncAll(@AuthenticationPrincipal AuthenticatedUser user) {
        if (!integrations.active(user.requireOffice(), IntegrationProvider.DATAJUD)) {
            throw new br.com.erpadv.exceptions.BusinessException("INTEGRATION_DISABLED", "A integração com o DataJud está desativada ou sem chave de acesso.");
        }
        return dataJud.syncAll(user.officeId(), "MANUAL", user.displayName());
    }

    @PostMapping("/djen/sync")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public IntegrationRun syncDjen(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!integrations.active(user.requireOffice(), IntegrationProvider.DJEN)) {
            throw new br.com.erpadv.exceptions.BusinessException("INTEGRATION_DISABLED", "A consulta ao DJEN está desativada.");
        }
        LocalDate end = to == null ? FinanceService.today() : to;
        return publications.sync(user.officeId(), from == null ? end.minusDays(7) : from, end, "MANUAL", user.displayName());
    }

    // ------------------------------------------------------------------ assinatura eletrônica

    @GetMapping("/signatures")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public List<br.com.erpadv.services.integrations.SignatureService.SignatureRequest> signatures(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                                 @RequestParam(required = false) String documentId) {
        return signatureService.list(user, documentId);
    }

    @PostMapping("/signatures")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public br.com.erpadv.services.integrations.SignatureService.SignatureRequest sendSignature(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                              @RequestBody br.com.erpadv.services.integrations.SignatureService.SendRequest req) {
        return signatureService.send(user, req);
    }

    @PostMapping("/signatures/{id}/refresh")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public br.com.erpadv.services.integrations.SignatureService.SignatureRequest refreshSignature(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                                 @PathVariable String id) {
        return signatureService.refresh(user, id);
    }

    // ------------------------------------------------------------------ Google Agenda (por usuário)

    @GetMapping("/google/me")
    public br.com.erpadv.services.integrations.GoogleCalendarService.Status googleStatus(@AuthenticationPrincipal AuthenticatedUser user) {
        return google.status(user);
    }

    @PostMapping("/google/connect")
    public Map<String, String> googleConnect(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("url", google.authorizationUrl(user));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/google/me")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void googleDisconnect(@AuthenticationPrincipal AuthenticatedUser user) {
        google.disconnect(user);
    }

    @GetMapping("/import/cases/template")
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("modelo-importacao-processos.csv").build().toString())
                .body(("\uFEFF" + CaseImportService.TEMPLATE).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/import/cases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public CaseImportService.ImportResult importCases(@AuthenticationPrincipal AuthenticatedUser user, @RequestPart("file") MultipartFile file) {
        if (file.getSize() > 2L * 1024 * 1024) {
            throw new br.com.erpadv.exceptions.BusinessException("FILE_TOO_LARGE", "Planilha maior que 2 MB.");
        }
        return imports.importCsv(user, DocumentController.bytes(file));
    }

    @SuppressWarnings("unused")
    private static Map<String, String> none() {
        return Map.of();
    }
}
