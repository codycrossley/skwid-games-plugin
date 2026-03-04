package gay.runescape;

import com.google.gson.JsonElement;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TileMarkerReducer
{
    public static final class TileMarkerEntry
    {
        public final WorldPoint point;
        public final String label;
        public final String color;
        public final String markedBy;
        /** One of: "STANDARD", "LANDMINE", "STOPLIGHT". Null treated as STANDARD. */
        public final String tileClass;
        /** Roles that can see this tile. Empty set = visible to no one. */
        public final Set<String> visibleTo;

        public TileMarkerEntry(WorldPoint point, String label, String color,
                               String markedBy, String tileClass, Set<String> visibleTo)
        {
            this.point     = point;
            this.label     = label;
            this.color     = color;
            this.markedBy  = markedBy;
            this.tileClass = tileClass;
            this.visibleTo = (visibleTo != null) ? visibleTo : Collections.emptySet();
        }
    }

    private final ConcurrentHashMap<String, TileMarkerEntry> markers = new ConcurrentHashMap<>();
    private volatile String stoplightState = "GREEN";

    public String getStoplightState()
    {
        return stoplightState;
    }

    public void setStoplightState(String state)
    {
        if ("RED".equalsIgnoreCase(state) || "GREEN".equalsIgnoreCase(state))
        {
            stoplightState = state.toUpperCase(Locale.ROOT);
        }
    }

    public void apply(RelayClient.EventOut e)
    {
        if (e == null || e.type == null) return;

        final String type = e.type.toUpperCase(Locale.ROOT);

        if ("TILE_MARKED".equals(type))
        {
            Integer x     = safeInt(e.payload, "x");
            Integer y     = safeInt(e.payload, "y");
            Integer plane = safeInt(e.payload, "plane");
            if (x == null || y == null || plane == null) return;

            String label     = safeStr(e.payload, "label");
            String color     = safeStr(e.payload, "color");
            String markedBy  = safeStr(e.payload, "markedBy");
            String tileClass = safeStr(e.payload, "tileClass");

            Set<String> visibleTo = Collections.emptySet();
            if (e.payload != null && e.payload.has("visibleTo")
                    && e.payload.get("visibleTo").isJsonArray())
            {
                Set<String> roles = new HashSet<>();
                for (JsonElement el : e.payload.getAsJsonArray("visibleTo"))
                {
                    if (!el.isJsonNull())
                    {
                        roles.add(el.getAsString().toUpperCase(Locale.ROOT));
                    }
                }
                visibleTo = Collections.unmodifiableSet(roles);
            }

            WorldPoint wp = new WorldPoint(x, y, plane);
            markers.put(key(x, y, plane),
                    new TileMarkerEntry(wp, label, color, markedBy, tileClass, visibleTo));
            return;
        }

        if ("STOPLIGHT_STATE".equals(type))
        {
            String state = safeStr(e.payload, "state");
            if ("RED".equalsIgnoreCase(state) || "GREEN".equalsIgnoreCase(state))
            {
                stoplightState = state.toUpperCase(Locale.ROOT);
            }
            return;
        }

        if ("TILE_UNMARKED".equals(type))
        {
            Integer x     = safeInt(e.payload, "x");
            Integer y     = safeInt(e.payload, "y");
            Integer plane = safeInt(e.payload, "plane");
            if (x == null || y == null || plane == null) return;

            markers.remove(key(x, y, plane));
        }
    }

    public void loadAll(List<TileMarkerEntry> entries)
    {
        if (entries == null) return;
        for (TileMarkerEntry entry : entries)
        {
            if (entry == null || entry.point == null) continue;
            markers.put(key(entry.point.getX(), entry.point.getY(), entry.point.getPlane()), entry);
        }
    }

    /** Returns only tiles whose tileClass is "STOPLIGHT". */
    public List<TileMarkerEntry> snapshotStoplights()
    {
        List<TileMarkerEntry> out = new ArrayList<>();
        for (TileMarkerEntry entry : markers.values())
        {
            if ("STOPLIGHT".equalsIgnoreCase(entry.tileClass))
            {
                out.add(entry);
            }
        }
        return out;
    }

    public void reset()
    {
        markers.clear();
        stoplightState = "GREEN";
    }

    public List<TileMarkerEntry> snapshot()
    {
        return Collections.unmodifiableList(new ArrayList<>(markers.values()));
    }

    /** Returns only tiles whose tileClass is "LANDMINE". */
    public List<TileMarkerEntry> snapshotLandmines()
    {
        List<TileMarkerEntry> out = new ArrayList<>();
        for (TileMarkerEntry entry : markers.values())
        {
            if ("LANDMINE".equalsIgnoreCase(entry.tileClass))
            {
                out.add(entry);
            }
        }
        return out;
    }

    public TileMarkerEntry getMarker(WorldPoint wp)
    {
        if (wp == null) return null;
        return markers.get(key(wp.getX(), wp.getY(), wp.getPlane()));
    }

    static String key(int x, int y, int plane)
    {
        return x + ":" + y + ":" + plane;
    }

    private static String safeStr(com.google.gson.JsonObject o, String k)
    {
        return (o != null && o.has(k) && !o.get(k).isJsonNull())
                ? o.get(k).getAsString()
                : null;
    }

    private static Integer safeInt(com.google.gson.JsonObject o, String k)
    {
        try
        {
            return (o != null && o.has(k) && !o.get(k).isJsonNull())
                    ? o.get(k).getAsInt()
                    : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}