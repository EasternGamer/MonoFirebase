package io.github.easterngamer.firebase.request;

import java.util.function.Supplier;

public record MonoWriteRequest(String documentReference, String fieldName, Supplier<?> dataSupplier) { }
