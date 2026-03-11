package gay.runescape;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;
import net.runelite.client.util.ImageUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SharedTileOverlay extends Overlay
{
    // Default border colors used when entry.color is absent or unparseable
    private static final Color COLOR_STANDARD            = new Color(255, 255,   0, 200); // yellow
    private static final Color COLOR_LANDMINE            = new Color(  0,   0,   0, 200); // charcoal
    private static final Color COLOR_LANDMINE_DETONATED  = new Color(255, 106,   0, 200); // orange-red
    private static final Color COLOR_STOPLIGHT_RED       = new Color(255,   0,   0, 200); // red
    private static final Color COLOR_STOPLIGHT_GREEN     = new Color(  0, 255,   0, 200); // green

    private static final long  DETONATION_ANIM_DURATION_MS = 1500;

    /**
     * Keyed by WorldPoint string. Populated only when a tile transitions INTO LANDMINE_DETONATED.
     * Removed when the tile leaves the snapshot.
     */
    private final Map<String, Long> detonationTimes = new HashMap<>();
    /** Last-seen tileClass per tile, used to detect transitions. */
    private final Map<String, String> previousTileClass = new HashMap<>();

    private final Client client;
    private final SkwidGamesConfig config;
    private final GameService gameService;
    private final TileMarkerReducer tileMarkers;
    private final RosterReducer roster;

    public SharedTileOverlay(Client client, SkwidGamesConfig config,
                             GameService gameService, TileMarkerReducer tileMarkers,
                             RosterReducer roster)
    {
        this.client      = client;
        this.config      = config;
        this.gameService = gameService;
        this.tileMarkers = tileMarkers;
        this.roster      = roster;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showTileOverlay()) return null;

        String gameId = gameService.getActiveGameId();
        if (gameId == null || gameId.isBlank()) return null;

        List<TileMarkerReducer.TileMarkerEntry> entries = tileMarkers.snapshot();
        if (entries.isEmpty()) return null;

        String localRole = resolveLocalRole();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Update transition tracking: detect tiles that just became LANDMINE_DETONATED.
        java.util.Set<String> currentKeys = new java.util.HashSet<>();
        for (TileMarkerReducer.TileMarkerEntry e : entries)
        {
            String key = e.point.toString();
            currentKeys.add(key);
            String prev = previousTileClass.get(key);
            if ("LANDMINE_DETONATED".equals(e.tileClass) && "LANDMINE".equals(prev))
                detonationTimes.put(key, System.currentTimeMillis());
            previousTileClass.put(key, e.tileClass);
        }
        // Clean up state for tiles no longer in the snapshot.
        previousTileClass.keySet().retainAll(currentKeys);
        detonationTimes.keySet().retainAll(currentKeys);

        for (TileMarkerReducer.TileMarkerEntry entry : entries)
        {
            if (!isVisibleToRole(entry, localRole)) continue;
            renderMarker(g, entry);
        }

        return null;
    }

    private void renderMarker(Graphics2D g, TileMarkerReducer.TileMarkerEntry entry)
    {
        WorldPoint wp = entry.point;

        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            Color base   = resolveColor(entry);
            Color fill   = new Color(base.getRed(), base.getGreen(), base.getBlue(), 60);
            Color border = new Color(base.getRed(), base.getGreen(), base.getBlue(), 200);

            g.setColor(fill);
            g.fillPolygon(poly);

            g.setColor(border);
            g.setStroke(new BasicStroke(1f));
            g.drawPolygon(poly);

            if (entry.label != null && !entry.label.isBlank())
            {
                int cx = (int) poly.getBounds().getCenterX();
                int cy = (int) poly.getBounds().getCenterY();
                OverlayUtil.renderTextLocation(g, new net.runelite.api.Point(cx, cy), entry.label, border);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Visibility helpers
    // -------------------------------------------------------------------------

    private String resolveLocalRole()
    {
        if (gameService.isLocalCommander()) return "COMMANDER";
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) return null;
        String rsn = Text.toJagexName(client.getLocalPlayer().getName());
        PlayerRole role = roster.getRole(rsn);
        if (role == PlayerRole.GUARD) return "GUARD";
        if (role == PlayerRole.CONTESTANT) return "CONTESTANT";
        return null;
    }

    private static boolean isVisibleToRole(TileMarkerReducer.TileMarkerEntry entry, String localRole)
    {
        if (entry.visibleTo == null || entry.visibleTo.isEmpty()) return false;
        if (localRole == null) return false;
        return entry.visibleTo.contains(localRole);
    }

    // -------------------------------------------------------------------------
    // Color helpers
    // -------------------------------------------------------------------------

    private Color resolveColor(TileMarkerReducer.TileMarkerEntry entry)
    {
        return classDefaultColor(entry.tileClass);
    }

    private Color classDefaultColor(String tileClass)
    {
        if (tileClass == null) return COLOR_STANDARD;
        switch (tileClass.toUpperCase())
        {
            case "LANDMINE":            return COLOR_LANDMINE;
            case "LANDMINE_DETONATED":  return COLOR_LANDMINE_DETONATED;
            case "STOPLIGHT":
                return "RED".equals(tileMarkers.getStoplightState())
                        ? COLOR_STOPLIGHT_RED : COLOR_STOPLIGHT_GREEN;
            default:          return COLOR_STANDARD;
        }
    }

}