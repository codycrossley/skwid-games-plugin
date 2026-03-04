package com.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.client.util.Text;

public class RosterReducer
{
    private final ConcurrentHashMap<String, PlayerRole> roleByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> numberByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerStatus> statusByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> displayNameByPlayer = new ConcurrentHashMap<>();
    private final Set<String> joinedPlayers = ConcurrentHashMap.newKeySet();

    public static final class SnapshotPlayer
    {
        public final String rsn;
        public final String role;
        public final Integer number;
        public final String status;
        public final boolean joined;

        public SnapshotPlayer(String rsn, String role, Integer number, String status, boolean joined)
        {
            this.rsn    = rsn;
            this.role   = role;
            this.number = number;
            this.status = status;
            this.joined = joined;
        }
    }

    public static final class RosterEntry
    {
        public final String rsn;           // canonical RSN (lowercase or normalized)
        public final Integer number;       // may be null
        public final PlayerRole role;      // CONTESTANT / GUARD / REMOVED / null
        public final PlayerStatus status;  // ALIVE / ELIMINATED / null
        public final boolean joined;       // whether we've seen PLAYER_JOINED

        public RosterEntry(String rsn,
                           Integer number,
                           PlayerRole role,
                           PlayerStatus status,
                           boolean joined)
        {
            this.rsn = rsn;
            this.number = number;
            this.role = role;
            this.status = status;
            this.joined = joined;
        }
    }

    public PlayerRole getRole(String canonicalRsn)
    {
        if (canonicalRsn == null) return null;
        return roleByPlayer.get(canonicalRsn.toLowerCase());
    }

    public Integer getNumber(String canonicalRsn)
    {
        if (canonicalRsn == null) return null;
        return numberByPlayer.get(canonicalRsn.toLowerCase());
    }

    public PlayerStatus getStatus(String canonicalRsn)
    {
        if (canonicalRsn == null) return null;
        return statusByPlayer.get(canonicalRsn.toLowerCase());
    }

    /** Reverse lookup: given a contestant number, return the canonical RSN, or null if not found. */
    public String getRsnByNumber(int number)
    {
        for (Map.Entry<String, Integer> entry : numberByPlayer.entrySet())
        {
            if (entry.getValue() == number)
            {
                return entry.getKey();
            }
        }
        return null;
    }

    public Map<String, PlayerRole> rolesSnapshot()
    {
        return Collections.unmodifiableMap(roleByPlayer);
    }

    public void reset()
    {
        roleByPlayer.clear();
        numberByPlayer.clear();
        statusByPlayer.clear();
        displayNameByPlayer.clear();
        joinedPlayers.clear();
    }

    public void loadSnapshot(List<SnapshotPlayer> players)
    {
        reset();
        if (players == null) return;

        for (SnapshotPlayer p : players)
        {
            if (p == null || p.rsn == null) continue;

            final String key = canonicalKey(p.rsn);
            if (key == null) continue;

            displayNameByPlayer.put(key, displayName(p.rsn));

            if (p.joined) joinedPlayers.add(key);

            if (p.role != null)
            {
                try
                {
                    PlayerRole role = PlayerRole.valueOf(p.role.trim().toUpperCase(Locale.ROOT));
                    roleByPlayer.put(key, role);

                    if (isContestant(role) && p.number != null && p.number > 0)
                    {
                        numberByPlayer.put(key, p.number);
                    }
                }
                catch (IllegalArgumentException ignored) { }
            }

            if (p.status != null)
            {
                try
                {
                    statusByPlayer.put(key, PlayerStatus.valueOf(p.status.trim().toUpperCase(Locale.ROOT)));
                }
                catch (IllegalArgumentException ignored) { }
            }
        }
    }

    public void apply(RelayClient.EventOut e)
    {
        if (e == null || e.type == null) return;

        final String type = e.type.toUpperCase(Locale.ROOT);

        if ("PLAYER_JOINED".equals(type))
        {
            final String playerRaw = safeStr(e.payload, "player");
            if (playerRaw == null) return;

            final String playerKey = canonicalKey(playerRaw);
            if (playerKey == null) return;

            displayNameByPlayer.putIfAbsent(playerKey, displayName(playerRaw));

            // Presence only. No number assignment here.
            statusByPlayer.put(playerKey, PlayerStatus.ALIVE);

            // Keep track of players who have joined
            joinedPlayers.add(playerKey);

            return;
        }

        if ("ENLIST".equals(type))
        {
            final String playerRaw = safeStr(e.payload, "player");
            final String roleRaw   = safeStr(e.payload, "role");
            if (playerRaw == null || roleRaw == null) return;

            final String playerKey = canonicalKey(playerRaw);
            if (playerKey == null) return;

            displayNameByPlayer.putIfAbsent(playerKey, displayName(playerRaw));

            final PlayerRole role;
            try
            {
                role = PlayerRole.valueOf(roleRaw.trim().toUpperCase(Locale.ROOT));
            }
            catch (Exception ignored)
            {
                return;
            }

            roleByPlayer.put(playerKey, role);

            // Only contestants get (sticky) numbers
            if (isContestant(role))
            {
                final Integer number = safeInt(e.payload, "number"); // relay assigns
                if (number != null && number > 0)
                {
                    numberByPlayer.putIfAbsent(playerKey, number); // sticky
                }

                statusByPlayer.put(playerKey, PlayerStatus.ALIVE);
            }
            else
            {
                // Guards/Commander never have a number; defensively clear stale numbers
                numberByPlayer.remove(playerKey);
            }
            return;
        }

        if ("ELIMINATED".equals(type))
        {
            final String playerRaw = safeStr(e.payload, "player");
            if (playerRaw == null) return;

            final String playerKey = canonicalKey(playerRaw);
            if (playerKey == null) return;

            statusByPlayer.put(playerKey, PlayerStatus.ELIMINATED);
            return;
        }

        if ("REMOVE".equals(type))
        {
            final String playerRaw = safeStr(e.payload, "player");
            if (playerRaw == null) return;

            final String playerKey = canonicalKey(playerRaw);
            if (playerKey == null) return;

            roleByPlayer.put(playerKey, PlayerRole.REMOVED);

            // Sticky numbering rule:
            // Do NOT clear number/status so a contestant can re-join and keep their number.
            return;
        }

        if ("PLAYER_LEFT".equals(type))
        {
            final String playerRaw = safeStr(e.payload, "player");
            if (playerRaw == null) return;

            final String playerKey = canonicalKey(playerRaw);
            if (playerKey == null) return;

            // Fully forget this player from the current game
            roleByPlayer.remove(playerKey);
            numberByPlayer.remove(playerKey);
            statusByPlayer.remove(playerKey);
            joinedPlayers.remove(playerKey);

            return;
        }
    }

    public List<RosterEntry> snapshot()
    {
        Map<String, RosterEntry> tmp = new HashMap<>();

        // Start with joined players
        for (String playerKey : joinedPlayers)
        {
            PlayerRole role = roleByPlayer.get(playerKey);
            PlayerStatus status = statusByPlayer.get(playerKey);
            Integer number = numberByPlayer.get(playerKey);
            String display = displayNameByPlayer.getOrDefault(playerKey, playerKey);

            tmp.put(playerKey, new RosterEntry(
                    display,
                    number,
                    role,
                    status,
                    true
            ));
        }

        // Also include players we know about via role/status/number but who aren't in joinedPlayers
        for (String playerKey : roleByPlayer.keySet())
        {
            if (tmp.containsKey(playerKey)) continue;

            PlayerRole role = roleByPlayer.get(playerKey);
            PlayerStatus status = statusByPlayer.get(playerKey);
            Integer number = numberByPlayer.get(playerKey);
            String display = displayNameByPlayer.getOrDefault(playerKey, playerKey);

            tmp.put(playerKey, new RosterEntry(
                    display,
                    number,
                    role,
                    status,
                    false
            ));
        }

        // Removed players are hidden from the roster (internal state is kept for sticky number re-assignment)
        tmp.values().removeIf(e -> e.role == PlayerRole.REMOVED);

        // Convert to list and sort
        List<RosterEntry> out = new ArrayList<>(tmp.values());

        out.sort((a, b) ->
        {
            boolean aContestant = a.role == PlayerRole.CONTESTANT;
            boolean bContestant = b.role == PlayerRole.CONTESTANT;

            // Contestants first
            if (aContestant && !bContestant) return -1;
            if (!aContestant && bContestant) return 1;

            // Among contestants, sort by number if present
            if (aContestant && bContestant)
            {
                int an = (a.number != null) ? a.number : Integer.MAX_VALUE;
                int bn = (b.number != null) ? b.number : Integer.MAX_VALUE;
                int cmp = Integer.compare(an, bn);
                if (cmp != 0) return cmp;
            }

            // Fallback: alphabetical by rsn
            return a.rsn.compareTo(b.rsn);
        });

        return out;
    }

    private static String canonicalKey(String playerRaw)
    {
        if (playerRaw == null) return null;

        String s = Text.removeTags(playerRaw);
        s = s.replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");

        String canon = Text.toJagexName(s);
        if (canon == null || canon.isBlank()) return null;

        return canon.toLowerCase(Locale.ROOT);
    }

    /** Returns the properly-cased display name for a raw player string from an event payload. */
    private static String displayName(String playerRaw)
    {
        if (playerRaw == null) return playerRaw;

        String s = Text.removeTags(playerRaw);
        s = s.replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");

        String canon = Text.toJagexName(s);
        return (canon != null && !canon.isBlank()) ? canon : s.trim();
    }

    private static boolean isContestant(PlayerRole role)
    {
        return role == PlayerRole.CONTESTANT;
    }


    public boolean hasJoined(String canonicalRsn)
    {
        if (canonicalRsn == null) return false;
        return joinedPlayers.contains(canonicalRsn.toLowerCase());
    }


    private static String safeStr(com.google.gson.JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull())
                ? o.get(key).getAsString()
                : null;
    }

    private static Integer safeInt(com.google.gson.JsonObject o, String key)
    {
        try
        {
            return (o != null && o.has(key) && !o.get(key).isJsonNull())
                    ? o.get(key).getAsInt()
                    : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}