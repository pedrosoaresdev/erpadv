package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CaseDtos.CaseFilter;
import br.com.erpadv.dto.CaseDtos.CaseRequest;
import br.com.erpadv.dto.CaseDtos.CaseResponse;
import br.com.erpadv.dto.CaseDtos.MovementRequest;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.CaseMovement;
import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.LegalArea;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.MovementType;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseMovementRepository;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gestão processual. Processos criminais ou sigilosos ficam "restritos": só aparecem para quem
 * tem CRIMINAL_READ (para os demais, simplesmente não existem — 404).
 */
@Service
public class CaseService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final CaseRepository cases;
    private final CaseMovementRepository movements;
    private final ClientRepository clients;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;

    public CaseService(CaseRepository cases, CaseMovementRepository movements, ClientRepository clients,
                       UserRepository users, AuditService audit, NotificationService notifications) {
        this.cases = cases;
        this.movements = movements;
        this.clients = clients;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
    }

    public CursorPage<CaseResponse> list(AuthenticatedUser user, CaseFilter filter, String cursor, int size) {
        String officeId = user.requireOffice();
        Query query = cases.collection(officeId);
        boolean canSeeRestricted = user.has(Permission.CRIMINAL_READ);
        if (!canSeeRestricted) {
            if (filter.area() == LegalArea.CRIMINAL) {
                throw new ForbiddenException("CRIMINAL_ACCESS_DENIED", "Acesso restrito ao departamento criminal.");
            }
            query = query.whereEqualTo("restricted", false);
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            String token = searchToken(filter.search());
            if (token == null) {
                return new CursorPage<>(List.of(), null);
            }
            query = query.whereArrayContains("searchTokens", token);
        }
        if (filter.status() != null) {
            query = query.whereEqualTo("status", filter.status().name());
        } else if (!filter.includeArchived()) {
            query = query.whereEqualTo("archived", false);
        }
        if (filter.area() != null) {
            query = query.whereEqualTo("area", filter.area().name());
        }
        if (notBlank(filter.responsibleUid())) {
            query = query.whereEqualTo("responsibleUid", filter.responsibleUid());
        }
        if (notBlank(filter.court())) {
            query = query.whereEqualTo("court", filter.court().trim());
        }
        if (notBlank(filter.clientId())) {
            query = query.whereEqualTo("clientId", filter.clientId());
        }
        if (notBlank(filter.unitId())) {
            query = query.whereEqualTo("unitId", filter.unitId());
        }
        return cases.page(query, "createdAt", Query.Direction.DESCENDING, cursor, size).map(CaseResponse::of);
    }

    public CaseResponse get(AuthenticatedUser user, String id) {
        LegalCase legalCase = load(user, id);
        if (legalCase.isRestricted()) {
            audit.record(user, "CASE_RESTRICTED_VIEWED", "case", id, "Consulta a processo restrito");
        }
        return CaseResponse.of(legalCase);
    }

    public CaseResponse create(AuthenticatedUser user, CaseRequest req) {
        String officeId = user.requireOffice();
        if (req.status() == CaseStatus.ARQUIVADO) {
            throw new BusinessException("INVALID_STATUS", "Use a ação de arquivamento para arquivar processos.");
        }
        String cnj = normalizeCnj(req.cnjNumber());
        if (cnj != null) {
            cases.findByCnj(officeId, cnj).ifPresent(c -> {
                throw new ConflictException("CASE_DUPLICATE", "Já existe processo cadastrado com este número CNJ.");
            });
        }
        LegalCase legalCase = new LegalCase();
        apply(user, legalCase, req, cnj);
        Instant now = Instant.now();
        legalCase.setCreatedAt(now);
        legalCase.setUpdatedAt(now);
        legalCase.setCreatedBy(user.uid());
        legalCase.setUpdatedBy(user.uid());
        cases.save(officeId, legalCase);
        systemMovement(user, legalCase, "Processo cadastrado no sistema",
                "Situação inicial: " + legalCase.getStatus().label());
        audit.record(user, "CASE_CREATED", "case", legalCase.getId(), "Processo cadastrado",
                Map.of("cnj", cnj == null ? "" : cnj, "clientId", legalCase.getClientId()));
        notifyResponsible(user, legalCase);
        return CaseResponse.of(legalCase);
    }

    public CaseResponse update(AuthenticatedUser user, String id, CaseRequest req) {
        String officeId = user.requireOffice();
        LegalCase legalCase = load(user, id);
        Map<String, Object> auditBefore = audit.snapshot(legalCase);
        if (legalCase.isArchived()) {
            throw new BusinessException("CASE_ARCHIVED", "Processo arquivado. Desarquive para editar.");
        }
        if (req.status() == CaseStatus.ARQUIVADO) {
            throw new BusinessException("INVALID_STATUS", "Use a ação de arquivamento para arquivar processos.");
        }
        String cnj = normalizeCnj(req.cnjNumber());
        if (cnj != null && !cnj.equals(legalCase.getCnjNumber())) {
            cases.findByCnj(officeId, cnj).filter(c -> !c.getId().equals(id)).ifPresent(c -> {
                throw new ConflictException("CASE_DUPLICATE", "Já existe processo cadastrado com este número CNJ.");
            });
        }
        CaseStatus previousStatus = legalCase.getStatus();
        String previousResponsible = legalCase.getResponsibleName();
        String previousResponsibleUid = legalCase.getResponsibleUid();
        apply(user, legalCase, req, cnj);
        legalCase.setUpdatedAt(Instant.now());
        legalCase.setUpdatedBy(user.uid());
        cases.save(officeId, legalCase);
        if (previousStatus != legalCase.getStatus()) {
            systemMovement(user, legalCase, "Situação alterada",
                    previousStatus.label() + " → " + legalCase.getStatus().label());
        }
        if (previousResponsible != null && !previousResponsible.equals(legalCase.getResponsibleName())) {
            systemMovement(user, legalCase, "Responsável alterado",
                    previousResponsible + " → " + legalCase.getResponsibleName());
        }
        audit.recordChange(user, "CASE_UPDATED", "case", id, "Processo atualizado",
                Map.of(), auditBefore, legalCase);
        if (!legalCase.getResponsibleUid().equals(previousResponsibleUid)) {
            notifyResponsible(user, legalCase);
        }
        return CaseResponse.of(legalCase);
    }

    /** Duplica o cadastro (sem número CNJ, movimentações ou vínculos) — útil para processos conexos/recursos. */
    public CaseResponse duplicate(AuthenticatedUser user, String id) {
        LegalCase source = load(user, id);
        CaseRequest req = new CaseRequest(null, source.getClientId(), source.getOpposingParty(), source.getCourt(),
                source.getCourtDivision(), source.getDistrict(), source.getState(), source.getArea(),
                source.getProceduralClass(), source.getSubject(), source.getResponsibleUid(), source.getClaimValueCents(),
                null, CaseStatus.EM_ANDAMENTO, "Cadastro duplicado de " + (source.getCnjNumber() == null ? "processo sem número" : source.getCnjNumber())
                + (source.getNotes() == null ? "" : "\n\n" + source.getNotes()), source.isConfidential(),
                source.getActiveParty(), source.getPassiveParty(), source.getInstance(), source.getJudgingBody(),
                source.getSecondarySubjects(), source.isFreeJustice(), source.getPhase(), source.getUnitId());
        CaseResponse created = create(user, req);
        audit.record(user, "CASE_DUPLICATED", "case", created.id(), "Processo duplicado a partir de " + id, Map.of("sourceId", id));
        return created;
    }

    /** Coleções que impedem a exclusão (guarda obrigatória). */
    static final Map<String, String> LINKS = new java.util.LinkedHashMap<>(Map.of(
            "deadlines", "prazo(s)", "appointments", "compromisso(s)", "documents", "documento(s)",
            "financial_transactions", "lançamento(s) financeiro(s)", "contracts", "contrato(s)"));

    public Map<String, Long> links(AuthenticatedUser user, String id) {
        load(user, id);
        var office = cases.collection(user.officeId()).getParent();
        Map<String, com.google.api.core.ApiFuture<com.google.cloud.firestore.AggregateQuerySnapshot>> f = new java.util.LinkedHashMap<>();
        LINKS.keySet().forEach(col -> f.put(col, office.collection(col).whereEqualTo("caseId", id).count().get()));
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        f.forEach((k, v) -> counts.put(k, br.com.erpadv.firebase.Futures.await(v).getCount()));
        return counts;
    }

    /**
     * Exclusão definitiva conforme política de retenção: somente processos já arquivados e sem prazos,
     * compromissos, documentos, lançamentos ou contratos vinculados. A exclusão fica na auditoria.
     */
    public void delete(AuthenticatedUser user, String id, String reason) {
        LegalCase legalCase = load(user, id);
        if (!legalCase.isArchived()) {
            throw new BusinessException("CASE_NOT_ARCHIVED", "Arquive o processo antes de excluí-lo.");
        }
        List<String> blocking = links(user, id).entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getValue() + " " + LINKS.get(e.getKey())).toList();
        if (!blocking.isEmpty()) {
            throw new ConflictException("CASE_HAS_LINKS", "Processo com " + String.join(", ", blocking)
                    + ". Mantenha-o arquivado (obrigação de guarda).");
        }
        for (CaseMovement m : movements.list(movements.collection(user.officeId()).whereEqualTo("caseId", id), 500)) {
            br.com.erpadv.firebase.Futures.await(movements.collection(user.officeId()).document(m.getId()).delete());
        }
        br.com.erpadv.firebase.Futures.await(cases.collection(user.officeId()).document(id).delete());
        audit.record(user, "CASE_DELETED", "case", id, "Processo excluído: " + (legalCase.getCnjNumber() == null ? "sem número" : legalCase.getCnjNumber()),
                Map.of("reason", reason == null ? "" : reason, "clientId", legalCase.getClientId()));
    }

    /** Todas as movimentações (até 1000) para relatórios. */
    public List<CaseMovement> allMovements(AuthenticatedUser user, String id) {
        load(user, id);
        List<CaseMovement> all = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<CaseMovement> page = movements.byCase(user.officeId(), id, cursor, 100);
            all.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null && all.size() < 1000);
        return all;
    }

    public CaseResponse archive(AuthenticatedUser user, String id) {
        LegalCase legalCase = load(user, id);
        if (legalCase.isArchived()) {
            throw new BusinessException("CASE_ARCHIVED", "Processo já está arquivado.");
        }
        legalCase.setStatusBeforeArchive(legalCase.getStatus());
        legalCase.setStatus(CaseStatus.ARQUIVADO);
        legalCase.setArchived(true);
        legalCase.setArchivedAt(Instant.now());
        legalCase.setUpdatedAt(Instant.now());
        legalCase.setUpdatedBy(user.uid());
        cases.save(user.officeId(), legalCase);
        systemMovement(user, legalCase, "Processo arquivado", null);
        audit.record(user, "CASE_ARCHIVED", "case", id, "Processo arquivado");
        return CaseResponse.of(legalCase);
    }

    public CaseResponse unarchive(AuthenticatedUser user, String id) {
        LegalCase legalCase = load(user, id);
        if (!legalCase.isArchived()) {
            throw new BusinessException("CASE_NOT_ARCHIVED", "Processo não está arquivado.");
        }
        CaseStatus restored = legalCase.getStatusBeforeArchive() == null
                ? CaseStatus.EM_ANDAMENTO
                : legalCase.getStatusBeforeArchive();
        legalCase.setStatus(restored);
        legalCase.setStatusBeforeArchive(null);
        legalCase.setArchived(false);
        legalCase.setArchivedAt(null);
        legalCase.setUpdatedAt(Instant.now());
        legalCase.setUpdatedBy(user.uid());
        cases.save(user.officeId(), legalCase);
        systemMovement(user, legalCase, "Processo desarquivado", "Situação restaurada: " + restored.label());
        audit.record(user, "CASE_UNARCHIVED", "case", id, "Processo desarquivado");
        return CaseResponse.of(legalCase);
    }

    public CursorPage<CaseMovement> movements(AuthenticatedUser user, String caseId, String cursor, int size) {
        load(user, caseId);
        return movements.byCase(user.officeId(), caseId, cursor, size);
    }

    public CaseMovement addMovement(AuthenticatedUser user, String caseId, MovementRequest req) {
        LegalCase legalCase = load(user, caseId);
        if (req.type() == MovementType.SISTEMA) {
            throw new BusinessException("INVALID_MOVEMENT_TYPE", "Tipo reservado ao sistema.");
        }
        if (legalCase.isRestricted() && !user.has(Permission.CRIMINAL_WRITE)) {
            throw new ForbiddenException("CRIMINAL_ACCESS_DENIED", "Sem permissão para alterar processo restrito.");
        }
        CaseMovement movement = newMovement(user, legalCase, req.type(), req.date(), req.title().trim(),
                req.description() == null ? null : req.description().trim());
        movement.setImportant(req.important() || req.type() == MovementType.SENTENCA || req.type() == MovementType.ACORDAO
                || req.type() == MovementType.TRANSITO_JULGADO || req.type() == MovementType.DECISAO);
        movements.save(user.officeId(), movement);
        legalCase.setLastMovementAt(Instant.now());
        // Registros estruturados a partir das movimentações-chave.
        if (req.type() == MovementType.SENTENCA && legalCase.getSentenceDate() == null) {
            legalCase.setSentenceDate(req.date());
            if (legalCase.getPhase() == null || legalCase.getPhase().ordinal() < br.com.erpadv.models.CasePhase.DECISORIA.ordinal()) {
                legalCase.setPhase(br.com.erpadv.models.CasePhase.DECISORIA);
            }
        }
        if (req.type() == MovementType.RECURSO && legalCase.getStatus() == CaseStatus.EM_ANDAMENTO) {
            legalCase.setStatus(CaseStatus.EM_RECURSO);
            legalCase.setPhase(br.com.erpadv.models.CasePhase.RECURSAL);
        }
        if (req.type() == MovementType.TRANSITO_JULGADO) {
            legalCase.setFinalJudgmentDate(req.date());
        }
        if (req.type() == MovementType.SUSPENSAO && legalCase.getStatus().isActive()) {
            legalCase.setStatus(CaseStatus.SUSPENSO);
        }
        if (movement.isImportant() && legalCase.getResponsibleUid() != null && !legalCase.getResponsibleUid().equals(user.uid())) {
            notifications.notify(user.officeId(), legalCase.getResponsibleUid(), "CASE_MOVEMENT",
                    req.type().label() + " registrada no processo",
                    (legalCase.getCnjNumber() == null ? legalCase.getClientName() : legalCase.getCnjNumber()) + " — " + movement.getTitle(),
                    "/processos/" + legalCase.getId());
        }
        legalCase.setUpdatedAt(Instant.now());
        legalCase.setUpdatedBy(user.uid());
        cases.save(user.officeId(), legalCase);
        audit.record(user, "CASE_MOVEMENT_ADDED", "case", caseId, req.type().label() + ": " + movement.getTitle());
        return movement;
    }

    public LegalCase load(AuthenticatedUser user, String id) {
        LegalCase legalCase = cases.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Processo"));
        if (legalCase.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
            throw new NotFoundException("Processo");
        }
        return legalCase;
    }

    // ------------------------------------------------------------------ helpers

    private void notifyResponsible(AuthenticatedUser actor, LegalCase c) {
        if (c.getResponsibleUid() == null || c.getResponsibleUid().equals(actor.uid())) {
            return;
        }
        String number = c.getCnjNumber() == null ? "sem número CNJ" : c.getCnjNumber();
        notifications.notify(actor.officeId(), c.getResponsibleUid(), "CASE_ASSIGNED",
                "Novo processo sob sua responsabilidade",
                c.getClientName() + " · " + number + " — designado por " + actor.displayName(),
                "/processos/" + c.getId());
    }

    private void apply(AuthenticatedUser user, LegalCase c, CaseRequest req, String cnj) {
        String officeId = user.officeId();
        if (notBlank(req.unitId())) {
            var unit = br.com.erpadv.firebase.Futures.await(cases.collection(officeId).getParent().collection("units").document(req.unitId()).get());
            if (!unit.exists()) {
                throw new br.com.erpadv.exceptions.BusinessException("INVALID_UNIT", "Unidade não encontrada.");
            }
            c.setUnitId(req.unitId());
            c.setUnitName(unit.getString("name"));
        } else {
            c.setUnitId(null);
            c.setUnitName(null);
        }
        boolean restricted = req.area() == LegalArea.CRIMINAL || req.confidential();
        if (restricted && !user.has(Permission.CRIMINAL_WRITE)) {
            throw new ForbiddenException("CRIMINAL_ACCESS_DENIED",
                    "Processos criminais ou sigilosos exigem permissão do departamento criminal.");
        }
        Client client = clients.findById(officeId, req.clientId())
                .orElseThrow(() -> new BusinessException("INVALID_CLIENT", "Cliente não encontrado."));
        UserProfile responsible = users.findInOffice(officeId, req.responsibleUid())
                .filter(u -> u.getRole() != null && u.getRole().isLawyer())
                .orElseThrow(() -> new BusinessException("INVALID_RESPONSIBLE",
                        "O responsável deve ser um advogado do escritório."));
        if (req.distributionDate() != null && req.distributionDate().isAfter(LocalDate.now(ZONE))) {
            throw new BusinessException("INVALID_DATE", "Data de distribuição não pode ser futura.");
        }
        c.setCnjNumber(cnj);
        c.setClientId(client.getId());
        c.setClientName(client.getName());
        c.setOpposingParty(trim(req.opposingParty()));
        c.setCourt(trim(req.court()));
        c.setCourtDivision(trim(req.courtDivision()));
        c.setDistrict(trim(req.district()));
        c.setState(trim(req.state()));
        c.setArea(req.area());
        c.setProceduralClass(trim(req.proceduralClass()));
        c.setSubject(trim(req.subject()));
        c.setResponsibleUid(responsible.getId());
        c.setResponsibleName(responsible.getDisplayName());
        c.setClaimValueCents(req.claimValueCents());
        c.setDistributionDate(req.distributionDate());
        c.setStatus(req.status());
        c.setNotes(trim(req.notes()));
        c.setConfidential(req.confidential());
        c.setRestricted(restricted);
        c.setActiveParty(trim(req.activeParty()));
        c.setPassiveParty(trim(req.passiveParty()));
        c.setInstance(trim(req.instance()));
        c.setJudgingBody(trim(req.judgingBody()));
        c.setSecondarySubjects(req.secondarySubjects() == null ? new ArrayList<>()
                : req.secondarySubjects().stream().filter(this::notBlankStr).map(String::trim).distinct().toList());
        c.setFreeJustice(req.freeJustice());
        c.setPhase(req.phase());
        c.setLawyerOab(responsible.getOabNumber());
        // Histórico de responsáveis: fecha o período anterior e abre o novo.
        List<br.com.erpadv.models.ResponsibleChange> history = new ArrayList<>(c.getResponsibleHistory() == null ? List.of() : c.getResponsibleHistory());
        br.com.erpadv.models.ResponsibleChange current = history.isEmpty() ? null : history.get(history.size() - 1);
        if (current == null || !responsible.getId().equals(current.getUid())) {
            Instant now = Instant.now();
            if (current != null && current.getTo() == null) {
                current.setTo(now);
            }
            br.com.erpadv.models.ResponsibleChange change = new br.com.erpadv.models.ResponsibleChange();
            change.setUid(responsible.getId());
            change.setName(responsible.getDisplayName());
            change.setFrom(now);
            change.setChangedBy(user.uid());
            history.add(change);
        }
        c.setResponsibleHistory(history);
        c.setSearchTokens(buildTokens(c));
    }

    private List<String> buildTokens(LegalCase c) {
        Set<String> tokens = new LinkedHashSet<>();
        if (c.getCnjNumber() != null) {
            tokens.add("c:" + BrazilianDocuments.digits(c.getCnjNumber()));
        }
        tokens.addAll(SearchTokens.forText(c.getClientName()));
        tokens.addAll(SearchTokens.forText(c.getOpposingParty()));
        tokens.addAll(SearchTokens.forText(c.getActiveParty()));
        tokens.addAll(SearchTokens.forText(c.getPassiveParty()));
        tokens.addAll(SearchTokens.forText(c.getSubject()));
        List<String> list = new ArrayList<>(tokens);
        return list.subList(0, Math.min(list.size(), 400));
    }

    String searchToken(String term) {
        String digits = BrazilianDocuments.digits(term);
        if (digits.length() == 20) {
            return "c:" + digits;
        }
        return SearchTokens.queryToken(term);
    }

    private String normalizeCnj(String cnj) {
        if (cnj == null || cnj.isBlank()) {
            return null;
        }
        return BrazilianDocuments.formatCnj(cnj);
    }

    private void systemMovement(AuthenticatedUser user, LegalCase c, String title, String description) {
        movements.save(user.officeId(), newMovement(user, c, MovementType.SISTEMA, LocalDate.now(ZONE), title, description));
    }

    private CaseMovement newMovement(AuthenticatedUser user, LegalCase c, MovementType type, LocalDate date,
                                     String title, String description) {
        CaseMovement m = new CaseMovement();
        m.setCaseId(c.getId());
        m.setType(type);
        m.setDate(date);
        m.setTitle(title);
        m.setDescription(description);
        m.setSource("MANUAL");
        m.setAuthorUid(user.uid());
        m.setAuthorName(user.displayName());
        Instant now = Instant.now();
        m.setCreatedAt(now);
        m.setCreatedBy(user.uid());
        m.setSortKey(date + "T" + String.format("%013d", now.toEpochMilli()));
        return m;
    }

    private boolean notBlankStr(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
