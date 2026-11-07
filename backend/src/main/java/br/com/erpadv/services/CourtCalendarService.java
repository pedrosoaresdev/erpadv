package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.DeadlineDtos.CalculateRequest;
import br.com.erpadv.dto.DeadlineDtos.CourtCalendarRequest;
import br.com.erpadv.dto.DeadlineDtos.CourtCalendarResponse;
import br.com.erpadv.dto.DeadlineDtos.HolidayInfo;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.CourtCalendarEntry;
import br.com.erpadv.repositories.CourtCalendarRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.deadlines.BrazilianHolidays;
import br.com.erpadv.services.deadlines.DeadlineCalculator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Calendário forense do escritório (feriados locais e suspensões) e cálculo de prazos. */
@Service
public class CourtCalendarService {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record CacheEntry(List<CourtCalendarEntry> entries, Instant expiresAt) {
    }

    private final CourtCalendarRepository repository;
    private final AuditService audit;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CourtCalendarService(CourtCalendarRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public DeadlineCalculator calculator(String officeId, String court) {
        return new DeadlineCalculator(entries(officeId), court);
    }

    public DeadlineCalculator.Result calculate(AuthenticatedUser user, CalculateRequest req) {
        try {
            return calculator(user.requireOffice(), req.court()).calculate(req.startDate(), req.days(), req.countingMode());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_CALCULATION", "Informe data inicial, quantidade de dias e modo de contagem (úteis ou corridos).");
        }
    }

    public CourtCalendarResponse year(AuthenticatedUser user, int year) {
        if (year < 2000 || year > 2100) {
            throw new BusinessException("INVALID_YEAR", "Ano inválido.");
        }
        List<HolidayInfo> national = BrazilianHolidays.of(year).entrySet().stream()
                .map(e -> new HolidayInfo(e.getKey(), e.getValue(), true)).toList();
        return new CourtCalendarResponse(national, repository.byYear(user.requireOffice(), year));
    }

    public CourtCalendarEntry create(AuthenticatedUser user, CourtCalendarRequest req) {
        String officeId = user.requireOffice();
        LocalDate end = req.endDate() == null ? req.startDate() : req.endDate();
        if (end.isBefore(req.startDate())) {
            throw new BusinessException("INVALID_PERIOD", "Data final anterior à inicial.");
        }
        if (ChronoUnit.DAYS.between(req.startDate(), end) > 366) {
            throw new BusinessException("INVALID_PERIOD", "Período máximo de um ano.");
        }
        CourtCalendarEntry entry = new CourtCalendarEntry();
        entry.setStartDate(req.startDate());
        entry.setEndDate(end);
        entry.setCourt(req.court() == null || req.court().isBlank() ? null : req.court().trim().toUpperCase());
        entry.setDescription(req.description().trim());
        entry.setKind(req.kind());
        entry.setCreatedAt(Instant.now());
        entry.setCreatedBy(user.uid());
        repository.save(officeId, entry);
        cache.remove(officeId);
        audit.record(user, "COURT_CALENDAR_ADDED", "court_calendar", entry.getId(),
                entry.getKind() + ": " + entry.getDescription() + " (" + entry.getStartDate() + " a " + end + ")");
        return entry;
    }

    public void delete(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        CourtCalendarEntry entry = repository.findById(officeId, id)
                .orElseThrow(() -> new NotFoundException("Registro de calendário"));
        repository.delete(officeId, id);
        cache.remove(officeId);
        audit.record(user, "COURT_CALENDAR_REMOVED", "court_calendar", id,
                "Removido: " + entry.getDescription() + " (" + entry.getStartDate() + ")");
    }

    /** Entradas a partir do ano anterior (cobre prazos longos), em cache por 5 minutos. */
    List<CourtCalendarEntry> entries(String officeId) {
        CacheEntry cached = cache.get(officeId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.entries();
        }
        LocalDate from = LocalDate.now(ZONE).minusYears(1).withDayOfYear(1);
        List<CourtCalendarEntry> entries = repository.endingFrom(officeId, from);
        cache.put(officeId, new CacheEntry(entries, Instant.now().plus(CACHE_TTL)));
        return entries;
    }
}
