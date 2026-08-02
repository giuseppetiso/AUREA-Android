package it.creativemaker3d.aurea;

import java.util.ArrayList;
import java.util.List;

/** Regole di visibilità delle bozze Routine Studio per il profilo corrente. */
final class AureaRoutineDraftAccess {
    private AureaRoutineDraftAccess() {
    }

    static List<AureaRoutineDraftStore.Draft> listForPerson(
            AureaRoutineDraftStore store,
            String person) {
        ArrayList<AureaRoutineDraftStore.Draft> result = new ArrayList<>();
        if (store == null) {
            return result;
        }
        String target = clean(person);
        for (AureaRoutineDraftStore.Draft draft : store.list()) {
            if (clean(draft.actor).equalsIgnoreCase(target)) {
                result.add(draft);
            }
        }
        return result;
    }

    static int countForPerson(AureaRoutineDraftStore store, String person) {
        return listForPerson(store, person).size();
    }

    static void clearForPerson(AureaRoutineDraftStore store, String person) {
        if (store == null) {
            return;
        }
        for (AureaRoutineDraftStore.Draft draft : listForPerson(store, person)) {
            store.delete(draft.id);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
