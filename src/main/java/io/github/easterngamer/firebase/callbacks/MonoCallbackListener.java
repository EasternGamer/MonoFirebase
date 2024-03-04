package io.github.easterngamer.firebase.callbacks;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import reactor.core.publisher.MonoSink;

public class MonoCallbackListener<T> extends ClientCall.Listener<T> {
    private final MonoSink<T> sink;

    public MonoCallbackListener(MonoSink<T> sink) {
        this.sink = sink;
    }

    @Override
    public void onHeaders(Metadata headers) {
        System.out.println("Headers");
        System.out.println(headers);
    }

    @Override
    public void onReady() {
        System.out.println("Ready");
    }

    @Override
    public void onMessage(T message) {
        //System.out.println("Message: " + message);
        sink.success(message);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
        System.out.println(status);
        System.out.println(trailers);
        if (status.isOk()) {
            sink.success();
        } else {
            sink.error(status.asRuntimeException(trailers));
        }
    }
}
