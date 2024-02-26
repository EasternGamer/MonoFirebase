package io.github.easterngamer.firebase.request;

import java.util.Map;
import java.util.function.Supplier;

public record CreateRequest(String documentPath, Supplier<Map<String, Object>> dataSupplier) { }
