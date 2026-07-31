package net.crashcraft.crashclaim.compatability;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class ModernCompatibilityWrapper implements CompatabilityWrapper {

    @Override
    public void sendActionBarTitle(Player player, Component message, int fade_in, int duration, int fade_out) {
        sendPacket(player, new WrapperPlayServerActionBar(message));
        sendPacket(player, new WrapperPlayServerSetTitleTimes(fade_in, duration, fade_out));
    }

    @Override
    public void spawnGlowingInvisibleMagmaSlime(Player player, double x, double z, double y, int id, UUID uuid, HashMap<Integer, String> fakeEntities,
        HashMap<Integer, Location> entityLocations) {
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(id, uuid, EntityTypes.MAGMA_CUBE, new com.github.retrooper.packetevents.protocol.world.Location(x, y, z, 0, 0), 0, 0, null);

        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(id, List.of(
            new EntityData(0, EntityDataTypes.BYTE, (byte) (0x20 | 0x40)), // Glowing Invisible
            new EntityData(18, EntityDataTypes.INT, 2) //Slime size : 12
        ));

        sendPacket(player, packet);
        sendPacket(player, metadata);

        fakeEntities.put(id, uuid.toString());
        entityLocations.put(id, new Location(player.getWorld(), x, y, z));
    }

    @Override
    public void removeEntity(Player player, Set<Integer> entity_ids) {
        List<Integer> ids = new ArrayList<>(entity_ids);
        int[] idsArray = new int[ids.size()];

        for (int i = 0; i < ids.size(); i++) {
            idsArray[i] = ids.get(i);
        }

        WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(idsArray);
        sendPacket(player, packet);
    }

    @Override
    public void setEntityTeam(Player player, String team, List<String> uuids) {
        WrapperPlayServerTeams teams = new WrapperPlayServerTeams(team, TeamMode.UPDATE, (ScoreBoardTeamInfo) null, uuids);
        sendPacket(player, teams);
    }

    @Override
    public int getMinWorldHeight(World world) {
        return world.getMinHeight();
    }

    @Override
    public int getUniqueEntityID() {
        return SpigotReflectionUtil.generateEntityId();
    }

    private void sendPacket(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }
}
