package io.github.easterngamer.firebase;

import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.*;
import java.util.function.Supplier;

/**
 * Represents the data of an object for Firestore.
 */
@SuppressWarnings({"unused"})
public interface FirestoreDataObject {

    void loadFromMap(final Map<String, Value> map);
    Map<String, Value> getDataMap();

    default void loadFromRaw(Map<String, byte[]> raw) {
        final Map<String, Value> output = new HashMap<>();
        raw.forEach((s, bytes) -> {
            try {
                output.put(s, Value.parseFrom(bytes));
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        });
        loadFromMap(output);
    }

    default Map<String, byte[]> toMappedRaw() {
        final Map<String, byte[]> output = new HashMap<>();
        getDataMap().forEach((s, value) -> output.put(s, value.toByteArray()));
        return output;
    }
    default byte[] toRaw() {
        MapValue.Builder builder = MapValue.newBuilder();
        builder.putAllFields(getDataMap());
        return builder.buildPartial().toByteArray();
    }

    default <T extends FirestoreDataObject> List<T> getListOf(final Map<Long, T> objectMap) {
        final List<T> dataMap = new ArrayList<>();
        objectMap.forEach((aLong, firestoreObject) -> dataMap.add(firestoreObject));
        return dataMap;
    }

    default <T extends FirestoreDataObject> List<T> loadList(final String field, final Map<String, Value> dataMap, final Supplier<? extends T> supplier) {
        final Value data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("Array value not present for " + field + " in " + dataMap);
        }
        return loadList(data, supplier);
    }

    default <T extends FirestoreDataObject> List<T> loadList(final Value data, final Supplier<? extends T> supplier) {
        final List<Value> list = data.getArrayValue().getValuesList();
        final int size = list.size();
        final List<T> loadedList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final T object = supplier.get();
            object.loadFromMap(list.get(i).getMapValue().getFieldsMap());
            loadedList.add(object);
        }
        return loadedList;
    }

    default <T extends FirestoreDataObject> Map<Long, T> loadMap(final Value data, final Supplier<? extends T> supplier) {
        final Map<Long, T> firestoreObjectMap = Collections.synchronizedMap(new LinkedHashMap<>());
        data.getMapValue()
                .getFieldsMap()
                .forEach((aLong, stringObjectMap) -> {
                    final T firestoreObject = supplier.get();
                    firestoreObject.loadFromMap(stringObjectMap.getMapValue().getFieldsMap());
                    firestoreObjectMap.put(Long.parseLong(aLong), firestoreObject);
                });
        return firestoreObjectMap;
    }

    default boolean loadBoolean(final String field, final Map<String, Value> dataMap) {
        final Value data = dataMap.get(field);
        if (data == null || !data.hasBooleanValue()) {
            throw new MissingValueException("Boolean value not present for " + field + " in " + dataMap);
        }
        return data.getBooleanValue();
    }

    default long loadLong(final String field, final Map<String, Value> dataMap) {
        final Value data = dataMap.get(field);
        if (data == null || !data.hasIntegerValue()) {
            throw new MissingValueException("Long value not present for " + field + " in " + dataMap);
        }
        return data.getIntegerValue();
    }

    default String loadString(final String field, final Map<String, Value> dataMap) {
        final Value data = dataMap.get(field);
        if (data == null || !data.hasStringValue()) {
            throw new MissingValueException("String value not present for " + field + " in " + dataMap);
        }
        return data.getStringValue();
    }
    default long loadId(final String field, final Map<String, Value> dataMap) {
        final Value data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("ID value not present for " + field + " in " + dataMap);
        }
        if (data.hasIntegerValue()) {
            return data.getIntegerValue();
        }
        if (data.hasStringValue()) {
            return Long.parseLong(data.getStringValue());
        }
        throw new MissingValueException("ID value not present for " + field + " in " + dataMap);
    }
}
