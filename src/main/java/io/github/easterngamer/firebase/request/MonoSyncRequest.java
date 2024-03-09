package io.github.easterngamer.firebase.request;

import com.google.firestore.v1.Value;
import io.github.easterngamer.firebase.FirestoreObject;
import org.reactivestreams.Publisher;

import java.util.Map;
import java.util.function.Supplier;

public record MonoSyncRequest(FirestoreObject object, Supplier<Map<String, Value>> dataSupplier) {
    public Publisher<?> performSync() {
        return object.syncFromFirebase(dataSupplier.get());
    }
}
