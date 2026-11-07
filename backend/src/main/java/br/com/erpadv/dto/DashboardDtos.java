package br.com.erpadv.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record DashboardResponse(
            LocalDate from,
            LocalDate to,
            ClientStats clients,
            CaseStats cases,
            List<MonthPoint> clientGrowth,
            List<MonthPoint> newCasesByMonth,
            List<NamedCount> casesByArea,
            List<NamedCount> casesByStatus,
            List<NamedCount> productivity,
            DeadlineStats deadlines,
            HearingStats hearings,
            ContractStats contracts,
            /** Indicadores que dependem de módulos ainda não implantados → etapa prevista. */
            Map<String, String> pendingModules,
            String lawyerUid,
            DeadlineWindows deadlineWindows,
            CaseExtra caseExtra,
            long agendaNext7Days,
            List<DeadlineMonth> deadlineTrend,
            List<NamedCount> casesByCourt,
            List<NamedCount> clientsBySource,
            List<TeamMember> team,
            Comparison previous) {
    }

    /** Prazos pendentes que vencem nos próximos N dias (excluindo hoje). */
    public record DeadlineWindows(long next3Days, long next7Days, long next15Days) {
    }

    /** {@code awaitingMovement}: ativos sem movimentação há {@code staleDays} dias ou mais. */
    public record CaseExtra(long suspended, long awaitingMovement, int staleDays) {
    }

    /** Prazos cumpridos × não cumpridos (pendentes já vencidos) por mês de vencimento. */
    public record DeadlineMonth(String month, long completed, long missed) {
    }

    public record TeamMember(String uid, String name, long activeCases, long deadlinesCompleted, long deadlinesOverdue) {
    }

    /** Mesmos indicadores no período anterior de mesma duração. */
    public record Comparison(LocalDate from, LocalDate to, long newClients, long newCases, long deadlinesCompleted) {
    }

    public record DeadlineStats(long dueToday, long urgent, long overdue, long upcoming, long completedInPeriod,
                                long unvalidated) {
    }

    /** Audiências: próximas 30 dias e distribuição mensal no período. */
    public record HearingStats(long next30Days, List<MonthPoint> byMonth) {
    }

    public record ContractStats(long active, long expiring30Days, long drafts) {
    }

    public record ClientStats(long total, long active, long inactive, long defaulting, long newInPeriod) {
    }

    public record CaseStats(long total, long inProgress, long closed, long archived, long newInPeriod) {
    }

    /** {@code month} no formato yyyy-MM; {@code cumulative} pode ser nulo quando não se aplica. */
    public record MonthPoint(String month, long value, Long cumulative) {
    }

    public record NamedCount(String key, String label, long value) {
    }
}
