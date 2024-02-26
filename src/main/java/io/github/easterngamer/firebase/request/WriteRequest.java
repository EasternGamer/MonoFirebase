package io.github.easterngamer.firebase.request;

import java.util.function.Supplier;

public record WriteRequest(String documentReference, String fieldName, Supplier<Object> dataSupplier) { }
