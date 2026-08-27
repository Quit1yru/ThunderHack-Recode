package thunder.hack.utility.discord;

import com.sun.jna.Native;
import com.sun.jna.Library;

public interface DiscordRPC extends Library {
    DiscordRPC INSTANCE = loadInstance();

    // Safely load the discord-rpc native library. On platforms where it is
    // unavailable (e.g. Android/aarch64 via Zalith Launcher) Native.load would
    // throw UnsatisfiedLinkError during class initialization and crash the
    // whole mod, so fall back to null and let callers no-op instead.
    private static DiscordRPC loadInstance() {
        try {
            return Native.load("discord-rpc", DiscordRPC.class);
        } catch (UnsatisfiedLinkError | RuntimeException ignored) {
            return null;
        }
    }
    
    void Discord_UpdateHandlers(final DiscordEventHandlers p0);
    
    void Discord_UpdatePresence(final DiscordRichPresence p0);
    
    void Discord_Respond(final String p0, final int p1);
    
    void Discord_Register(final String p0, final String p1);
    
    void Discord_Shutdown();
    
    void Discord_UpdateConnection();
    
    void Discord_RegisterSteamGame(final String p0, final String p1);
    
    void Discord_RunCallbacks();
    
    void Discord_Initialize(final String p0, final DiscordEventHandlers p1, final boolean p2, final String p3);
    
    void Discord_ClearPresence();
}
