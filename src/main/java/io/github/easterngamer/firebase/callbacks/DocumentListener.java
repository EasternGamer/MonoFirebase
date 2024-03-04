package io.github.easterngamer.firebase.callbacks;

import com.google.firestore.v1.Document;
import com.google.firestore.v1.ListenResponse;
import io.grpc.ClientCall;

import java.util.function.Consumer;

public class DocumentListener extends ClientCall.Listener<ListenResponse> {
    private final Consumer<Document> update;
    private boolean firstDocumentChange = true;
    public DocumentListener(final Consumer<Document> update) {
        this.update = update;
    }
    @Override
    public void onMessage(final ListenResponse listenResponse) {
        switch (listenResponse.getResponseTypeCase()) {
            case DOCUMENT_CHANGE:
                if (firstDocumentChange) {
                    firstDocumentChange = false;
                    return;
                }
                update.accept(listenResponse.getDocumentChange().getDocument());
                break;
            case DOCUMENT_DELETE, DOCUMENT_REMOVE:
                update.accept(null);
                break;
        }
    }
}