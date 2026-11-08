package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.ProposalDtos.DecisionRequest;
import br.com.erpadv.dto.ProposalDtos.ProposalRequest;
import br.com.erpadv.dto.ProposalDtos.ProposalResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.CrmEnums.LeadActivityType;
import br.com.erpadv.models.CrmEnums.LeadStage;
import br.com.erpadv.models.Lead;
import br.com.erpadv.models.LeadActivity;
import br.com.erpadv.models.LeadProposal;
import br.com.erpadv.models.Office;
import br.com.erpadv.repositories.CrmRepositories;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ValorExtenso;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Propostas de honorários do CRM: rascunho → enviada (a oportunidade avança para "Proposta enviada") → aceita
 * ("Aguardando decisão" → "Contrato enviado") ou recusada. PDF no papel timbrado padrão do escritório.
 */
@Service
public class ProposalService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT_BR);

    private final CrmRepositories.Proposals proposals;
    private final CrmRepositories.Leads leads;
    private final CrmRepositories.Activities activities;
    private final CrmService crm;
    private final OfficeRepository offices;
    private final LegalEditorService editor;
    private final AuditService audit;

    public ProposalService(CrmRepositories.Proposals proposals, CrmRepositories.Leads leads, CrmRepositories.Activities activities,
                           CrmService crm, OfficeRepository offices, LegalEditorService editor, AuditService audit) {
        this.proposals = proposals;
        this.leads = leads;
        this.activities = activities;
        this.crm = crm;
        this.offices = offices;
        this.editor = editor;
        this.audit = audit;
    }

    public List<ProposalResponse> list(AuthenticatedUser user, String leadId) {
        crm.get(user, leadId);
        return proposals.list(proposals.collection(user.requireOffice()).whereEqualTo("leadId", leadId), 100).stream()
                .sorted(Comparator.comparing(LeadProposal::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ProposalResponse::of).toList();
    }

    public ProposalResponse create(AuthenticatedUser user, String leadId, ProposalRequest req) {
        Lead lead = lead(user, leadId);
        LeadProposal p = new LeadProposal();
        p.setId(UUID.randomUUID().toString());
        p.setLeadId(lead.getId());
        p.setLeadName(lead.getName());
        int seq = proposals.list(proposals.collection(user.officeId()).whereEqualTo("leadId", leadId), 100).size() + 1;
        p.setNumber("PROP-" + lead.getId().substring(0, Math.min(5, lead.getId().length())).toUpperCase(Locale.ROOT) + "-" + seq);
        apply(p, req);
        p.setCreatedByName(user.displayName());
        p.setCreatedAt(Instant.now());
        p.setCreatedBy(user.uid());
        p.setUpdatedAt(p.getCreatedAt());
        p.setUpdatedBy(user.uid());
        proposals.save(user.officeId(), p);
        audit.record(user, "PROPOSAL_CREATED", "lead", leadId, "Proposta " + p.getNumber() + " criada para " + lead.getName());
        return ProposalResponse.of(p);
    }

    public ProposalResponse update(AuthenticatedUser user, String id, ProposalRequest req) {
        LeadProposal p = load(user, id);
        if (p.getStatus() != LeadProposal.Status.RASCUNHO) {
            throw new BusinessException("PROPOSAL_SENT", "Proposta já enviada: crie uma nova versão.");
        }
        apply(p, req);
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(user.uid());
        proposals.save(user.officeId(), p);
        return ProposalResponse.of(p);
    }

    public void delete(AuthenticatedUser user, String id) {
        LeadProposal p = load(user, id);
        if (p.getStatus() != LeadProposal.Status.RASCUNHO) {
            throw new BusinessException("PROPOSAL_SENT", "Propostas enviadas ficam no histórico da oportunidade.");
        }
        proposals.delete(user.officeId(), id);
    }

    /** Marca como enviada (o envio em si é feito pelo escritório) e avança a oportunidade, se ainda estiver antes. */
    public ProposalResponse send(AuthenticatedUser user, String id) {
        LeadProposal p = load(user, id);
        if (p.getStatus() != LeadProposal.Status.RASCUNHO) {
            throw new BusinessException("PROPOSAL_SENT", "Proposta já enviada.");
        }
        p.setStatus(LeadProposal.Status.ENVIADA);
        p.setSentAt(Instant.now());
        proposals.save(user.officeId(), p);
        Lead lead = lead(user, p.getLeadId());
        if (lead.getStage().isOpen() && lead.getStage().ordinal() < LeadStage.PROPOSTA.ordinal()) {
            crm.changeStage(user, lead.getId(), LeadStage.PROPOSTA, null);
        }
        activity(user, lead, "Proposta " + p.getNumber() + " enviada — " + FinanceSetupService.brl(p.getValueCents()));
        audit.record(user, "PROPOSAL_SENT", "lead", lead.getId(), "Proposta " + p.getNumber() + " enviada para " + lead.getName());
        return ProposalResponse.of(p);
    }

    public ProposalResponse decide(AuthenticatedUser user, String id, DecisionRequest req) {
        LeadProposal p = load(user, id);
        if (req.status() != LeadProposal.Status.ACEITA && req.status() != LeadProposal.Status.RECUSADA) {
            throw new BusinessException("INVALID_DECISION", "Informe se a proposta foi aceita ou recusada.");
        }
        if (p.getStatus() != LeadProposal.Status.ENVIADA) {
            throw new BusinessException("PROPOSAL_NOT_SENT", "Somente propostas enviadas recebem decisão.");
        }
        p.setStatus(req.status());
        p.setDecidedAt(Instant.now());
        p.setDecisionNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        proposals.save(user.officeId(), p);
        Lead lead = lead(user, p.getLeadId());
        if (req.status() == LeadProposal.Status.ACEITA && lead.getStage().isOpen()
                && lead.getStage().ordinal() < LeadStage.CONTRATO_ENVIADO.ordinal()) {
            crm.changeStage(user, lead.getId(), LeadStage.CONTRATO_ENVIADO, null);
        } else if (req.status() == LeadProposal.Status.RECUSADA && lead.getStage().isOpen()
                && lead.getStage().ordinal() < LeadStage.NEGOCIACAO.ordinal()) {
            crm.changeStage(user, lead.getId(), LeadStage.NEGOCIACAO, null);
        }
        activity(user, lead, "Proposta " + p.getNumber() + (req.status() == LeadProposal.Status.ACEITA ? " aceita" : " recusada")
                + (p.getDecisionNote() == null ? "" : ": " + p.getDecisionNote()));
        audit.record(user, "PROPOSAL_DECIDED", "lead", lead.getId(), "Proposta " + p.getNumber() + ": " + req.status(),
                Map.of("status", req.status().name()));
        return ProposalResponse.of(p);
    }

    public ReportExporter.Exported pdf(AuthenticatedUser user, String id) {
        LeadProposal p = load(user, id);
        String officeId = user.officeId();
        Office office = offices.findById(officeId).orElse(null);
        String html = "<h1>PROPOSTA DE HONORÁRIOS</h1>"
                + "<p style=\"text-align: right;\">Proposta nº " + esc(p.getNumber()) + "</p>"
                + "<p><strong>A/C:</strong> " + esc(p.getLeadName()) + "</p>"
                + "<h2>" + esc(p.getTitle()) + "</h2>"
                + "<h3>Escopo dos serviços</h3>" + paragraphs(p.getScope())
                + "<h3>Honorários</h3><p><strong>" + esc(FinanceSetupService.brl(p.getValueCents())) + "</strong> ("
                + esc(ValorExtenso.reais(p.getValueCents())) + ")</p>" + paragraphs(p.getFeeTerms())
                + (p.getValidUntil() == null ? "" : "<p>Proposta válida até " + p.getValidUntil().format(BR) + ".</p>")
                + "<p>Despesas processuais, custas e honorários de sucumbência não estão incluídos, salvo indicação expressa.</p><p><br></p>"
                + "<p style=\"text-align: right;\">" + esc(office == null || office.getCity() == null ? "" : office.getCity() + ", ")
                + esc(FinanceService.today().format(LONG_DATE)) + ".</p><p><br></p>"
                + "<p style=\"text-align: center;\">_______________________________________<br>" + esc(office == null ? "" : office.getName())
                + "<br>" + esc(p.getCreatedByName()) + "</p>";
        HtmlDocument.Background bg = editor.defaultBackground(officeId);
        byte[] pdf = HtmlDocument.toPdf(html, new HtmlDocument.Layout(bg == null && office != null ? office.getName().toUpperCase(PT_BR) : null,
                null, true, bg));
        audit.record(user, "PROPOSAL_EXPORTED", "lead", p.getLeadId(), "Proposta " + p.getNumber() + " exportada em PDF");
        return new ReportExporter.Exported(pdf, p.getNumber().toLowerCase(Locale.ROOT) + ".pdf", ReportExporter.PDF);
    }

    // ------------------------------------------------------------------ helpers

    private void apply(LeadProposal p, ProposalRequest req) {
        p.setTitle(req.title().trim());
        p.setScope(blank(req.scope()) ? null : req.scope().trim());
        p.setFeeTerms(blank(req.feeTerms()) ? null : req.feeTerms().trim());
        p.setValueCents(req.valueCents());
        p.setValidUntil(req.validUntil());
    }

    private LeadProposal load(AuthenticatedUser user, String id) {
        LeadProposal p = proposals.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Proposta"));
        crm.get(user, p.getLeadId());
        return p;
    }

    private Lead lead(AuthenticatedUser user, String id) {
        crm.get(user, id);
        return leads.findById(user.officeId(), id).orElseThrow(() -> new NotFoundException("Oportunidade"));
    }

    private void activity(AuthenticatedUser user, Lead lead, String text) {
        LeadActivity a = new LeadActivity();
        a.setId(UUID.randomUUID().toString());
        a.setLeadId(lead.getId());
        a.setType(LeadActivityType.PROPOSTA);
        a.setText(text);
        a.setActorUid(user.uid());
        a.setActorName(user.displayName());
        a.setAt(Instant.now());
        a.setCreatedAt(a.getAt());
        activities.save(user.officeId(), a);
    }

    private static String paragraphs(String text) {
        if (blank(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            sb.append(line.isBlank() ? "<p><br></p>" : "<p>" + esc(line.trim()) + "</p>");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return HtmlDocument.escape(s);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @SuppressWarnings("unused")
    private static Query.Direction desc() {
        return Query.Direction.DESCENDING;
    }
}
