package br.com.erpadv.repositories;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientInteraction;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ClientRepository extends FirestoreRepository<Client> {

    private final InteractionStore interactions;

    public ClientRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, Client.class, "clients");
        this.interactions = new InteractionStore(db, mapper);
    }

    public Optional<Client> findByDocumentHash(String officeId, String documentHash) {
        return list(collection(officeId).whereEqualTo("documentHash", documentHash), 1).stream().findFirst();
    }

    public ClientInteraction saveInteraction(String officeId, ClientInteraction interaction) {
        CollectionReference col = interactions.collection(officeId, interaction.getClientId());
        if (interaction.getId() == null) {
            interaction.setId(col.document().getId());
        }
        interaction.setOfficeId(officeId);
        Futures.await(col.document(interaction.getId()).set(mapper.toMap(interaction)));
        return interaction;
    }

    public CursorPage<ClientInteraction> interactions(String officeId, String clientId, String cursor, int size) {
        return interactions.page(interactions.collection(officeId, clientId), "occurredAt",
                Query.Direction.DESCENDING, cursor, size);
    }

    /** Subcoleção {@code clients/{clientId}/interactions}. */
    private static final class InteractionStore extends FirestoreRepository<ClientInteraction> {
        InteractionStore(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, ClientInteraction.class, "clients");
        }

        CollectionReference collection(String officeId, String clientId) {
            return collection(officeId).document(clientId).collection("interactions");
        }
    }
}
