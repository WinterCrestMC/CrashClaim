package net.crashcraft.crashclaim.claimobjects;

import java.util.List;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.data.MathUtils;
import net.crashcraft.crashclaim.permissions.PermissionRoute;
import net.crashcraft.crashclaim.permissions.PermissionRouter;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.Predicate;

public class Claim extends BaseClaim {
    private boolean toSave;
    private long saveVersion;

    private ArrayList<SubClaim> subClaims;
    private UUID owner;

    private HashMap<UUID, Integer> contribution;

    public Claim(int id, int upperCornerX, int upperCornerZ, int lowerCornerX, int lowerCornerZ, UUID world, PermissionGroup perms, UUID owner, List<UUID> bannedPlayers) {
        super(id, upperCornerX, upperCornerZ, lowerCornerX, lowerCornerZ, world, perms, bannedPlayers);
        this.toSave = false;
        this.saveVersion = 0;
        this.subClaims = new ArrayList<>();
        this.owner = owner;
        this.contribution = new HashMap<>();
    }

    public boolean hasGlobalPermission(PermissionRoute route){
        return route.getPerm(getPerms().getGlobalPermissionSet()) == PermState.ENABLED;
    }

    public boolean hasPermission(UUID uuid, Location location, PermissionRoute route){
        return getActivePermission(uuid, location, route) == PermState.ENABLED;
    }

    public boolean hasPermission(UUID uuid, Location location, Material material){
        return getActivePermission(uuid, location, material) == PermState.ENABLED;
    }

    public boolean hasPermission(Location location, PermissionRoute route){
        return getActivePermission(location, route) == PermState.ENABLED;
    }

    public boolean hasPermission(Location location, Material material){
        return getActivePermission(location, material) == PermState.ENABLED;
    }

    public SubClaim getSubClaim(int x, int z){
        for (SubClaim subClaim : subClaims) {
            if (MathUtils.iskPointCollide(subClaim.getMinX(), subClaim.getMinZ(), subClaim.getMaxX(),
                    subClaim.getMaxZ(), x, z)) {
                return subClaim;
            }
        }
        return null;
    }

    private int getActivePermission(Location location, PermissionRoute route){
        if (location != null && subClaims.size() > 0){
            SubClaim subClaim = getSubClaim(location.getBlockX(), location.getBlockZ());
            if (subClaim != null){
                return PermissionRouter.getLayeredPermission(this, subClaim, route);
            }
            return route.getPerm(getPerms().getGlobalPermissionSet());
        } else {
            return PermissionRouter.getLayeredPermission(this, null, route);
        }
    }

    private int getActivePermission(Location location, Material material){
        if (location != null && subClaims.size() > 0){
            SubClaim subClaim = getSubClaim(location.getBlockX(), location.getBlockZ());
            if (subClaim != null){
                return PermissionRouter.getLayeredPermission(this, subClaim, material);
            }
            return PermissionRoute.CONTAINERS.getPerm(getPerms().getGlobalPermissionSet());
        } else {
            return PermissionRouter.getLayeredPermission(this, null, material);
        }
    }

    private int getActivePermission(UUID uuid, Location location, PermissionRoute route){   //should only be executed with a location inside the claim
        if (isBanned(uuid))
            return PermState.DISABLE;

        if (uuid.equals(owner))
            return PermState.ENABLED;

        if (location != null && subClaims.size() > 0){
            SubClaim subClaim = getSubClaim(location.getBlockX(), location.getBlockZ());
            if (subClaim != null){
                return PermissionRouter.getLayeredPermission(this, subClaim, uuid, route);
            } else {
                return PermissionRouter.getLayeredPermission(this, null, uuid, route);
            }
        } else {
            return PermissionRouter.getLayeredPermission(this, null, uuid, route);
        }
    }

    private int getActivePermission(UUID uuid, Location location, Material material){   //should only be executed with a location inside the claim
        if (isBanned(uuid))
            return PermState.DISABLE;

        if (uuid.equals(owner))
            return PermState.ENABLED;

        if (location != null && subClaims.size() > 0){
            SubClaim subClaim = getSubClaim(location.getBlockX(), location.getBlockZ());
            if (subClaim != null){
                return PermissionRouter.getLayeredContainer(this, subClaim, uuid, material);
            } else {
                return PermissionRouter.getLayeredContainer(this, null, uuid, material);
            }
        } else {
            return PermissionRouter.getLayeredContainer(this, null, uuid, material);
        }
    }

    public synchronized void addContribution(UUID player, int area){
        Integer existingContribution = contribution.get(player);
        int adder = existingContribution == null ? 0 : existingContribution;
        contribution.put(player, adder + area);
        setToSave(true);
    }

    public synchronized void adjustContribution(UUID player, int area){
        Integer existingContribution = contribution.get(player);
        int adder = (existingContribution == null ? 0 : existingContribution) - area;
        if (adder <= 0){
            contribution.remove(player);
        } else {
            contribution.put(player, adder);
        }
        setToSave(true);
    }

    public synchronized int getContriubtion(UUID player){
        return contribution.getOrDefault(player, 0);
    }

    public synchronized HashMap<UUID, Integer> getContribution(){
        return new HashMap<>(contribution);
    }

    @Override
    public synchronized boolean isToSave() {
        return toSave;
    }

    @Override
    public synchronized void setToSave(boolean toSave) {
        this.toSave = toSave;
        if (toSave) {
            saveVersion++;
        }
    }

    public synchronized long getSaveVersion() {
        return saveVersion;
    }

    public synchronized void markSaved(long savedVersion) {
        if (saveVersion == savedVersion) {
            toSave = false;
        }
    }

    public synchronized void addSubClaim(SubClaim subClaim){
        subClaims.add(subClaim);
        setToSave(true);
    }

    public synchronized void removeSubClaim(int id){
        Iterator<SubClaim> subClaimIterator = subClaims.iterator();
        while (subClaimIterator.hasNext()){
            SubClaim subClaim = subClaimIterator.next();

            if (subClaim.getId() == id){
                subClaimIterator.remove();
                setToSave(true);
                return;
            }
        }
    }

    public synchronized ArrayList<SubClaim> getSubClaims(){
        return new ArrayList<>(subClaims);
    }

    @Override
    public void ban(UUID player) {
        for (SubClaim subClaim : getSubClaims()) {
            subClaim.getPerms().removePlayerPermissionSet(player);
        }
        super.ban(player);
    }

    public UUID getOwner() {
        return owner;
    }

    //JSON needs this

    public synchronized void setSubClaims(ArrayList<SubClaim> subClaims) {
        this.subClaims = new ArrayList<>(subClaims);
    }

    public synchronized void setContribution(HashMap<UUID, Integer> contribution) {
        this.contribution = new HashMap<>(contribution);
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

}
