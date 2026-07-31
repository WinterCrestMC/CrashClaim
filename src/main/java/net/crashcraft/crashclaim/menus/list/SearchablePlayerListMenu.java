package net.crashcraft.crashclaim.menus.list;

import dev.whip.crashutils.menusystem.GUI;
import dev.whip.crashutils.menusystem.defaultmenus.PlayerListMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.localization.Localization;
import net.kyori.adventure.text.Component;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

public class SearchablePlayerListMenu extends PlayerListMenu {

    private final Component title;
    private final GUI previousMenu;
    private final ArrayList<UUID> uuids;
    private final ArrayList<UUID> originalIds;
    private final BiFunction<GUI, UUID, String> function;

    public SearchablePlayerListMenu(Component title, Player player, GUI previousMenu, ArrayList<UUID> arrayList, BiFunction<GUI, UUID, String> function) {
        super(title, player, previousMenu, arrayList, function);
        this.title = title;
        this.previousMenu = previousMenu;
        this.uuids = arrayList;
        this.originalIds = new ArrayList<>(arrayList);
        this.function = function;
    }

    private SearchablePlayerListMenu(Component title, Player player, GUI previousMenu, ArrayList<UUID> arrayList, BiFunction<GUI, UUID, String> function, ArrayList<UUID> originalIds) {
        super(title, player, previousMenu, arrayList, function);
        this.title = title;
        this.previousMenu = previousMenu;
        this.uuids = arrayList;
        this.originalIds = new ArrayList<>(originalIds);
        this.function = function;
    }

    @Override
    public void loadItems() {
        super.loadItems();

        Bukkit.getScheduler().runTaskLater(CrashClaim.getPlugin(), () -> {
            if (this.originalIds == null || this.originalIds.isEmpty()) {
                return;
            }

            inv.setItem(53, createGuiItem(ChatColor.YELLOW + "Search", Material.NAME_TAG));
        }, 1L);
    }

    @Override
    public void onClick(InventoryClickEvent event, String rawItemName) {
        if (rawItemName.equalsIgnoreCase("search")) {
            new AnvilGUI.Builder()
                .plugin(CrashClaim.getPlugin())
                .itemLeft(Localization.MENU__PERMISSIONS__PLAYER__LOOKUP.getItem(getPlayer()))
                .onClick((integer, snapshot) -> {
                    String text = snapshot.getText();

                    // We need to limit our list to only include players that start with the given name
                    ArrayList<UUID> filteredList = new ArrayList<>();
                    String lowerCaseText = text.toLowerCase();

                    for (UUID uuid : this.originalIds) {
                        String name = Bukkit.getOfflinePlayer(uuid).getName();

                        if (name == null) {
                            continue;
                        }

                        String lowerCaseName = name.toLowerCase(Locale.ROOT);

                        if (lowerCaseName.startsWith(lowerCaseText) || lowerCaseName.contains(lowerCaseText)) {
                            filteredList.add(uuid);
                        }
                    }

                    new SearchablePlayerListMenu(title, player, previousMenu, filteredList, function, originalIds).open();
                    return Collections.singletonList(AnvilGUI.ResponseAction.close());
                }).open(getPlayer());
            return;
        }

        super.onClick(event, rawItemName);
    }
}
