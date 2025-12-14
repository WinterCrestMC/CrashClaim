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

@CommandAlias("claimunban")
public class UnbanCommand extends BaseCommand {

    private final ClaimDataManager manager;

    public UnbanCommand(ClaimDataManager manager){
        this.manager = manager;
    }

    @Default
    @CommandPermission("crashclaim.user.claimban")
    @CommandCompletion("@players")
    public void unban(Player player, @Flags("other") String value){
        OfflinePlayer otherPlayer = Bukkit.getOfflinePlayerIfCached(value);

        if (otherPlayer == null) {
            player.sendMessage(Localization.UNBAN__INVALID_PLAYER.getMessage(player));
            return;
        }

        if (otherPlayer.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Localization.UNBAN__SELF.getMessage(player));
            return;
        }

        Location location = player.getLocation();
        Claim claim = manager.getClaim(location.getBlockX(), location.getBlockZ(), location.getWorld().getUID());

        if (claim == null) {
            player.sendMessage(Localization.UNBAN__NO_CLAIM.getMessage(player));
            return;
        }

        if (!PermissionHelper.getPermissionHelper().hasPermission(player.getUniqueId(), player.getLocation(), PermissionRoute.MODIFY_PERMISSIONS)){
            player.sendMessage(Localization.UNBAN__NO_PERMISSION.getMessage(player));
            return;
        }

        if (!claim.isBanned(otherPlayer.getUniqueId())){
            player.sendMessage(Localization.UNBAN__NOT_BANNED.getMessage(player));
            return;
        }

        claim.unban(otherPlayer.getUniqueId());

        player.sendMessage(Localization.UNBAN__SUCCESS.getMessage(player));
    }

}
