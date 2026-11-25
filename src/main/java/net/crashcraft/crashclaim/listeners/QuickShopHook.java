package net.crashcraft.crashclaim.listeners;

import com.google.common.collect.Sets;
import java.util.Collection;
import net.crashcraft.crashclaim.CrashClaim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;

/**
 * QuickShop-free entry point for the QuickShop-Hikari integration. Other listeners only talk to this class, so
 * nothing from QuickShop's API is loaded unless the plugin is actually installed.
 */
public final class QuickShopHook {

    public static final String PLUGIN_NAME = "QuickShop-Hikari";

    static final Collection<Event> exemptEvents = Sets.newConcurrentHashSet();
    private static boolean hooked = false;

    private QuickShopHook() {
    }

    public static void register(CrashClaim plugin) {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) {
            return;
        }

        try {
            Bukkit.getPluginManager().registerEvents(new QuickShopListener(), plugin);
            hooked = true;
            plugin.getLogger().info("Hooked into " + PLUGIN_NAME);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to hook into " + PLUGIN_NAME + ": " + t);
        }
    }

    /**
     * @return true if this event is QuickShop's fake protection-check event. CrashClaim does its own check for those
     * in {@link QuickShopListener}, so regular listeners should skip them.
     */
    public static boolean isExempt(Event event) {
        return hooked && exemptEvents.contains(event);
    }

    /**
     * @return true if QuickShop is hooked and there is a shop (or a shop sign) at this location.
     */
    public static boolean isShopAt(Location location) {
        return hooked && QuickShopListener.isShopAt(location);
    }
}
