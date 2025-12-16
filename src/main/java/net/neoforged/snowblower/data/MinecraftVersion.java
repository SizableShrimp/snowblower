/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.data;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.util.Objects;
import java.util.regex.Pattern;

public record MinecraftVersion(Type type, String version) {
    private static final Pattern RELEASE = Pattern.compile("\\d+(?:\\.\\d+){1,3}");
    private static final Pattern NEW_SNAPSHOT = Pattern.compile("\\d+.\\d+-snapshot-\\d+");
    private static final Pattern OLD_SNAPSHOT = Pattern.compile("\\d{2}w\\d{2}[a-z]");
    private static final Pattern PRE_RC = Pattern.compile(RELEASE.pattern() + "(?: Pre-Release |-rc|-pre)\\d+");

    public static MinecraftVersion from(String version) {
        Type type = Type.SPECIAL;

        if (NEW_SNAPSHOT.matcher(version).matches() || PRE_RC.matcher(version).matches() || OLD_SNAPSHOT.matcher(version).matches()) {
            type = Type.SNAPSHOT;
        } else if (RELEASE.matcher(version).matches()) {
            type = Type.RELEASE;
        }

        return new MinecraftVersion(type, version);
    }

    @Override
    public String toString() {
        return this.version;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || this.getClass() != o.getClass())
            return false;

        MinecraftVersion that = (MinecraftVersion) o;
        return Objects.equals(this.version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.version);
    }

    public enum Type {
        RELEASE,
        SNAPSHOT,
        SPECIAL(true);

        private final boolean special;

        Type() {
            this(false);
        }

        Type(boolean special) {
            this.special = special;
        }

        public boolean isSpecial() {
            return this.special;
        }
    }

    public static class Deserializer implements JsonDeserializer<MinecraftVersion> {
        public static final Deserializer INSTANCE = new Deserializer();

        private Deserializer() {}

        @Override
        public MinecraftVersion deserialize(JsonElement json, java.lang.reflect.Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                return MinecraftVersion.from(json.getAsString());
            } else {
                throw new JsonParseException("Expected minecraft version string");
            }
        }
    }
}
