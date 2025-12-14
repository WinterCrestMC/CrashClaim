package net.crashcraft.crashclaim.listeners;

import com.ghostchu.quickshop.api.event.ProtectionCheckStatus;
import com.ghostchu.quickshop.api.event.ShopProtectionCheckEvent;
import com.google.common.collect.Sets;
import java.util.Collection;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class QuickShopListener implements Listener {

    private static final Collection<Event> exemptEvents = Sets.newConcurrentHashSet();

    @EventHandler
    public void onShopProtectionCheck(ShopProtectionCheckEvent event) {
        if (event.getStatus() == ProtectionCheckStatus.BEGIN) {
            exemptEvents.add(event.getEvent());
        } else if (event.getStatus() == ProtectionCheckStatus.END) {
            exemptEvents.remove(event.getEvent());
        }
    }

    public static boolean isExempt(Event event) {
        return exemptEvents.contains(event);
    }
}
