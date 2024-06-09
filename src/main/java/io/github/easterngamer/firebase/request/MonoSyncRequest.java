package io.github.easterngamer.firebase.request;

import com.google.firestore.v1.Value;
import io.github.easterngamer.firebase.FirestoreObject;

import java.util.Map;
import java.util.function.Supplier;

public record MonoSyncRequest(FirestoreObject object, Supplier<Map<String, Value>> dataSupplier) {
    public void performSync() {
        object.syncFromFirebase(dataSupplier.get());
    }
}
