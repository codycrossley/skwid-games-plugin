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

//            if ("LANDMINE_DETONATED".equals(entry.tileClass))
//                renderDetonationEffect(g, entry, poly);

            if (entry.label != null && !entry.label.isBlank())
            {
                int cx = (int) poly.getBounds().getCenterX();
                int cy = (int) poly.getBounds().getCenterY();
                OverlayUtil.renderTextLocation(g, new net.runelite.api.Point(cx, cy), entry.label, border);
            }
        }
    }

    private void renderDetonationEffect(Graphics2D g, TileMarkerReducer.TileMarkerEntry entry, Polygon poly)
    {
        Long startTime = detonationTimes.get(entry.point.toString());
        if (startTime == null) return;

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed >= DETONATION_ANIM_DURATION_MS) return;

        float t = (float) elapsed / DETONATION_ANIM_DURATION_MS; // 0 → 1

        Rectangle bounds = poly.getBounds();
        int cx = (int) bounds.getCenterX();
        int cy = (int) bounds.getCenterY();
        int r  = Math.max(bounds.width, bounds.height) / 2;

        Stroke savedStroke = g.getStroke();
        Color  savedColor  = g.getColor();

        // Layer 1: Smoke/dust ring — slow, wide, late (t 0.45 → 1.0)
        if (t > 0.45f)
        {
            float st = (t - 0.45f) / 0.55f;
            int   sr = (int) (r * (1.8f + st * 2.0f));
            int   sa = (int) (90 * (1f - st));
            g.setColor(new Color(160, 100, 40, sa));
            g.setStroke(new BasicStroke(3f + st * 4f));
            g.drawOval(cx - sr, cy - sr, sr * 2, sr * 2);
        }

        // Layer 2: Secondary shockwave ring — delayed (t 0.15 → 1.0)
        if (t > 0.15f)
        {
            float t2 = (t - 0.15f) / 0.85f;
            int   r2 = (int) (r * 3.0f * easeOut(t2));
            int   a2 = (int) (150 * (1f - t2));
            g.setColor(new Color(255, 200, 50, a2));
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(cx - r2, cy - r2, r2 * 2, r2 * 2);
        }

        // Layer 3: Primary shockwave ring (t 0 → 1.0)
        {
            int sr = (int) (r * 3.2f * easeOut(t));
            int sa = (int) (220 * (1f - t));
            g.setColor(new Color(255, 130, 0, sa));
            g.setStroke(new BasicStroke(2.5f));
            g.drawOval(cx - sr, cy - sr, sr * 2, sr * 2);
        }

        // Layer 4: Fireball core fill (t 0 → 0.55)
        if (t < 0.55f)
        {
            float ft = t / 0.55f;
            int   fr = (int) (r * 2.0f * easeOut(ft));
            int   gr = (int) (200 * (1f - ft * 0.8f));
            int   fa = (int) (200 * (1f - ft));
            g.setColor(new Color(255, Math.max(0, gr), 0, fa));
            g.fillOval(cx - fr, cy - fr, fr * 2, fr * 2);
        }

        // Layer 5: Ignition flash (t 0 → 0.12)
        if (t < 0.12f)
        {
            float ft = t / 0.12f;
            int   fa = (int) (230 * (1f - ft));
            int   fr = (int) (r * 0.9f);
            g.setColor(new Color(255, 255, 200, fa));
            g.fillOval(cx - fr, cy - fr, fr * 2, fr * 2);
        }

        // Layer 6: Radial jets — 8 irregular streaks (t 0.04 → 0.70)
        if (t > 0.04f && t < 0.70f)
        {
            float jt = (t - 0.04f) / 0.66f;
            // Intentionally irregular angles and lengths — avoids uniform symmetry
            int[]   angles  = {  8, 52, 97, 148, 188, 235, 278, 333 };
            float[] lengths = { 1.9f, 1.3f, 2.1f, 1.5f, 2.0f, 1.2f, 1.8f, 1.4f };
            float[] widths  = { 3.0f, 1.5f, 2.5f, 1.5f, 3.0f, 2.0f, 1.5f, 2.0f };

            for (int i = 0; i < angles.length; i++)
            {
                double rad = Math.toRadians(angles[i]);
                float  len = r * lengths[i] * easeOut(jt);
                float fade = 1f - jt;
                int    ja  = (int) (200 * fade);
                int    jg  = (int) (140 * fade);
                int x2 = cx + (int) (Math.cos(rad) * len);
                int y2 = cy + (int) (Math.sin(rad) * len);
                g.setColor(new Color(255, jg, 0, ja));
                g.setStroke(new BasicStroke(widths[i]));
                g.drawLine(cx, cy, x2, y2);
            }
        }

        // Layer 7: Spark debris — small dots moving outward (t 0.08 → 0.85)
        if (t > 0.08f && t < 0.85f)
        {
            float st   = (t - 0.08f) / 0.77f;
            float fade = 1f - st;
            // [angle°, speed×10] — uneven spacing, two speed tiers
            int[][] sparks = {
                { 22, 28 }, { 68, 20 }, { 115, 30 }, { 157, 22 },
                { 202, 28 }, { 248, 20 }, { 295, 30 }, { 342, 22 },
                { 43, 18 }, { 130, 25 }, { 218, 18 }, { 310, 25 }
            };
            for (int[] spark : sparks)
            {
                double rad  = Math.toRadians(spark[0]);
                float  dist = r * (spark[1] / 10.0f) * easeOut(st);
                int    sa   = (int) (255 * fade * fade); // quadratic fade
                int sx = cx + (int) (Math.cos(rad) * dist);
                int sy = cy + (int) (Math.sin(rad) * dist);
                g.setColor(new Color(255, 220, 80, sa));
                g.setStroke(savedStroke);
                g.fillOval(sx - 2, sy - 2, 4, 4);
            }
        }

        g.setStroke(savedStroke);
        g.setColor(savedColor);
    }

    private static float easeOut(float t)
    {
        float c = 1f - t;
        return 1f - c * c;
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