package io.github.easterngamer.firebase.request;

import io.github.easterngamer.firebase.FirestoreObject;
import org.reactivestreams.Publisher;

import java.util.Map;
import java.util.function.Supplier;

public record SyncRequest(FirestoreObject object, Supplier<Map<String, Object>> dataSupplier) {
    public Publisher<?> performSync() {
        return object.syncFromFirebase(dataSupplier.get());
    }
}
