package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Board;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CardActivity;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

public final class BoardRepositories {

    private BoardRepositories() {
    }

    @Repository
    public static class Boards extends FirestoreRepository<Board> {
        public Boards(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Board.class, "boards");
        }

        public List<Board> byArchived(String officeId, boolean archived) {
            return list(collection(officeId).whereEqualTo("archived", archived), 200);
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Cards extends FirestoreRepository<BoardCard> {
        public Cards(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, BoardCard.class, "board_cards");
        }

        public List<BoardCard> byBoard(String officeId, String boardId, boolean archived) {
            return list(collection(officeId).whereEqualTo("boardId", boardId).whereEqualTo("archived", archived), 500);
        }

        public List<BoardCard> allOfBoard(String officeId, String boardId) {
            return list(collection(officeId).whereEqualTo("boardId", boardId), 500);
        }

        public long countOpen(String officeId, String boardId) {
            return count(collection(officeId).whereEqualTo("boardId", boardId).whereEqualTo("archived", false)
                    .whereEqualTo("completed", false));
        }

        public List<BoardCard> assignedTo(String officeId, String uid) {
            return list(collection(officeId).whereArrayContains("assigneeUids", uid)
                    .whereEqualTo("archived", false).whereEqualTo("completed", false), 300);
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Activities extends FirestoreRepository<CardActivity> {
        public Activities(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, CardActivity.class, "card_activity");
        }

        public List<CardActivity> byCard(String officeId, String cardId) {
            return list(collection(officeId).whereEqualTo("cardId", cardId).orderBy("at", Query.Direction.DESCENDING), 200);
        }

        public List<CardActivity> byBoard(String officeId, String boardId) {
            return list(collection(officeId).whereEqualTo("boardId", boardId), 500);
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
