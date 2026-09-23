package io.ohmvir.plugins.ollamaclient.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public record OllamaMessage(String role, String content) {
    public JsonObject toJson() {
        Gson gson = new Gson();
        return gson.toJsonTree(this).getAsJsonObject();
    }
}
