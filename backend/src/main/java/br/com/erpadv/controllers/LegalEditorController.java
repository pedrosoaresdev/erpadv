package br.com.erpadv.controllers;

import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.dto.LegalEditorDtos.CreateRequest;
import br.com.erpadv.dto.LegalEditorDtos.DraftResponse;
import br.com.erpadv.dto.LegalEditorDtos.DraftSummary;
import br.com.erpadv.dto.LegalEditorDtos.FieldsResponse;
import br.com.erpadv.dto.LegalEditorDtos.SaveToCentralRequest;
import br.com.erpadv.dto.LegalEditorDtos.SnapshotRequest;
import br.com.erpadv.dto.LegalEditorDtos.TemplateRequest;
import br.com.erpadv.dto.LegalEditorDtos.TemplateResponse;
import br.com.erpadv.dto.LegalEditorDtos.UpdateRequest;
import br.com.erpadv.dto.LegalEditorDtos.VersionResponse;
import br.com.erpadv.dto.LegalEditorDtos.VersionSummary;
import br.com.erpadv.models.LegalDraft;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.LegalEditorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Editor Jurídico: leitura exige DOCUMENTS_READ; criação, edição e envio à central exigem DOCUMENTS_WRITE. */
@RestController
@RequestMapping("/api/legal-editor")
public class LegalEditorController {

    private final LegalEditorService editor;

    public LegalEditorController(LegalEditorService editor) {
        this.editor = editor;
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public List<TemplateResponse> templates(@AuthenticationPrincipal AuthenticatedUser user) {
        return editor.templates(user);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public TemplateResponse createTemplate(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody TemplateRequest req) {
        return editor.saveTemplate(user, null, req);
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public TemplateResponse updateTemplate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                           @Valid @RequestBody TemplateRequest req) {
        return editor.saveTemplate(user, id, req);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public void deleteTemplate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        editor.deleteTemplate(user, id);
    }

    // ------------------------------------------------------------------ papel timbrado

    @GetMapping("/letterheads")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public List<br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse> letterheads(@AuthenticationPrincipal AuthenticatedUser user) {
        return editor.letterheads(user);
    }

    @PostMapping(value = "/letterheads", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse createLetterhead(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @org.springframework.web.bind.annotation.RequestPart("meta") br.com.erpadv.dto.LegalEditorDtos.LetterheadMeta meta,
            @org.springframework.web.bind.annotation.RequestPart("image") org.springframework.web.multipart.MultipartFile image,
            @org.springframework.web.bind.annotation.RequestPart(value = "pdf", required = false) org.springframework.web.multipart.MultipartFile pdf) {
        return editor.createLetterhead(user, meta, DocumentController.bytes(image), image.getOriginalFilename(),
                pdf == null ? null : DocumentController.bytes(pdf), pdf == null ? null : pdf.getOriginalFilename());
    }

    @PutMapping("/letterheads/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse updateLetterhead(@AuthenticationPrincipal AuthenticatedUser user,
                                                                               @PathVariable String id,
                                                                               @Valid @RequestBody br.com.erpadv.dto.LegalEditorDtos.LetterheadMeta meta) {
        return editor.updateLetterhead(user, id, meta);
    }

    @DeleteMapping("/letterheads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public void deleteLetterhead(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        editor.deleteLetterhead(user, id);
    }

    @GetMapping("/letterheads/{id}/image")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public ResponseEntity<byte[]> letterheadImage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        var file = editor.letterheadImage(user, id);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(file.contentType()))
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }

    @GetMapping("/fields")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public FieldsResponse fields(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) String clientId,
                                 @RequestParam(required = false) String caseId,
                                 @RequestParam(required = false) String lawyerUid) {
        return editor.fields(user, clientId, caseId, lawyerUid);
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public List<DraftSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(required = false) LegalDraft.Status status,
                                   @RequestParam(required = false) String clientId,
                                   @RequestParam(required = false) String caseId,
                                   @RequestParam(required = false) String search) {
        return editor.list(user, status, clientId, caseId, search);
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public DraftResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return editor.get(user, id);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateRequest req) {
        return editor.create(user, req);
    }

    @PutMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                @Valid @RequestBody UpdateRequest req) {
        return editor.update(user, id, req);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        editor.delete(user, id);
    }

    @PostMapping("/documents/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse duplicate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return editor.duplicate(user, id);
    }

    @PostMapping("/documents/{id}/versions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse saveVersion(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @Valid @RequestBody SnapshotRequest req) {
        return editor.saveVersion(user, id, req.note());
    }

    @GetMapping("/documents/{id}/versions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public List<VersionSummary> versions(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return editor.versions(user, id);
    }

    @GetMapping("/documents/{id}/versions/{version}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public VersionResponse version(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @PathVariable int version) {
        return editor.version(user, id, version);
    }

    @PostMapping("/documents/{id}/versions/{version}/restore")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @PathVariable int version) {
        return editor.restoreVersion(user, id, version);
    }

    @PostMapping("/documents/{id}/finalize")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse finalizeDraft(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return editor.finalizeDraft(user, id);
    }

    @PostMapping("/documents/{id}/reopen")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DraftResponse reopen(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return editor.reopen(user, id);
    }

    @GetMapping("/documents/{id}/export")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                         @RequestParam(defaultValue = "pdf") String format) {
        return DashboardController.file(editor.export(user, id, format));
    }

    @PostMapping("/documents/{id}/save-to-central")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DocumentResponse saveToCentral(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                          @Valid @RequestBody SaveToCentralRequest req) {
        return editor.saveToCentral(user, id, req.format());
    }
}
