package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.FinanceDtos.BankAccountRequest;
import br.com.erpadv.dto.FinanceDtos.BankAccountResponse;
import br.com.erpadv.dto.FinanceDtos.CollectionDraft;
import br.com.erpadv.dto.FinanceDtos.CollectionRequest;
import br.com.erpadv.dto.FinanceDtos.CollectionResponse;
import br.com.erpadv.dto.FinanceDtos.CostCenterRequest;
import br.com.erpadv.dto.FinanceDtos.CostCenterResponse;
import br.com.erpadv.dto.FinanceDtos.StatementLine;
import br.com.erpadv.dto.FinanceDtos.StatementResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.BankAccount;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.CostCenter;
import br.com.erpadv.models.FinanceCollection;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.FinanceRepositories;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ValorExtenso;
import br.com.erpadv.validation.BrazilianDocuments;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cadastros auxiliares do financeiro (centros de custo e contas bancárias), extrato por conta, recibos e
 * registro de cobranças. Somente lançamentos do escritório (os pessoais dos sócios ficam de fora).
 */
@Service
public class FinanceSetupService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT_BR);

    private final FinanceRepositories.CostCenters costCenters;
    private final FinanceRepositories.BankAccounts accounts;
    private final FinanceRepositories.Collections collections;
    private final FinancialTransactionRepository transactions;
    private final FinanceService finance;
    private final ClientRepository clients;
    private final OfficeRepository offices;
    private final CryptoService crypto;
    private final LegalEditorService editor;
    private final AuditService audit;

    public FinanceSetupService(FinanceRepositories.CostCenters costCenters, FinanceRepositories.BankAccounts accounts,
                               FinanceRepositories.Collections collections, FinancialTransactionRepository transactions,
                               FinanceService finance, ClientRepository clients, OfficeRepository offices, CryptoService crypto,
                               LegalEditorService editor, AuditService audit) {
        this.costCenters = costCenters;
        this.accounts = accounts;
        this.collections = collections;
        this.transactions = transactions;
        this.finance = finance;
        this.clients = clients;
        this.offices = offices;
        this.crypto = crypto;
        this.editor = editor;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ centros de custo

    public List<CostCenterResponse> costCenters(AuthenticatedUser user) {
        return costCenters.list(costCenters.collection(user.requireOffice()).orderBy("nameLower"), 200).stream()
                .map(CostCenterResponse::of).toList();
    }

    public CostCenterResponse saveCostCenter(AuthenticatedUser user, String id, CostCenterRequest req) {
        String officeId = user.requireOffice();
        CostCenter c = id == null ? new CostCenter() : costCenters.findById(officeId, id).orElseThrow(() -> new NotFoundException("Centro de custo"));
        String nameLower = SearchTokens.normalize(req.name());
        boolean duplicate = costCenters.list(costCenters.collection(officeId).whereEqualTo("nameLower", nameLower), 2).stream()
                .anyMatch(o -> !o.getId().equals(id));
        if (duplicate) {
            throw new BusinessException("DUPLICATE_NAME", "Já existe um centro de custo com esse nome.");
        }
        c.setName(req.name().trim());
        c.setNameLower(nameLower);
        c.setCode(blank(req.code()) ? null : req.code().trim());
        c.setDescription(blank(req.description()) ? null : req.description().trim());
        c.setActive(req.active());
        stamp(user, c, id == null);
        costCenters.save(officeId, c);
        audit.record(user, id == null ? "COST_CENTER_CREATED" : "COST_CENTER_UPDATED", "cost_center", c.getId(), "Centro de custo: " + c.getName());
        return CostCenterResponse.of(c);
    }

    public void deleteCostCenter(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        CostCenter c = costCenters.findById(officeId, id).orElseThrow(() -> new NotFoundException("Centro de custo"));
        if (!transactions.list(transactions.collection(officeId).whereEqualTo("costCenterId", id), 1).isEmpty()) {
            throw new BusinessException("COST_CENTER_IN_USE", "Há lançamentos neste centro de custo — desative-o em vez de excluir.");
        }
        costCenters.delete(officeId, id);
        audit.record(user, "COST_CENTER_DELETED", "cost_center", id, "Centro de custo excluído: " + c.getName());
    }

    // ------------------------------------------------------------------ contas bancárias

    public List<BankAccountResponse> accounts(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        return accounts.list(accounts.collection(officeId).orderBy("name"), 100).stream().map(a -> toResponse(officeId, a)).toList();
    }

    public BankAccountResponse saveAccount(AuthenticatedUser user, String id, BankAccountRequest req) {
        String officeId = user.requireOffice();
        BankAccount a = id == null ? new BankAccount() : loadAccount(officeId, id);
        a.setName(req.name().trim());
        a.setBankName(trimToNull(req.bankName()));
        a.setBankCode(trimToNull(req.bankCode()));
        a.setAgency(trimToNull(req.agency()));
        a.setAccountNumber(trimToNull(req.accountNumber()));
        a.setKind(req.kind());
        a.setInitialBalanceCents(req.initialBalanceCents());
        a.setInitialBalanceDate(req.initialBalanceDate());
        a.setActive(req.active());
        stamp(user, a, id == null);
        accounts.save(officeId, a);
        audit.record(user, id == null ? "BANK_ACCOUNT_CREATED" : "BANK_ACCOUNT_UPDATED", "bank_account", a.getId(), "Conta: " + a.getName());
        return toResponse(officeId, a);
    }

    public void deleteAccount(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        BankAccount a = loadAccount(officeId, id);
        if (!transactions.list(transactions.collection(officeId).whereEqualTo("bankAccountId", id), 1).isEmpty()) {
            throw new BusinessException("ACCOUNT_IN_USE", "Há baixas registradas nesta conta — desative-a em vez de excluir.");
        }
        accounts.delete(officeId, id);
        audit.record(user, "BANK_ACCOUNT_DELETED", "bank_account", id, "Conta excluída: " + a.getName());
    }

    /** Extrato da conta: baixas do período com saldo acumulado a partir do saldo inicial. */
    public StatementResponse statement(AuthenticatedUser user, String id, LocalDate from, LocalDate to) {
        String officeId = user.requireOffice();
        BankAccount a = loadAccount(officeId, id);
        LocalDate end = to == null ? FinanceService.today() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            throw new BusinessException("INVALID_RANGE", "Período inválido.");
        }
        List<FinancialTransaction> moves = movements(officeId, a);
        long opening = a.getInitialBalanceCents();
        List<StatementLine> lines = new ArrayList<>();
        long balance;
        for (FinancialTransaction t : moves) {
            if (t.getPaidDate().isBefore(start)) {
                opening += signed(t);
            }
        }
        balance = opening;
        for (FinancialTransaction t : moves) {
            if (!t.getPaidDate().isBefore(start) && !t.getPaidDate().isAfter(end)) {
                balance += signed(t);
                lines.add(new StatementLine(t.getId(), t.getPaidDate(), t.getDescription(), t.getCounterparty(), t.getCategory(),
                        t.getCategory().label(), signed(t), balance));
            }
        }
        return new StatementResponse(toResponse(officeId, a), start, end, opening, balance, lines);
    }

    private List<FinancialTransaction> movements(String officeId, BankAccount a) {
        return transactions.list(transactions.collection(officeId).whereEqualTo("bankAccountId", a.getId())
                        .whereEqualTo("status", TransactionStatus.PAGO.name()), 5000).stream()
                .filter(t -> t.getPaidDate() != null)
                .filter(t -> a.getInitialBalanceDate() == null || !t.getPaidDate().isBefore(a.getInitialBalanceDate()))
                .sorted(Comparator.comparing(FinancialTransaction::getPaidDate).thenComparing(FinancialTransaction::getId))
                .toList();
    }

    private BankAccountResponse toResponse(String officeId, BankAccount a) {
        long in = 0;
        long out = 0;
        List<FinancialTransaction> moves = a.getId() == null ? List.of() : movements(officeId, a);
        for (FinancialTransaction t : moves) {
            if (t.getDirection() == FinanceDirection.RECEITA) {
                in += paid(t);
            } else {
                out += paid(t);
            }
        }
        String number = a.getAccountNumber();
        String masked = number == null ? null : number.length() <= 4 ? number : "•••" + number.substring(number.length() - 4);
        return new BankAccountResponse(a.getId(), a.getName(), a.getBankName(), a.getBankCode(), a.getAgency(), number, masked,
                a.getKind(), a.getInitialBalanceCents(), a.getInitialBalanceDate(), a.isActive(),
                a.getInitialBalanceCents() + in - out, in, out, moves.size());
    }

    private BankAccount loadAccount(String officeId, String id) {
        return accounts.findById(officeId, id).orElseThrow(() -> new NotFoundException("Conta bancária"));
    }

    private static long paid(FinancialTransaction t) {
        return t.getPaidAmountCents() == null ? t.getAmountCents() : t.getPaidAmountCents();
    }

    private static long signed(FinancialTransaction t) {
        return t.getDirection() == FinanceDirection.RECEITA ? paid(t) : -paid(t);
    }

    // ------------------------------------------------------------------ recibo

    /** Recibo de quitação (PDF) de uma receita paga, no papel timbrado padrão do escritório quando houver. */
    public ReportExporter.Exported receipt(AuthenticatedUser user, String transactionId) {
        String officeId = user.requireOffice();
        FinancialTransaction t = finance.load(user, transactionId);
        if (t.getScope() != FinanceScope.ESCRITORIO || t.getDirection() != FinanceDirection.RECEITA || t.getStatus() != TransactionStatus.PAGO) {
            throw new BusinessException("RECEIPT_UNAVAILABLE", "O recibo é emitido para receitas do escritório já recebidas.");
        }
        if (t.getReceiptNumber() == null) {
            t.setReceiptNumber("R" + t.getPaidDate().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                    + t.getId().replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(6, t.getId().length())).toUpperCase(Locale.ROOT));
            transactions.save(officeId, t);
        }
        Office office = offices.findById(officeId).orElse(null);
        String payer = t.getCounterparty() != null ? t.getCounterparty() : t.getClientName();
        String payerDoc = null;
        if (t.getClientId() != null && user.has(Permission.CLIENTS_SENSITIVE_READ)) {
            Client c = clients.findById(officeId, t.getClientId()).orElse(null);
            if (c != null && c.getDocumentEnc() != null) {
                String doc = BrazilianDocuments.format(crypto.decrypt(c.getDocumentEnc()));
                payerDoc = (c.getType() == br.com.erpadv.models.ClientType.PJ ? "CNPJ " : "CPF ") + doc;
            }
        }
        long amount = paid(t);
        String reference = t.getDescription()
                + (t.getInstallmentCount() != null ? " (parcela " + t.getInstallmentNumber() + "/" + t.getInstallmentCount() + ")" : "")
                + (t.getCaseNumber() != null ? ", processo nº " + t.getCaseNumber() : "");
        String html = "<h1>RECIBO Nº " + esc(t.getReceiptNumber()) + "</h1>"
                + "<p style=\"text-align: right;\"><strong>Valor: " + esc(brl(amount)) + "</strong></p><p><br></p>"
                + "<p>Recebemos de <strong>" + esc(payer == null ? "—" : payer) + "</strong>" + (payerDoc == null ? "" : ", " + esc(payerDoc))
                + ", a importância de <strong>" + esc(brl(amount)) + "</strong> (" + esc(ValorExtenso.reais(amount)) + "), referente a "
                + esc(reference) + ".</p>"
                + "<p>Forma de pagamento: " + esc(method(t)) + ". Data do pagamento: " + t.getPaidDate().format(BR) + ".</p>"
                + "<p>Para clareza e como prova de quitação do valor acima, firmamos o presente recibo.</p><p><br></p>"
                + "<p style=\"text-align: right;\">" + esc(office == null || office.getCity() == null ? "" : office.getCity() + ", ")
                + esc(t.getPaidDate().format(LONG_DATE)) + ".</p><p><br></p><p><br></p>"
                + "<p style=\"text-align: center;\">_______________________________________<br>" + esc(office == null ? "" : office.getName())
                + (office != null && office.getCnpj() != null ? "<br>CNPJ " + esc(office.getCnpj()) : "") + "</p>";
        HtmlDocument.Background bg = editor.defaultBackground(officeId);
        String header = bg == null && office != null ? office.getName().toUpperCase(PT_BR) : null;
        byte[] pdf = HtmlDocument.toPdf(html, new HtmlDocument.Layout(header, "Recibo gerado pelo ERPAdv em "
                + FinanceService.today().format(BR), false, bg));
        audit.record(user, "FINANCE_RECEIPT_ISSUED", "finance", t.getId(), "Recibo " + t.getReceiptNumber() + " emitido: " + t.getDescription());
        return new ReportExporter.Exported(pdf, "recibo-" + t.getReceiptNumber() + ".pdf", ReportExporter.PDF);
    }

    private static String method(FinancialTransaction t) {
        if (t.getPaymentMethod() == null) {
            return "não informada";
        }
        return switch (t.getPaymentMethod()) {
            case PIX -> "Pix";
            case BOLETO -> "boleto bancário";
            case TRANSFERENCIA -> "transferência bancária";
            case CARTAO -> "cartão";
            case DINHEIRO -> "dinheiro";
            case CHEQUE -> "cheque";
            default -> "outra";
        };
    }

    // ------------------------------------------------------------------ cobranças

    public List<CollectionResponse> collections(AuthenticatedUser user, String clientId) {
        var q = collections.collection(user.requireOffice());
        var query = blank(clientId) ? q.orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING) : q.whereEqualTo("clientId", clientId);
        return collections.list(query, 300).stream()
                .sorted(Comparator.comparing(FinanceCollection::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(CollectionResponse::of).toList();
    }

    public CollectionResponse registerCollection(AuthenticatedUser user, CollectionRequest req) {
        String officeId = user.requireOffice();
        List<FinancialTransaction> items = new ArrayList<>();
        for (String id : new LinkedHashSet<>(req.transactionIds())) {
            FinancialTransaction t = finance.load(user, id);
            if (t.getScope() != FinanceScope.ESCRITORIO || t.getDirection() != FinanceDirection.RECEITA) {
                throw new BusinessException("INVALID_COLLECTION", "Cobranças são registradas para contas a receber do escritório.");
            }
            items.add(t);
        }
        String clientId = items.get(0).getClientId();
        if (items.stream().anyMatch(t -> !java.util.Objects.equals(t.getClientId(), clientId))) {
            throw new BusinessException("INVALID_COLLECTION", "Registre a cobrança de um cliente por vez.");
        }
        if (req.outcome() == FinanceCollection.Outcome.PROMESSA_PAGAMENTO && req.promisedDate() == null) {
            throw new BusinessException("PROMISE_DATE", "Informe a data prometida para o pagamento.");
        }
        FinanceCollection c = new FinanceCollection();
        c.setId(UUID.randomUUID().toString());
        c.setClientId(clientId);
        c.setClientName(items.get(0).getClientName() != null ? items.get(0).getClientName() : items.get(0).getCounterparty());
        c.setTransactionIds(items.stream().map(FinancialTransaction::getId).toList());
        c.setAmountCents(items.stream().filter(t -> t.getStatus() == TransactionStatus.ABERTO).mapToLong(FinancialTransaction::getAmountCents).sum());
        c.setChannel(req.channel());
        c.setOutcome(req.outcome());
        c.setMessage(trimToNull(req.message()));
        c.setPromisedDate(req.promisedDate());
        c.setCreatedByName(user.displayName());
        stamp(user, c, true);
        collections.save(officeId, c);
        audit.record(user, "FINANCE_COLLECTION", "finance", items.get(0).getId(), "Cobrança registrada (" + req.channel() + "): "
                + (c.getClientName() == null ? "" : c.getClientName()), Map.of("outcome", req.outcome().name(), "titles", String.valueOf(items.size())));
        return CollectionResponse.of(c);
    }

    /** Mensagem de cobrança com os títulos em atraso do cliente e link oficial de conversa do WhatsApp. */
    public CollectionDraft draft(AuthenticatedUser user, String clientId) {
        String officeId = user.requireOffice();
        Client client = clients.findById(officeId, clientId).orElseThrow(() -> new NotFoundException("Cliente"));
        LocalDate today = FinanceService.today();
        List<FinancialTransaction> overdue = transactions.list(transactions.collection(officeId).whereEqualTo("clientId", clientId)
                        .whereEqualTo("status", TransactionStatus.ABERTO.name()), 500).stream()
                .filter(t -> t.getScope() == FinanceScope.ESCRITORIO && t.getDirection() == FinanceDirection.RECEITA)
                .filter(t -> t.getDueDate().isBefore(today))
                .sorted(Comparator.comparing(FinancialTransaction::getDueDate)).toList();
        long total = overdue.stream().mapToLong(FinancialTransaction::getAmountCents).sum();
        Office office = offices.findById(officeId).orElse(null);
        String first = client.getName().split("\\s+")[0];
        StringBuilder msg = new StringBuilder("Olá, ").append(first).append("! Tudo bem?\n\n")
                .append("Identificamos ").append(overdue.size() == 1 ? "um pagamento em aberto" : overdue.size() + " pagamentos em aberto")
                .append(" junto ao ").append(office == null ? "escritório" : office.getName()).append(", no total de ").append(brl(total)).append(":\n");
        for (FinancialTransaction t : overdue) {
            msg.append("• ").append(t.getDescription()).append(" — ").append(brl(t.getAmountCents()))
                    .append(" (vencimento em ").append(t.getDueDate().format(BR)).append(")\n");
        }
        msg.append("\nPodemos ajudar a regularizar? Caso o pagamento já tenha sido feito, por favor desconsidere esta mensagem e nos envie o comprovante.");
        String phone = digits(client.getWhatsapp() != null ? client.getWhatsapp() : client.getPhone());
        String whatsapp = phone == null ? null : "https://wa.me/" + (phone.startsWith("55") && phone.length() > 11 ? phone : "55" + phone)
                + "?text=" + URLEncoder.encode(msg.toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return new CollectionDraft(client.getId(), client.getName(), client.getWhatsapp() != null ? client.getWhatsapp() : client.getPhone(),
                client.getEmail(), total, overdue.size(), msg.toString(), whatsapp);
    }

    // ------------------------------------------------------------------ helpers

    private static void stamp(AuthenticatedUser user, br.com.erpadv.models.BaseEntity e, boolean created) {
        Instant now = Instant.now();
        if (created) {
            if (e.getId() == null) {
                e.setId(UUID.randomUUID().toString());
            }
            e.setCreatedAt(now);
            e.setCreatedBy(user.uid());
        }
        e.setUpdatedAt(now);
        e.setUpdatedBy(user.uid());
    }

    static String brl(long cents) {
        return "R$ " + String.format(PT_BR, "%,.2f", cents / 100.0);
    }

    private static String esc(String s) {
        return HtmlDocument.escape(s);
    }

    private static String digits(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("\\D", "");
        return d.length() >= 10 ? d : null;
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
