package com.example;

import net.runelite.client.config.*;

@ConfigGroup(SkwidGamesConfig.GROUP)
public interface SkwidGamesConfig extends Config
{
    String GROUP = "skwidgames";

    @ConfigItem(
            keyName = "relayBaseUrl",
            name = "Relay base URL",
            description = "Base URL of your Skwid Relay, e.g. https://relay.example.com or http://127.0.0.1:8000"
    )
    default String relayBaseUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show numbers overlay",
            description = "Draw each enlisted number above the player’s head"
    )
    default boolean showOverlay()
    {
        return true;
    }

}