package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.FinanceDtos.PayRequest;
import br.com.erpadv.dto.FinanceDtos.TransactionFilter;
import br.com.erpadv.dto.FinanceDtos.TransactionRequest;
import br.com.erpadv.dto.FinanceDtos.TransactionResponse;
import br.com.erpadv.dto.FinanceDtos.View;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FileInspector;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.firebase.StorageGateway;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Role;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contas a receber e a pagar: lançamentos, parcelamentos, recorrências, baixa, comprovantes e
 * exportação. Lançamentos pessoais de sócios ficam isolados (visíveis só ao próprio sócio).
 */
@Service
public class FinanceService {

    public static final long MAX_RECEIPT_BYTES = 10L * 1024 * 1024;
    private static final int EXPORT_LIMIT = 5000;

    private final FinancialTransactionRepository transactions;
    private final ClientRepository clients;
    private final CaseService cases;
    private final StorageGateway storage;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.repositories.FinanceRepositories.CostCenters costCenters;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.repositories.FinanceRepositories.BankAccounts bankAccounts;

    public FinanceService(FinancialTransactionRepository transactions, ClientRepository clients, CaseService cases,
                          StorageGateway storage, AuditService audit) {
        this.transactions = transactions;
        this.clients = clients;
        this.cases = cases;
        this.storage = storage;
        this.audit = audit;
    }

    public static LocalDate today() {
        return LocalDate.now(CourtCalendarService.ZONE);
    }

    // ------------------------------------------------------------------ consultas

    public CursorPage<TransactionResponse> list(AuthenticatedUser user, TransactionFilter filter, String cursor, int size) {
        LocalDate today = today();
        View view = filter.view() == null ? View.ABERTOS : filter.view();
        Query.Direction dir = view == View.ABERTOS || view == View.A_VENCER || view == View.ATRASADOS
                ? Query.Direction.ASCENDING : Query.Direction.DESCENDING;
        return transactions.page(query(user, filter, today), "dueDate", dir, cursor, size)
                .map(t -> TransactionResponse.of(t, today));
    }

    /** Exportação CSV (separador ";" e BOM para abrir corretamente no Excel em pt-BR). */
    public byte[] exportCsv(AuthenticatedUser user, TransactionFilter filter) {
        LocalDate today = today();
        List<FinancialTransaction> items = transactions.list(
                query(user, filter, today).orderBy("dueDate", Query.Direction.ASCENDING), EXPORT_LIMIT);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Tipo;Categoria;Descrição;Cliente/Favorecido;Processo;Vencimento;Valor;Situação;Pagamento;Valor pago;Forma;Documento;Parcela\n");
        for (FinancialTransaction t : items) {
            TransactionResponse r = TransactionResponse.of(t, today);
            sb.append(csv(t.getDirection() == FinanceDirection.RECEITA ? "Receita" : "Despesa")).append(';')
                    .append(csv(t.getCategory().label())).append(';')
                    .append(csv(t.getDescription())).append(';')
                    .append(csv(t.getCounterparty())).append(';')
                    .append(csv(t.getCaseNumber())).append(';')
                    .append(t.getDueDate()).append(';')
                    .append(money(t.getAmountCents())).append(';')
                    .append(r.situation()).append(';')
                    .append(t.getPaidDate() == null ? "" : t.getPaidDate()).append(';')
                    .append(t.getPaidAmountCents() == null ? "" : money(t.getPaidAmountCents())).append(';')
                    .append(t.getPaymentMethod() == null ? "" : t.getPaymentMethod()).append(';')
                    .append(csv(t.getDocumentNumber())).append(';')
                    .append(t.getInstallmentCount() == null ? "" : t.getInstallmentNumber() + "/" + t.getInstallmentCount())
                    .append('\n');
        }
        audit.record(user, "FINANCE_EXPORTED", "finance", null, "Exportação CSV de " + items.size() + " lançamento(s)");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public TransactionResponse get(AuthenticatedUser user, String id) {
        return TransactionResponse.of(load(user, id), today());
    }

    // ------------------------------------------------------------------ escrita

    public List<TransactionResponse> create(AuthenticatedUser user, TransactionRequest req) {
        String officeId = user.requireOffice();
        FinanceScope scope = resolveScope(user, req.scope());
        if (req.category().direction() != req.direction()) {
            throw new BusinessException("CATEGORY_MISMATCH", "Categoria incompatível com o tipo de lançamento.");
        }
        int installments = req.installments() == null ? 1 : req.installments();
        int recurrence = req.recurrenceMonths() == null ? 1 : req.recurrenceMonths();
        if (installments > 1 && recurrence > 1) {
            throw new BusinessException("INVALID_SCHEDULE", "Escolha parcelamento ou recorrência, não ambos.");
        }
        if (installments > 1 && req.amountCents() < installments) {
            throw new BusinessException("INVALID_INSTALLMENTS", "Valor insuficiente para o número de parcelas.");
        }
        FinancialTransaction template = new FinancialTransaction();
        applyCommon(user, template, req, scope);

        int count = Math.max(installments, recurrence);
        String groupId = count > 1 ? UUID.randomUUID().toString() : null;
        long base = installments > 1 ? req.amountCents() / installments : req.amountCents();
        long remainder = installments > 1 ? req.amountCents() - base * installments : 0;
        Instant now = Instant.now();
        List<FinancialTransaction> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FinancialTransaction t = copy(template);
            t.setAmountCents(i == 0 ? base + remainder : base);
            t.setDueDate(req.dueDate().plusMonths(i));
            t.setStatus(TransactionStatus.ABERTO);
            if (count > 1) {
                t.setGroupId(groupId);
                t.setInstallmentNumber(i + 1);
                t.setInstallmentCount(count);
                t.setRecurrent(recurrence > 1);
            }
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            t.setCreatedBy(user.uid());
            t.setUpdatedBy(user.uid());
            items.add(t);
        }
        transactions.saveAll(officeId, items);
        audit.record(user, "FINANCE_CREATED", "finance", items.get(0).getId(),
                (req.direction() == FinanceDirection.RECEITA ? "Conta a receber" : "Conta a pagar") + ": "
                        + template.getDescription() + " — " + money(req.amountCents())
                        + (count > 1 ? " em " + count + (installments > 1 ? " parcelas" : " meses") : ""),
                Map.of("scope", scope.name(), "count", String.valueOf(count)));
        refreshClientDefaulting(officeId, template.getClientId(), scope);
        LocalDate today = today();
        return items.stream().map(t -> TransactionResponse.of(t, today)).toList();
    }

    public TransactionResponse update(AuthenticatedUser user, String id, TransactionRequest req) {
        FinancialTransaction t = load(user, id);
        if (t.getStatus() != TransactionStatus.ABERTO) {
            throw new BusinessException("TRANSACTION_CLOSED", "Reabra o lançamento para editá-lo.");
        }
        if (req.category().direction() != t.getDirection() || req.direction() != t.getDirection()) {
            throw new BusinessException("CATEGORY_MISMATCH", "Não é possível trocar receita por despesa; cancele e lance novamente.");
        }
        String previousClient = t.getClientId();
        long previousAmount = t.getAmountCents();
        applyCommon(user, t, req, t.getScope());
        t.setAmountCents(req.amountCents());
        t.setDueDate(req.dueDate());
        touch(user, t);
        transactions.save(user.officeId(), t);
        audit.record(user, "FINANCE_UPDATED", "finance", id, "Lançamento atualizado: " + t.getDescription(),
                Map.of("amount", money(previousAmount) + "->" + money(t.getAmountCents())));
        refreshClientDefaulting(user.officeId(), previousClient, t.getScope());
        refreshClientDefaulting(user.officeId(), t.getClientId(), t.getScope());
        return TransactionResponse.of(t, today());
    }

    public TransactionResponse pay(AuthenticatedUser user, String id, PayRequest req) {
        FinancialTransaction t = load(user, id);
        if (t.getStatus() != TransactionStatus.ABERTO) {
            throw new BusinessException("TRANSACTION_CLOSED", "Lançamento não está em aberto.");
        }
        if (req.paidDate().isAfter(today())) {
            throw new BusinessException("INVALID_DATE", "A data de pagamento não pode ser futura.");
        }
        t.setStatus(TransactionStatus.PAGO);
        t.setPaidDate(req.paidDate());
        t.setPaidAmountCents(req.paidAmountCents());
        t.setPaymentMethod(req.paymentMethod());
        t.setPaidAt(Instant.now());
        t.setPaidBy(user.uid());
        t.setBankAccountId(null);
        t.setBankAccountName(null);
        if (req.bankAccountId() != null && !req.bankAccountId().isBlank()) {
            if (t.getScope() != FinanceScope.ESCRITORIO) {
                throw new BusinessException("INVALID_ACCOUNT", "Contas bancárias são do escritório; não use em lançamentos pessoais.");
            }
            var account = bankAccounts.findById(user.officeId(), req.bankAccountId())
                    .orElseThrow(() -> new BusinessException("INVALID_ACCOUNT", "Conta bancária não encontrada."));
            if (!account.isActive()) {
                throw new BusinessException("INVALID_ACCOUNT", "Conta bancária inativa.");
            }
            t.setBankAccountId(account.getId());
            t.setBankAccountName(account.getName());
        }
        touch(user, t);
        transactions.save(user.officeId(), t);
        audit.record(user, "FINANCE_SETTLED", "finance", id,
                (t.getDirection() == FinanceDirection.RECEITA ? "Recebimento" : "Pagamento") + " baixado: "
                        + t.getDescription() + " — " + money(req.paidAmountCents()),
                Map.of("paidDate", req.paidDate().toString(), "method", req.paymentMethod().name()));
        refreshClientDefaulting(user.officeId(), t.getClientId(), t.getScope());
        return TransactionResponse.of(t, today());
    }

    public TransactionResponse reopen(AuthenticatedUser user, String id) {
        FinancialTransaction t = load(user, id);
        if (t.getStatus() == TransactionStatus.ABERTO) {
            throw new BusinessException("TRANSACTION_OPEN", "Lançamento já está em aberto.");
        }
        TransactionStatus previous = t.getStatus();
        t.setStatus(TransactionStatus.ABERTO);
        t.setPaidDate(null);
        t.setPaidAmountCents(null);
        t.setPaidAt(null);
        t.setPaidBy(null);
        t.setCanceledReason(null);
        t.setBankAccountId(null);
        t.setBankAccountName(null);
        touch(user, t);
        transactions.save(user.officeId(), t);
        audit.record(user, "FINANCE_REOPENED", "finance", id, "Lançamento reaberto (" + previous + "): " + t.getDescription());
        refreshClientDefaulting(user.officeId(), t.getClientId(), t.getScope());
        return TransactionResponse.of(t, today());
    }

    public TransactionResponse cancel(AuthenticatedUser user, String id, String reason) {
        FinancialTransaction t = load(user, id);
        if (t.getStatus() != TransactionStatus.ABERTO) {
            throw new BusinessException("TRANSACTION_CLOSED", "Somente lançamentos em aberto podem ser cancelados.");
        }
        t.setStatus(TransactionStatus.CANCELADO);
        t.setCanceledReason(reason.trim());
        touch(user, t);
        transactions.save(user.officeId(), t);
        audit.record(user, "FINANCE_CANCELED", "finance", id, "Lançamento cancelado: " + t.getDescription(),
                Map.of("reason", reason.trim()));
        refreshClientDefaulting(user.officeId(), t.getClientId(), t.getScope());
        return TransactionResponse.of(t, today());
    }

    // ------------------------------------------------------------------ integração com contratos

    /**
     * Gera contas a receber a partir de um contrato (regra de negócio do contrato: não exige
     * FINANCE_WRITE de quem ativa o contrato). Registrado na auditoria.
     */
    public List<FinancialTransaction> createForContract(AuthenticatedUser actor, List<FinancialTransaction> items) {
        String officeId = actor.requireOffice();
        Instant now = Instant.now();
        for (FinancialTransaction t : items) {
            t.setDirection(FinanceDirection.RECEITA);
            t.setScope(FinanceScope.ESCRITORIO);
            t.setStatus(TransactionStatus.ABERTO);
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            t.setCreatedBy(actor.uid());
            t.setUpdatedBy(actor.uid());
            if (t.getCounterparty() == null) {
                t.setCounterparty(t.getClientName());
            }
        }
        if (items.isEmpty()) {
            return items;
        }
        transactions.saveAll(officeId, items);
        long total = items.stream().mapToLong(FinancialTransaction::getAmountCents).sum();
        audit.record(actor, "FINANCE_CREATED", "finance", items.get(0).getId(),
                "Honorários gerados pelo contrato: " + items.size() + " lançamento(s), total " + money(total),
                Map.of("contractId", items.get(0).getContractId() == null ? "" : items.get(0).getContractId()));
        refreshClientDefaulting(officeId, items.get(0).getClientId(), FinanceScope.ESCRITORIO);
        return items;
    }

    /** Cancela as contas em aberto de um contrato (cancelamento/encerramento antecipado). */
    public int cancelOpenByContract(AuthenticatedUser actor, String contractId, String reason) {
        String officeId = actor.requireOffice();
        List<FinancialTransaction> open = transactions.list(transactions.collection(officeId)
                .whereEqualTo("contractId", contractId)
                .whereEqualTo("status", TransactionStatus.ABERTO.name()), 500);
        for (FinancialTransaction t : open) {
            t.setStatus(TransactionStatus.CANCELADO);
            t.setCanceledReason(reason);
            touch(actor, t);
        }
        if (!open.isEmpty()) {
            transactions.saveAll(officeId, open);
            audit.record(actor, "FINANCE_CANCELED", "finance", open.get(0).getId(),
                    open.size() + " lançamento(s) cancelado(s) com o contrato", Map.of("contractId", contractId, "reason", reason));
            refreshClientDefaulting(officeId, open.get(0).getClientId(), FinanceScope.ESCRITORIO);
        }
        return open.size();
    }

    // ------------------------------------------------------------------ comprovantes

    public TransactionResponse attachReceipt(AuthenticatedUser user, String id, byte[] bytes, String originalName) {
        FinancialTransaction t = load(user, id);
        FileInspector.Inspection file = FileInspector.inspect(bytes, originalName, MAX_RECEIPT_BYTES,
                EnumSet.of(FileInspector.FileKind.PDF, FileInspector.FileKind.PNG, FileInspector.FileKind.JPEG));
        String path = "offices/" + user.officeId() + "/finance/" + t.getId() + "/" + UUID.randomUUID() + "-" + file.safeName();
        storage.put(path, bytes, file.kind().contentType, Map.of(
                "officeId", user.officeId(), "transactionId", t.getId(), "uploadedBy", user.uid(), "sha256", file.sha256()));
        String previous = t.getReceiptPath();
        t.setReceiptPath(path);
        t.setReceiptName(file.safeName());
        t.setReceiptContentType(file.kind().contentType);
        t.setReceiptSize(file.size());
        t.setReceiptSha256(file.sha256());
        t.setReceiptUploadedBy(user.uid());
        t.setReceiptUploadedAt(Instant.now());
        touch(user, t);
        transactions.save(user.officeId(), t);
        if (previous != null) {
            try {
                storage.delete(previous);
            } catch (RuntimeException ignored) {
                // o comprovante anterior permanece órfão; não impede a substituição
            }
        }
        audit.record(user, "FINANCE_RECEIPT_UPLOADED", "finance", id,
                "Comprovante anexado: " + file.safeName(), Map.of("sha256", file.sha256(), "size", String.valueOf(file.size())));
        return TransactionResponse.of(t, today());
    }

    public record Receipt(byte[] bytes, String name, String contentType) {
    }

    public Receipt downloadReceipt(AuthenticatedUser user, String id) {
        FinancialTransaction t = load(user, id);
        if (t.getReceiptPath() == null) {
            throw new NotFoundException("Comprovante");
        }
        byte[] bytes = storage.get(t.getReceiptPath());
        audit.record(user, "FINANCE_RECEIPT_DOWNLOADED", "finance", id, "Comprovante baixado: " + t.getReceiptName());
        return new Receipt(bytes, t.getReceiptName(), t.getReceiptContentType());
    }

    // ------------------------------------------------------------------ inadimplência ↔ CRM

    /**
     * Marca o cliente como inadimplente quando há contas a receber vencidas e desmarca quando a marcação
     * foi automática e não há mais pendências. Marcações manuais do CRM não são removidas.
     */
    public void refreshClientDefaulting(String officeId, String clientId, FinanceScope scope) {
        if (clientId == null || scope != FinanceScope.ESCRITORIO) {
            return;
        }
        long overdue = Futures.await(transactions.collection(officeId)
                .whereEqualTo("scope", FinanceScope.ESCRITORIO.name())
                .whereEqualTo("direction", FinanceDirection.RECEITA.name())
                .whereEqualTo("status", TransactionStatus.ABERTO.name())
                .whereEqualTo("clientId", clientId)
                .whereLessThan("dueDate", today().toString())
                .count().get()).getCount();
        clients.findById(officeId, clientId).ifPresent(c -> {
            if (overdue > 0 && !c.isDefaulting()) {
                c.setDefaulting(true);
                c.setDefaultingAuto(true);
                c.setUpdatedAt(Instant.now());
                clients.save(officeId, c);
                audit.record(null, officeId, "CLIENT_MARKED_DEFAULTING", "client", clientId,
                        "Inadimplência automática: " + overdue + " conta(s) a receber vencida(s)", Map.of());
            } else if (overdue == 0 && c.isDefaulting() && c.isDefaultingAuto()) {
                c.setDefaulting(false);
                c.setDefaultingAuto(false);
                c.setUpdatedAt(Instant.now());
                clients.save(officeId, c);
                audit.record(null, officeId, "CLIENT_UNMARKED_DEFAULTING", "client", clientId,
                        "Inadimplência regularizada automaticamente", Map.of());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    public FinancialTransaction load(AuthenticatedUser user, String id) {
        FinancialTransaction t = transactions.findById(user.requireOffice(), id)
                .orElseThrow(() -> new NotFoundException("Lançamento"));
        if (t.getScope() == FinanceScope.PESSOAL && !user.uid().equals(t.getOwnerUid())) {
            throw new NotFoundException("Lançamento");
        }
        return t;
    }

    Query query(AuthenticatedUser user, TransactionFilter filter, LocalDate today) {
        FinanceScope scope = resolveScope(user, filter.scope());
        Query q = transactions.collection(user.requireOffice()).whereEqualTo("scope", scope.name());
        if (scope == FinanceScope.PESSOAL) {
            q = q.whereEqualTo("ownerUid", user.uid());
        }
        if (filter.direction() != null) {
            q = q.whereEqualTo("direction", filter.direction().name());
        }
        if (filter.category() != null) {
            q = q.whereEqualTo("category", filter.category().name());
        }
        if (filter.clientId() != null && !filter.clientId().isBlank()) {
            q = q.whereEqualTo("clientId", filter.clientId());
        }
        if (filter.contractId() != null && !filter.contractId().isBlank()) {
            q = q.whereEqualTo("contractId", filter.contractId());
        }
        if (filter.costCenterId() != null && !filter.costCenterId().isBlank()) {
            q = q.whereEqualTo("costCenterId", filter.costCenterId());
        }
        LocalDate from = filter.from();
        LocalDate to = filter.to();
        switch (filter.view() == null ? View.ABERTOS : filter.view()) {
            case ABERTOS -> q = q.whereEqualTo("status", TransactionStatus.ABERTO.name());
            case A_VENCER -> {
                q = q.whereEqualTo("status", TransactionStatus.ABERTO.name());
                from = from == null || from.isBefore(today) ? today : from;
            }
            case ATRASADOS -> {
                q = q.whereEqualTo("status", TransactionStatus.ABERTO.name());
                LocalDate yesterday = today.minusDays(1);
                to = to == null || to.isAfter(yesterday) ? yesterday : to;
            }
            case PAGOS -> q = q.whereEqualTo("status", TransactionStatus.PAGO.name());
            case CANCELADOS -> q = q.whereEqualTo("status", TransactionStatus.CANCELADO.name());
            case TODOS -> { }
        }
        if (from != null) {
            q = q.whereGreaterThanOrEqualTo("dueDate", from.toString());
        }
        if (to != null) {
            q = q.whereLessThanOrEqualTo("dueDate", to.toString());
        }
        return q;
    }

    /** Escopo pessoal só existe para sócios (e o master); os demais sempre operam no escopo do escritório. */
    static FinanceScope resolveScope(AuthenticatedUser user, FinanceScope requested) {
        if (requested == FinanceScope.PESSOAL) {
            if (user.role() != Role.SOCIO_ADMIN && user.role() != Role.MASTER) {
                throw new ForbiddenException("PERSONAL_FINANCE_DENIED", "Finanças pessoais são exclusivas dos sócios.");
            }
            return FinanceScope.PESSOAL;
        }
        return FinanceScope.ESCRITORIO;
    }

    private void applyCommon(AuthenticatedUser user, FinancialTransaction t, TransactionRequest req, FinanceScope scope) {
        String officeId = user.officeId();
        t.setDirection(req.direction());
        t.setCategory(req.category());
        t.setScope(scope);
        t.setOwnerUid(scope == FinanceScope.PESSOAL ? user.uid() : null);
        t.setDescription(req.description().trim());
        t.setPaymentMethod(req.paymentMethod());
        t.setDocumentNumber(trim(req.documentNumber()));
        t.setNotes(trim(req.notes()));
        t.setCaseId(null);
        t.setCaseNumber(null);
        t.setClientId(null);
        t.setClientName(null);
        if (scope == FinanceScope.ESCRITORIO && req.caseId() != null && !req.caseId().isBlank()) {
            LegalCase c = cases.load(user, req.caseId());
            t.setCaseId(c.getId());
            t.setCaseNumber(c.getCnjNumber());
            t.setClientId(c.getClientId());
            t.setClientName(c.getClientName());
        } else if (scope == FinanceScope.ESCRITORIO && req.clientId() != null && !req.clientId().isBlank()) {
            Client client = clients.findById(officeId, req.clientId())
                    .orElseThrow(() -> new BusinessException("INVALID_CLIENT", "Cliente não encontrado."));
            t.setClientId(client.getId());
            t.setClientName(client.getName());
        }
        String counterparty = trim(req.counterparty());
        t.setCounterparty(counterparty != null ? counterparty : t.getClientName());
        t.setCostCenterId(null);
        t.setCostCenterName(null);
        if (scope == FinanceScope.ESCRITORIO && req.costCenterId() != null && !req.costCenterId().isBlank()) {
            var center = costCenters.findById(officeId, req.costCenterId())
                    .orElseThrow(() -> new BusinessException("INVALID_COST_CENTER", "Centro de custo não encontrado."));
            t.setCostCenterId(center.getId());
            t.setCostCenterName(center.getName());
        }
    }

    private static FinancialTransaction copy(FinancialTransaction s) {
        FinancialTransaction t = new FinancialTransaction();
        t.setDirection(s.getDirection());
        t.setCategory(s.getCategory());
        t.setScope(s.getScope());
        t.setOwnerUid(s.getOwnerUid());
        t.setDescription(s.getDescription());
        t.setPaymentMethod(s.getPaymentMethod());
        t.setClientId(s.getClientId());
        t.setClientName(s.getClientName());
        t.setCaseId(s.getCaseId());
        t.setCaseNumber(s.getCaseNumber());
        t.setCounterparty(s.getCounterparty());
        t.setDocumentNumber(s.getDocumentNumber());
        t.setNotes(s.getNotes());
        t.setCostCenterId(s.getCostCenterId());
        t.setCostCenterName(s.getCostCenterName());
        return t;
    }

    private void touch(AuthenticatedUser user, FinancialTransaction t) {
        t.setUpdatedAt(Instant.now());
        t.setUpdatedBy(user.uid());
    }

    static String money(long cents) {
        return String.format("%d,%02d", cents / 100, Math.abs(cents % 100));
    }

    /** Escapa CSV e neutraliza injeção de fórmulas (=, +, -, @) ao abrir no Excel. */
    static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
