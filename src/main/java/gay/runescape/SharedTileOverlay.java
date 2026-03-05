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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.Collection;
import java.util.List;

public class SharedTileOverlay extends Overlay
{
    // Default border colors used when entry.color is absent or unparseable
    private static final Color COLOR_STANDARD       = new Color(255, 255,   0, 200); // yellow
    private static final Color COLOR_LANDMINE       = new Color(0, 0, 0, 200); // charcoal
    private static final Color COLOR_STOPLIGHT_RED  = new Color(255,   0,   0, 200); // red
    private static final Color COLOR_STOPLIGHT_GREEN = new Color(  0, 255,   0, 200); // green

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
            case "LANDMINE":  return COLOR_LANDMINE;
            case "STOPLIGHT":
                return "RED".equals(tileMarkers.getStoplightState())
                        ? COLOR_STOPLIGHT_RED : COLOR_STOPLIGHT_GREEN;
            default:          return COLOR_STANDARD;
        }
    }

}