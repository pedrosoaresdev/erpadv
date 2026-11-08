package br.com.erpadv.services.integrations;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CaseDtos.CaseRequest;
import br.com.erpadv.dto.CaseDtos.CaseResponse;
import br.com.erpadv.exceptions.ApiException;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.LegalArea;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CaseService;
import br.com.erpadv.validation.BrazilianDocuments;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Importação assistida de processos por planilha CSV — caminho permitido para tribunais sem API pública
 * (PJe, eproc, Projudi, e-SAJ): o escritório exporta a lista do próprio sistema e importa aqui. Evita duplicidade
 * pelo número CNJ e vincula ao cliente pelo CPF/CNPJ ou nome; nunca cria clientes automaticamente.
 */
@Service
public class CaseImportService {

    public static final String TEMPLATE = "numero_cnj;cliente_cpf_cnpj;cliente_nome;area;tribunal;vara;comarca;uf;parte_contraria;"
            + "classe;assunto;valor_causa;responsavel_email\n"
            + "0000000-00.0000.0.00.0000;000.000.000-00;Nome do cliente;CIVEL;TJPE;1ª Vara Cível;Recife;PE;Parte contrária;Procedimento Comum Cível;"
            + "Indenização por Dano Moral;10000,00;advogado@escritorio.com.br\n";
    private static final int MAX_ROWS = 1000;

    public record RowResult(int line, String cnj, String status, String message, String caseId) {
    }

    public record ImportResult(int total, int created, int duplicates, int errors, List<RowResult> rows) {
    }

    private final CaseService cases;
    private final CaseRepository caseRepo;
    private final ClientRepository clients;
    private final UserRepository users;
    private final CryptoService crypto;
    private final Validator validator;
    private final AuditService audit;

    public CaseImportService(CaseService cases, CaseRepository caseRepo, ClientRepository clients, UserRepository users, CryptoService crypto,
                             Validator validator, AuditService audit) {
        this.cases = cases;
        this.caseRepo = caseRepo;
        this.clients = clients;
        this.users = users;
        this.crypto = crypto;
        this.validator = validator;
        this.audit = audit;
    }

    public ImportResult importCsv(AuthenticatedUser user, byte[] bytes) {
        String officeId = user.requireOffice();
        String text = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.size() < 2) {
            throw new BusinessException("EMPTY_FILE", "A planilha não tem linhas de dados.");
        }
        if (lines.size() - 1 > MAX_ROWS) {
            throw new BusinessException("TOO_MANY_ROWS", "Importe no máximo " + MAX_ROWS + " processos por vez.");
        }
        char sep = lines.get(0).chars().filter(ch -> ch == ';').count() >= lines.get(0).chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<String> header = split(lines.get(0), sep).stream().map(h -> SearchTokens.normalize(h).replace(' ', '_')).toList();
        if (!header.contains("numero_cnj")) {
            throw new BusinessException("INVALID_HEADER", "Cabeçalho inválido: use o modelo (coluna numero_cnj obrigatória).");
        }
        Map<String, String> userByEmail = new HashMap<>();
        for (UserProfile u : users.activeStaff(officeId)) {
            if (u.getEmail() != null) {
                userByEmail.put(u.getEmail().toLowerCase(Locale.ROOT), u.getId());
            }
        }
        List<RowResult> rows = new ArrayList<>();
        int created = 0;
        int dup = 0;
        int errors = 0;
        for (int i = 1; i < lines.size(); i++) {
            Map<String, String> r = new LinkedHashMap<>();
            List<String> cells = split(lines.get(i), sep);
            for (int c = 0; c < header.size() && c < cells.size(); c++) {
                r.put(header.get(c), cells.get(c).trim());
            }
            String cnj = BrazilianDocuments.formatCnj(r.getOrDefault("numero_cnj", ""));
            try {
                if (cnj == null || !BrazilianDocuments.isValidCnj(cnj)) {
                    throw new BusinessException("INVALID_CNJ", "Número CNJ inválido.");
                }
                if (!caseRepo.list(caseRepo.collection(officeId).whereEqualTo("cnjNumber", cnj), 1).isEmpty()) {
                    rows.add(new RowResult(i + 1, cnj, "DUPLICADO", "Processo já cadastrado.", null));
                    dup++;
                    continue;
                }
                Client client = findClient(officeId, r.get("cliente_cpf_cnpj"), r.get("cliente_nome"));
                String responsible = userByEmail.getOrDefault(r.getOrDefault("responsavel_email", "").toLowerCase(Locale.ROOT), user.uid());
                Map<String, Object> req = new HashMap<>();
                req.put("cnjNumber", cnj);
                req.put("clientId", client.getId());
                req.put("opposingParty", blankToNull(r.get("parte_contraria")));
                req.put("court", blankToNull(r.get("tribunal")));
                req.put("courtDivision", blankToNull(r.get("vara")));
                req.put("district", blankToNull(r.get("comarca")));
                req.put("state", blankToNull(r.get("uf")) == null ? null : r.get("uf").toUpperCase(Locale.ROOT));
                req.put("area", area(r.get("area")).name());
                req.put("proceduralClass", blankToNull(r.get("classe")));
                req.put("subject", blankToNull(r.get("assunto")));
                req.put("responsibleUid", responsible);
                req.put("claimValueCents", cents(r.get("valor_causa")));
                req.put("status", CaseStatus.EM_ANDAMENTO.name());
                req.put("notes", "Importado por planilha em " + br.com.erpadv.services.FinanceService.today());
                CaseRequest request = HttpJson.JSON.convertValue(req, CaseRequest.class);
                Set<ConstraintViolation<CaseRequest>> violations = validator.validate(request);
                if (!violations.isEmpty()) {
                    ConstraintViolation<CaseRequest> v = violations.iterator().next();
                    throw new BusinessException("INVALID_ROW", v.getPropertyPath() + ": " + v.getMessage());
                }
                CaseResponse c = cases.create(user, request);
                rows.add(new RowResult(i + 1, cnj, "CRIADO", "Processo cadastrado (" + client.getName() + ").", c.id()));
                created++;
            } catch (ApiException e) {
                rows.add(new RowResult(i + 1, cnj, "ERRO", e.getMessage(), null));
                errors++;
            } catch (IllegalArgumentException e) {
                rows.add(new RowResult(i + 1, cnj, "ERRO", "Valor inválido na linha.", null));
                errors++;
            }
        }
        audit.record(user, "CASES_IMPORTED", "case", null, "Importação de planilha: " + created + " criado(s), " + dup + " duplicado(s), "
                + errors + " erro(s)");
        return new ImportResult(lines.size() - 1, created, dup, errors, rows);
    }

    private Client findClient(String officeId, String document, String name) {
        String digits = BrazilianDocuments.digits(document);
        if (digits != null && (digits.length() == 11 || digits.length() == 14)) {
            var byDoc = clients.findByDocumentHash(officeId, crypto.hmac(digits));
            if (byDoc.isPresent()) {
                return byDoc.get();
            }
        }
        if (name != null && !name.isBlank()) {
            List<Client> byName = clients.list(clients.collection(officeId).whereEqualTo("nameLower", SearchTokens.normalize(name)), 2);
            if (byName.size() == 1) {
                return byName.get(0);
            }
            if (byName.size() > 1) {
                throw new BusinessException("AMBIGUOUS_CLIENT", "Há mais de um cliente com esse nome — informe o CPF/CNPJ.");
            }
        }
        throw new BusinessException("CLIENT_NOT_FOUND", "Cliente não encontrado — cadastre-o antes (o import não cria clientes).");
    }

    private static LegalArea area(String raw) {
        if (raw == null || raw.isBlank()) {
            return LegalArea.OUTRO;
        }
        String n = SearchTokens.normalize(raw);
        for (LegalArea a : LegalArea.values()) {
            if (a.name().equalsIgnoreCase(raw.trim()) || SearchTokens.normalize(a.label()).startsWith(n)) {
                return a;
            }
        }
        return LegalArea.OUTRO;
    }

    private static Long cents(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace("R$", "").replace(" ", "");
        if (s.contains(",")) {
            s = s.replace(".", "").replace(',', '.');
        }
        return Math.round(Double.parseDouble(s) * 100);
    }

    static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
