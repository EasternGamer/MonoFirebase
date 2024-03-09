package io.github.easterngamer.firebase;

import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;
import com.google.protobuf.NullValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"unused", "unchecked"})
public class MapUtils {

    public static Map<String, Value> toValueMap(final Map<String, Object> data) {
        final Map<String, Value> valueMap = new LinkedHashMap<>(data.size());
        data.forEach((s, object) -> valueMap.put(s, fromObject(object)));
        return valueMap;
    }
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
    private static Value fromObject(Object object) {
        if (object instanceof Map<?,?> map) {
            return Value.newBuilder()
                    .setMapValue(MapValue.newBuilder()
                            .putAllFields(toValueMap((Map<String, Object>) map))
                            .buildPartial()
                    ).buildPartial();
        } else if (object instanceof Number number) {
            if (number instanceof Double) {
                return Value.newBuilder().setDoubleValue(number.doubleValue()).buildPartial();
            } else {
                return Value.newBuilder().setDoubleValue(number.longValue()).buildPartial();
            }
        } else if (object instanceof String string) {
            return Value.newBuilder().setStringValue(string).buildPartial();
        } else if (object instanceof Boolean bool) {
            return Value.newBuilder().setBooleanValue(bool).buildPartial();
        } else if (object instanceof List<?> list) {
            return fromList(list);
        } else if (object == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).buildPartial();
        } else {
            return null;
        }
    }

    private static Value fromList(final List<?> objects) {
        final ArrayValue.Builder builder = ArrayValue.newBuilder();
        final int size = objects.size();
        for (int i = 0; i < size; i++) {
            builder.addValues(i, fromObject(objects.get(i)));
        }
        return  Value.newBuilder().setArrayValue(builder.buildPartial()).buildPartial();
    }

    public static Map.Entry<String, Value> entry(final String name, List<? extends FirestoreDataObject> value) {
        final ArrayValue.Builder builder = ArrayValue.newBuilder();
        final int size = value.size();
        for (int i = 0; i < size; i++) {
            builder.addValues(i, Value.newBuilder().setMapValue(MapValue.newBuilder().putAllFields(value.get(i).getDataMap()).buildPartial()));
        }
        return Map.entry(name, Value.newBuilder().setArrayValue(builder.buildPartial()).buildPartial());
    }

}
