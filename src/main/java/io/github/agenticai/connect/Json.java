package io.github.agenticai.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.util.Objects;

/** Gson-backed JSON utilities without reflective DTO binding or provider SDKs. */
public final class Json {
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT)
            .disableHtmlEscaping().serializeNulls().create();
    private Json() {}
    public static JsonElement parse(String text) {
        JsonElement value = GSON.fromJson(Objects.requireNonNull(text), JsonElement.class);
        if (value == null) throw new IllegalArgumentException("JSON 不能为空");
        return value;
    }
    public static JsonObject object(String text) {
        JsonElement value = parse(text);
        if (!value.isJsonObject()) throw new IllegalArgumentException("协议数据必须是 JSON 对象");
        return value.getAsJsonObject();
    }
    public static String stringify(JsonElement value) { return GSON.toJson(value); }
    static JsonObject obj(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) put(o, (String) pairs[i], pairs[i + 1]);
        return o;
    }
    static void put(JsonObject o, String key, Object value) {
        if (value instanceof JsonElement e) o.add(key, e.deepCopy());
        else if (value instanceof Number n) o.addProperty(key, n);
        else if (value instanceof Boolean b) o.addProperty(key, b);
        else if (value == null) o.add(key, JsonNull.INSTANCE);
        else o.addProperty(key, value.toString());
    }
    static JsonArray arr(JsonElement... items) {
        JsonArray a = new JsonArray();
        for (JsonElement item : items) a.add(item);
        return a;
    }
    static JsonObject o(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }
    static JsonObject o(JsonObject e, String key) { return o(e.get(key)); }
    static JsonArray a(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
    static JsonArray a(JsonObject e, String key) { return a(e.get(key)); }
    static String s(JsonObject e, String key) {
        JsonElement v = e.get(key);
        return v == null || v.isJsonNull() ? "" : v.isJsonPrimitive() ? v.getAsString() : stringify(v);
    }
    static long n(JsonObject e, String key) {
        JsonElement v = e.get(key);
        return v == null || v.isJsonNull() ? 0 : v.getAsLong();
    }
    static boolean b(JsonObject e, String key) {
        JsonElement v = e.get(key);
        return v != null && !v.isJsonNull() && v.getAsBoolean();
    }
    static boolean has(JsonObject e, String key) { return e.has(key) && !e.get(key).isJsonNull(); }
    static void copy(JsonObject from, JsonObject to, String... keys) {
        for (String key : keys) if (from.has(key)) to.add(key, from.get(key).deepCopy());
    }
    static void rename(JsonObject from, String key, JsonObject to, String target) {
        if (from.has(key)) to.add(target, from.get(key).deepCopy());
    }
}
