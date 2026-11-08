package br.com.erpadv.services;

import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.AppointmentStatus;
import br.com.erpadv.models.AppointmentType;
import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.Contract;
import br.com.erpadv.models.ContractEnums.ContractStatus;
import br.com.erpadv.models.CrmEnums.LeadStage;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.Lead;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.AppointmentRepository;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.ContractRepositories;
import br.com.erpadv.repositories.CrmRepositories;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ReportExporter.Report;
import br.com.erpadv.services.generation.ReportExporter.Section;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central de relatórios: catálogo filtrado pelas permissões do usuário e geração em PDF/Excel com cabeçalho do
 * escritório, período e data de geração. Respeita sigilo (processos, prazos e compromissos restritos) e
 * mascaramento de CPF/CNPJ.
 */
@Service
public class ReportCenterService {

    private static final ZoneId ZONE = CourtCalendarService.ZONE;
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter BR_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);
    private static final int LIMIT = 5000;
    private static final Set<AppointmentType> HEARINGS = EnumSet.of(AppointmentType.AUDIENCIA, AppointmentType.JULGAMENTO,
            AppointmentType.SUSTENTACAO_ORAL, AppointmentType.PERICIA);

    public record ReportDef(String key, String group, String title, String description, boolean usesPeriod, boolean usesLawyer,
                            List<Permission> permissions) {
    }

    public static final List<ReportDef> CATALOG = List.of(
            def("clientes", "Clientes", "Clientes cadastrados", "Todos os clientes com tipo, situação, origem e contato.", false, false, Permission.CLIENTS_READ),
            def("clientes-periodo", "Clientes", "Clientes por período", "Novos clientes cadastrados no período, por origem.", true, false, Permission.CLIENTS_READ),
            def("processos-area", "Processos", "Processos por área", "Distribuição dos processos por área do direito e situação.", false, true, Permission.CASES_READ),
            def("processos-advogado", "Processos", "Processos por advogado", "Carteira de cada advogado (ativos, suspensos, encerrados).", false, true, Permission.CASES_READ),
            def("processos-tribunal", "Processos", "Processos por tribunal", "Processos por tribunal e comarca.", false, true, Permission.CASES_READ),
            def("processos-ativos", "Processos", "Processos ativos", "Processos em andamento, em recurso, aguardando julgamento ou suspensos.", false, true, Permission.CASES_READ),
            def("processos-encerrados", "Processos", "Processos encerrados", "Processos encerrados ou arquivados.", false, true, Permission.CASES_READ),
            def("prazos-vencidos", "Prazos e audiências", "Prazos vencidos", "Prazos pendentes com vencimento anterior a hoje.", false, true, Permission.DEADLINES_READ),
            def("prazos-cumpridos", "Prazos e audiências", "Prazos cumpridos", "Prazos concluídos no período, com antecedência.", true, true, Permission.DEADLINES_READ),
            def("audiencias-realizadas", "Prazos e audiências", "Audiências realizadas", "Audiências, julgamentos e perícias realizadas, com resultado.", true, true, Permission.CALENDAR_READ),
            def("contratos-ativos", "Contratos e honorários", "Contratos ativos", "Contratos vigentes com modelo de honorários e término.", false, true, Permission.CONTRACTS_READ),
            def("contratos-vencidos", "Contratos e honorários", "Contratos vencidos", "Contratos com término já ultrapassado.", false, true, Permission.CONTRACTS_READ),
            def("honorarios-recebidos", "Contratos e honorários", "Honorários recebidos", "Honorários recebidos no período (data do recebimento).", true, false, Permission.FINANCE_READ),
            def("honorarios-pendentes", "Contratos e honorários", "Honorários pendentes", "Honorários em aberto com vencimento no período.", true, false, Permission.FINANCE_READ),
            def("despesas", "Financeiro", "Despesas", "Despesas pagas e a pagar no período, por categoria e centro de custo.", true, false, Permission.FINANCE_READ),
            def("fluxo-caixa", "Financeiro", "Fluxo de caixa", "Entradas e saídas realizadas e previstas, mês a mês.", true, false, Permission.FINANCE_READ),
            def("produtividade", "Equipe e comercial", "Produtividade da equipe", "Processos, prazos cumpridos/vencidos e audiências por integrante.", true, false, Permission.CASES_READ, Permission.DEADLINES_READ),
            def("desempenho-comercial", "Equipe e comercial", "Desempenho comercial", "Funil do CRM, conversão por origem e por responsável.", true, false, Permission.CRM_USE),
            def("historico", "Equipe e comercial", "Histórico de alterações", "Trilha de auditoria do período (quem fez o quê e quando).", true, false, Permission.AUDIT_READ));

    private static ReportDef def(String key, String group, String title, String description, boolean period, boolean lawyer, Permission... perms) {
        return new ReportDef(key, group, title, description, period, lawyer, List.of(perms));
    }

    private final ClientRepository clients;
    private final CaseRepository cases;
    private final DeadlineRepository deadlines;
    private final AppointmentRepository appointments;
    private final ContractRepositories.Contracts contracts;
    private final FinancialTransactionRepository transactions;
    private final CrmRepositories.Leads leads;
    private final UserRepository users;
    private final OfficeRepository offices;
    private final AuditService audit;

    public ReportCenterService(ClientRepository clients, CaseRepository cases, DeadlineRepository deadlines, AppointmentRepository appointments,
                               ContractRepositories.Contracts contracts, FinancialTransactionRepository transactions, CrmRepositories.Leads leads,
                               UserRepository users, OfficeRepository offices, AuditService audit) {
        this.clients = clients;
        this.cases = cases;
        this.deadlines = deadlines;
        this.appointments = appointments;
        this.contracts = contracts;
        this.transactions = transactions;
        this.leads = leads;
        this.users = users;
        this.offices = offices;
        this.audit = audit;
    }

    public List<ReportDef> catalog(AuthenticatedUser user) {
        return CATALOG.stream().filter(d -> allowed(user, d)).toList();
    }

    private static boolean allowed(AuthenticatedUser user, ReportDef d) {
        return user.has(Permission.REPORTS_VIEW) && d.permissions().stream().allMatch(user::has);
    }

    public ReportExporter.Exported generate(AuthenticatedUser user, String key, LocalDate from, LocalDate to, String lawyerUid, String format) {
        String officeId = user.requireOffice();
        ReportDef def = CATALOG.stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow(() -> new NotFoundException("Relatório"));
        if (!allowed(user, def)) {
            throw new ForbiddenException("REPORT_FORBIDDEN", "Sem permissão para este relatório.");
        }
        LocalDate today = FinanceService.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusMonths(12).plusDays(1) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 1100) {
            throw new BusinessException("INVALID_RANGE", "Período inválido (máximo de 3 anos).");
        }
        String lawyer = blank(lawyerUid) ? null : lawyerUid;
        Ctx ctx = new Ctx(user, officeId, start, end, lawyer, today);
        List<Section> sections = switch (key) {
            case "clientes" -> clientsReport(ctx, false);
            case "clientes-periodo" -> clientsReport(ctx, true);
            case "processos-area" -> casesGrouped(ctx, c -> c.getArea() == null ? "Não informada" : c.getArea().label(), "Área");
            case "processos-advogado" -> casesGrouped(ctx, c -> c.getResponsibleName() == null ? "Sem responsável" : c.getResponsibleName(), "Advogado");
            case "processos-tribunal" -> casesGrouped(ctx, c -> (c.getCourt() == null ? "Não informado" : c.getCourt())
                    + (c.getDistrict() == null ? "" : " — " + c.getDistrict()), "Tribunal / comarca");
            case "processos-ativos" -> casesList(ctx, true);
            case "processos-encerrados" -> casesList(ctx, false);
            case "prazos-vencidos" -> overdueDeadlines(ctx);
            case "prazos-cumpridos" -> completedDeadlines(ctx);
            case "audiencias-realizadas" -> hearings(ctx);
            case "contratos-ativos" -> contractsReport(ctx, false);
            case "contratos-vencidos" -> contractsReport(ctx, true);
            case "honorarios-recebidos" -> feesReceived(ctx);
            case "honorarios-pendentes" -> feesPending(ctx);
            case "despesas" -> expenses(ctx);
            case "fluxo-caixa" -> cashFlow(ctx);
            case "produtividade" -> productivity(ctx);
            case "desempenho-comercial" -> commercial(ctx);
            case "historico" -> history(ctx);
            default -> throw new NotFoundException("Relatório");
        };
        String officeName = offices.findById(officeId).map(o -> o.getName()).orElse("Escritório");
        String subtitle = (def.usesPeriod() ? "Período: " + start.format(BR) + " a " + end.format(BR) : "Posição em " + today.format(BR))
                + (lawyer == null ? "" : " · Advogado: " + users.findInOffice(officeId, lawyer).map(UserProfile::getDisplayName).orElse("—"));
        boolean landscape = sections.stream().anyMatch(s -> s.headers() != null && s.headers().size() > 6);
        Report report = new Report(def.title(), officeName, subtitle, sections, landscape);
        audit.record(user, "REPORT_EXPORTED", "report", key, "Relatório gerado: " + def.title() + " (" + (format == null ? "pdf" : format) + ")",
                Map.of("from", start.toString(), "to", end.toString()));
        return ReportExporter.export(report, format, "relatorio-" + key + "-" + today);
    }

    private record Ctx(AuthenticatedUser user, String officeId, LocalDate from, LocalDate to, String lawyerUid, LocalDate today) {
        boolean inPeriod(LocalDate d) {
            return d != null && !d.isBefore(from) && !d.isAfter(to);
        }

        boolean inPeriod(Instant i) {
            return i != null && inPeriod(LocalDate.ofInstant(i, ZONE));
        }
    }

    // ------------------------------------------------------------------ clientes

    private List<Section> clientsReport(Ctx ctx, boolean period) {
        List<Client> list = clients.list(clients.collection(ctx.officeId()).orderBy("nameLower"), LIMIT).stream()
                .filter(c -> !period || ctx.inPeriod(c.getCreatedAt())).toList();
        List<List<String>> rows = list.stream().map(c -> row(c.getName(), c.getType() == null ? "" : c.getType().name(), c.getDocumentMasked(),
                c.getStatus() == null ? "" : c.getStatus().name(), c.getSource(), c.getPhone(), c.getEmail(), date(c.getCreatedAt()))).toList();
        List<Section> out = new ArrayList<>();
        out.add(Section.fields("Resumo", List.of(row("Clientes", String.valueOf(list.size())),
                row("Pessoas físicas", String.valueOf(list.stream().filter(c -> c.getType() != null && c.getType().name().equals("PF")).count())),
                row("Pessoas jurídicas", String.valueOf(list.stream().filter(c -> c.getType() != null && c.getType().name().equals("PJ")).count())),
                row("Inadimplentes", String.valueOf(list.stream().filter(Client::isDefaulting).count())))));
        if (period) {
            out.add(Section.table("Por origem", List.of("Origem", "Clientes"), count(list, c -> c.getSource() == null ? "Não informada" : c.getSource())));
        }
        out.add(Section.table("Clientes", List.of("Nome", "Tipo", "CPF/CNPJ", "Situação", "Origem", "Telefone", "E-mail", "Cadastro"), rows));
        return out;
    }

    // ------------------------------------------------------------------ processos

    private List<LegalCase> cases(Ctx ctx) {
        return cases.list(cases.collection(ctx.officeId()), LIMIT).stream()
                .filter(c -> !c.isRestricted() || ctx.user().has(Permission.CRIMINAL_READ))
                .filter(c -> ctx.lawyerUid() == null || ctx.lawyerUid().equals(c.getResponsibleUid()))
                .toList();
    }

    private List<Section> casesGrouped(Ctx ctx, Function<LegalCase, String> key, String label) {
        List<LegalCase> list = cases(ctx);
        Map<String, List<LegalCase>> grouped = new TreeMap<>();
        list.forEach(c -> grouped.computeIfAbsent(key.apply(c), k -> new ArrayList<>()).add(c));
        List<List<String>> rows = new ArrayList<>();
        grouped.forEach((k, v) -> rows.add(row(k, String.valueOf(v.size()),
                String.valueOf(v.stream().filter(c -> c.getStatus() != null && c.getStatus().isActive()).count()),
                String.valueOf(v.stream().filter(c -> c.getStatus() == CaseStatus.SUSPENSO).count()),
                String.valueOf(v.stream().filter(c -> c.getStatus() == CaseStatus.ENCERRADO || c.getStatus() == CaseStatus.ARQUIVADO).count()))));
        rows.sort(Comparator.comparingInt((List<String> r) -> Integer.parseInt(r.get(1))).reversed());
        return List.of(Section.fields("Resumo", List.of(row("Processos", String.valueOf(list.size())), row("Grupos", String.valueOf(grouped.size())))),
                Section.table("Processos por " + label.toLowerCase(PT_BR), List.of(label, "Total", "Ativos", "Suspensos", "Encerrados/arquivados"), rows));
    }

    private List<Section> casesList(Ctx ctx, boolean active) {
        List<LegalCase> list = cases(ctx).stream()
                .filter(c -> c.getStatus() != null && (active ? c.getStatus().isActive() : !c.getStatus().isActive()))
                .sorted(Comparator.comparing(LegalCase::getClientName, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        List<List<String>> rows = list.stream().map(c -> row(c.getCnjNumber() == null ? "Sem CNJ" : c.getCnjNumber(), c.getClientName(),
                c.getArea() == null ? "" : c.getArea().label(), c.getCourt(), c.getResponsibleName(), c.getStatus().label(),
                c.getPhase() == null ? "" : c.getPhase().name(), date(c.getLastMovementAt()))).toList();
        return List.of(Section.fields("Resumo", List.of(row(active ? "Processos ativos" : "Processos encerrados/arquivados", String.valueOf(list.size())))),
                Section.table("Processos", List.of("Nº CNJ", "Cliente", "Área", "Tribunal", "Responsável", "Situação", "Fase", "Última movimentação"), rows));
    }

    // ------------------------------------------------------------------ prazos e audiências

    private List<Deadline> deadlines(Ctx ctx, com.google.cloud.firestore.Query q) {
        return deadlines.list(q, LIMIT).stream()
                .filter(d -> !d.isRestricted() || ctx.user().has(Permission.CRIMINAL_READ))
                .filter(d -> ctx.lawyerUid() == null || ctx.lawyerUid().equals(d.getResponsibleUid()))
                .toList();
    }

    private List<Section> overdueDeadlines(Ctx ctx) {
        List<Deadline> list = deadlines(ctx, deadlines.collection(ctx.officeId()).whereEqualTo("status", DeadlineStatus.PENDENTE.name())
                        .whereLessThan("dueDate", ctx.today().toString())).stream()
                .sorted(Comparator.comparing(Deadline::getDueDate)).toList();
        List<List<String>> rows = list.stream().map(d -> row(d.getDueDate().format(BR), String.valueOf(ChronoUnit.DAYS.between(d.getDueDate(), ctx.today())),
                d.getDescription(), d.getType() == null ? "" : d.getType().name(), d.getCaseNumber(), d.getClientName(), d.getResponsibleName(),
                d.getPriority() == null ? "" : d.getPriority().name())).toList();
        return List.of(Section.fields("Resumo", List.of(row("Prazos vencidos", String.valueOf(list.size())))),
                Section.table("Por responsável", List.of("Responsável", "Prazos"), count(list, d -> nz(d.getResponsibleName(), "Sem responsável"))),
                Section.table("Prazos vencidos", List.of("Vencimento", "Dias", "Descrição", "Tipo", "Processo", "Cliente", "Responsável", "Prioridade"), rows));
    }

    private List<Section> completedDeadlines(Ctx ctx) {
        List<Deadline> list = deadlines(ctx, deadlines.collection(ctx.officeId()).whereEqualTo("status", DeadlineStatus.CONCLUIDO.name())).stream()
                .filter(d -> ctx.inPeriod(d.getCompletedAt() != null ? LocalDate.ofInstant(d.getCompletedAt(), ZONE) : d.getDueDate()))
                .sorted(Comparator.comparing(Deadline::getDueDate)).toList();
        long onTime = list.stream().filter(d -> d.getCompletedAt() == null || !LocalDate.ofInstant(d.getCompletedAt(), ZONE).isAfter(d.getDueDate())).count();
        List<List<String>> rows = list.stream().map(d -> row(d.getDueDate().format(BR), date(d.getCompletedAt()), d.getDescription(),
                d.getCaseNumber(), d.getClientName(), d.getResponsibleName(), d.getReceiptName() == null ? "—" : "Sim")).toList();
        return List.of(Section.fields("Resumo", List.of(row("Prazos cumpridos", String.valueOf(list.size())), row("No prazo", String.valueOf(onTime)),
                        row("Com comprovante", String.valueOf(list.stream().filter(d -> d.getReceiptName() != null).count())))),
                Section.table("Por responsável", List.of("Responsável", "Prazos"), count(list, d -> nz(d.getResponsibleName(), "Sem responsável"))),
                Section.table("Prazos cumpridos", List.of("Vencimento", "Concluído em", "Descrição", "Processo", "Cliente", "Responsável", "Comprovante"), rows));
    }

    private List<Section> hearings(Ctx ctx) {
        long from = ctx.from().atStartOfDay(ZONE).toInstant().toEpochMilli();
        long to = ctx.to().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<Appointment> list = appointments.list(appointments.collection(ctx.officeId()).whereGreaterThanOrEqualTo("start", from)
                        .whereLessThan("start", to).orderBy("start"), LIMIT).stream()
                .filter(a -> HEARINGS.contains(a.getType()) && a.getStatus() == AppointmentStatus.REALIZADO)
                .filter(a -> !a.isRestricted() || ctx.user().has(Permission.CRIMINAL_READ))
                .filter(a -> ctx.lawyerUid() == null || a.getResponsibleUids().contains(ctx.lawyerUid()))
                .toList();
        List<List<String>> rows = list.stream().map(a -> row(BR_TIME.format(a.getStart()), a.getType().label(), a.getTitle(), a.getCaseNumber(),
                a.getClientName(), String.join(", ", a.getResponsibleNames()), nz(a.getOutcome(), "—"))).toList();
        return List.of(Section.fields("Resumo", List.of(row("Audiências realizadas", String.valueOf(list.size())),
                        row("Sem resultado registrado", String.valueOf(list.stream().filter(a -> blank(a.getOutcome())).count())))),
                Section.table("Por tipo", List.of("Tipo", "Quantidade"), count(list, a -> a.getType().label())),
                Section.table("Audiências", List.of("Data", "Tipo", "Título", "Processo", "Cliente", "Responsáveis", "Resultado"), rows));
    }

    // ------------------------------------------------------------------ contratos e honorários

    private List<Section> contractsReport(Ctx ctx, boolean expired) {
        List<Contract> list = contracts.list(contracts.collection(ctx.officeId()), LIMIT).stream()
                .filter(c -> ctx.lawyerUid() == null || ctx.lawyerUid().equals(c.getResponsibleUid()))
                .filter(c -> expired ? c.getEndDate() != null && c.getEndDate().isBefore(ctx.today())
                        && (c.getStatus() == ContractStatus.ATIVO || c.getStatus() == ContractStatus.ENCERRADO)
                        : c.getStatus() == ContractStatus.ATIVO && (c.getEndDate() == null || !c.getEndDate().isBefore(ctx.today())))
                .sorted(Comparator.comparing(Contract::getEndDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        List<List<String>> rows = list.stream().map(c -> row(c.getNumber(), c.getTitle(), c.getClientName(), c.getType() == null ? "" : c.getType().label(),
                c.getFeeModel() == null ? "" : c.getFeeModel().label(), value(c), c.getStartDate() == null ? "" : c.getStartDate().format(BR),
                c.getEndDate() == null ? "Indeterminado" : c.getEndDate().format(BR), c.getStatus().name())).toList();
        return List.of(Section.fields("Resumo", List.of(row(expired ? "Contratos vencidos" : "Contratos ativos", String.valueOf(list.size())))),
                Section.table("Contratos", List.of("Número", "Título", "Cliente", "Tipo", "Honorários", "Valor", "Início", "Término", "Situação"), rows));
    }

    private static String value(Contract c) {
        if (c.getAmountCents() != null) {
            return brl(c.getAmountCents());
        }
        if (c.getMonthlyCents() != null) {
            return brl(c.getMonthlyCents()) + "/mês";
        }
        if (c.getSuccessPercentBp() != null) {
            return FeeService.percent(c.getSuccessPercentBp()) + " de êxito";
        }
        return c.getStages().isEmpty() ? "—" : brl(c.getStages().stream().mapToLong(Contract.FeeStage::getAmountCents).sum());
    }

    private List<FinancialTransaction> office(Ctx ctx, FinanceDirection direction) {
        return transactions.list(transactions.collection(ctx.officeId()).whereEqualTo("scope", FinanceScope.ESCRITORIO.name())
                .whereEqualTo("direction", direction.name()), 20000);
    }

    private List<Section> feesReceived(Ctx ctx) {
        List<FinancialTransaction> list = office(ctx, FinanceDirection.RECEITA).stream()
                .filter(t -> FeeService.FEE_CATEGORIES.contains(t.getCategory()) && t.getStatus() == TransactionStatus.PAGO && ctx.inPeriod(t.getPaidDate()))
                .sorted(Comparator.comparing(FinancialTransaction::getPaidDate)).toList();
        long total = list.stream().mapToLong(ReportCenterService::paid).sum();
        List<List<String>> rows = list.stream().map(t -> row(t.getPaidDate().format(BR), t.getDescription(), nz(t.getClientName(), t.getCounterparty()),
                t.getCaseNumber(), t.getCategory().label(), brl(paid(t)), t.getPaymentMethod() == null ? "" : t.getPaymentMethod().name())).toList();
        return List.of(Section.fields("Resumo", List.of(row("Total recebido", brl(total)), row("Recebimentos", String.valueOf(list.size())))),
                Section.table("Por categoria", List.of("Categoria", "Valor"), sum(list, t -> t.getCategory().label(), ReportCenterService::paid)),
                Section.table("Por cliente", List.of("Cliente", "Valor"), sum(list, t -> nz(t.getClientName(), nz(t.getCounterparty(), "Sem cliente")), ReportCenterService::paid)),
                Section.table("Recebimentos", List.of("Data", "Descrição", "Cliente", "Processo", "Categoria", "Valor", "Forma"), rows));
    }

    private List<Section> feesPending(Ctx ctx) {
        List<FinancialTransaction> list = office(ctx, FinanceDirection.RECEITA).stream()
                .filter(t -> FeeService.FEE_CATEGORIES.contains(t.getCategory()) && t.getStatus() == TransactionStatus.ABERTO && ctx.inPeriod(t.getDueDate()))
                .sorted(Comparator.comparing(FinancialTransaction::getDueDate)).toList();
        long total = list.stream().mapToLong(FinancialTransaction::getAmountCents).sum();
        long overdue = list.stream().filter(t -> t.getDueDate().isBefore(ctx.today())).mapToLong(FinancialTransaction::getAmountCents).sum();
        List<List<String>> rows = list.stream().map(t -> row(t.getDueDate().format(BR), t.getDescription(), nz(t.getClientName(), t.getCounterparty()),
                t.getCaseNumber(), t.getCategory().label(), brl(t.getAmountCents()),
                t.getDueDate().isBefore(ctx.today()) ? ChronoUnit.DAYS.between(t.getDueDate(), ctx.today()) + " dia(s) de atraso" : "A vencer")).toList();
        return List.of(Section.fields("Resumo", List.of(row("Total pendente", brl(total)), row("Em atraso", brl(overdue)), row("Títulos", String.valueOf(list.size())))),
                Section.table("Por cliente", List.of("Cliente", "Valor"), sum(list, t -> nz(t.getClientName(), nz(t.getCounterparty(), "Sem cliente")), FinancialTransaction::getAmountCents)),
                Section.table("Honorários pendentes", List.of("Vencimento", "Descrição", "Cliente", "Processo", "Categoria", "Valor", "Situação"), rows));
    }

    private List<Section> expenses(Ctx ctx) {
        List<FinancialTransaction> list = office(ctx, FinanceDirection.DESPESA).stream()
                .filter(t -> t.getStatus() != TransactionStatus.CANCELADO
                        && (t.getStatus() == TransactionStatus.PAGO ? ctx.inPeriod(t.getPaidDate()) : ctx.inPeriod(t.getDueDate())))
                .sorted(Comparator.comparing(FinancialTransaction::getDueDate)).toList();
        Function<FinancialTransaction, Long> value = t -> t.getStatus() == TransactionStatus.PAGO ? paid(t) : t.getAmountCents();
        long paidTotal = list.stream().filter(t -> t.getStatus() == TransactionStatus.PAGO).mapToLong(ReportCenterService::paid).sum();
        long openTotal = list.stream().filter(t -> t.getStatus() == TransactionStatus.ABERTO).mapToLong(FinancialTransaction::getAmountCents).sum();
        List<List<String>> rows = list.stream().map(t -> row(t.getStatus() == TransactionStatus.PAGO ? t.getPaidDate().format(BR) : t.getDueDate().format(BR),
                t.getDescription(), t.getCounterparty(), t.getCategory().label(), nz(t.getCostCenterName(), "—"), brl(value.apply(t)),
                t.getStatus() == TransactionStatus.PAGO ? "Paga" : "A pagar")).toList();
        return List.of(Section.fields("Resumo", List.of(row("Despesas pagas", brl(paidTotal)), row("A pagar", brl(openTotal)))),
                Section.table("Por categoria", List.of("Categoria", "Valor"), sum(list, t -> t.getCategory().label(), value)),
                Section.table("Por centro de custo", List.of("Centro de custo", "Valor"), sum(list, t -> nz(t.getCostCenterName(), "Sem centro de custo"), value)),
                Section.table("Despesas", List.of("Data", "Descrição", "Favorecido", "Categoria", "Centro de custo", "Valor", "Situação"), rows));
    }

    private List<Section> cashFlow(Ctx ctx) {
        Map<YearMonth, long[]> months = new TreeMap<>();
        for (YearMonth m = YearMonth.from(ctx.from()); !m.isAfter(YearMonth.from(ctx.to())); m = m.plusMonths(1)) {
            months.put(m, new long[4]);
        }
        for (FinanceDirection dir : FinanceDirection.values()) {
            for (FinancialTransaction t : office(ctx, dir)) {
                int base = dir == FinanceDirection.RECEITA ? 0 : 1;
                if (t.getStatus() == TransactionStatus.PAGO && ctx.inPeriod(t.getPaidDate())) {
                    months.get(YearMonth.from(t.getPaidDate()))[base] += paid(t);
                } else if (t.getStatus() == TransactionStatus.ABERTO && ctx.inPeriod(t.getDueDate())) {
                    months.get(YearMonth.from(t.getDueDate()))[base + 2] += t.getAmountCents();
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        long cumulative = 0;
        long in = 0;
        long out = 0;
        for (var e : months.entrySet()) {
            long[] v = e.getValue();
            cumulative += v[0] - v[1];
            in += v[0];
            out += v[1];
            rows.add(row(e.getKey().format(DateTimeFormatter.ofPattern("MM/yyyy")), brl(v[0]), brl(v[1]), brl(v[0] - v[1]), brl(cumulative),
                    brl(v[2]), brl(v[3])));
        }
        return List.of(Section.fields("Resumo", List.of(row("Entradas realizadas", brl(in)), row("Saídas realizadas", brl(out)),
                        row("Resultado do período", brl(in - out)))),
                Section.table("Fluxo de caixa mensal", List.of("Mês", "Entradas", "Saídas", "Saldo do mês", "Acumulado", "A receber (previsto)", "A pagar (previsto)"), rows));
    }

    // ------------------------------------------------------------------ equipe e comercial

    private List<Section> productivity(Ctx ctx) {
        List<UserProfile> team = users.activeStaff(ctx.officeId());
        List<LegalCase> allCases = cases(new Ctx(ctx.user(), ctx.officeId(), ctx.from(), ctx.to(), null, ctx.today()));
        List<Deadline> allDeadlines = deadlines(new Ctx(ctx.user(), ctx.officeId(), ctx.from(), ctx.to(), null, ctx.today()),
                deadlines.collection(ctx.officeId()).whereGreaterThanOrEqualTo("dueDate", ctx.from().minusYears(3).toString()));
        long from = ctx.from().atStartOfDay(ZONE).toInstant().toEpochMilli();
        long to = ctx.to().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<Appointment> appts = appointments.list(appointments.collection(ctx.officeId()).whereGreaterThanOrEqualTo("start", from)
                .whereLessThan("start", to), LIMIT);
        List<List<String>> rows = new ArrayList<>();
        for (UserProfile u : team) {
            String uid = u.getId();
            long activeCases = allCases.stream().filter(c -> uid.equals(c.getResponsibleUid()) && c.getStatus() != null && c.getStatus().isActive()).count();
            long done = allDeadlines.stream().filter(d -> uid.equals(d.getResponsibleUid()) && d.getStatus() == DeadlineStatus.CONCLUIDO
                    && ctx.inPeriod(d.getCompletedAt() != null ? LocalDate.ofInstant(d.getCompletedAt(), ZONE) : d.getDueDate())).count();
            long overdue = allDeadlines.stream().filter(d -> uid.equals(d.getResponsibleUid()) && d.getStatus() == DeadlineStatus.PENDENTE
                    && d.getDueDate().isBefore(ctx.today())).count();
            long hearings = appts.stream().filter(a -> HEARINGS.contains(a.getType()) && a.getStatus() == AppointmentStatus.REALIZADO
                    && a.getResponsibleUids().contains(uid)).count();
            long meetings = appts.stream().filter(a -> (a.getType() == AppointmentType.REUNIAO || a.getType() == AppointmentType.CONSULTA)
                    && a.getResponsibleUids().contains(uid)).count();
            long total = done + overdue;
            rows.add(row(u.getDisplayName(), u.getRole() == null ? "" : u.getRole().name(), String.valueOf(activeCases), String.valueOf(done),
                    String.valueOf(overdue), total == 0 ? "—" : Math.round(done * 100.0 / total) + "%", String.valueOf(hearings), String.valueOf(meetings)));
        }
        rows.sort(Comparator.comparing((List<String> r) -> r.get(0)));
        return List.of(Section.table("Produtividade por integrante",
                List.of("Integrante", "Papel", "Processos ativos", "Prazos cumpridos", "Prazos vencidos", "Cumprimento", "Audiências realizadas", "Reuniões"), rows));
    }

    private List<Section> commercial(Ctx ctx) {
        List<Lead> all = leads.list(leads.collection(ctx.officeId()), LIMIT).stream().filter(l -> ctx.inPeriod(l.getCreatedAt())).toList();
        long won = all.stream().filter(l -> l.getStage() == LeadStage.GANHO).count();
        long closed = all.stream().filter(l -> l.getStage() == LeadStage.PERDIDO).count();
        long open = all.size() - won - closed;
        long pipeline = all.stream().filter(l -> l.getStage().isOpen()).mapToLong(l -> l.getEstimatedValueCents() * l.getStage().probability() / 100).sum();
        List<List<String>> funnel = new ArrayList<>();
        for (LeadStage s : LeadStage.values()) {
            funnel.add(row(s.label(), String.valueOf(all.stream().filter(l -> l.getStage() == s).count()),
                    brl(all.stream().filter(l -> l.getStage() == s).mapToLong(Lead::getEstimatedValueCents).sum())));
        }
        Function<List<Lead>, String> rate = l -> {
            long w = l.stream().filter(x -> x.getStage() == LeadStage.GANHO).count();
            long c = l.stream().filter(x -> !x.getStage().isOpen()).count();
            return c == 0 ? "—" : Math.round(w * 100.0 / c) + "%";
        };
        List<List<String>> bySource = group(all, l -> l.getSource() == null ? "Não informada" : l.getSource().label()).entrySet().stream()
                .map(e -> row(e.getKey(), String.valueOf(e.getValue().size()), String.valueOf(e.getValue().stream().filter(x -> x.getStage() == LeadStage.GANHO).count()),
                        rate.apply(e.getValue()))).toList();
        List<List<String>> byOwner = group(all, l -> nz(l.getOwnerName(), "Sem responsável")).entrySet().stream()
                .map(e -> row(e.getKey(), String.valueOf(e.getValue().size()), String.valueOf(e.getValue().stream().filter(x -> x.getStage() == LeadStage.GANHO).count()),
                        rate.apply(e.getValue()))).toList();
        return List.of(Section.fields("Resumo", List.of(row("Oportunidades no período", String.valueOf(all.size())), row("Convertidas em cliente", String.valueOf(won)),
                        row("Encerradas sem contratação", String.valueOf(closed)), row("Em andamento", String.valueOf(open)),
                        row("Taxa de conversão", rate.apply(all)), row("Pipeline ponderado", brl(pipeline)))),
                Section.table("Funil", List.of("Etapa", "Oportunidades", "Valor estimado"), funnel),
                Section.table("Conversão por origem", List.of("Origem", "Oportunidades", "Convertidas", "Conversão"), bySource),
                Section.table("Conversão por responsável", List.of("Responsável", "Oportunidades", "Convertidas", "Conversão"), byOwner));
    }

    private List<Section> history(Ctx ctx) {
        List<List<String>> rows = new ArrayList<>();
        String cursor = null;
        Instant start = ctx.from().atStartOfDay(ZONE).toInstant();
        Instant end = ctx.to().plusDays(1).atStartOfDay(ZONE).toInstant();
        outer:
        for (int page = 0; page < 50; page++) {
            CursorPage<AuditLog> p = audit.list(ctx.officeId(), null, null, null, cursor, 100);
            for (AuditLog l : p.items()) {
                if (l.getAt() == null || l.getAt().isBefore(start)) {
                    break outer;
                }
                if (l.getAt().isBefore(end)) {
                    rows.add(row(BR_TIME.format(l.getAt()), nz(l.getActorEmail(), "sistema"), l.getAction(), nz(l.getEntityType(), ""), l.getSummary()));
                }
            }
            if (p.nextCursor() == null) {
                break;
            }
            cursor = p.nextCursor();
        }
        return List.of(Section.fields("Resumo", List.of(row("Registros", String.valueOf(rows.size())),
                        row("Observação", rows.size() >= 5000 ? "Limitado aos 5.000 registros mais recentes do período" : "Trilha completa do período"))),
                Section.table("Histórico", List.of("Quando", "Quem", "Ação", "Entidade", "Resumo"), rows));
    }

    // ------------------------------------------------------------------ helpers

    private static <T> Map<String, List<T>> group(List<T> items, Function<T, String> key) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        items.forEach(i -> out.computeIfAbsent(key.apply(i), k -> new ArrayList<>()).add(i));
        return out;
    }

    private static <T> List<List<String>> count(List<T> items, Function<T, String> key) {
        return group(items, key).entrySet().stream().sorted(Map.Entry.<String, List<T>>comparingByValue(Comparator.comparingInt(List::size)).reversed())
                .map(e -> row(e.getKey(), String.valueOf(e.getValue().size()))).collect(Collectors.toList());
    }

    private static <T> List<List<String>> sum(List<T> items, Function<T, String> key, Function<T, Long> value) {
        Map<String, Long> totals = new LinkedHashMap<>();
        items.forEach(i -> totals.merge(key.apply(i), value.apply(i), Long::sum));
        return totals.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> row(e.getKey(), brl(e.getValue()))).collect(Collectors.toList());
    }

    private static List<String> row(String... cells) {
        List<String> r = new ArrayList<>();
        for (String c : cells) {
            r.add(c == null ? "" : c);
        }
        return r;
    }

    private static long paid(FinancialTransaction t) {
        return t.getPaidAmountCents() == null ? t.getAmountCents() : t.getPaidAmountCents();
    }

    private static String date(Instant i) {
        return i == null ? "" : LocalDate.ofInstant(i, ZONE).format(BR);
    }

    private static String brl(long cents) {
        return FinanceSetupService.brl(cents);
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @SuppressWarnings("unused")
    private static boolean fee(FinanceCategory c) {
        return FeeService.FEE_CATEGORIES.contains(c);
    }
}
