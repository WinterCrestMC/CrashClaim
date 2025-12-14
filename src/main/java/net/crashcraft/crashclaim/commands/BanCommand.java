package net.crashcraft.crashclaim.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Flags;
import co.aikar.commands.annotation.Subcommand;
import io.papermc.lib.PaperLib;
import java.util.List;
import java.util.UUID;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.claimobjects.Claim;
import net.crashcraft.crashclaim.config.GlobalConfig;
import net.crashcraft.crashclaim.data.ClaimDataManager;
import net.crashcraft.crashclaim.localization.Localization;
import net.crashcraft.crashclaim.permissions.PermissionHelper;
import net.crashcraft.crashclaim.permissions.PermissionRoute;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

@CommandAlias("claimban")
public class BanCommand extends BaseCommand {

    private final ClaimDataManager manager;

    public BanCommand(ClaimDataManager manager){
        this.manager = manager;
    }

    @Default
    @CommandPermission("crashclaim.user.claimban")
    @CommandCompletion("@players")
    public void onDefault(Player player, @Flags("other") String value){
        OfflinePlayer otherPlayer = Bukkit.getOfflinePlayerIfCached(value);

        if (otherPlayer == null) {
            player.sendMessage("You need to be standing in a claim to ban another player.");
            return;
        }

        if (otherPlayer.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage("You cannot ban yourself.");
            return;
        }

        Location location = player.getLocation();
        Claim claim = manager.getClaim(location.getBlockX(), location.getBlockZ(), location.getWorld().getUID());

        if (claim == null) {
            player.sendMessage("You need to be standing in a claim to ban another player.");
            return;
        }

        if (!PermissionHelper.getPermissionHelper().hasPermission(player.getUniqueId(), player.getLocation(), PermissionRoute.MODIFY_PERMISSIONS)){
            player.sendMessage(Localization.BAN__NO_PERMISSION.getMessage(player));
            return;
        }

        if (PermissionHelper.getPermissionHelper().hasPermission(otherPlayer.getUniqueId(), player.getLocation(), PermissionRoute.MODIFY_PERMISSIONS)){
            player.sendMessage(Localization.BAN__HAS_PERMISSION.getMessage(player));
            return;
        }

        if (claim.isBanned(otherPlayer.getUniqueId())){
            player.sendMessage(Localization.BAN__ALREADY_BANNED.getMessage(player));
            return;
        }

        claim.ban(otherPlayer.getUniqueId());

        Player onlinePlayer = otherPlayer.getPlayer();
        if (onlinePlayer != null) {
            if (GlobalConfig.useCommandInsteadOfEdgeEject) {
                onlinePlayer.performCommand(GlobalConfig.claimEjectCommand);
            } else {
                int distMax = Math.abs(location.getBlockX() - claim.getMaxX());
                int distMin = Math.abs(location.getBlockX() - claim.getMinX());

                World world = location.getWorld();
                if (distMax > distMin) {    //Find closest side
                    PaperLib.teleportAsync(onlinePlayer, new Location(world, claim.getMinX() - 1,
                        world.getHighestBlockYAt(claim.getMinX() - 1,
                            location.getBlockZ()), location.getBlockZ()));
                } else {
                    PaperLib.teleportAsync(onlinePlayer, new Location(world, claim.getMaxX() + 1,
                        world.getHighestBlockYAt(claim.getMaxX() + 1,
                            location.getBlockZ()), location.getBlockZ()));
                }
            }
        }

        player.sendMessage(Localization.BAN__SUCCESS.getMessage(player));
    }

    @Subcommand("list")
    @CommandPermission("crashclaim.user.claimban")
    public void listBans(Player player) {
        Location location = player.getLocation();
        Claim claim = manager.getClaim(location.getBlockX(), location.getBlockZ(), location.getWorld().getUID());

        if (claim == null) {
            player.sendMessage(Localization.BAN__LIST_NO_CLAIM.getMessage(player));
            return;
        }

        if (!PermissionHelper.getPermissionHelper().hasPermission(player.getUniqueId(), player.getLocation(), PermissionRoute.MODIFY_PERMISSIONS)){
            player.sendMessage(Localization.BAN__LIST_NO_PERMISSION.getMessage(player));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(CrashClaim.getPlugin(), () -> {
            List<UUID> bannedPlayers = claim.getBannedPlayers();

            player.sendMessage(Localization.BAN__LIST_HEADER.getMessage(player));
            for (UUID bannedPlayer : bannedPlayers) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(bannedPlayer);
                player.sendMessage(Localization.BAN__LIST_PLAYER.getMessage(player, "name", offlinePlayer.getName()));
            }
        });

    }


}
