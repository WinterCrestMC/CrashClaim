package net.crashcraft.crashclaim.claimobjects;

import java.util.List;
import java.util.Set;
import net.crashcraft.crashclaim.CrashClaim;

import java.util.UUID;

public class SubClaim extends BaseClaim {
    private final Claim parent;

    public SubClaim(Claim parent, int id, int upperCornerX, int upperCornerY, int lowerCornerX, int lowerCornerY, UUID world, PermissionGroup perms, List<UUID> bannedPlayers) {
        super(id, upperCornerX, upperCornerY, lowerCornerX, lowerCornerY, world, perms, bannedPlayers);
        this.parent = parent;
    }

    @Override
    public void setToSave(boolean toSave) {
        if (parent == null){
            // Needed for json setting this should never happen after load
            return;
        }
        parent.setToSave(true);
        //CrashClaim.getPlugin().getLogger().info("setToSave " + getId() + " (parentSetToSave)");
    }

    @Override
    public boolean isToSave() {
        return parent.isToSave();
    }

    @Override
    public UUID getWorld(){
        return parent.getWorld();
    }

    public Claim getParent() {
        return parent;
    }
}
