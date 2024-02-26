package io.github.easterngamer.firebase;

import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.rpc.*;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.easterngamer.firebase.request.CreateRequest;
import io.github.easterngamer.firebase.request.DeleteRequest;
import io.github.easterngamer.firebase.request.SyncRequest;
import io.github.easterngamer.firebase.request.WriteRequest;
import io.github.easterngamer.utils.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@SuppressWarnings({"unused", "unchecked"})
public class MonoFirebase {

    private static final Scheduler WRITE_SCHEDULER = Schedulers.newBoundedElastic(Runtime.getRuntime().availableProcessors()/2, Integer.MAX_VALUE, "Firebase-Write");
    private static final Scheduler READ_SCHEDULER = Schedulers.newBoundedElastic(Runtime.getRuntime().availableProcessors(), Integer.MAX_VALUE, "Firebase-Read");
    private static final Scheduler DELETE_SCHEDULER = Schedulers.newBoundedElastic(Runtime.getRuntime().availableProcessors()/2, Integer.MAX_VALUE, "Firebase-Delete");
    private record FirebaseCallback<T>(MonoSink<T> sink) implements ApiFutureCallback<T> {
        @Override
        public void onFailure(Throwable t) {
            sink.error(t);
        }

        @Override
        public void onSuccess(T result) {
            sink.success(result);
        }
    }
    protected final Firestore db;

    protected final Sinks.Many<WriteRequest> writeSink;
    protected final Sinks.Many<CreateRequest> createSink;
    protected final Sinks.Many<DeleteRequest> deleteSink;
    protected final Sinks.Many<SyncRequest> syncSink;
    protected final List<ListenerRegistration> registers;
    protected final Map<String, Sinks.One<FirestoreObject>> cacheSinks = Collections.synchronizedMap(new LinkedHashMap<>());
    public final Disposable createDisposable;
    private final Disposable writeDisposable;
    private final Disposable deleteDisposable;
    private final Disposable syncDisposable;
    private final Disposable batchDisposable;
    private final Map<String, Tuple2<SetOptions, Map<String, Object>>> batchQueue;
    private WriteBatch batch;

    public MonoFirebase(final FirestoreOptions options) {
        this.db = options.getService();
        registers = Collections.synchronizedList(new ArrayList<>());
        createSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100));
        writeSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100));
        deleteSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100));
        syncSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100), () -> registers.forEach(ListenerRegistration::remove));
        this.createDisposable = createSink.asFlux()
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER)
                .flatMap(this::createDocument)
                .subscribe();
        this.writeDisposable = writeSink.asFlux()
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER)
                .subscribe(this::updateDocument);
        this.deleteDisposable = deleteSink.asFlux()
                .publishOn(DELETE_SCHEDULER)
                .subscribeOn(DELETE_SCHEDULER)
                .flatMap(this::deleteDocument)
                .subscribe();
        this.syncDisposable = syncSink.asFlux()
                .publishOn(READ_SCHEDULER)
                .subscribeOn(READ_SCHEDULER)
                .flatMap(SyncRequest::performSync)
                .subscribe();
        this.batchQueue = new ConcurrentHashMap<>();
        this.batch = db.batch();

        this.batchDisposable = Flux.interval(Duration.ofSeconds(10))
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER)
                .filter(ignored -> !batchQueue.isEmpty())
                .flatMap(ignored -> {
                    batchQueue.forEach((key, value) -> batch.set(db.document(key), value.getT2(), value.getT1()));
                    return commit().onErrorResume((error) -> Mono.empty());
                })
                .subscribe();
    }
    private static Mono<WriteResult> setDocument(final DocumentReference documentReference, final Map<String, Object> dataMap) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(documentReference.set(dataMap, SetOptions.merge()), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER).subscribeOn(WRITE_SCHEDULER);
    }

    public Mono<WriteResult> setDocument(final String documentReference, final Map<String, Object> dataMap) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(db.document(documentReference).set(dataMap, SetOptions.merge()), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER).subscribeOn(WRITE_SCHEDULER);
    }

    public void setDocument(final String path, final Map<String, Object> dataMap, final SetOptions options) {
        if (batchQueue.containsKey(path)) {
            Tuple2<SetOptions, Map<String, Object>> data = batchQueue.get(path);
            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                data.getT2().merge(entry.getKey(), entry.getValue(), (o, o2) -> o2);
            }
        } else {
            batchQueue.put(path, Tuples.of(options, new ConcurrentHashMap<>(dataMap)));
        }
    }

    private void updateDocument(final WriteRequest request) {
        final String path = request.documentReference();
        if (batchQueue.containsKey(path)) {
            Tuple2<SetOptions, Map<String, Object>> data = batchQueue.get(path);
            data.getT2().put(request.fieldName(), request.dataSupplier().get());
        } else {
            batchQueue.put(path, Tuples.of(SetOptions.merge(), new ConcurrentHashMap<>(Map.of(request.fieldName(), request.dataSupplier().get()))));
        }
    }

    private Mono<WriteResult> createDocument(final CreateRequest request) {
        return createDocument(request.documentPath(), request.dataSupplier().get());
    }

    public Mono<WriteResult> createDocument(final String path, final Map<String, Object> map) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(db.document(path).create(map), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER);
    }

    public Mono<WriteResult> deleteDocument(final String path) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(db.document(path).delete(), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER);
    }

    public Mono<WriteResult> deleteDocument(final DeleteRequest path) {
        return deleteDocument(path.documentReference());
    }

    public Flux<DocumentSnapshot> listFromCollection(String collection) {
        return Flux.<DocumentSnapshot>create(fluxSink -> db.collection(collection).stream(new ApiStreamObserver<>() {
            @Override
            public void onNext(DocumentSnapshot value) {
                fluxSink.next(value);
            }

            @Override
            public void onError(Throwable t) {
                fluxSink.error(t);
            }

            @Override
            public void onCompleted() {
                fluxSink.complete();
            }
        })).publishOn(READ_SCHEDULER).subscribeOn(READ_SCHEDULER);
    }

    public Mono<DocumentSnapshot> getDocument(final String path) {
        return Mono.<DocumentSnapshot>create(sink -> ApiFutures.addCallback(db.document(path).get(), new FirebaseCallback<>(sink), MoreExecutors.directExecutor())).publishOn(READ_SCHEDULER).subscribeOn(READ_SCHEDULER);
    }

    public static <T extends FirestoreObject> T load(final DocumentSnapshot snapshot, final Supplier<T> newObjectSupplier) {
        if (snapshot.exists()) {
            final T obj = newObjectSupplier.get();
            obj.loadFromMap(snapshot.getData());
            return obj;
        } else {
            return null;
        }
    }
    public <T extends FirestoreObject> Flux<T> getObjects(final String collectionPath, final Supplier<? extends T> newObjectSupplier) {
        return listFromCollection(collectionPath)
                .mapNotNull(documentSnapshot -> load(documentSnapshot, newObjectSupplier));
    }

    public <T extends FirestoreObject> Mono<T> getObject(final String documentPath, final Supplier<T> newObjectSupplier) {
        final Sinks.One<T> sink = (Sinks.One<T>) cacheSinks.get(documentPath);
        if (sink != null) {
            return sink.asMono();
        } else {
            cacheSinks.put(documentPath, Sinks.one());
            return getDocument(documentPath)
                    .mapNotNull(documentSnapshot -> load(documentSnapshot, newObjectSupplier))
                    .doOnNext(firestoreObject -> firestoreObject.bind(this))
                    .doOnTerminate(() -> cacheSinks.remove(documentPath));
        }
    }

    public Mono<List<WriteResult>> commit() {
        final WriteBatch currentBatch = batch;
        batch = db.batch();
        batchQueue.clear();
        return Mono.<List<WriteResult>>create(sink -> ApiFutures.addCallback(currentBatch.commit(), new FirebaseCallback<>(sink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER);
    }

    public Mono<Boolean> logout() {
        return Mono.fromCallable(() -> {
            try {
                writeDisposable.dispose();
                deleteDisposable.dispose();
                syncDisposable.dispose();
                batchDisposable.dispose();
                db.shutdown();
                return true;
            } catch (Exception e) {
                return false;
            }
        }).publishOn(Schedulers.single()).subscribeOn(Schedulers.single());
    }

    private static int getPathSize(final String path) {
        int size = 16;
        final String[] pathString = StringUtils.fastSplit(path, "/");
        final int pathStringLength = pathString.length;
        for (int i = 0; i < pathStringLength; i++) {
            size += pathString[i].length() + 1;
        }
        return size;
    }

    private static int getSizeOfValue(Object object) {
        int size = 0;
        if (object instanceof List) {
            List<Object> objects = (List<Object>) object;
            final int objectsLength = objects.size();
            for (int i = 0; i < objectsLength; i++) {
                size += getSizeOfValue(objects.get(i));
            }
            return size;
        } else if (object instanceof Map) {
            return getSizeOfMap((Map<String, Object>) object);
        } else if (object == null || object instanceof Boolean) {
            return 1;
        } else if (object instanceof String) {
            return ((String) object).getBytes(StandardCharsets.UTF_8).length + 1;
        } else if (object instanceof Timestamp) {
            return 16;
        } else if (object instanceof Blob) {
            return ((Blob) object).toBytes().length;
        } else if (object instanceof Double || object instanceof Integer) {
            return 8;
        } else if (object instanceof DocumentReference) {
            return getPathSize(((DocumentReference) object).getPath());
        } else if (object instanceof GeoPoint) {
            return 16;
        }
        return 0;
    }
    private static int getSizeOfMap(final Map<String, Object> data) {
        if (data == null) {
            return 0;
        }
        List<Map.Entry<String, Object>> dataEntries = new ArrayList<>(data.entrySet());
        final int dataEntryListSize = dataEntries.size();
        int size = 0;
        for (int i = 0; i < dataEntryListSize; i++) {
            Map.Entry<String, Object> entry = dataEntries.get(i);
            size += entry.getKey().getBytes(StandardCharsets.UTF_8).length + 1 + getSizeOfValue(entry.getValue());
        }
        return size;
    }

    public Mono<Integer> getDocumentSize(final String path) {
        final int pathSize = getPathSize(path);
        return getDocument(path)
                .map(snapshot -> pathSize + getSizeOfMap(snapshot.getData()) + 32);
    }
    public static int getDocumentSize(final String path, Map<String, Object> data) {
        return getPathSize(path)
                + getSizeOfMap(data) + 32;
    }
    public static int getDocumentSize(final DocumentSnapshot documentSnapshot) {
        return getPathSize(documentSnapshot.getReference().getPath())
                + getSizeOfMap(documentSnapshot.getData()) + 32;
    }

}
