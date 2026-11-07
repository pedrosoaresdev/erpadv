package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Lead;
import br.com.erpadv.models.LeadActivity;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

public final class CrmRepositories {

    private CrmRepositories() {
    }

    @Repository
    public static class Leads extends FirestoreRepository<Lead> {
        public Leads(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lead.class, "leads");
        }

        /** Funil completo do escritório (até 500 oportunidades mais recentes). */
        public List<Lead> recent(String officeId) {
            return list(collection(officeId).orderBy("createdAt", Query.Direction.DESCENDING), 500);
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Activities extends FirestoreRepository<LeadActivity> {
        public Activities(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, LeadActivity.class, "lead_activities");
        }

        public List<LeadActivity> byLead(String officeId, String leadId) {
            return list(collection(officeId).whereEqualTo("leadId", leadId).orderBy("at", Query.Direction.DESCENDING), 200);
        }

        public List<LeadActivity> allOfLead(String officeId, String leadId) {
            return list(collection(officeId).whereEqualTo("leadId", leadId), 500);
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
