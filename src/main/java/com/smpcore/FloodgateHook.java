package com.smpcore;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.function.Consumer;

/** Only loaded when Floodgate is installed. */
public final class FloodgateHook {
    private final FloodgateApi api = FloodgateApi.getInstance();

    public boolean isBedrock(UUID id) {
        return api.isFloodgatePlayer(id);
    }

    public String prefix() {
        String p = api.getPlayerPrefix();
        return p == null ? "" : p;
    }

    /** Looks up a Bedrock gamertag (without prefix). cb gets null if not found. */
    public void uuidFor(String gamertag, Consumer<UUID> cb) {
        api.getUuidFor(gamertag).whenComplete((u, t) -> cb.accept(t != null ? null : u));
    }

    public void send(UUID id, SimpleForm form) {
        api.sendForm(id, form);
    }
}
