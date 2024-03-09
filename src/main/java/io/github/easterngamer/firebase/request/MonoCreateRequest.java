package io.github.easterngamer.firebase.request;

import com.google.firestore.v1.Value;

import java.util.Map;
import java.util.function.Supplier;

public record MonoCreateRequest(String documentPath, Supplier<Map<String, Value>> dataSupplier) { }
