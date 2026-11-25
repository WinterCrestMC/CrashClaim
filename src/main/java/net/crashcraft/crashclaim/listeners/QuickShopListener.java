package net.crashcraft.crashclaim.listeners;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.event.ProtectionCheckStatus;
import com.ghostchu.quickshop.api.event.ShopCreateEvent;
import com.ghostchu.quickshop.api.event.ShopPreCreateEvent;
import com.ghostchu.quickshop.api.event.ShopProtectionCheckEvent;
import com.ghostchu.quickshop.api.obj.QUser;
import java.util.UUID;
import net.crashcraft.crashclaim.localization.Localization;
import net.crashcraft.crashclaim.permissions.PermissionHelper;
import net.crashcraft.crashclaim.permissions.PermissionRoute;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * QuickShop-Hikari integration. Only registered through {@link QuickShopHook#register}.
 * <p>
 * QuickShop verifies build permissions by firing a fake BlockBreakEvent. If CrashClaim cancels it, QuickShop prints
 * its own "3rd-party plugin CrashClaim denied the permission checks" message plus an admin-only debugging hint.
 * To avoid that, the fake event is exempted from CrashClaim's regular listeners and the claim check is done here
 * instead, cancelling QuickShop's create events with a normal CrashClaim message.
 */
public class QuickShopListener implements Listener {

    @EventHandler
    public void onShopProtectionCheck(ShopProtectionCheckEvent event) {
        if (event.getStatus() == ProtectionCheckStatus.BEGIN) {
            QuickShopHook.exemptEvents.add(event.getEvent());
        } else if (event.getStatus() == ProtectionCheckStatus.END) {
            QuickShopHook.exemptEvents.remove(event.getEvent());
        }
    }

    // Click-to-create flow. QuickShop only logs a debug line when this is cancelled, so the player is messaged here.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShopPreCreate(ShopPreCreateEvent event) {
        if (canCreate(event.getCreator(), event.getLocation())) {
            return;
        }

        Player player = event.getCreator().getBukkitPlayer().orElse(null);
        Component message = Localization.ALERT__NO_PERMISSIONS__SHOP_CREATE.getMessage(player);
        event.setCancelled(true, message);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    // Final create step (also used by /qs create). QuickShop shows the cancel reason to the player itself.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShopCreate(ShopCreateEvent event) {
        if (canCreate(event.getCreator(), event.getShop().getLocation())) {
            return;
        }

        Player player = event.getCreator().getBukkitPlayer().orElse(null);
        event.setCancelled(true, Localization.ALERT__NO_PERMISSIONS__SHOP_CREATE.getMessage(player));
    }

    private static boolean canCreate(QUser creator, Location location) {
        if (creator == null || location == null) {
            return true;
        }

        UUID uuid = creator.getUniqueIdOptional().orElse(null);
        if (uuid == null) {
            return true; // Not a player (e.g. created by another plugin)
        }

        Boolean allowed = PermissionHelper.getPermissionHelper().hasPermission(uuid, location, PermissionRoute.BUILD);
        return allowed == null || allowed;
    }

    static boolean isShopAt(Location location) {
        return QuickShopAPI.getInstance().getShopManager().getShopIncludeAttached(location) != null;
    }
}
