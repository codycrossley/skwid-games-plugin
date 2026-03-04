package com.example;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GameService owns "game lifecycle" + per-account pointers:
 *   - activeGameId  (everyone)
 *   - joinCode      (everyone; used to join/share)
 *   - writeKey      (commander only; used to publish events)
 *   - commander     (authoritative, from relay; used for overlays/UI)
 *
 * The authoritative roster/state lives in the relay event log.
 * Clients maintain a local reduced view via polling (RosterReducer/EventPoller).
 */
@RequiredArgsConstructor
public class GameService
{
    private final Client client;
    private final AccountConfig accountConfig;
    private final RelayGateway relay;

    public String startNewGame() throws Exception
    {
        String commander = requireLocalPlayerCanonical();

        requireRelayEnabled();

        RelayGateway.CreateGameResult created = relay.createGame(commander);

        accountConfig.setActiveGameId(created.gameId);
        accountConfig.setJoinCode(created.joinCode);
        accountConfig.setWriteKey(created.writeKey);
        accountConfig.cacheWriteKeyForGame(created.gameId, created.writeKey);
        accountConfig.setCommander(created.commander);

        return created.gameId;
    }

    public void joinGame(String joinCode) throws Exception
    {
        String code = normalizeJoinCode(joinCode);
        String me = requireLocalPlayerCanonical();

        requireRelayEnabled();

        RelayGateway.JoinResult joined = relay.joinByCode(code, me);

        accountConfig.setActiveGameId(joined.gameId);
        accountConfig.setJoinCode(code);
        accountConfig.setCommander(joined.commander);
        String cached = accountConfig.getCachedWriteKeyForGame(joined.gameId);
        accountConfig.setWriteKey(cached);
    }

    public void enlist(String targetCanonical, PlayerRole playerRole) throws Exception
    {
        String gameId = requireActiveGameId();
        String writeKey = requireWriteKey();
        requireRelayEnabled();

        String player = requireCanonicalPlayer(targetCanonical);
        relay.publishEnlist(gameId, writeKey, player, playerRole);
    }

    public void eliminateAsGuard(String playerCanonical) throws Exception
    {
        requireRelayEnabled();

        String gameId = requireActiveGameId();
        String actor = requireLocalPlayerCanonical();
        relay.eliminateAsGuard(gameId, playerCanonical, actor);
    }

    public void eliminate(String playerCanonical) throws Exception
    {
        requireRelayEnabled();

        String gameId = getActiveGameId();
        if (gameId == null || gameId.isBlank())
        {
            throw new IllegalStateException("No active game.");
        }

        String writeKey = accountConfig.getWriteKey();
        if (writeKey == null || writeKey.isBlank())
        {
            throw new IllegalStateException("Missing write key.");
        }

        relay.publishEliminated(gameId, writeKey, playerCanonical, requireLocalPlayerCanonical());
    }

    public void removePlayer(String targetCanonical) throws Exception
    {
        String gameId = requireActiveGameId();
        String writeKey = requireWriteKey();
        requireRelayEnabled();

        String player = requireCanonicalPlayer(targetCanonical);
        relay.publishRemove(gameId, writeKey, player);
    }

    public void markTile(WorldPoint wp, String label, String tileClass, Set<String> visibleTo) throws Exception
    {
        String gameId = requireActiveGameId();
        String writeKey = requireWriteKey();
        requireRelayEnabled();
        String me = requireLocalPlayerCanonical();
        String color = colorForClass(tileClass);
        relay.publishTileMarked(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane(),
                label, color, me, tileClass, visibleTo);
    }

    private static String colorForClass(String tileClass)
    {
        if (tileClass == null) return "#FFFF00";
        switch (tileClass.toUpperCase(Locale.ROOT))
        {
            case "LANDMINE":  return "#FF0000";
            case "SAFE_ZONE": return "#00FF00";
            case "BOUNDARY":  return "#0080FF";
            case "STOPLIGHT": return "#00FF00"; // starts green; overlay overrides dynamically
            default:          return "#FFFF00";
        }
    }

    public void unmarkTile(WorldPoint wp) throws Exception
    {
        String gameId = requireActiveGameId();
        String writeKey = requireWriteKey();
        requireRelayEnabled();
        relay.publishTileUnmarked(gameId, writeKey, wp.getX(), wp.getY(), wp.getPlane());
    }

    public List<TileMarkerReducer.TileMarkerEntry> fetchTiles(String gameId) throws Exception
    {
        return relay.fetchTiles(gameId);
    }

    public RelayClient.RosterSnapshotResponse fetchRoster(String gameId) throws Exception
    {
        return relay.fetchRoster(gameId);
    }

    public void publishStoplightState(String state) throws Exception
    {
        String gameId = requireActiveGameId();
        String writeKey = requireWriteKey();
        requireRelayEnabled();
        relay.publishStoplightState(gameId, writeKey, state);
    }

    public void leaveGameRemote() throws Exception
    {
        String gameId = getActiveGameId();
        if (gameId == null || gameId.isBlank())
        {
            return; // nothing to do
        }

        requireRelayEnabled();

        String me = requireLocalPlayerCanonical();
        relay.publishLeft(gameId, me);
    }

    public void endGame() throws Exception
    {
        String gameId = requireActiveGameId();
        String writeKey = requireWriteKey();
        requireRelayEnabled();

        relay.publishGameEnded(gameId, writeKey);
        relay.endGame(gameId, writeKey);
        accountConfig.forgetWriteKeyForGame(gameId);
        clearActiveGameLocally();
    }

    // ----------------------------
    // Per-account state access
    // ----------------------------

    public String getActiveGameId() { return accountConfig.getActiveGameId(); }
    public String getJoinCode()     { return accountConfig.getJoinCode(); }
    public String getWriteKey()     { return accountConfig.getWriteKey(); }
    public String getCommander()    { return accountConfig.getCommander(); }

    public void clearActiveGameLocally()
    {
        accountConfig.clearGamePointers(); // should clear gameId/joinCode/writeKey/commander
    }

    // ----------------------------
    // Validation + normalization
    // ----------------------------

    private void requireRelayEnabled()
    {
        if (!relay.isEnabled())
        {
            throw new IllegalStateException("Relay is not configured/enabled.");
        }
    }

    private String requireActiveGameId()
    {
        String gameId = accountConfig.getActiveGameId();
        if (gameId == null || gameId.isBlank())
        {
            throw new IllegalStateException("No active game. Start or join a game first.");
        }
        return gameId;
    }

    private String requireWriteKey()
    {
        String writeKey = accountConfig.getWriteKey();
        if (writeKey == null || writeKey.isBlank())
        {
            throw new IllegalStateException("Only the Commander can perform this action in the current game.");
        }
        return writeKey;
    }

    private String requireLocalPlayerCanonical()
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
        {
            throw new IllegalStateException("Not logged in yet (no local player).");
        }
        return Text.toJagexName(client.getLocalPlayer().getName());
    }

    private static String normalizeJoinCode(String joinCode)
    {
        if (joinCode == null) throw new IllegalArgumentException("joinCode is required.");
        String code = joinCode.trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) throw new IllegalArgumentException("joinCode is required.");
        return code;
    }

    /**
     * Defensive: ensures we only ever send canonical RSNs to the relay.
     * (Prevents "(level 126)" and weird whitespace from leaking into events.)
     */
    private static String requireCanonicalPlayer(String player)
    {
        if (player == null) throw new IllegalArgumentException("player is required.");
        String canon = Text.toJagexName(player);
        if (canon == null || canon.isBlank())
        {
            throw new IllegalArgumentException("player is required.");
        }
        return canon;
    }

    // In GameService
    public boolean isLocalCommander()
    {
        return isLocalPlayerCommanderByName() &&
                accountConfig.getWriteKey() != null;
    }

    public boolean isLocalPlayerCommanderByName()
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) return false;
        String me = Text.toJagexName(client.getLocalPlayer().getName());
        String commander = accountConfig.getCommander();
        if (commander == null || commander.isBlank()) return false;
        return me.equals(Text.toJagexName(commander));
    }

    // ----------------------------
    // Relay abstraction
    // ----------------------------

    public interface RelayGateway
    {
        boolean isEnabled();

        final class CreateGameResult
        {
            public final String gameId;
            public final String joinCode;
            public final String writeKey;
            public final String commander;

            public CreateGameResult(String gameId, String joinCode, String writeKey, String commander)
            {
                this.gameId = gameId;
                this.joinCode = joinCode;
                this.writeKey = writeKey;
                this.commander = commander;
            }
        }

        final class JoinResult
        {
            public final String gameId;
            public final String commander;

            public JoinResult(String gameId, String commander)
            {
                this.gameId = gameId;
                this.commander = commander;
            }
        }

        CreateGameResult createGame(String commanderCanonical) throws Exception;

        JoinResult joinByCode(String joinCode, String playerCanonical) throws Exception;

        void publishEnlist(String gameId, String writeKey, String playerCanonical, PlayerRole playerRole) throws Exception;

        void publishEliminated(String gameId, String writeKey, String playerCanonical, String actor) throws Exception;

        void publishRemove(String gameId, String writeKey, String playerCanonical) throws Exception;

        void publishLeft(String gameId, String playerCanonical) throws Exception;

        void eliminateAsGuard(String gameId, String playerCanonical, String actorCanonical) throws Exception;

        void publishGameEnded(String gameId, String writeKey) throws Exception;

        void endGame(String gameId, String writeKey) throws Exception;

        void publishTileMarked(String gameId, String writeKey, int x, int y, int plane,
                               String label, String color, String markedBy,
                               String tileClass, Set<String> visibleTo) throws Exception;

        void publishTileUnmarked(String gameId, String writeKey, int x, int y, int plane) throws Exception;

        List<TileMarkerReducer.TileMarkerEntry> fetchTiles(String gameId) throws Exception;

        RelayClient.RosterSnapshotResponse fetchRoster(String gameId) throws Exception;

        void publishStoplightState(String gameId, String writeKey, String state) throws Exception;
    }
}