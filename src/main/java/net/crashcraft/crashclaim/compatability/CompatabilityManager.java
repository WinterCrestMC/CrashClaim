package net.crashcraft.crashclaim.compatability;

/**
 * Inspired by AnvilGUI
 * https://github.com/WesJD/AnvilGUI/blob/master/api/src/main/java/net/wesjd/anvilgui/version/VersionMatcher.java
 */
public class CompatabilityManager {

    private final CompatabilityWrapper wrapper;

    public CompatabilityManager(){
        this.wrapper = new ModernCompatibilityWrapper();
    }


    public CompatabilityWrapper getWrapper() {
        return wrapper;
    }
}
