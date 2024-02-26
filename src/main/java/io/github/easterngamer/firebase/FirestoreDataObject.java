package io.github.easterngamer.firebase;

import com.google.cloud.firestore.DocumentSnapshot;

import java.util.*;
import java.util.function.Supplier;

/**
 * Represents the data of an object for Firestore.
 */
public interface FirestoreDataObject {
    void loadFromMap(final Map<String, Object> map);
    Map<String, Object> getDataMap();

    default long loadLong(final String field, final Map<String, Object> dataMap) {
        final Object data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("Long value not present for " + field + " in " + dataMap);
        }
        return ((Number) data).longValue();
    }

    default <T extends FirestoreDataObject> Map<Long, T> loadMap(final Object data, final Supplier<? extends T> supplier) {
        final Map<Long, T> firestoreObjectMap = Collections.synchronizedMap(new LinkedHashMap<>());
        ((Map<String, Map<String, Object>>) data).forEach((aLong, stringObjectMap) -> {
            final T firestoreObject = supplier.get();
            firestoreObject.loadFromMap(stringObjectMap);
            firestoreObjectMap.put(Long.parseLong(aLong), firestoreObject);
        });
        return firestoreObjectMap;
    }

    default <T extends FirestoreDataObject> Map<String, Map<String, Object>> getMapOf(final Map<Long, T> objectMap) {
        final Map<String, Map<String, Object>> dataMap = new HashMap<>();
        objectMap.forEach((aLong, firestoreObject) -> {
            dataMap.put(aLong.toString(), firestoreObject.getDataMap());
        });
        return dataMap;
    }

    default <T extends FirestoreDataObject> List<Map<String, Object>> getListOf(final Map<Long, T> objectMap) {
        final List<Map<String, Object>> dataMap = new ArrayList<>();
        objectMap.forEach((aLong, firestoreObject) -> dataMap.add(firestoreObject.getDataMap()));
        return dataMap;
    }

    default boolean loadBoolean(final String field, final Map<String, Object> dataMap) {
        final Object data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("Boolean value not present for " + field + " in " + dataMap);
        }
        return (boolean) data;
    }

    default <T extends FirestoreDataObject> List<T> loadList(final String field, final Map<String, Object> dataMap, final Supplier<? extends T> supplier) {
        final Object data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("Array value not present for " + field + " in " + dataMap);
        }
        return loadList(data, supplier);
    }

    default <T extends FirestoreDataObject> List<T> loadList(final Object data, final Supplier<? extends T> supplier) {
        final List<Map<String, Object>> list = (List<Map<String, Object>>) data;
        final int size = list.size();
        final List<T> loadedList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final T object = supplier.get();
            object.loadFromMap(list.get(i));
            loadedList.add(object);
        }
        return loadedList;
    }

    default String loadString(final String field, final Map<String, Object> dataMap) {
        final Object data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("String value not present for " + field + " in " + dataMap);
        }
        return (String) data;
    }
    default long loadId(final String field, final Map<String, Object> dataMap) {
        final Object data = dataMap.get(field);
        if (data == null) {
            throw new MissingValueException("String value not present for " + field + " in " + dataMap);
        }
        return Long.parseLong((String) data);
    }
}
