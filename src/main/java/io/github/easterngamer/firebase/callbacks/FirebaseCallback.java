package io.github.easterngamer.firebase.callbacks;

import com.google.api.core.ApiFutureCallback;
import reactor.core.publisher.MonoSink;

public record FirebaseCallback<T>(MonoSink<T> sink) implements ApiFutureCallback<T> {
    @Override
    public void onFailure(Throwable t) {
        sink.error(t);
    }

    @Override
    public void onSuccess(T result) {
        sink.success(result);
    }
}

