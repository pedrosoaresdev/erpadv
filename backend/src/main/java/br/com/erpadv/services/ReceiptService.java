package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.DocumentDtos.DocumentMeta;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.models.DocumentCategory;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.Receipt;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ValorExtenso;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Recibos e declarações para clientes: recibo de pagamento, de reembolso de despesas, declaração de quitação e recibos de
 * entrega/devolução de documentos. Numeração anual, PDF no papel timbrado padrão, cópia na central documental do cliente e
 * cancelamento com motivo (o número não é reaproveitado).
 */
@Service
public class ReceiptService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT_BR);

    public record ReceiptRequest(Receipt.Kind kind, String clientId, String payerName, Long amountCents, String description,
                                 LocalDate date, String paymentMethod, String caseId, String city, boolean saveToDocuments) {
    }

    public record FromTransactionRequest(String transactionId, boolean saveToDocuments) {
    }

    public record PendingRevenue(String transactionId, String description, String clientId, String clientName, long amountCents,
                                 LocalDate paidDate, String paymentMethod, String caseNumber) {
    }

    @Repository
    public static class Receipts extends FirestoreRepository<Receipt> {
        private final Firestore db;

        public Receipts(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Receipt.class, "receipts");
            this.db = db;
        }

        public String nextNumber(String officeId, int year) {
            DocumentReference counter = db.collection("offices").document(officeId).collection("counters").document("receipts-" + year);
            long next = Futures.await(db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(counter).get();
                Long current = snap.exists() ? snap.getLong("value") : null;
                long value = current == null ? 1 : current + 1;
                tx.set(counter, Map.of("value", value));
                return value;
            }));
            return String.format("REC-%d-%04d", year, next);
        }
    }

    private final Receipts receipts;
    private final ClientRepository clients;
    private final CaseRepository cases;
    private final FinancialTransactionRepository transactions;
    private final OfficeRepository offices;
    private final CryptoService crypto;
    private final DocumentService documents;
    private final LegalEditorService editor;
    private final AuditService audit;

    public ReceiptService(Receipts receipts, ClientRepository clients, CaseRepository cases, FinancialTransactionRepository transactions,
                          OfficeRepository offices, CryptoService crypto, DocumentService documents, LegalEditorService editor,
                          AuditService audit) {
        this.receipts = receipts;
        this.clients = clients;
        this.cases = cases;
        this.transactions = transactions;
        this.offices = offices;
        this.crypto = crypto;
        this.documents = documents;
        this.editor = editor;
        this.audit = audit;
    }

    public List<Receipt> list(AuthenticatedUser user, String clientId) {
        Query q = receipts.collection(user.requireOffice());
        if (clientId != null && !clientId.isBlank()) {
            q = q.whereEqualTo("clientId", clientId);
        }
        return receipts.list(q, 2000).stream()
                .filter(r -> visible(user, r))
                .sorted(Comparator.comparing(Receipt::getNumber, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public Receipt get(AuthenticatedUser user, String id) {
        Receipt r = receipts.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Recibo"));
        if (!visible(user, r)) {
            throw new NotFoundException("Recibo");
        }
        return r;
    }

    /** Recibos com valor ficam com quem vê o financeiro; os de documentos, com quem vê a central documental. */
    private static boolean visible(AuthenticatedUser user, Receipt r) {
        return r.getKind() == null || (r.getKind().monetary() ? user.has(Permission.FINANCE_READ) : user.has(Permission.DOCUMENTS_READ));
    }

    private static void requireIssue(AuthenticatedUser user, Receipt.Kind kind) {
        if (kind.monetary() ? !user.has(Permission.FINANCE_WRITE) : !user.has(Permission.DOCUMENTS_WRITE)) {
            throw new ForbiddenException("ACCESS_DENIED", kind.monetary()
                    ? "Emitir recibos com valor exige permissão para lançar no financeiro."
                    : "Emitir recibos de documentos exige permissão para enviar documentos.");
        }
    }

    // ------------------------------------------------------------------ emissão

    public Receipt create(AuthenticatedUser user, ReceiptRequest req) {
        String officeId = user.requireOffice();
        if (req.kind() == null) {
            throw new BusinessException("KIND_REQUIRED", "Escolha o tipo de documento.");
        }
        requireIssue(user, req.kind());
        if (req.clientId() == null || req.clientId().isBlank()) {
            throw new BusinessException("CLIENT_REQUIRED", "Escolha o cliente.");
        }
        Client client = clients.findById(officeId, req.clientId()).orElseThrow(() -> new NotFoundException("Cliente"));
        if (client.getAnonymizedAt() != null) {
            throw new BusinessException("CLIENT_ANONYMIZED", "Cliente anonimizado (LGPD): não é possível emitir documentos em nome dele.");
        }
        String description = clean(req.description(), 2000);
        if (description == null) {
            throw new BusinessException("DESCRIPTION_REQUIRED", req.kind().monetary() ? "Informe a que se refere o valor."
                    : "Liste os documentos entregues ou devolvidos.");
        }
        if (req.kind().monetary() && (req.amountCents() == null || req.amountCents() <= 0 || req.amountCents() > 100_000_000_000L)) {
            throw new BusinessException("INVALID_AMOUNT", "Informe um valor maior que zero.");
        }
        LocalDate date = req.date() == null ? FinanceService.today() : req.date();
        if (date.isAfter(FinanceService.today())) {
            throw new BusinessException("INVALID_DATE", "A data não pode ser futura.");
        }
        Receipt r = new Receipt();
        r.setKind(req.kind());
        r.setClientId(client.getId());
        r.setClientName(client.getName());
        r.setPayerName(clean(req.payerName(), 200) == null ? client.getName() : clean(req.payerName(), 200));
        r.setPayerDocumentMasked(r.getPayerName().equals(client.getName()) ? client.getDocumentMasked() : null);
        r.setAmountCents(req.kind().monetary() ? req.amountCents() : null);
        r.setDescription(description);
        r.setDate(date);
        r.setPaymentMethod(req.kind().monetary() ? clean(req.paymentMethod(), 60) : null);
        if (req.caseId() != null && !req.caseId().isBlank()) {
            LegalCase c = cases.findById(officeId, req.caseId()).orElseThrow(() -> new NotFoundException("Processo"));
            if (!client.getId().equals(c.getClientId())) {
                throw new BusinessException("CASE_CLIENT_MISMATCH", "O processo escolhido não é deste cliente.");
            }
            if (c.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
                throw new NotFoundException("Processo");
            }
            r.setCaseId(c.getId());
            r.setCaseNumber(c.getCnjNumber());
        }
        return issue(user, r, clean(req.city(), 80), req.saveToDocuments());
    }

    /** Recibo de uma receita do escritório já recebida no financeiro (o número passa a constar no lançamento). */
    public Receipt fromTransaction(AuthenticatedUser user, FromTransactionRequest req) {
        String officeId = user.requireOffice();
        requireIssue(user, Receipt.Kind.PAGAMENTO);
        FinancialTransaction t = transactions.findById(officeId, req.transactionId()).orElseThrow(() -> new NotFoundException("Lançamento"));
        if (t.getScope() != FinanceScope.ESCRITORIO || t.getDirection() != FinanceDirection.RECEITA || t.getStatus() != TransactionStatus.PAGO) {
            throw new BusinessException("RECEIPT_UNAVAILABLE", "O recibo é emitido para receitas do escritório já recebidas.");
        }
        if (t.getClientId() == null) {
            throw new BusinessException("CLIENT_REQUIRED", "O lançamento não está vinculado a um cliente.");
        }
        boolean exists = receipts.list(receipts.collection(officeId).whereEqualTo("transactionId", t.getId()), 10).stream().anyMatch(x -> !x.isCanceled());
        if (exists) {
            throw new BusinessException("RECEIPT_EXISTS", "Este recebimento já tem recibo emitido.");
        }
        Client client = clients.findById(officeId, t.getClientId()).orElseThrow(() -> new NotFoundException("Cliente"));
        Receipt r = new Receipt();
        r.setKind(Receipt.Kind.PAGAMENTO);
        r.setClientId(client.getId());
        r.setClientName(client.getName());
        r.setPayerName(t.getCounterparty() != null ? t.getCounterparty() : client.getName());
        r.setPayerDocumentMasked(r.getPayerName().equals(client.getName()) ? client.getDocumentMasked() : null);
        r.setAmountCents(t.getPaidAmountCents() != null ? t.getPaidAmountCents() : t.getAmountCents());
        r.setDescription(t.getDescription() + (t.getInstallmentCount() != null ? " (parcela " + t.getInstallmentNumber() + "/" + t.getInstallmentCount() + ")" : ""));
        r.setDate(t.getPaidDate() == null ? FinanceService.today() : t.getPaidDate());
        r.setPaymentMethod(t.getPaymentMethod() == null ? null : method(t.getPaymentMethod().name()));
        r.setCaseId(t.getCaseId());
        r.setCaseNumber(t.getCaseNumber());
        r.setTransactionId(t.getId());
        Receipt saved = issue(user, r, null, req.saveToDocuments());
        t.setReceiptNumber(saved.getNumber());
        transactions.save(officeId, t);
        return saved;
    }

    private Receipt issue(AuthenticatedUser user, Receipt r, String city, boolean saveToDocuments) {
        String officeId = user.officeId();
        Office office = offices.findById(officeId).orElse(null);
        r.setId(UUID.randomUUID().toString());
        r.setNumber(receipts.nextNumber(officeId, r.getDate().getYear()));
        r.setCity(city != null ? city : office == null ? null : office.getCity());
        r.setIssuerName(user.displayName());
        r.setCreatedAt(Instant.now());
        r.setCreatedBy(user.uid());
        r.setUpdatedAt(r.getCreatedAt());
        r.setUpdatedBy(user.uid());
        receipts.save(officeId, r);
        if (saveToDocuments && user.has(Permission.DOCUMENTS_WRITE)) {
            byte[] pdf = render(user, r, office);
            DocumentResponse d = documents.upload(user, new DocumentMeta(r.getKind().label() + " " + r.getNumber(),
                    "Emitido em Recibos por " + user.displayName(), DocumentCategory.COMPROVANTE, r.getClientId(), r.getCaseId(),
                    false, false), pdf, fileName(r));
            r.setDocumentId(d.id());
            receipts.save(officeId, r);
        }
        audit.record(user, "RECEIPT_ISSUED", "receipt", r.getId(), r.getKind().label() + " " + r.getNumber() + " · " + r.getClientName()
                + (r.getAmountCents() == null ? "" : " · " + brl(r.getAmountCents())));
        return r;
    }

    public Receipt cancel(AuthenticatedUser user, String id, String reason) {
        Receipt r = get(user, id);
        requireIssue(user, r.getKind());
        if (r.isCanceled()) {
            throw new BusinessException("ALREADY_CANCELED", "Este recibo já foi cancelado.");
        }
        if (clean(reason, 500) == null) {
            throw new BusinessException("REASON_REQUIRED", "Informe o motivo do cancelamento.");
        }
        r.setCanceled(true);
        r.setCancelReason(clean(reason, 500));
        r.setCanceledAt(Instant.now());
        r.setCanceledByName(user.displayName());
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(user.uid());
        receipts.save(user.officeId(), r);
        if (r.getTransactionId() != null) {
            transactions.findById(user.officeId(), r.getTransactionId()).filter(t -> r.getNumber().equals(t.getReceiptNumber())).ifPresent(t -> {
                t.setReceiptNumber(null);
                transactions.save(user.officeId(), t);
            });
        }
        audit.record(user, "RECEIPT_CANCELED", "receipt", id, "Recibo " + r.getNumber() + " cancelado: " + r.getCancelReason());
        return r;
    }

    /** Receitas recebidas nos últimos 180 dias, vinculadas a cliente e ainda sem recibo. */
    public List<PendingRevenue> pending(AuthenticatedUser user) {
        if (!user.has(Permission.FINANCE_READ)) {
            throw new ForbiddenException("ACCESS_DENIED", "Sem acesso ao financeiro.");
        }
        LocalDate since = FinanceService.today().minusDays(180);
        return transactions.list(transactions.collection(user.requireOffice()).whereEqualTo("status", TransactionStatus.PAGO.name()), 5000).stream()
                .filter(t -> t.getScope() == FinanceScope.ESCRITORIO && t.getDirection() == FinanceDirection.RECEITA)
                .filter(t -> t.getClientId() != null && t.getReceiptNumber() == null)
                .filter(t -> t.getPaidDate() != null && !t.getPaidDate().isBefore(since))
                .sorted(Comparator.comparing(FinancialTransaction::getPaidDate).reversed())
                .map(t -> new PendingRevenue(t.getId(), t.getDescription(), t.getClientId(), t.getClientName(),
                        t.getPaidAmountCents() != null ? t.getPaidAmountCents() : t.getAmountCents(), t.getPaidDate(),
                        t.getPaymentMethod() == null ? null : method(t.getPaymentMethod().name()), t.getCaseNumber()))
                .toList();
    }

    // ------------------------------------------------------------------ PDF

    public ReportExporter.Exported pdf(AuthenticatedUser user, String id) {
        Receipt r = get(user, id);
        byte[] pdf = render(user, r, offices.findById(user.officeId()).orElse(null));
        return new ReportExporter.Exported(pdf, fileName(r), ReportExporter.PDF);
    }

    private static String fileName(Receipt r) {
        return (r.getKind().monetary() ? "recibo-" : "recibo-documentos-") + r.getNumber() + ".pdf";
    }

    private byte[] render(AuthenticatedUser user, Receipt r, Office office) {
        String payerDoc = payerDocument(user, r);
        String payer = "<strong>" + esc(r.getPayerName()) + "</strong>" + (payerDoc == null ? "" : ", " + esc(payerDoc));
        String ref = esc(r.getDescription()) + (r.getCaseNumber() == null ? "" : ", processo nº " + esc(r.getCaseNumber()));
        String officeName = office == null ? "" : office.getName();
        StringBuilder h = new StringBuilder();
        String title = switch (r.getKind()) {
            case PAGAMENTO, REEMBOLSO -> "RECIBO Nº " + r.getNumber();
            case QUITACAO -> "DECLARAÇÃO DE QUITAÇÃO Nº " + r.getNumber();
            case ENTREGA_DOCUMENTOS -> "RECIBO DE ENTREGA DE DOCUMENTOS Nº " + r.getNumber();
            case DEVOLUCAO_DOCUMENTOS -> "RECIBO DE DEVOLUÇÃO DE DOCUMENTOS Nº " + r.getNumber();
        };
        h.append("<h1>").append(esc(title)).append("</h1>");
        if (r.isCanceled()) {
            h.append("<p style=\"text-align: center;\"><strong>DOCUMENTO CANCELADO em ")
                    .append(r.getCanceledAt().atZone(CourtCalendarService.ZONE).toLocalDate().format(BR)).append(" — ")
                    .append(esc(r.getCancelReason())).append("</strong></p>");
        }
        if (r.getAmountCents() != null) {
            h.append("<p style=\"text-align: right;\"><strong>Valor: ").append(esc(brl(r.getAmountCents()))).append("</strong></p>");
        }
        h.append("<p><br></p>");
        String valor = r.getAmountCents() == null ? "" : "<strong>" + esc(brl(r.getAmountCents())) + "</strong> ("
                + esc(ValorExtenso.reais(r.getAmountCents())) + ")";
        switch (r.getKind()) {
            case PAGAMENTO -> h.append("<p>Recebemos de ").append(payer).append(" a importância de ").append(valor)
                    .append(", referente a ").append(ref).append(".</p>")
                    .append(r.getPaymentMethod() == null ? "" : "<p>Forma de pagamento: " + esc(r.getPaymentMethod()) + ".</p>")
                    .append("<p>Para clareza e como prova de quitação do valor acima, firmamos o presente recibo.</p>");
            case REEMBOLSO -> h.append("<p>Recebemos de ").append(payer).append(" a importância de ").append(valor)
                    .append(", a título de reembolso de despesas referentes a ").append(ref).append(".</p>")
                    .append(r.getPaymentMethod() == null ? "" : "<p>Forma de pagamento: " + esc(r.getPaymentMethod()) + ".</p>")
                    .append("<p>Os comprovantes das despesas ficam à disposição do cliente.</p>");
            case QUITACAO -> h.append("<p>Declaramos, para os devidos fins, que ").append(payer).append(" quitou integralmente o valor de ")
                    .append(valor).append(", referente a ").append(ref)
                    .append(", nada mais havendo a reclamar a esse título, dando-se plena, geral e irrevogável quitação.</p>");
            case ENTREGA_DOCUMENTOS -> h.append("<p>Recebemos de ").append(payer).append(" os documentos abaixo relacionados")
                    .append(r.getCaseNumber() == null ? "" : ", para instrução do processo nº " + esc(r.getCaseNumber()))
                    .append(", que ficarão sob guarda do escritório enquanto necessários:</p>").append(list(r.getDescription()));
            case DEVOLUCAO_DOCUMENTOS -> h.append("<p>Declaro ter recebido de ").append(esc(officeName))
                    .append(" a devolução dos documentos abaixo relacionados")
                    .append(r.getCaseNumber() == null ? "" : ", referentes ao processo nº " + esc(r.getCaseNumber())).append(":</p>")
                    .append(list(r.getDescription()));
        }
        h.append("<p><br></p><p style=\"text-align: right;\">").append(esc(r.getCity() == null ? "" : r.getCity() + ", "))
                .append(esc(r.getDate().format(LONG_DATE))).append(".</p><p><br></p><p><br></p>");
        String signer = r.getKind() == Receipt.Kind.DEVOLUCAO_DOCUMENTOS
                ? esc(r.getPayerName()) + (payerDoc == null ? "" : "<br>" + esc(payerDoc))
                : esc(officeName) + (office != null && office.getCnpj() != null ? "<br>CNPJ " + esc(BrazilianDocuments.format(office.getCnpj())) : "");
        h.append("<p style=\"text-align: center;\">_______________________________________<br>").append(signer).append("</p>");
        HtmlDocument.Background bg = editor.defaultBackground(user.officeId());
        String header = bg == null && office != null ? office.getName().toUpperCase(PT_BR) : null;
        return HtmlDocument.toPdf(h.toString(), new HtmlDocument.Layout(header, r.getNumber() + " · emitido por " + r.getIssuerName()
                + " pelo ERPAdv", false, bg));
    }

    /** CPF/CNPJ completo só para quem pode ver dados sensíveis; os demais veem o número mascarado. */
    private String payerDocument(AuthenticatedUser user, Receipt r) {
        if (r.getPayerDocumentMasked() == null) {
            return null;
        }
        Client c = clients.findById(user.officeId(), r.getClientId()).orElse(null);
        boolean pj = c != null && c.getType() == ClientType.PJ;
        String doc = r.getPayerDocumentMasked();
        if (c != null && c.getDocumentEnc() != null && user.has(Permission.CLIENTS_SENSITIVE_READ)) {
            doc = BrazilianDocuments.format(crypto.decrypt(c.getDocumentEnc()));
        }
        return (pj ? "CNPJ " : "CPF ") + doc;
    }

    private static String list(String text) {
        StringBuilder sb = new StringBuilder("<ul>");
        for (String line : text.split("\\r?\\n|;")) {
            String l = line.strip().replaceFirst("^[-•*]\\s*", "");
            if (!l.isEmpty()) {
                sb.append("<li>").append(esc(l)).append("</li>");
            }
        }
        return sb.append("</ul>").toString();
    }

    private static String method(String m) {
        return switch (m) {
            case "PIX" -> "Pix";
            case "BOLETO" -> "boleto bancário";
            case "TRANSFERENCIA" -> "transferência bancária";
            case "CARTAO" -> "cartão";
            case "DINHEIRO" -> "dinheiro";
            case "CHEQUE" -> "cheque";
            default -> "outra";
        };
    }

    private static String brl(long cents) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(cents / 100.0).replace(' ', ' ');
    }

    private static String esc(String s) {
        return HtmlDocument.escape(s == null ? "" : s);
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
