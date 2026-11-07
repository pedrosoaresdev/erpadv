package br.com.erpadv.controllers;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.DocumentDtos.DocumentFilter;
import br.com.erpadv.dto.DocumentDtos.DocumentMeta;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.dto.DocumentDtos.FileContent;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.models.DocumentAccessLog;
import br.com.erpadv.models.DocumentCategory;
import br.com.erpadv.models.DocumentVersion;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.DocumentService;
import jakarta.validation.Valid;
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
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public CursorPage<DocumentResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam(required = false) String search,
                                             @RequestParam(required = false) DocumentCategory category,
                                             @RequestParam(required = false) String clientId,
                                             @RequestParam(required = false) String caseId,
                                             @RequestParam(defaultValue = "false") boolean includeArchived,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(defaultValue = "30") int size) {
        return documents.list(user, new DocumentFilter(search, category, clientId, caseId, includeArchived), cursor, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public DocumentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return documents.get(user, id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DocumentResponse upload(@AuthenticationPrincipal AuthenticatedUser user,
                                   @Valid @RequestPart("meta") DocumentMeta meta,
                                   @RequestPart("file") MultipartFile file) {
        return documents.upload(user, meta, bytes(file), file.getOriginalFilename());
    }

    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DocumentResponse newVersion(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) String note) {
        return documents.newVersion(user, id, bytes(file), file.getOriginalFilename(), note);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DocumentResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody DocumentMeta meta) {
        return documents.update(user, id, meta);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DocumentResponse archive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return documents.setArchived(user, id, true);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_WRITE')")
    public DocumentResponse unarchive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return documents.setArchived(user, id, false);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public List<DocumentVersion> versions(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return documents.versions(user, id);
    }

    @GetMapping("/{id}/access-log")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public CursorPage<DocumentAccessLog> accessLog(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "30") int size) {
        return documents.accessLog(user, id, cursor, size);
    }

    /**
     * Conteúdo autenticado (sem URLs públicas). {@code download=false} serve inline para visualização
     * (PDF/imagens) e registra VIEW; {@code download=true} registra DOWNLOAD.
     */
    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('PERM_DOCUMENTS_READ')")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                          @RequestParam(required = false) Integer version,
                                          @RequestParam(defaultValue = "false") boolean download) {
        FileContent file = documents.content(user, id, version, download);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(file.bytes());
    }

    static byte[] bytes(MultipartFile file) {
        if (file.getSize() > DocumentService.MAX_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE", "Arquivo excede o limite de 25 MB.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("UPLOAD_FAILED", "Não foi possível ler o arquivo enviado.");
        }
    }
}
