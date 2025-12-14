package net.crashcraft.crashclaim.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.commands.claiming.ClaimCommand;
import net.crashcraft.crashclaim.visualize.api.BaseVisual;
import net.crashcraft.crashclaim.visualize.api.VisualGroup;
import net.crashcraft.crashclaim.visualize.api.visuals.BaseGlowVisual;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class ProtocalListener implements PacketListener {
    private final CrashClaim crashClaim;
    private final ClaimCommand command;

    public ProtocalListener(CrashClaim crashClaim, ClaimCommand command){
        this.crashClaim = crashClaim;
        this.command = command;

        PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

        if (wrapper.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        if (wrapper.getAction() != InteractAction.INTERACT) {
            return;
        }

        Player player = event.getPlayer();

        if (player == null)
            return;

        VisualGroup group = crashClaim.getVisualizationManager().fetchVisualGroup(player, false);

        if (group == null)
            return;

        int id = wrapper.getEntityId();

        for (BaseVisual visual : group.getActiveVisuals()){
            if (visual instanceof BaseGlowVisual glowVisual) {
                Location location = glowVisual.getEntityLocation(id);
                if (location != null) {
                    Bukkit.getScheduler().runTask(crashClaim, () -> command.click(player, location));
                    return;
                }
            }
        }
    }
}
