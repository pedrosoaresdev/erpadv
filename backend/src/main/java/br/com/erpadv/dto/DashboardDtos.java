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
            /** Indicadores que dependem de módulos ainda não implantados → etapa prevista. */
            Map<String, String> pendingModules) {
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
