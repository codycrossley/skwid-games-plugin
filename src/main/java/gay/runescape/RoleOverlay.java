package gay.runescape;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

public class RoleOverlay extends Overlay
{
    private final Client client;
    private final SkwidGamesConfig config;
    private final GameService gameService;
    private final RosterReducer roster;

    private static final float FONT_SIZE_PT = 16f;
    private final Font font = FontManager.getRunescapeBoldFont().deriveFont(FONT_SIZE_PT);

    private final BufferedImage commanderIcon;
    private final BufferedImage guardIcon;

    static final Color CONTESTANT_ALIVE_COLOR       = new Color(25, 177, 86);
    private static final Color CONTESTANT_ELIMINATED_COLOR = new Color(214, 9, 65);
    private static final Color COMMANDER_COLOR      = new Color(246, 101, 244);
    static final Color GUARD_COLOR                  = new Color(246, 101, 244);

    private static final Color JOINED_COLOR = new Color(255, 222, 0);
    private static final Color REMOVED_COLOR    = new Color(24, 66, 5, 255);

    @Inject
    public RoleOverlay(Client client, SkwidGamesConfig config, GameService gameService, RosterReducer roster)
    {
        this.client = client;
        this.config = config;
        this.gameService = gameService;
        this.roster = roster;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);

        commanderIcon = ImageUtil.resizeImage(
                ImageUtil.loadImageResource(RoleOverlay.class, "commander_icon.png"), 16, 16);
        guardIcon     = ImageUtil.resizeImage(
                ImageUtil.loadImageResource(RoleOverlay.class, "guard_icon.png"), 16, 16);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showOverlay()) return null;

        String gameId = gameService.getActiveGameId();
        if (gameId == null || gameId.isBlank()) return null;

        g.setFont(font);

        // Commander (set from relay on start/join)
        final String commanderRaw = gameService.getCommander();
        final String commanderCanon = (commanderRaw == null || commanderRaw.isBlank())
                ? null
                : Text.toJagexName(commanderRaw);

        // Local player canonical name
        final String localCanon = client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null
                ? Text.toJagexName(client.getLocalPlayer().getName())
                : null;

        // We only consider ourselves commander if we have a writeKey
        final boolean iAmCommander = gameService.isLocalCommander();

        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;

            String rsn = Text.toJagexName(p.getName());
            if (rsn == null || rsn.isBlank()) continue;

            // Roster-derived most-recent enlist role (GUARD/CONTESTANT/REMOVED)
            PlayerRole role = roster.getRole(rsn);

            // If removed, don't show anything (even if they were commander)
            if (role == PlayerRole.REMOVED) continue;

            // Commander identifier
            final boolean isCommander = rsn.equals(commanderCanon);

            // Commander and Guard use PNG icons
            if (isCommander || role == PlayerRole.GUARD)
            {
                BufferedImage icon = isCommander ? commanderIcon : guardIcon;
                if (icon != null)
                {
                    int yOffset = p.getLogicalHeight() + icon.getHeight();
                    net.runelite.api.Point loc = p.getCanvasImageLocation(icon, yOffset);
                    if (loc != null)
                    {
                        g.drawImage(icon, loc.getX(), loc.getY(), null);
                    }
                }
                continue;
            }

            final String label;
            final Color color;

            if (role == PlayerRole.CONTESTANT)
            {
                Integer n = roster.getNumber(rsn);

                if (roster.getStatus(rsn) == PlayerStatus.ELIMINATED)
                {
                    color = CONTESTANT_ELIMINATED_COLOR;
                }
                else
                {
                    color = CONTESTANT_ALIVE_COLOR;
                }
                // If enlisted as contestant but number hasn't arrived yet, show "?"
                // This will also format the number as 0-padded 3-digit integer
                label = (n != null && n > 0) ? String.format("%03d", n) : "?";
            }
            else
            {
                // Joined but not enlisted yet
                if (!roster.hasJoined(rsn))
                {
                    continue; // hasn't joined (or we don't know about them)
                }
                label = "???";
                color = JOINED_COLOR;
            }

            int yOffset = p.getLogicalHeight() + (int) (FONT_SIZE_PT * 0.6f);
            net.runelite.api.Point loc = p.getCanvasTextLocation(g, label, yOffset);
            if (loc != null)
            {
                OverlayUtil.renderTextLocation(g, loc, label, color);
            }
        }

        return null;
    }
}