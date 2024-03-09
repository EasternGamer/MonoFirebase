package io.github.easterngamer.firebase;

import com.google.api.core.ApiFutures;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.gax.rpc.ClientContext;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.WriteResult;
import com.google.cloud.firestore.*;
import com.google.cloud.firestore.spi.v1.FirestoreRpc;
import com.google.cloud.firestore.v1.stub.GrpcFirestoreStub;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firestore.v1.*;
import com.google.firestore.v1.Precondition;
import com.google.protobuf.Descriptors;
import io.github.easterngamer.firebase.callbacks.DocumentListener;
import io.github.easterngamer.firebase.callbacks.FirebaseCallback;
import io.github.easterngamer.firebase.callbacks.FluxCallbackListener;
import io.github.easterngamer.firebase.callbacks.MonoCallbackListener;
import io.github.easterngamer.firebase.request.MonoCreateRequest;
import io.github.easterngamer.firebase.request.MonoDeleteRequest;
import io.github.easterngamer.firebase.request.MonoSyncRequest;
import io.github.easterngamer.firebase.request.MonoWriteRequest;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SuppressWarnings({"unused", "unchecked"})
public class MonoFirebase {

    private static final Scheduler WRITE_SCHEDULER = Schedulers.newBoundedElastic(Runtime.getRuntime().availableProcessors()/2, Integer.MAX_VALUE, "Firebase-Write");
    private static final Scheduler READ_SCHEDULER = Schedulers.newBoundedElastic(Runtime.getRuntime().availableProcessors(), Integer.MAX_VALUE, "Firebase-Read");
    private static final Scheduler DELETE_SCHEDULER = Schedulers.newBoundedElastic(Runtime.getRuntime().availableProcessors()/2, Integer.MAX_VALUE, "Firebase-Delete");

    /**
     * Taken from {@link GrpcFirestoreStub#writeCallable()}
     */
    private static final MethodDescriptor<com.google.firestore.v1.WriteRequest, WriteResponse> SINGLE_WRITE =
            MethodDescriptor.<com.google.firestore.v1.WriteRequest, WriteResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName("google.firestore.v1.Firestore/Write")
                    .setRequestMarshaller(ProtoUtils.marshaller(com.google.firestore.v1.WriteRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(WriteResponse.getDefaultInstance()))
                    .build();
    /**
     * Taken from {@link GrpcFirestoreStub#batchWriteCallable()}
     */
    private static final MethodDescriptor<BatchWriteRequest, BatchWriteResponse>
            BATCH_WRITE =
            MethodDescriptor.<BatchWriteRequest, BatchWriteResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("google.firestore.v1.Firestore/BatchWrite")
                    .setRequestMarshaller(ProtoUtils.marshaller(BatchWriteRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(BatchWriteResponse.getDefaultInstance()))
                    .build();

    /**
     * Taken from {@link GrpcFirestoreStub#getDocumentCallable()}
     */
    private static final MethodDescriptor<GetDocumentRequest, Document> GET_DOCUMENT = MethodDescriptor.<GetDocumentRequest, Document>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("google.firestore.v1.Firestore/GetDocument")
            .setRequestMarshaller(ProtoUtils.marshaller(GetDocumentRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(Document.getDefaultInstance()))
            .build();
    /**
     * Taken from {@link GrpcFirestoreStub#runQueryCallable()}
     */
    private static final MethodDescriptor<RunQueryRequest, RunQueryResponse> GET_DOCUMENTS =
            MethodDescriptor.<RunQueryRequest, RunQueryResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName("google.firestore.v1.Firestore/RunQuery")
                    .setRequestMarshaller(ProtoUtils.marshaller(RunQueryRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(RunQueryResponse.getDefaultInstance()))
                    .build();
    /**
     * Taken from {@link GrpcFirestoreStub#listenCallable()}
     */
    private static final MethodDescriptor<ListenRequest, ListenResponse> LISTEN_DOCUMENT =
            MethodDescriptor.<ListenRequest, ListenResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName("google.firestore.v1.Firestore/Listen")
                    .setRequestMarshaller(ProtoUtils.marshaller(ListenRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(ListenResponse.getDefaultInstance()))
                    .build();
    private static final Precondition NOT_EXISTS = Precondition.newBuilder().setExists(false).build();
    private static final Precondition EXISTS = Precondition.newBuilder().setExists(false).build();
    protected final Firestore db;
    private final ApiCallContext context;
    private final String documentPathPrefix;
    private final String databasePathPrefix;
    protected final Sinks.Many<MonoWriteRequest> writeSink;
    protected final Sinks.Many<MonoCreateRequest> createSink;
    protected final Sinks.Many<MonoDeleteRequest> deleteSink;
    protected final Sinks.Many<MonoSyncRequest> syncSink;
    protected final List<ClientCall<?,?>> registers;
    protected final Map<String, Sinks.One<FirestoreObject>> cacheSinks = Collections.synchronizedMap(new LinkedHashMap<>());
    public final Disposable createDisposable;
    private final Disposable writeDisposable;
    private final Disposable deleteDisposable;
    private final Disposable syncDisposable;
    private final Disposable batchDisposable;
    private final Map<String, Tuple2<SetOptions, Map<String, Object>>> batchQueue;
    private WriteBatch batch;
    private static <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(final MethodDescriptor<RequestT, ResponseT> descriptor, final ApiCallContext context) {
        final GrpcCallContext grpcContext = (GrpcCallContext) context;
        return grpcContext.getChannel().newCall(descriptor, grpcContext.getCallOptions());
    }
    private static <T, K> T getField(final String fieldName, final K instance, final Class<T> cast) {
        try {
            final Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return cast.cast(field.get(instance));
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Requires an input stream of the Google Credentials
     * @throws IOException if there is an issue.
     */
    public MonoFirebase(InputStream googleCredentials) throws IOException {
        this(FirestoreOptions.newBuilder().setCredentials(GoogleCredentials.fromStream(googleCredentials)).build());
    }

    public MonoFirebase(final FirestoreOptions options) {
        this.db = options.getService();
        {
            this.context = getField(
                    "clientContext",
                    getField(
                            "firestoreClient",
                            db,
                            FirestoreRpc.class
                    ),
                    ClientContext.class
            ).getDefaultCallContext();
            this.databasePathPrefix = "projects/" + options.getProjectId() + "/databases/" + options.getDatabaseId();
            this.documentPathPrefix = "projects/" + options.getProjectId() + "/databases/" + options.getDatabaseId() + "/documents/";
        }
        registers = Collections.synchronizedList(new ArrayList<>());
        createSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100));
        writeSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100));
        deleteSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100));
        syncSink = Sinks.many().unicast().onBackpressureBuffer(new ArrayDeque<>(100), () -> registers.forEach(call -> call.cancel(null, null)));
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
                .flatMap(MonoSyncRequest::performSync)
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
    // TODO: Fix for value inputs
    private static Mono<WriteResult> setDocument(final DocumentReference documentReference, final Map<String, Object> dataMap) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(documentReference.set(dataMap, SetOptions.merge()), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER).subscribeOn(WRITE_SCHEDULER);
    }

    // TODO: Fix for value inputs
    public Mono<WriteResult> setDocument(final String documentReference, final Map<String, Object> dataMap) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(db.document(documentReference).set(dataMap, SetOptions.merge()), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER).subscribeOn(WRITE_SCHEDULER);
    }

    // TODO: Fix for value inputs
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

    private void updateDocument(final MonoWriteRequest request) {
        final String path = request.documentReference();
        if (batchQueue.containsKey(path)) {
            Tuple2<SetOptions, Map<String, Object>> data = batchQueue.get(path);
            data.getT2().put(request.fieldName(), request.dataSupplier().get());
        } else {
            batchQueue.put(path, Tuples.of(SetOptions.merge(), new ConcurrentHashMap<>(Map.of(request.fieldName(), request.dataSupplier().get()))));
        }
    }

    private static Map<String, Object> decodeMapValue(Map<String, Value> valueMap) {
        Map<String, Object> outputMap = new HashMap<>();
        for (Map.Entry<String, Value> entry : valueMap.entrySet()) {
            outputMap.put(entry.getKey(), decodeValue(entry.getValue()));
        }
        return outputMap;
    }

    private static Object decodeValue(Value v) {
        Value.ValueTypeCase typeCase = v.getValueTypeCase();
        switch (typeCase) {
            case NULL_VALUE:
                return null;
            case BOOLEAN_VALUE:
                return v.getBooleanValue();
            case INTEGER_VALUE:
                return v.getIntegerValue();
            case DOUBLE_VALUE:
                return v.getDoubleValue();
            case TIMESTAMP_VALUE:
                return Timestamp.fromProto(v.getTimestampValue());
            case STRING_VALUE:
                return v.getStringValue();
            case BYTES_VALUE:
                return Blob.fromByteString(v.getBytesValue());
            case GEO_POINT_VALUE:
                return new GeoPoint(
                        v.getGeoPointValue().getLatitude(), v.getGeoPointValue().getLongitude());
            case ARRAY_VALUE:
                List<Object> list = new ArrayList<>();
                List<Value> lv = v.getArrayValue().getValuesList();
                for (Value iv : lv) {
                    list.add(decodeValue(iv));
                }
                return list;
            case MAP_VALUE:
                return decodeMapValue(v.getMapValue().getFieldsMap());
            default:
                throw FirestoreException.forInvalidArgument(
                        String.format("Unknown Value Type: %s", typeCase));
        }
    }

    private Mono<WriteResponse> createDocument(final MonoCreateRequest request) {
        return createDocument(request.documentPath(), request.dataSupplier().get());
    }

    public Mono<WriteResponse> createDocument(final String path, final Map<String, Value> map) {
        return Mono.<WriteResponse>create(monoSink -> {
                    final ClientCall<WriteRequest, WriteResponse> call = newCall(SINGLE_WRITE, context);
                    call.start(new MonoCallbackListener<>(monoSink), null);
                    call.sendMessage(WriteRequest.newBuilder().addWrites(
                                    Write.newBuilder()
                                            .setUpdate(Document.newBuilder().putAllFields(map).buildPartial())
                                            .setCurrentDocument(NOT_EXISTS)
                                            .build()
                            ).build()
                    );
                    call.halfClose();
                })
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER);
    }

    public Mono<WriteResult> deleteDocument(final String path) {
        return Mono.<WriteResult>create(monoSink -> ApiFutures.addCallback(db.document(path).delete(), new FirebaseCallback<>(monoSink), MoreExecutors.directExecutor()))
                .publishOn(WRITE_SCHEDULER)
                .subscribeOn(WRITE_SCHEDULER);
    }

    public Mono<WriteResult> deleteDocument(final MonoDeleteRequest path) {
        return deleteDocument(path.documentReference());
    }

    public Flux<Document> listFromCollection(String collection) {
        return Flux.<RunQueryResponse>create(fluxSink -> {
            final ClientCall<RunQueryRequest, RunQueryResponse> call = newCall(GET_DOCUMENTS, context);
            fluxSink.onRequest(value -> call.request(value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value));
            fluxSink.onCancel(() -> call.cancel(null, null));
            call.start(new FluxCallbackListener<>(fluxSink), new Metadata());
            call.request(1);
            call.sendMessage(RunQueryRequest.newBuilder().setParent(documentPathPrefix + collection).build());
        }).publishOn(READ_SCHEDULER).subscribeOn(READ_SCHEDULER).map(RunQueryResponse::getDocument);
    }
    public Mono<Document> getDocument(final String path) {
        return Mono.<Document>create(sink -> {
            ClientCall<GetDocumentRequest, Document> call = newCall(GET_DOCUMENT, context);
            call.start(new MonoCallbackListener<>(sink), new Metadata());
            call.request(1);
            call.sendMessage(GetDocumentRequest.newBuilder().setName(documentPathPrefix + path).build());
            sink.onCancel(() -> call.cancel(null, null));
        }).publishOn(READ_SCHEDULER).subscribeOn(READ_SCHEDULER);
    }

    public void addListener(final String document, final Consumer<Document> update) {
        final ClientCall<ListenRequest, ListenResponse> call = newCall(LISTEN_DOCUMENT, context);
        call.start(new DocumentListener(update), new Metadata());
        call.request(Integer.MAX_VALUE);
        call.sendMessage(ListenRequest.newBuilder()
                .setDatabase(databasePathPrefix)
                .setAddTarget(
                        Target.newBuilder()
                                .setDocuments(Target.DocumentsTarget.newBuilder()
                                        .addDocuments(documentPathPrefix + document)
                                        .build()
                                ).build()
                ).build()
        );
        registers.add(call);
    }

    public static <T extends FirestoreObject> T load(final Document snapshot, final Supplier<T> newObjectSupplier) {
        if (snapshot != null) {
            final Map<String, Value> data = snapshot.getFieldsMap();
            final T obj = newObjectSupplier.get();
            obj.loadFromMap(data);
            return obj;
        } else {
            return null;
        }
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

    private static String[] fastSplit(final String content, final String splitter) {
        if (splitter.isEmpty()) {
            final int contentLength = content.length();
            final String[] splits = new String[contentLength];
            for (int i = 0; i < contentLength;) {
                splits[i] = content.substring(i, ++i);
            }
            return splits;
        }
        final int splitterLength = splitter.length();
        String[] splitContent = new String[16];
        int sizeOfSplitContent = 0;
        int maxSize = splitContent.length;
        int indexOfSplitter = content.indexOf(splitter);
        int previousIndex = 0;
        while (indexOfSplitter != -1) {
            if (sizeOfSplitContent == maxSize) {
                final String[] newSplitContent = new String[maxSize <<= 1];
                System.arraycopy(splitContent, 0, newSplitContent, 0, sizeOfSplitContent);
                splitContent = newSplitContent;
            }
            final String temp = content.substring(previousIndex, indexOfSplitter);
            if (!temp.isEmpty()) {
                splitContent[sizeOfSplitContent++] = temp;
            }

            previousIndex = indexOfSplitter + splitterLength;
            indexOfSplitter = content.indexOf(splitter, previousIndex);
        }
        final String temp = content.substring(previousIndex);
        final String[] finalSplitContent;
        if (!temp.isEmpty()) {
            finalSplitContent = new String[sizeOfSplitContent+1];
            finalSplitContent[sizeOfSplitContent] = temp;
        } else {
            finalSplitContent = new String[sizeOfSplitContent];
        }
        System.arraycopy(splitContent, 0, finalSplitContent, 0, sizeOfSplitContent);
        return finalSplitContent;
    }

    private static int getPathSize(final String path) {
        int size = 16;
        final String[] pathString = fastSplit(path, "/");
        final int pathStringLength = pathString.length;
        for (int i = 0; i < pathStringLength; i++) {
            size += pathString[i].length() + 1;
        }
        return size;
    }

    private static int getSizeOfValue(Value object) {
        switch (object.getValueTypeCase()) {
            case STRING_VALUE -> {
                return object.getStringValueBytes().size() + 1;
            }
            case INTEGER_VALUE, DOUBLE_VALUE -> {
                return 8;
            }
            case BOOLEAN_VALUE, NULL_VALUE, VALUETYPE_NOT_SET -> {
                return 1;
            }
            case ARRAY_VALUE -> {
                int size = 0;
                List<Value> objects = object.getArrayValue().getValuesList();
                final int objectsLength = objects.size();
                for (int i = 0; i < objectsLength; i++) {
                    size += getSizeOfValue(objects.get(i));
                }
                return size;
            }
            case MAP_VALUE -> {
                return getSizeOfMap(object.getMapValue().getFieldsMap());
            }
            case TIMESTAMP_VALUE, GEO_POINT_VALUE -> {
                return 16;
            }
            case BYTES_VALUE -> {
                return object.getBytesValue().size();
            }
            case REFERENCE_VALUE -> {
                return getPathSize(object.getReferenceValue());
            }
            default -> {
                return 0;
            }
        }
    }
    private static int getSizeOfMap(final Map<String, Value> data) {
        if (data == null) {
            return 0;
        }
        List<Map.Entry<String, Value>> dataEntries = new ArrayList<>(data.entrySet());
        final int dataEntryListSize = dataEntries.size();
        int size = 0;
        for (int i = 0; i < dataEntryListSize; i++) {
            Map.Entry<String, Value> entry = dataEntries.get(i);
            size += entry.getKey().getBytes(StandardCharsets.UTF_8).length + 1 + getSizeOfValue(entry.getValue());
        }
        return size;
    }

    public Mono<Integer> getDocumentSize(final String path) {
        final int pathSize = getPathSize(path);
        return getDocument(path)
                .map(snapshot -> pathSize + getSizeOfMap(snapshot.getFieldsMap()) + 32);
    }
    public static int getDocumentSize(final String path, Map<String, Value> data) {
        return getPathSize(path)
                + getSizeOfMap(data) + 32;
    }
    public static int getDocumentSize(final Document document) {
        return getPathSize(document.getName())
                + getSizeOfMap(document.getFieldsMap()) + 32;
    }

}
