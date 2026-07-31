package net.crashcraft.crashclaim.claimobjects.permission;

import org.bukkit.Material;

import java.util.HashMap;

public abstract class PermissionSet implements Cloneable{
    private int build;
    private int interactions;
    private int entities;
    private int teleportation;
    private int viewSubClaims;
    private int pvp;
    private int warps;

    private HashMap<Material, Integer> containers;
    private int defaultContainerValue;

    public PermissionSet(){

    }

    public PermissionSet(int build, int interactions, int entities, int teleportation, int viewSubClaims, HashMap<Material, Integer> containers, int defaultContainerValue, int pvp, int warps) {
        this.build = build;
        this.interactions = interactions;
        this.entities = entities;
        this.teleportation = teleportation;
        this.viewSubClaims = viewSubClaims;
        this.containers = containers;
        this.defaultContainerValue = defaultContainerValue;
        this.pvp = pvp;
        this.warps = warps;
    }

    public int getBuild() {
        return build;
    }

    public void setBuild(int build) {
        this.build = build;
    }

    public int getInteractions() {
        return interactions;
    }

    public void setInteractions(int interactions) {
        this.interactions = interactions;
    }

    public int getEntities() {
        return entities;
    }

    public void setEntities(int entities) {
        this.entities = entities;
    }

    public int getTeleportation() {
        return teleportation;
    }

    public void setTeleportation(int teleportation) {
        this.teleportation = teleportation;
    }

    public int getViewSubClaims() {
        return viewSubClaims;
    }

    public void setViewSubClaims(int viewSubClaims) {
        this.viewSubClaims = viewSubClaims;
    }

    public void setContainer(Material material, int value){
        this.containers.put(material, value);
    }

    public void setPvp(int pvp) {
        this.pvp = pvp;
    }

    public int getPvp() {
        return pvp;
    }

    public int getWarps() {
        return warps;
    }

    public void setWarps(int warps) {
        this.warps = warps;
    }

    public HashMap<Material, Integer> getContainers() {
        return containers;
    }

    public PermissionSet clone() {
        try {
            return (PermissionSet) super.clone();
        } catch (CloneNotSupportedException e){
            e.printStackTrace();
        }
        return null;
    }

    public int getDefaultConatinerValue() {
        return defaultContainerValue;
    }

    public void setDefaultConatinerValue(int defaultContainerValue) {
        this.defaultContainerValue = defaultContainerValue;
    }
}
