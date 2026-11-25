package net.crashcraft.crashclaim.commands.claiming.modes;

import dev.whip.crashutils.menusystem.defaultmenus.ConfirmationMenu;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.claimobjects.Claim;
import net.crashcraft.crashclaim.commands.claiming.ClaimCommand;
import net.crashcraft.crashclaim.commands.claiming.ClaimMode;
import net.crashcraft.crashclaim.config.GlobalConfig;
import net.crashcraft.crashclaim.data.ClaimDataManager;
import net.crashcraft.crashclaim.data.ClaimResponse;
import net.crashcraft.crashclaim.data.ContributionManager;
import net.crashcraft.crashclaim.data.StaticClaimLogic;
import net.crashcraft.crashclaim.localization.Localization;
import net.crashcraft.crashclaim.permissions.PermissionHelper;
import net.crashcraft.crashclaim.visualize.VisualizationManager;
import net.crashcraft.crashclaim.visualize.api.BaseVisual;
import net.crashcraft.crashclaim.visualize.api.VisualColor;
import net.crashcraft.crashclaim.visualize.api.VisualGroup;
import net.crashcraft.crashclaim.visualize.api.VisualType;
import net.crashcraft.crashpayment.payment.TransactionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.UUID;

public class NewClaimMode implements ClaimMode {
    private final ClaimCommand commandManager;
    private final Player player;
    private final Location firstLocation;
    private final VisualizationManager visualizationManager;
    private final ClaimDataManager manager;
    private boolean active;
    private boolean validationPending;
    private boolean purchasePending;
    private boolean confirmationOpen;

    public NewClaimMode(ClaimCommand commandManager, Player player, Location firstLocation){
        this.commandManager = commandManager;
        this.player = player;
        this.firstLocation = firstLocation;
        this.visualizationManager = commandManager.getVisualizationManager();
        this.manager = commandManager.getDataManager();
        this.active = true;

        player.sendMessage(Localization.NEW_CLAIM__CLICK_CORNER.getMessage(player));

        VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
        group.removeAllVisualsOfType(VisualType.MARKER);

        visualizationManager.getProvider(player.getUniqueId()).spawnMarkerVisual(VisualColor.YELLOW, group, firstLocation).spawn();
    }

    private boolean checkCanCreate(Location min, Location max, int ownedBlocks){
        if ((max.getBlockX() - min.getBlockX()) < 4 || (max.getBlockZ() - min.getBlockZ()) < 4) {
            player.sendMessage(Localization.NEW_CLAIM__MIN_SIZE.getMessage(player));
            return false;
        }

        if (manager.checkOverLapSurroudningClaims(-1, max.getBlockX(), max.getBlockZ(), min.getBlockX(), min.getBlockZ(), min.getWorld().getUID())){
            player.sendMessage(Localization.NEW_CLAIM__OVERLAPPING.getMessage(player));
            return false;
        }

        if (!CrashClaim.getPlugin().getPluginSupport().canClaim(min, max)){
            player.sendMessage(Localization.NEW_CLAIM__OTHER_ERROR.getMessage(player));
            return false;
        }
        int blocks = ownedBlocks + (max.getBlockX() - min.getBlockX()) * (max.getBlockZ() - min.getBlockZ());
        // Check max blocks
        if (GlobalConfig.maxClaimBlocks > -1  && (blocks > GlobalConfig.maxClaimBlocks)){
            player.sendMessage(Localization.CLAIM__TOO_BIG.getMessage(player,
                    "blocks", Integer.toString(GlobalConfig.maxClaimBlocks)));
            return false;
        }

        return true;
    }

    @Override
    public void click(Player player, Location secondLocation) {
        if (!active || validationPending || purchasePending || confirmationOpen) {
            return;
        }

        Location min = StaticClaimLogic.calculateMinCorner(firstLocation, secondLocation);
        Location max = StaticClaimLogic.calculateMaxCorner(firstLocation, secondLocation);

        validateAsync(min, max, () -> openPurchaseConfirmation(min, max));
    }

    private void openPurchaseConfirmation(Location min, Location max) {
        int area = ContributionManager.getArea(min.getBlockX(), min.getBlockZ(), max.getBlockX(), max.getBlockZ());
        int price = (GlobalConfig.bypassModeBypassesMoney && PermissionHelper.getPermissionHelper().getBypassManager().isBypass(player.getUniqueId())) ?
                0 : (int) Math.ceil(area * GlobalConfig.getCostOfBlock(player));
        String priceString = Integer.toString(price);

        if (price <= 0) {
            purchasePending = true;
            afterTransaction(min, max, 0, player.getUniqueId());
            return;
        }

        confirmationOpen = true;
        new ConfirmationMenu(player,
                Localization.NEW_CLAIM__CREATE_MENU__TITLE.getMessage(player),
                Localization.NEW_CLAIM__CREATE_MENU__MESSAGE.getItem(player, "price", priceString),
                Localization.NEW_CLAIM__CREATE_MENU__ACCEPT.getItem(player, "price", priceString),
                Localization.NEW_CLAIM__CREATE_MENU__DENY.getItem(player, "price", priceString),
                (confirmationPlayer, accepted) -> {
                    confirmationOpen = false;
                    if (!accepted) {
                        cleanup(player.getUniqueId(), true);
                        return "";
                    }

                    validateAsync(min, max, () -> {
                        purchasePending = true;
                        CrashClaim.getPlugin().getPayment().makeTransaction(
                                player.getUniqueId(),
                                TransactionType.WITHDRAW,
                                "Claim Purchase",
                                price,
                                response -> Bukkit.getScheduler().runTask(CrashClaim.getPlugin(), () -> {
                                    if (!active) {
                                        return;
                                    }

                                    if (!response.transactionSuccess()) {
                                        player.sendMessage(Localization.NEW_CLAIM__NOT_ENOUGH_BALANCE.getMessage(player,
                                                "price", priceString));
                                        cleanup(player.getUniqueId(), true);
                                        return;
                                    }

                                    afterTransaction(min, max, price, player.getUniqueId());
                                }));
                    });
                    return "";
                },
                confirmationPlayer -> {
                    Bukkit.getScheduler().runTask(CrashClaim.getPlugin(), () -> {
                        if (!active || !confirmationOpen) {
                            return;
                        }

                        confirmationOpen = false;
                        cleanup(player.getUniqueId(), true);
                    });
                    return "";
                }).open();
    }

    private void validateAsync(Location min, Location max, Runnable continuation) {
        if (validationPending || purchasePending) {
            return;
        }

        validationPending = true;
        manager.getOwnedClaimsAsync(player.getUniqueId()).whenComplete((ownedClaims, throwable) ->
                Bukkit.getScheduler().runTask(CrashClaim.getPlugin(), () -> {
                    validationPending = false;
                    if (!active) {
                        return;
                    }

                    if (throwable != null) {
                        CrashClaim.getPlugin().getLogger().severe("Unable to validate claim purchase for "
                                + player.getUniqueId() + ": " + throwable.getMessage());
                        player.sendMessage(Localization.NEW_CLAIM__ERROR.getMessage(player));
                        cleanup(player.getUniqueId(), true);
                        return;
                    }

                    int ownedBlocks = ownedClaims.stream().map(Claim::getAreaBlocks).reduce(0, Integer::sum);
                    if (!checkCanCreate(min, max, ownedBlocks)) {
                        cleanup(player.getUniqueId(), true);
                        return;
                    }

                    continuation.run();
                }));
    }

    private BaseComponent[] asBungee(Component component){
        return BungeeComponentSerializer.get().serialize(component);
    }

    private void afterTransaction(Location min, Location max, int price, UUID target){
        ClaimResponse response = manager.createClaim(max, min, player.getUniqueId());

        if (response.isStatus()) {
            player.sendMessage(Localization.NEW_CLAIM__SUCCESS.getMessage(player));

            VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
            group.removeAllVisuals();

            BaseVisual visual = visualizationManager.getProvider(target).spawnClaimVisual(VisualColor.GREEN, group, response.getClaim(), player.getLocation().getBlockY() - 1);
            visual.spawn();

            visualizationManager.deSpawnAfter(visual, 5);

            cleanup(player.getUniqueId(), false);
        } else {
            refundFailedCreation(price);
            player.sendMessage(Localization.NEW_CLAIM__ERROR.getMessage(player));
            cleanup(player.getUniqueId(), true);
        }
    }

    private void refundFailedCreation(int price) {
        if (price <= 0) {
            return;
        }

        CrashClaim.getPlugin().getPayment().makeTransaction(player.getUniqueId(), TransactionType.DEPOSIT,
                "Failed Claim Purchase Refund", price, response -> {
                    if (!response.transactionSuccess()) {
                        CrashClaim.getPlugin().getLogger().severe("Unable to refund failed claim purchase for "
                                + player.getUniqueId() + " (" + price + ")");
                    }
                });
    }

    @Override
    public void cleanup(UUID player, boolean visuals) {
        active = false;
        commandManager.forceCleanup(player, visuals);
    }
}
