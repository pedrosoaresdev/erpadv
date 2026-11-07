package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.ClientDtos.ClientResponse;
import br.com.erpadv.dto.CrmDtos.ActivityRequest;
import br.com.erpadv.dto.CrmDtos.ConvertResponse;
import br.com.erpadv.dto.CrmDtos.CrmMetrics;
import br.com.erpadv.dto.CrmDtos.LeadDetail;
import br.com.erpadv.dto.CrmDtos.LeadRequest;
import br.com.erpadv.dto.CrmDtos.LeadResponse;
import br.com.erpadv.dto.CrmDtos.MonthMetric;
import br.com.erpadv.dto.CrmDtos.OwnerMetric;
import br.com.erpadv.dto.CrmDtos.SourceMetric;
import br.com.erpadv.dto.CrmDtos.StageMetric;
import br.com.erpadv.dto.ClientDtos.ClientRequest;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.CrmEnums.LeadActivityType;
import br.com.erpadv.models.CrmEnums.LeadSource;
import br.com.erpadv.models.CrmEnums.LeadStage;
import br.com.erpadv.models.Lead;
import br.com.erpadv.models.LeadActivity;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CrmRepositories;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * CRM comercial: funil de captação com etapas, valor estimado, próxima ação, histórico de
 * interações, pontuação automática (lead score), conversão em cliente e métricas de conversão.
 */
@Service
public class CrmService {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final CrmRepositories.Leads leads;
    private final CrmRepositories.Activities activities;
    private final UserRepository users;
    private final ClientService clientService;
    private final NotificationService notifications;
    private final AuditService audit;

    public CrmService(CrmRepositories.Leads leads, CrmRepositories.Activities activities, UserRepository users,
                      ClientService clientService, NotificationService notifications, AuditService audit) {
        this.leads = leads;
        this.activities = activities;
        this.users = users;
        this.clientService = clientService;
        this.notifications = notifications;
        this.audit = audit;
    }

    public List<LeadResponse> list(AuthenticatedUser user, LeadStage stage, String ownerUid, String search,
                                   boolean onlyOpen) {
        String officeId = user.requireOffice();
        LocalDate today = LocalDate.now(ZONE);
        Instant now = Instant.now();
        String term = search == null ? "" : SearchTokens.normalize(search);
        String digits = search == null ? "" : SearchTokens.digits(search);
        return leads.recent(officeId).stream()
                .filter(l -> stage == null || l.getStage() == stage)
                .filter(l -> !onlyOpen || l.getStage().isOpen())
                .filter(l -> ownerUid == null || ownerUid.isBlank() || ownerUid.equals(l.getOwnerUid()))
                .filter(l -> term.isBlank() || matches(l, term, digits))
                .map(l -> response(l, today, now))
                .toList();
    }

    public LeadDetail get(AuthenticatedUser user, String id) {
        Lead lead = load(user, id);
        return new LeadDetail(response(lead, LocalDate.now(ZONE), Instant.now()), activities.byLead(user.officeId(), id));
    }

    public LeadResponse create(AuthenticatedUser user, LeadRequest req) {
        String officeId = user.requireOffice();
        Instant now = Instant.now();
        Lead lead = new Lead();
        apply(user, lead, req);
        LeadStage stage = req.stage() == null ? LeadStage.NOVO : req.stage();
        if (!stage.isOpen()) {
            throw new BusinessException("INVALID_STAGE", "Cadastre a oportunidade em uma etapa aberta do funil.");
        }
        lead.setStage(stage);
        lead.setStageEnteredAt(now);
        lead.setLastActivityAt(now);
        lead.setCreatedAt(now);
        lead.setUpdatedAt(now);
        lead.setCreatedBy(user.uid());
        leads.save(officeId, lead);
        activity(user, lead, LeadActivityType.ETAPA, "Oportunidade cadastrada em \"" + stage.label() + "\"");
        if (lead.getOwnerUid() != null && !lead.getOwnerUid().equals(user.uid())) {
            notifications.notify(officeId, lead.getOwnerUid(), "CRM_LEAD", "Nova oportunidade para você",
                    lead.getName() + (lead.getSubject() == null ? "" : " — " + lead.getSubject()), "/crm?lead=" + lead.getId());
        }
        audit.record(user, "LEAD_CREATED", "lead", lead.getId(), "Oportunidade cadastrada: " + lead.getName());
        return response(lead, LocalDate.now(ZONE), now);
    }

    public LeadResponse update(AuthenticatedUser user, String id, LeadRequest req) {
        Lead lead = load(user, id);
        String previousOwner = lead.getOwnerUid();
        apply(user, lead, req);
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(user.uid());
        leads.save(user.officeId(), lead);
        if (lead.getOwnerUid() != null && !Objects.equals(previousOwner, lead.getOwnerUid())
                && !lead.getOwnerUid().equals(user.uid())) {
            notifications.notify(user.officeId(), lead.getOwnerUid(), "CRM_LEAD", "Oportunidade transferida para você",
                    lead.getName(), "/crm?lead=" + lead.getId());
            activity(user, lead, LeadActivityType.NOTA, "Responsável alterado para " + lead.getOwnerName());
        }
        return response(lead, LocalDate.now(ZONE), Instant.now());
    }

    public LeadResponse changeStage(AuthenticatedUser user, String id, LeadStage stage, String lostReason) {
        Lead lead = load(user, id);
        if (lead.getConvertedClientId() != null) {
            throw new BusinessException("LEAD_CONVERTED", "Oportunidade já convertida em cliente.");
        }
        if (lead.getStage() == stage) {
            return response(lead, LocalDate.now(ZONE), Instant.now());
        }
        if (stage == LeadStage.PERDIDO && (lostReason == null || lostReason.isBlank())) {
            throw new BusinessException("LOST_REASON_REQUIRED", "Informe o motivo da perda.");
        }
        LeadStage previous = lead.getStage();
        Instant now = Instant.now();
        lead.setStage(stage);
        lead.setStageEnteredAt(now);
        lead.setLastActivityAt(now);
        lead.setWonAt(stage == LeadStage.GANHO ? now : null);
        lead.setLostAt(stage == LeadStage.PERDIDO ? now : null);
        lead.setLostReason(stage == LeadStage.PERDIDO ? lostReason.trim() : null);
        lead.setUpdatedAt(now);
        lead.setUpdatedBy(user.uid());
        leads.save(user.officeId(), lead);
        activity(user, lead, LeadActivityType.ETAPA, "Etapa: " + previous.label() + " → " + stage.label()
                + (stage == LeadStage.PERDIDO ? " (motivo: " + lostReason.trim() + ")" : ""));
        audit.record(user, "LEAD_STAGE_CHANGED", "lead", id, "Oportunidade " + lead.getName() + ": " + stage.label(),
                Map.of("from", previous.name(), "to", stage.name()));
        return response(lead, LocalDate.now(ZONE), now);
    }

    public LeadDetail addActivity(AuthenticatedUser user, String id, ActivityRequest req) {
        Lead lead = load(user, id);
        if (req.type() == LeadActivityType.ETAPA || req.type() == LeadActivityType.CONVERSAO) {
            throw new BusinessException("INVALID_ACTIVITY", "Tipo de interação reservado ao sistema.");
        }
        activity(user, lead, req.type(), req.text().trim());
        lead.setLastActivityAt(Instant.now());
        if (req.nextAction() != null || req.nextActionDate() != null) {
            lead.setNextAction(blankToNull(req.nextAction()));
            lead.setNextActionDate(req.nextActionDate());
        }
        lead.setUpdatedAt(Instant.now());
        lead.setUpdatedBy(user.uid());
        leads.save(user.officeId(), lead);
        return get(user, id);
    }

    /** Converte a oportunidade em cliente (cadastro completo com CPF/CNPJ) e marca como ganha. */
    public ConvertResponse convert(AuthenticatedUser user, String id, ClientRequest clientRequest) {
        if (!user.has(Permission.CLIENTS_WRITE)) {
            throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para cadastrar clientes.");
        }
        Lead lead = load(user, id);
        if (lead.getConvertedClientId() != null) {
            throw new ConflictException("LEAD_CONVERTED", "Oportunidade já convertida em cliente.");
        }
        if (lead.getStage() == LeadStage.PERDIDO) {
            throw new BusinessException("LEAD_LOST", "Reabra a oportunidade antes de convertê-la.");
        }
        ClientRequest r = clientRequest;
        if ((r.source() == null || r.source().isBlank()) && lead.getSource() != null) {
            // O cliente herda a origem da oportunidade (indicadores "origem dos novos clientes").
            r = new ClientRequest(r.type(), r.name(), r.tradeName(), r.document(), r.rg(), r.birthDate(), r.phone(), r.whatsapp(),
                    r.email(), r.address(), r.profession(), r.maritalStatus(), r.notes(),
                    r.responsibleUid() == null ? lead.getOwnerUid() : r.responsibleUid(), r.nationality(), r.stateRegistration(),
                    r.municipalRegistration(), r.legalRepresentative(), r.legalRepresentativeRole(), r.contacts(),
                    lead.getSource().name(), r.sourceDetail() == null ? lead.getSourceDetail() : r.sourceDetail());
        }
        ClientResponse client = clientService.create(user, r);
        Instant now = Instant.now();
        LeadStage previous = lead.getStage();
        lead.setConvertedClientId(client.id());
        lead.setStage(LeadStage.GANHO);
        lead.setWonAt(now);
        lead.setStageEnteredAt(now);
        lead.setLastActivityAt(now);
        lead.setUpdatedAt(now);
        lead.setUpdatedBy(user.uid());
        leads.save(user.officeId(), lead);
        activity(user, lead, LeadActivityType.CONVERSAO, "Convertida em cliente (" + previous.label() + " → Ganho)");
        audit.record(user, "LEAD_CONVERTED", "lead", id, "Oportunidade convertida em cliente: " + lead.getName(),
                Map.of("clientId", client.id()));
        return new ConvertResponse(response(lead, LocalDate.now(ZONE), now), client.id());
    }

    /** Exclusão (direito de eliminação — LGPD). Oportunidades convertidas ficam no histórico do cliente. */
    public void delete(AuthenticatedUser user, String id) {
        Lead lead = load(user, id);
        if (lead.getConvertedClientId() != null) {
            throw new BusinessException("LEAD_CONVERTED", "Oportunidade convertida não pode ser excluída.");
        }
        if (!user.uid().equals(lead.getOwnerUid()) && !user.uid().equals(lead.getCreatedBy())
                && user.role() != Role.SOCIO_ADMIN) {
            throw new ForbiddenException("LEAD_DELETE_DENIED", "Somente o responsável ou o sócio administrador exclui.");
        }
        activities.allOfLead(user.officeId(), id).forEach(a -> activities.delete(user.officeId(), a.getId()));
        leads.delete(user.officeId(), id);
        audit.record(user, "LEAD_DELETED", "lead", id, "Oportunidade excluída: " + lead.getName());
    }

    public CrmMetrics metrics(AuthenticatedUser user, int months) {
        String officeId = user.requireOffice();
        return computeMetrics(leads.recent(officeId), Math.max(1, Math.min(months, 24)), LocalDate.now(ZONE), Instant.now());
    }

    // ------------------------------------------------------------------ cálculos

    static CrmMetrics computeMetrics(List<Lead> all, int months, LocalDate today, Instant now) {
        List<Lead> open = all.stream().filter(l -> l.getStage().isOpen()).toList();
        long pipeline = open.stream().mapToLong(Lead::getEstimatedValueCents).sum();
        long weighted = open.stream().mapToLong(l -> l.getEstimatedValueCents() * l.getStage().probability() / 100).sum();
        long won = all.stream().filter(l -> l.getStage() == LeadStage.GANHO).count();
        long lost = all.stream().filter(l -> l.getStage() == LeadStage.PERDIDO).count();
        double conversion = won + lost == 0 ? 0 : round1(100d * won / (won + lost));
        double avgDays = all.stream().filter(l -> l.getWonAt() != null && l.getCreatedAt() != null)
                .mapToDouble(l -> Duration.between(l.getCreatedAt(), l.getWonAt()).toHours() / 24d).average().orElse(0);
        long overdue = open.stream().filter(l -> l.getNextActionDate() != null && l.getNextActionDate().isBefore(today)).count();
        long hot = open.stream().filter(l -> score(l, today, now) >= 70).count();

        List<StageMetric> funnel = Arrays.stream(LeadStage.values())
                .map(s -> new StageMetric(s, s.label(), all.stream().filter(l -> l.getStage() == s).count(),
                        all.stream().filter(l -> l.getStage() == s).mapToLong(Lead::getEstimatedValueCents).sum()))
                .toList();

        List<SourceMetric> sources = new ArrayList<>();
        for (LeadSource s : LeadSource.values()) {
            List<Lead> of = all.stream().filter(l -> l.getSource() == s).toList();
            if (of.isEmpty()) {
                continue;
            }
            long w = of.stream().filter(l -> l.getStage() == LeadStage.GANHO).count();
            long closed = of.stream().filter(l -> !l.getStage().isOpen()).count();
            sources.add(new SourceMetric(s, s.label(), of.size(), w, closed == 0 ? 0 : round1(100d * w / closed)));
        }
        sources.sort(Comparator.comparingLong(SourceMetric::leads).reversed());

        Map<String, OwnerMetric> owners = new LinkedHashMap<>();
        for (Lead l : all) {
            if (l.getOwnerUid() == null) {
                continue;
            }
            OwnerMetric m = owners.getOrDefault(l.getOwnerUid(), new OwnerMetric(l.getOwnerUid(), l.getOwnerName(), 0, 0, 0));
            boolean isWon = l.getStage() == LeadStage.GANHO;
            owners.put(l.getOwnerUid(), new OwnerMetric(m.uid(), m.name(), m.open() + (l.getStage().isOpen() ? 1 : 0),
                    m.won() + (isWon ? 1 : 0), m.wonValueCents() + (isWon ? l.getEstimatedValueCents() : 0)));
        }
        List<OwnerMetric> ownerList = owners.values().stream()
                .sorted(Comparator.comparingLong(OwnerMetric::wonValueCents).thenComparingLong(OwnerMetric::open).reversed())
                .toList();

        List<MonthMetric> monthList = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = current.minusMonths(i);
            long created = all.stream().filter(l -> l.getCreatedAt() != null
                    && YearMonth.from(l.getCreatedAt().atZone(ZONE)).equals(ym)).count();
            List<Lead> wonIn = all.stream().filter(l -> l.getWonAt() != null
                    && YearMonth.from(l.getWonAt().atZone(ZONE)).equals(ym)).toList();
            monthList.add(new MonthMetric(ym.toString(), created, wonIn.size(),
                    wonIn.stream().mapToLong(Lead::getEstimatedValueCents).sum()));
        }

        List<String> reasons = all.stream().filter(l -> l.getLostReason() != null)
                .collect(Collectors.groupingBy(l -> l.getLostReason().trim().toLowerCase(), Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(e -> e.getKey() + " (" + e.getValue() + ")").toList();

        return new CrmMetrics(open.size(), pipeline, weighted, won, lost, conversion, round1(avgDays), overdue, hot,
                funnel, sources, ownerList, monthList, reasons);
    }

    /**
     * Pontuação 0–100: etapa do funil, valor estimado, origem, recência da última interação e próxima
     * ação em dia. Ajuda a priorizar quem atender primeiro.
     */
    static int score(Lead lead, LocalDate today, Instant now) {
        if (!lead.getStage().isOpen()) {
            return lead.getStage() == LeadStage.GANHO ? 100 : 0;
        }
        int score = switch (lead.getStage()) {
            case NOVO -> 10;
            case CONTATO -> 25;
            case QUALIFICADO -> 45;
            case PROPOSTA -> 60;
            case NEGOCIACAO -> 75;
            default -> 0;
        };
        if (lead.getEstimatedValueCents() >= 1_000_000) {
            score += 10;
        } else if (lead.getEstimatedValueCents() >= 300_000) {
            score += 5;
        }
        if (lead.getSource() == LeadSource.INDICACAO || lead.getSource() == LeadSource.CLIENTE_ATUAL) {
            score += 10;
        }
        if (lead.getLastActivityAt() != null) {
            long idle = Duration.between(lead.getLastActivityAt(), now).toDays();
            if (idle <= 3) {
                score += 10;
            } else if (idle > 14) {
                score -= 15;
            }
        }
        if (lead.getNextActionDate() == null) {
            score -= 5;
        } else if (lead.getNextActionDate().isBefore(today)) {
            score -= 10;
        }
        return Math.max(0, Math.min(100, score));
    }

    static String temperature(int score) {
        return score >= 70 ? "QUENTE" : score >= 40 ? "MORNO" : "FRIO";
    }

    static LeadResponse response(Lead l, LocalDate today, Instant now) {
        int score = score(l, today, now);
        return new LeadResponse(l.getId(), l.getName(), l.getType(), l.getCompany(), l.getPhone(), l.getEmail(),
                l.getSource(), l.getSource() == null ? null : l.getSource().label(), l.getSourceDetail(),
                l.getLegalArea(), l.getSubject(), l.getStage(), l.getStage().label(), l.getStage().probability(),
                l.getEstimatedValueCents(), l.getOwnerUid(), l.getOwnerName(), l.getNextAction(), l.getNextActionDate(),
                l.getStage().isOpen() && l.getNextActionDate() != null && l.getNextActionDate().isBefore(today),
                l.getNotes(), l.getLostReason(), l.getTags(), score, temperature(score),
                l.getStageEnteredAt() == null ? 0 : ChronoUnit.DAYS.between(l.getStageEnteredAt(), now),
                l.getStageEnteredAt(), l.getLastActivityAt(), l.getWonAt(), l.getLostAt(), l.getConvertedClientId(),
                l.getCreatedAt());
    }

    // ------------------------------------------------------------------ helpers

    private void apply(AuthenticatedUser user, Lead lead, LeadRequest req) {
        lead.setName(req.name().trim());
        lead.setNameLower(SearchTokens.normalize(req.name()));
        lead.setType(req.type());
        lead.setCompany(blankToNull(req.company()));
        lead.setPhone(blankToNull(req.phone()));
        lead.setEmail(blankToNull(req.email()) == null ? null : req.email().trim().toLowerCase());
        lead.setSource(req.source());
        lead.setSourceDetail(blankToNull(req.sourceDetail()));
        lead.setLegalArea(req.legalArea());
        lead.setSubject(blankToNull(req.subject()));
        lead.setEstimatedValueCents(req.estimatedValueCents());
        lead.setNextAction(blankToNull(req.nextAction()));
        lead.setNextActionDate(req.nextActionDate());
        lead.setNotes(blankToNull(req.notes()));
        lead.setTags(req.tags() == null ? new ArrayList<>()
                : req.tags().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList());
        String ownerUid = req.ownerUid() == null || req.ownerUid().isBlank() ? user.uid() : req.ownerUid();
        UserProfile owner = users.findInOffice(user.officeId(), ownerUid)
                .filter(u -> u.getRole() != null && u.getRole().isStaff())
                .orElseThrow(() -> new BusinessException("INVALID_OWNER", "Responsável não encontrado no escritório."));
        lead.setOwnerUid(owner.getId());
        lead.setOwnerName(owner.getDisplayName());
    }

    private void activity(AuthenticatedUser user, Lead lead, LeadActivityType type, String text) {
        LeadActivity a = new LeadActivity();
        a.setLeadId(lead.getId());
        a.setType(type);
        a.setText(text);
        a.setActorUid(user.uid());
        a.setActorName(user.displayName());
        a.setAt(Instant.now());
        a.setCreatedAt(a.getAt());
        a.setCreatedBy(user.uid());
        activities.save(user.officeId(), a);
    }

    private Lead load(AuthenticatedUser user, String id) {
        return leads.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Oportunidade"));
    }

    private static boolean matches(Lead l, String term, String digits) {
        if (l.getNameLower() != null && l.getNameLower().contains(term)) {
            return true;
        }
        if (l.getCompany() != null && SearchTokens.normalize(l.getCompany()).contains(term)) {
            return true;
        }
        if (l.getEmail() != null && l.getEmail().contains(term)) {
            return true;
        }
        return digits.length() >= 4 && l.getPhone() != null && SearchTokens.digits(l.getPhone()).contains(digits);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10d;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
