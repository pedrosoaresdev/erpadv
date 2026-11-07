package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Appointment;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

@Repository
public class AppointmentRepository extends FirestoreRepository<Appointment> {

    public AppointmentRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, Appointment.class, "appointments");
    }

    public void delete(String officeId, String id) {
        Futures.await(collection(officeId).document(id).delete());
    }
}
