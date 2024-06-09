package io.github.easterngamer.firebase;

import com.google.firestore.v1.Value;
import io.github.easterngamer.firebase.request.MonoCreateRequest;
import io.github.easterngamer.firebase.request.MonoDeleteRequest;
import io.github.easterngamer.firebase.request.MonoSyncRequest;
import io.github.easterngamer.firebase.request.MonoWriteRequest;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.github.easterngamer.firebase.MonoFirebase.DATABASE_REF;
import static io.github.easterngamer.firebase.MonoFirebase.REF_DATABASES;

/**
 * An object which represents a document in Firebase
 */
@SuppressWarnings({"unused"})
public interface FirestoreObject extends FirestoreDataObject {

    String getDocumentReference();
    default void syncFromFirebase(final Map<String, Value> map) {
        loadFromMap(map);
    }

    default void create() {
        REF_DATABASES.get(this).createSink.emitNext(new MonoCreateRequest(getDocumentReference(), this::getDataMap), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }

    default void delete() {
        final MonoFirebase firebase = REF_DATABASES.get(this);
        firebase.deleteSink.emitNext(new MonoDeleteRequest(getDocumentReference()), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
        REF_DATABASES.remove(this);
        DATABASE_REF.get(firebase).remove(this);
    }
    default void updateSubObjectField(final String field, final Supplier<List<? extends FirestoreDataObject>> supplier) {
        REF_DATABASES.get(this).writeSink.emitNext(new MonoWriteRequest(getDocumentReference(), field, () -> {
            final List<? extends FirestoreDataObject> objects = supplier.get();
            final List<Map<String, Value>> writeRequest = new ArrayList<>();
            objects.forEach(firestoreDataObject -> writeRequest.add(firestoreDataObject.getDataMap()));
            return writeRequest;
        }), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }
    default void updateBooleanField(final String field, final Supplier<Boolean> supplier) {
        REF_DATABASES.get(this).writeSink.emitNext(new MonoWriteRequest(getDocumentReference(), field, supplier), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }
    default void updateDoubleField(final String field, final Supplier<Double> supplier) {
        REF_DATABASES.get(this).writeSink.emitNext(new MonoWriteRequest(getDocumentReference(), field, supplier), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }
    default void updateLongField(final String field, final Supplier<Long> supplier) {
        REF_DATABASES.get(this).writeSink.emitNext(new MonoWriteRequest(getDocumentReference(), field, supplier), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }
    default void updateStringField(final String field, final Supplier<String> supplier) {
        REF_DATABASES.get(this).writeSink.emitNext(new MonoWriteRequest(getDocumentReference(), field, supplier), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }

    default void updateField(final String field, final Supplier<Object> supplier) {
        REF_DATABASES.get(this).writeSink.emitNext(new MonoWriteRequest(getDocumentReference(), field, supplier), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }

    default void bind(final MonoFirebase firebase) {
        REF_DATABASES.put(this, firebase);
        DATABASE_REF.computeIfAbsent(firebase, monoFirebase -> new HashSet<>()).add(this);
        firebase.addListener(getDocumentReference(), (document) -> {
            if (document != null) {
                firebase.syncSink.emitNext(new MonoSyncRequest(this, document::getFieldsMap), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
            } else {
                delete();
            }
        });
        firebase.cacheSinks.get(getDocumentReference()).emitValue(this, (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }

}
