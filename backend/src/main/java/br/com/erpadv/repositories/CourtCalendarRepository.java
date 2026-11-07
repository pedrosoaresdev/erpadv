package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.CourtCalendarEntry;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class CourtCalendarRepository extends FirestoreRepository<CourtCalendarEntry> {

    public CourtCalendarRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, CourtCalendarEntry.class, "court_calendar");
    }

    /** Entradas que terminam a partir de {@code from} (feriados/suspensões relevantes para contagens). */
    public List<CourtCalendarEntry> endingFrom(String officeId, LocalDate from) {
        return list(collection(officeId).whereGreaterThanOrEqualTo("endDate", from.toString())
                .orderBy("endDate", Query.Direction.ASCENDING), 500);
    }

    public List<CourtCalendarEntry> byYear(String officeId, int year) {
        return list(collection(officeId)
                .whereGreaterThanOrEqualTo("startDate", year + "-01-01")
                .whereLessThanOrEqualTo("startDate", year + "-12-31")
                .orderBy("startDate", Query.Direction.ASCENDING), 500);
    }

    public void delete(String officeId, String id) {
        Futures.await(collection(officeId).document(id).delete());
    }
}
