package io.github.easterngamer.firebase.callbacks;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import reactor.core.publisher.FluxSink;

public class FluxCallbackListener<T> extends ClientCall.Listener<T> {
    private final FluxSink<T> sink;
    public FluxCallbackListener(FluxSink<T> sink) {
        this.sink = sink;
    }

    @Override
    public void onMessage(T message) {
        sink.next(message);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
        if (status.isOk()) {
            sink.complete();
        } else {
            sink.error(status.asRuntimeException(trailers));
        }
    }
}
