package net.crashcraft.crashclaim.commands.claiming.modes;

import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.claimobjects.BaseClaim;
import net.crashcraft.crashclaim.claimobjects.Claim;
import net.crashcraft.crashclaim.claimobjects.SubClaim;
import net.crashcraft.crashclaim.commands.claiming.ClaimCommand;
import net.crashcraft.crashclaim.commands.claiming.ClaimMode;
import net.crashcraft.crashclaim.config.GlobalConfig;
import net.crashcraft.crashclaim.data.ClaimDataManager;
import net.crashcraft.crashclaim.data.ContributionManager;
import net.crashcraft.crashclaim.data.ErrorType;
import net.crashcraft.crashclaim.data.MathUtils;
import net.crashcraft.crashclaim.data.StaticClaimLogic;
import net.crashcraft.crashclaim.localization.Localization;
import net.crashcraft.crashclaim.permissions.PermissionHelper;
import net.crashcraft.crashclaim.permissions.PermissionRoute;
import net.crashcraft.crashclaim.visualize.VisualizationManager;
import net.crashcraft.crashclaim.visualize.api.BaseVisual;
import net.crashcraft.crashclaim.visualize.api.VisualColor;
import net.crashcraft.crashclaim.visualize.api.VisualGroup;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.UUID;

public class ResizeClaimMode implements ClaimMode {
    private final ClaimCommand commandManager;
    private final Player player;
    private Location firstLocation;
    private final Claim claim;
    private final VisualizationManager visualizationManager;
    private final ClaimDataManager manager;
    private int previewX = Integer.MIN_VALUE;
    private int previewZ = Integer.MIN_VALUE;
    private double availableBalance = Double.NaN;
    private long balanceCheckedAt;
    private boolean balanceRequestPending;

    public ResizeClaimMode(ClaimCommand commandManager, Player player, Claim claim, Location firstLocation){
        this.commandManager = commandManager;
        this.player = player;
        this.firstLocation = firstLocation;
        this.claim = claim;

        this.visualizationManager = commandManager.getVisualizationManager();
        this.manager = commandManager.getDataManager();

        requestBalance();
        firstClick();
    }

    private void firstClick(){
        if (firstLocation == null){
            claim.setEditing(true);
            showOriginalClaim();
            player.sendMessage(Localization.RESIZE__INSTRUCTIONS.getMessage(player));
            return;
        }
        if (StaticClaimLogic.isClaimBorder(claim.getMinX(), claim.getMaxX(), claim.getMinZ(),
                claim.getMaxZ(), firstLocation.getBlockX(), firstLocation.getBlockZ())){
            if (PermissionHelper.getPermissionHelper().hasPermission(claim, player.getUniqueId(), PermissionRoute.MODIFY_CLAIM)){
                claim.setEditing(true);

                showOriginalClaim();

                player.sendMessage(Localization.RESIZE__CLICK_ANOTHER_LOCATION.getMessage(player));
                move(player);
            } else {
                player.sendMessage(Localization.RESIZE__NO_PERMISSION.getMessage(player));
                cleanup(player.getUniqueId(), true);
            }
        } else {
            firstLocation = null;
            player.sendMessage(Localization.RESIZE__INSTRUCTIONS.getMessage(player));
        }
    }

    private void showOriginalClaim() {
        VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
        group.removeAllVisuals();
        visualizationManager.getProvider(player.getUniqueId())
                .spawnClaimVisual(VisualColor.GOLD, group, claim, player.getLocation().getBlockY() - 1)
                .spawn();
    }

    @Override
    public void move(Player player) {
        if (firstLocation == null) {
            return;
        }

        Block target = player.getTargetBlockExact(100);
        if (target == null || target.getX() == previewX && target.getZ() == previewZ) {
            return;
        }

        previewX = target.getX();
        previewZ = target.getZ();

        int[] resized = ClaimDataManager.calculateResize(
                claim.getMinX(), claim.getMaxX(), claim.getMinZ(), claim.getMaxZ(),
                firstLocation.getBlockX(), firstLocation.getBlockZ(), previewX, previewZ);

        showOriginalClaim();

        if (resized[4] != 1 || resized[0] == claim.getMinX() && resized[1] == claim.getMaxX()
                && resized[2] == claim.getMinZ() && resized[3] == claim.getMaxZ()) {
            return;
        }

        BaseClaim preview = new BaseClaim(-1, resized[1], resized[3], resized[0], resized[2],
                claim.getWorld(), claim.getPerms(), new ArrayList<>()) { };
        VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
        visualizationManager.getProvider(player.getUniqueId())
                .spawnClaimVisual(isValidPreview(resized) ? VisualColor.GREEN : VisualColor.RED,
                        group, preview, player.getLocation().getBlockY() - 1)
                .spawn();
    }

    private boolean isValidPreview(int[] resized) {
        int minX = resized[0];
        int maxX = resized[1];
        int minZ = resized[2];
        int maxZ = resized[3];

        if (manager.isTooSmall(maxX, maxZ, minX, minZ)
                || manager.checkOverLapSurroudningClaims(claim.getId(), maxX, maxZ, minX, minZ, claim.getWorld())) {
            return false;
        }

        for (SubClaim subClaim : claim.getSubClaims()) {
            if (!MathUtils.containedInside(minX, minZ, maxX, maxZ,
                    subClaim.getMinX(), subClaim.getMinZ(), subClaim.getMaxX(), subClaim.getMaxZ())) {
                return false;
            }
        }

        if (!CrashClaim.getPlugin().getPluginSupport().canClaim(
                new Location(player.getWorld(), minX, 0, minZ),
                new Location(player.getWorld(), maxX, 0, maxZ))) {
            return false;
        }

        requestBalance();
        int newArea = ContributionManager.getArea(minX, minZ, maxX, maxZ);
        int oldArea = ContributionManager.getArea(claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ());
        int price = (int) Math.ceil(Math.max(0, newArea - oldArea) * GlobalConfig.getCostOfBlock(player));
        return price == 0 || !Double.isNaN(availableBalance) && availableBalance >= price;
    }

    private void requestBalance() {
        long now = System.currentTimeMillis();
        if (balanceRequestPending || now - balanceCheckedAt < 2_000) {
            return;
        }

        balanceRequestPending = true;
        balanceCheckedAt = now;
        CrashClaim.getPlugin().getPayment().getBalance(player.getUniqueId(), balance ->
                CrashClaim.getPlugin().getServer().getScheduler().runTask(CrashClaim.getPlugin(), () -> {
                    balanceRequestPending = false;
                    availableBalance = balance == null ? 0 : balance;
                    if (commandManager.isCurrentMode(player, this)) {
                        previewX = Integer.MIN_VALUE;
                        previewZ = Integer.MIN_VALUE;
                        move(player);
                    }
                }));
    }

    @Override
    public void click(Player player, Location click) {
        if (firstLocation == null){
            firstLocation = click;
            firstClick();
            return;
        }

        UUID uuid = player.getUniqueId();

        if (!PermissionHelper.getPermissionHelper().hasPermission(claim, player.getUniqueId(), PermissionRoute.MODIFY_CLAIM)){
            cleanup(player.getUniqueId(), true);
            return;
        }

        ErrorType error = manager.resizeClaim(claim, firstLocation.getBlockX(), firstLocation.getBlockZ(), click.getBlockX(), click.getBlockZ(), player,
                aBoolean -> {
                    VisualGroup group = visualizationManager.fetchVisualGroup(player, true);
                    BaseVisual visual = visualizationManager.getProvider(uuid).spawnClaimVisual(VisualColor.GREEN, group, claim, player.getLocation().getBlockY() - 1);

                    group.removeAllVisuals();
                    visual.spawn();

                    visualizationManager.deSpawnAfter(visual, 5);

                    if (aBoolean) {
                        player.sendMessage(Localization.RESIZE__SUCCESS.getMessage(player));
                    } else {
                        cleanup(uuid, true);
                    }
                });

        switch (error){
            case OVERLAP_EXISTING:
                player.sendMessage(Localization.RESIZE__NO_OVERLAP.getMessage(player));
                cleanup(uuid, true);
                return;
            case TOO_SMALL:
                player.sendMessage(Localization.RESIZE__MIN_SIZE.getMessage(player));
                cleanup(uuid, true);
                return;
            case CANNOT_FLIP_ON_RESIZE:
                player.sendMessage(Localization.RESIZE__CANNOT_FLIP.getMessage(player));
                cleanup(uuid, true);
                return;
            case OVERLAP_EXISTING_SUBCLAIM:
                player.sendMessage(Localization.RESIZE__OVERLAP_EXISTING.getMessage(player));
                cleanup(uuid, true);
                return;
            case OVERLAP_EXISTING_OTHER:
                player.sendMessage(Localization.RESIZE__ERROR_OTHER.getMessage(player));
            case NONE:
                cleanup(uuid, false);
        }
    }

    @Override
    public void cleanup(UUID player, boolean visuals) {
        //firstLocation = null;
        claim.setEditing(false);

        commandManager.forceCleanup(player, visuals);
    }
}
