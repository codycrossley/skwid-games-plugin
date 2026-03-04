package gay.runescape;

import net.runelite.client.config.*;

@ConfigGroup(SkwidGamesConfig.GROUP)
public interface SkwidGamesConfig extends Config
{
    String GROUP = "skwidgames";

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show numbers overlay",
            description = "Draw each enlisted number above the player’s head"
    )
    default boolean showOverlay()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showTileOverlay",
            name = "Show shared tile markers",
            description = "Draw tile markers shared by the Commander"
    )
    default boolean showTileOverlay()
    {
        return true;
    }

}