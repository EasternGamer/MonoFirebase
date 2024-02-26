package io.github.easterngamer.firebase;

import io.github.easterngamer.firebase.request.CreateRequest;
import io.github.easterngamer.firebase.request.DeleteRequest;
import io.github.easterngamer.firebase.request.SyncRequest;
import io.github.easterngamer.firebase.request.WriteRequest;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.util.function.Supplier;

/**
 * An object which represents a document in Firebase
 */
public abstract class FirestoreObject implements FirestoreDataObject {
    private MonoFirebase refDatabase;
    public abstract String getDocumentReference();
    public Publisher<?> syncFromFirebase(final Map<String, Object> map) {
        loadFromMap(map);
        return Mono.empty();
    }

    public void create() {
        if (refDatabase != null) {
            refDatabase.createSink.emitNext(new CreateRequest(getDocumentReference(), this::getDataMap), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
        }
    }

    public void delete() {
        if (refDatabase != null) {
            refDatabase.deleteSink.emitNext(new DeleteRequest(getDocumentReference()), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
        }
    }

    public void updateField(final String field, final Supplier<Object> supplier) {
        if (refDatabase != null) {
            refDatabase.writeSink.emitNext(new WriteRequest(getDocumentReference(), field, supplier), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
        }
    }

    public final void bind(final MonoFirebase firebase) {
        this.refDatabase = firebase;
        firebase.registers.add(firebase.db.document(getDocumentReference()).addSnapshotListener((documentSnapshot, error) -> {
            if (documentSnapshot != null) {
                firebase.syncSink.emitNext(new SyncRequest(this, documentSnapshot::getData), (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
            }
        }));
        firebase.cacheSinks.get(getDocumentReference()).emitValue(this, (signalType, emitResult) -> signalType != SignalType.ON_ERROR);
    }

}
