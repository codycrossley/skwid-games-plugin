package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
public class RelayClient implements GameService.RelayGateway
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private final String baseUrl;

    public RelayClient(String baseUrl)
    {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    @Override
    public boolean isEnabled()
    {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public CreateGameResult createGame(String commanderCanonical) throws Exception
    {
        JsonObject body = new JsonObject();
        body.addProperty("commander", commanderCanonical);

        String url = baseUrl + "/v1/games";

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("Create game failed (" + resp.code() + "): " + respBody);
            }

            CreateGameResponse parsed = gson.fromJson(respBody, CreateGameResponse.class);
            if (parsed == null || isBlank(parsed.gameId) || isBlank(parsed.joinCode) || isBlank(parsed.writeKey) || isBlank(parsed.commander))
            {
                throw new IOException("Create game returned invalid response: " + respBody);
            }

            return new CreateGameResult(parsed.gameId, parsed.joinCode, parsed.writeKey, parsed.commander);
        }
    }

    @Override
    public JoinResult joinByCode(String joinCode, String playerCanonical) throws Exception
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerCanonical);

        String url = baseUrl + "/v1/join/" + joinCode;

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("Join failed (" + resp.code() + "): " + respBody);
            }

            JoinInfoResponse parsed = gson.fromJson(respBody, JoinInfoResponse.class);
            if (parsed == null || isBlank(parsed.gameId) || isBlank(parsed.commander))
            {
                throw new IOException("Join returned invalid response: " + respBody);
            }

            return new JoinResult(parsed.gameId, parsed.commander);
        }
    }

    @Override
    public void publishEnlist(String gameId, String writeKey, String playerCanonical, PlayerRole playerRole) throws Exception
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("player", playerCanonical);
        payload.addProperty("role", playerRole.name());

        publishEvent(gameId, writeKey, "ENLIST", payload);
    }

    @Override
    public void publishEliminated(String gameId, String writeKey, String playerCanonical, String actor) throws Exception
    {
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("player", playerCanonical);

        publishEvent(gameId, writeKey, "ELIMINATED", payload);
    }

    @Override
    public void publishRemove(String gameId, String writeKey, String playerCanonical) throws Exception
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("player", playerCanonical);

        publishEvent(gameId, writeKey, "REMOVE", payload);
    }

    @Override
    public void publishLeft(String gameId, String playerCanonical) throws Exception
    {
        if (isBlank(baseUrl))
        {
            throw new IllegalStateException("Relay base URL is not configured.");
        }
        if (isBlank(gameId))
        {
            throw new IllegalArgumentException("gameId is required");
        }
        if (isBlank(playerCanonical))
        {
            throw new IllegalArgumentException("playerCanonical is required");
        }

        String url = baseUrl + "/v1/games/" + gameId + "/leave";

        JsonObject body = new JsonObject();
        body.addProperty("player", playerCanonical);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                // no Authorization header — leaving doesn’t require a write key
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("LEFT failed (" + resp.code() + "): " + respBody);
            }
            // We don't actually care about the response body here; server returns {ok, seq}
        }
    }

    @Override
    public void eliminateAsGuard(String gameId, String playerCanonical, String actorCanonical) throws Exception
    {
        String url = baseUrl + "/v1/games/" + gameId + "/eliminate";

        JsonObject body = new JsonObject();
        body.addProperty("player", playerCanonical);
        if (actorCanonical != null) body.addProperty("actor", actorCanonical);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("Eliminate failed (" + resp.code() + "): " + respBody);
            }
        }
    }

    @Override
    public void publishGameEnded(String gameId, String writeKey) throws Exception
    {
        publishEvent(gameId, writeKey, "GAME_ENDED", new JsonObject());
    }

    @Override
    public void endGame(String gameId, String writeKey) throws Exception
    {
        String url = baseUrl + "/v1/games/" + gameId + "/end";

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, "{}"))
                .header("Authorization", "Bearer " + writeKey)
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("End game failed (" + resp.code() + "): " + respBody);
            }
        }
    }

    @Override
    public void publishTileMarked(String gameId, String writeKey, int x, int y, int plane,
                                  String label, String color, String markedBy,
                                  String tileClass, Set<String> visibleTo) throws Exception
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("x", x);
        payload.addProperty("y", y);
        payload.addProperty("plane", plane);
        if (label     != null) payload.addProperty("label",     label);
        if (color     != null) payload.addProperty("color",     color);
        if (markedBy  != null) payload.addProperty("markedBy",  markedBy);
        if (tileClass != null) payload.addProperty("tileClass", tileClass);
        if (visibleTo != null && !visibleTo.isEmpty())
        {
            JsonArray arr = new JsonArray();
            for (String role : visibleTo) arr.add(role);
            payload.add("visibleTo", arr);
        }
        publishEvent(gameId, writeKey, "TILE_MARKED", payload);
    }

    @Override
    public void publishStoplightState(String gameId, String writeKey, String state) throws Exception
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("state", state);
        publishEvent(gameId, writeKey, "STOPLIGHT_STATE", payload);
    }

    @Override
    public void publishTileUnmarked(String gameId, String writeKey, int x, int y, int plane) throws Exception
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("x", x);
        payload.addProperty("y", y);
        payload.addProperty("plane", plane);
        publishEvent(gameId, writeKey, "TILE_UNMARKED", payload);
    }

    @Override
    public List<TileMarkerReducer.TileMarkerEntry> fetchTiles(String gameId) throws Exception
    {
        String url = baseUrl + "/v1/games/" + gameId + "/tiles";

        Request req = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("Fetch tiles failed (" + resp.code() + "): " + respBody);
            }

            FetchTilesResponse parsed = gson.fromJson(respBody, FetchTilesResponse.class);
            if (parsed == null || parsed.tiles == null)
            {
                return Collections.emptyList();
            }

            List<TileMarkerReducer.TileMarkerEntry> out = new ArrayList<>();
            for (TileOut t : parsed.tiles)
            {
                net.runelite.api.coords.WorldPoint wp =
                        new net.runelite.api.coords.WorldPoint(t.x, t.y, t.plane);
                Set<String> visibleTo = (t.visibleTo != null)
                        ? Collections.unmodifiableSet(new HashSet<>(t.visibleTo))
                        : Collections.emptySet();
                out.add(new TileMarkerReducer.TileMarkerEntry(
                        wp, t.label, t.color, t.markedBy, t.tileClass, visibleTo));
            }
            return out;
        }
    }

    private void publishEvent(String gameId, String writeKey, String type, JsonObject payload) throws Exception
    {
        String url = baseUrl + "/v1/games/" + gameId + "/events";

        JsonObject body = new JsonObject();
        body.addProperty("eventId", makeEventId(type, playerSafe(payload)));
        body.addProperty("ts", java.time.Instant.now().toString());
        body.addProperty("type", type);
        body.add("payload", payload);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .header("Authorization", "Bearer " + writeKey)
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("Publish " + type + " failed (" + resp.code() + "): " + respBody);
            }
        }
    }

    public ReadEventsResponse readEvents(String gameId, int afterSeq) throws Exception
    {
        String url = baseUrl + "/v1/games/" + gameId + "/events?afterSeq=" + afterSeq;

        Request req = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response resp = http.newCall(req).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("Read events failed (" + resp.code() + "): " + respBody);
            }

            ReadEventsResponse parsed = gson.fromJson(respBody, ReadEventsResponse.class);
            if (parsed == null)
            {
                throw new IOException("Read events returned invalid response: " + respBody);
            }
            if (parsed.events == null)
            {
                parsed.events = java.util.Collections.emptyList();
            }
            return parsed;
        }
    }

    // -------- response models (match server JSON keys) --------

    private static class CreateGameResponse
    {
        String gameId;
        String joinCode;
        String writeKey;
        String createdAt;
        String commander;
    }

    private static class JoinInfoResponse
    {
        String gameId;
        String commander;
        String status;
        String createdAt;
        String endedAt;
    }

    public static class ReadEventsResponse
    {
        String gameId;
        int latestSeq;
        java.util.List<EventOut> events;
    }

    public static class EventOut
    {
        int seq;
        String eventId;
        String ts;
        String type;
        com.google.gson.JsonObject payload;
        String actor;
    }

    private static class FetchTilesResponse
    {
        String gameId;
        java.util.List<TileOut> tiles;
    }

    private static class TileOut
    {
        int x;
        int y;
        int plane;
        String label;
        String color;
        String markedBy;
        String tileClass;
        List<String> visibleTo;
    }

    // -------- small helpers --------

    private static String normalizeBaseUrl(String s)
    {
        if (s == null) return "";
        String x = s.trim();
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        return x;
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }

    private static String makeEventId(String type, String suffix)
    {
        // deterministic-ish uniqueness: type + time + a little random
        String t = java.time.Instant.now().toString().replace(":", "").replace("-", "");
        int r = (int) (Math.random() * 0xFFFF);
        return "e_" + t + "_" + type.toUpperCase(Locale.ROOT) + "_" + suffix + "_" + String.format("%04X", r);
    }

    private static String playerSafe(JsonObject payload)
    {
        try
        {
            if (payload != null && payload.has("player") && !payload.get("player").isJsonNull())
            {
                String p = payload.get("player").getAsString();
                if (p == null) return "na";
                return p.replaceAll("[^A-Za-z0-9]+", "");
            }
        }
        catch (Exception ignored) { }
        return "na";
    }
}