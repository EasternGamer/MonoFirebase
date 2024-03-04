package io.github.easterngamer.firebase;

import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;

import java.util.List;
import java.util.Map;

public class MapUtils {
    public static Map.Entry<String, Value> entry(final String name, String value) {
        return Map.entry(name, Value.newBuilder().setStringValue(value).buildPartial());
    }

    public static Map.Entry<String, Value> entry(final String name, long value) {
        return Map.entry(name, Value.newBuilder().setIntegerValue(value).buildPartial());
    }

    public static Map.Entry<String, Value> entry(final String name, double value) {
        return Map.entry(name, Value.newBuilder().setDoubleValue(value).buildPartial());
    }

    public static Map.Entry<String, Value> entry(final String name, boolean value) {
        return Map.entry(name, Value.newBuilder().setBooleanValue(value).buildPartial());
    }

    public static Map.Entry<String, Value> entry(final String name, List<? extends FirestoreDataObject> value) {
        final ArrayValue.Builder builder = ArrayValue.newBuilder();
        final int size = value.size();
        for (int i = 0; i < size; i++) {
            builder.addValues(i, Value.newBuilder().setMapValue(MapValue.newBuilder().putAllFields(value.get(i).getDataMap()).buildPartial()));
        }
        return Map.entry(name, Value.newBuilder().setArrayValue(ArrayValue.newBuilder().buildPartial()).buildPartial());
    }

}
