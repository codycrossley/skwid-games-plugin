package gay.runescape;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

@Slf4j
@RequiredArgsConstructor
public class AccountConfig
{
    private final ConfigManager configManager;
    private final Client client;
    private final Gson gson;

    private static final String KEY_ACTIVE_GAME = "activeGameId";
    private static final String KEY_JOIN_CODE   = "joinCode";
    private static final String KEY_WRITE_KEY   = "writeKey";
    private static final String KEY_COMMANDER   = "commander";

    // gameId -> writeKey cache (stored as JSON)
    private static final String KEY_COMMANDER_KEYRING_JSON = "commanderKeyringJson";
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    public String getActiveGameId()
    {
        String rsKey = getRsProfileKeyOrNull();
        if (rsKey != null)
        {
            return configManager.getRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_ACTIVE_GAME);
        }
        return configManager.getConfiguration(SkwidGamesConfig.GROUP, KEY_ACTIVE_GAME + "." + getLocalPlayerKey());
    }

    public void setActiveGameId(String gameId)
    {
        String rsKey = getRsProfileKeyOrNull();
        if (rsKey != null)
        {
            configManager.setRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_ACTIVE_GAME, gameId);
            return;
        }
        configManager.setConfiguration(SkwidGamesConfig.GROUP, KEY_ACTIVE_GAME + "." + getLocalPlayerKey(), gameId);
    }

    public String getJoinCode()
    {
        String rsKey = getRsProfileKeyOrNull();
        if (rsKey != null)
        {
            return configManager.getRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_JOIN_CODE);
        }
        return configManager.getConfiguration(SkwidGamesConfig.GROUP, KEY_JOIN_CODE + "." + getLocalPlayerKey());
    }

    public void setJoinCode(String joinCode)
    {
        String rsKey = getRsProfileKeyOrNull();
        if (rsKey != null)
        {
            configManager.setRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_JOIN_CODE, joinCode);
            return;
        }
        configManager.setConfiguration(SkwidGamesConfig.GROUP, KEY_JOIN_CODE + "." + getLocalPlayerKey(), joinCode);
    }

    public String getWriteKey()
    {
        String rsKey = getRsProfileKeyOrNull();
        if (rsKey != null)
        {
            String val = configManager.getRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_WRITE_KEY);
            log.debug("getWriteKey: path=RSProfile rsKey={} result={}", rsKey,
                    val == null ? "null" : (val.isBlank() ? "blank" : "present"));
            return val;
        }
        String playerKey = getLocalPlayerKey();
        String val = configManager.getConfiguration(SkwidGamesConfig.GROUP, KEY_WRITE_KEY + "." + playerKey);
        log.debug("getWriteKey: path=localPlayerKey playerKey={} result={}", playerKey,
                val == null ? "null" : (val.isBlank() ? "blank" : "present"));
        return val;
    }

    public void setWriteKey(String writeKey)
    {
        String rsKey = getRsProfileKeyOrNull();
        if (rsKey != null)
        {
            log.debug("setWriteKey: path=RSProfile rsKey={} value={}", rsKey,
                    writeKey == null ? "null" : (writeKey.isBlank() ? "blank" : "present"));
            configManager.setRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_WRITE_KEY, writeKey);
            return;
        }
        String playerKey = getLocalPlayerKey();
        log.debug("setWriteKey: path=localPlayerKey playerKey={} value={}", playerKey,
                writeKey == null ? "null" : (writeKey.isBlank() ? "blank" : "present"));
        configManager.setConfiguration(SkwidGamesConfig.GROUP, KEY_WRITE_KEY + "." + playerKey, writeKey);
    }

    public String getCommander()
    {
        return configManager.getRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_COMMANDER);
    }

    public void setCommander(String commander)
    {
        configManager.setRSProfileConfiguration(SkwidGamesConfig.GROUP, KEY_COMMANDER, commander);
    }

    public void clearGamePointers()
    {
        setActiveGameId("");
        setJoinCode("");
        setWriteKey("");
        setCommander("");
    }

    public void cacheWriteKeyForGame(final String gameId, final String writeKey)
    {
        if (Strings.isNullOrEmpty(gameId) || Strings.isNullOrEmpty(writeKey))
        {
            return;
        }
        final Map<String, String> keyring = loadCommanderKeyring();
        keyring.put(gameId, writeKey);
        saveCommanderKeyring(keyring);
    }

    public String getCachedWriteKeyForGame(final String gameId)
    {
        if (Strings.isNullOrEmpty(gameId))
        {
            return "";
        }
        final Map<String, String> keyring = loadCommanderKeyring();
        final String wk = keyring.get(gameId);
        return wk != null ? wk : "";
    }

    public void forgetWriteKeyForGame(final String gameId)
    {
        if (Strings.isNullOrEmpty(gameId))
        {
            return;
        }
        final Map<String, String> keyring = loadCommanderKeyring();
        if (keyring.remove(gameId) != null)
        {
            saveCommanderKeyring(keyring);
        }
    }

    private Map<String, String> loadCommanderKeyring()
    {
        final String raw = getCommanderKeyringJson();
        if (Strings.isNullOrEmpty(raw))
        {
            return new HashMap<>();
        }

        try
        {
            final Map<String, String> parsed = gson.fromJson(raw, MAP_TYPE);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        }
        catch (Exception ignored)
        {
            // If someone edits config manually and breaks JSON, fail safe.
            return new HashMap<>();
        }
    }

    private void saveCommanderKeyring(final Map<String, String> keyring)
    {
        setCommanderKeyringJson(gson.toJson(keyring != null ? keyring : new HashMap<>()));
    }

    private String getCommanderKeyringJson()
    {
        // Stored in global (non-profile-scoped) config so write keys survive RS profile changes.
        // The keyring is keyed internally by gameId, so there is no cross-account collision risk.
        return configManager.getConfiguration(SkwidGamesConfig.GROUP, KEY_COMMANDER_KEYRING_JSON);
    }

    private void setCommanderKeyringJson(final String json)
    {
        configManager.setConfiguration(SkwidGamesConfig.GROUP, KEY_COMMANDER_KEYRING_JSON, json);
    }

    private String getLocalPlayerKey()
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
        {
            return "unknown";
        }
        return Text.toJagexName(client.getLocalPlayer().getName()).toLowerCase();
    }

    private String getRsProfileKeyOrNull()
    {
        try
        {
            return configManager.getRSProfileKey();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}