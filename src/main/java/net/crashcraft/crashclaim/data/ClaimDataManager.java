package net.crashcraft.crashclaim.data;

import static net.crashcraft.crashclaim.data.StaticClaimLogic.getChunkHash;
import static net.crashcraft.crashclaim.data.StaticClaimLogic.getChunkHashFromLocation;
import static org.cache2k.expiry.ExpiryTimeValues.ETERNAL;

import dev.whip.crashutils.menusystem.defaultmenus.ConfirmationMenu;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.claimobjects.Claim;
import net.crashcraft.crashclaim.claimobjects.PermissionGroup;
import net.crashcraft.crashclaim.claimobjects.SubClaim;
import net.crashcraft.crashclaim.claimobjects.permission.child.SubPermissionGroup;
import net.crashcraft.crashclaim.claimobjects.permission.parent.ParentPermissionGroup;
import net.crashcraft.crashclaim.config.GlobalConfig;
import net.crashcraft.crashclaim.data.providers.DataProvider;
import net.crashcraft.crashclaim.data.providers.sqlite.SQLiteDataProvider;
import net.crashcraft.crashclaim.localization.Localization;
import net.crashcraft.crashclaim.permissions.PermissionHelper;
import net.crashcraft.crashclaim.permissions.PermissionRoute;
import net.crashcraft.crashclaim.permissions.PermissionSetup;
import net.crashcraft.crashpayment.payment.TransactionResponse;
import net.crashcraft.crashpayment.payment.TransactionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.IntCache;

public class ClaimDataManager implements Listener {
    private static final long CLAIM_COUNT_REFRESH_MILLIS = 5_000L;

    private final CrashClaim plugin;
    private final Logger logger;

    private final DataProvider provider;
    private final PermissionSetup permissionSetup;

    private final IntCache<Claim> claimLookup; // claim id - claim  - First to get called on loads
    private final HashMap<UUID, Long2ObjectOpenHashMap<ArrayList<Integer>>> chunkLookup; // Pre load with data from mem
    private final ExecutorService databaseExecutor;
    private final AtomicReference<Thread> databaseThread;
    private final Set<Integer> queuedClaimSaves;
    private final Map<Integer, CompletableFuture<Claim>> inFlightClaimLoads;
    private final Set<Integer> deletedClaimIds;
    private final Map<UUID, Integer> permittedClaimCounts;
    private final Map<UUID, Integer> ownedParentClaimCounts;
    private final Map<UUID, Long> permittedClaimCountRefreshes;
    private final Map<UUID, Long> ownedParentClaimCountRefreshes;

    private final AtomicInteger idCounter;
    private volatile boolean databaseClosing;

    public ClaimDataManager(CrashClaim plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.idCounter = new AtomicInteger(0);
        this.permissionSetup = new PermissionSetup(plugin);
        this.chunkLookup = new HashMap<>();
        this.databaseThread = new AtomicReference<>();
        this.databaseExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CrashClaim-Database");
            thread.setDaemon(true);
            databaseThread.set(thread);
            return thread;
        });
        this.queuedClaimSaves = ConcurrentHashMap.newKeySet();
        this.inFlightClaimLoads = new ConcurrentHashMap<>();
        this.deletedClaimIds = ConcurrentHashMap.newKeySet();
        this.permittedClaimCounts = new ConcurrentHashMap<>();
        this.ownedParentClaimCounts = new ConcurrentHashMap<>();
        this.permittedClaimCountRefreshes = new ConcurrentHashMap<>();
        this.ownedParentClaimCountRefreshes = new ConcurrentHashMap<>();
        this.databaseClosing = false;

        for (World world : Bukkit.getWorlds()) {
            chunkLookup.put(world.getUID(), new Long2ObjectOpenHashMap<>());
            logger.info("Loaded " + world.getName() + " into chunk map");
        }

        this.provider = new SQLiteDataProvider();
        provider.init(plugin, this);
        Bukkit.getPluginManager().registerEvents(provider, plugin);

        claimLookup = new Cache2kBuilder<Integer, Claim>() {
        }
            .name("chunkToClaimCache")
            .storeByReference(true)
            .expiryPolicy((id, claim, loadTime, currentEntry) -> ETERNAL)
            .disableStatistics(true)
            .buildForIntKey();

        logger.info("Starting claim saving routine");
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (CacheEntry<Integer, Claim> entry : claimLookup.entries()) {
                Claim claim = entry.getValue();

                if (claim == null || !claim.isToSave() || claim.isDeleted()) {
                    continue;
                }

                queueClaimSave(claim);
            }
        }, 200L, 200L);

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                preLoadChunk(world.getUID(), getChunkHash(chunk.getX(), chunk.getZ()));
            }
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public int requestUniqueID() {
        return idCounter.getAndIncrement();
    }

    public ClaimResponse createClaim(Location maxCorner, Location minCorner, UUID owner) {
        if (maxCorner == null || minCorner == null || maxCorner.getWorld() == null) {
            return new ClaimResponse(false, ErrorType.CLAIM_LOCATIONS_WERE_NULL);
        }

        if (isTooSmall(maxCorner.getBlockX(), maxCorner.getBlockZ(), minCorner.getBlockX(), minCorner.getBlockZ())) {
            return new ClaimResponse(false, ErrorType.TOO_SMALL);
        }

        if (checkOverLapSurroudningClaims(-1, maxCorner.getBlockX(), maxCorner.getBlockZ(), minCorner.getBlockX(), minCorner.getBlockZ(),
            maxCorner.getWorld().getUID())) {
            return new ClaimResponse(false, ErrorType.OVERLAP_EXISTING);
        }

        if (!plugin.getPluginSupport().canClaim(minCorner, maxCorner)) {
            return new ClaimResponse(false, ErrorType.OVERLAP_EXISTING_OTHER);
        }

        Claim claim = new Claim(requestUniqueID(),
            maxCorner.getBlockX(),
            maxCorner.getBlockZ(),
            minCorner.getBlockX(),
            minCorner.getBlockZ(),
            maxCorner.getWorld().getUID(),
            new ParentPermissionGroup(null, null, null),
            owner,
            new ArrayList<>());

        claim.getPerms().setOwner(claim, true);
        claim.getPerms().setPlayerPermissionSet(owner, permissionSetup.getOwnerPermissionSet().clone(), false);

        claim.addContribution(owner,
            ContributionManager.getArea(claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ())); //Contribution tracking initial put

        return addClaim(claim) ? new ClaimResponse(true, claim) : new ClaimResponse(false, ErrorType.FILESYSTEM_OR_MEMORY_ERROR);
    }

    public ErrorType resizeClaim(Claim claim, int start_x, int start_z, int end_x, int end_z, Player resizer, Consumer<Boolean> consumer) {
        if (claim.isDeleted()) {
            consumer.accept(false);
            return ErrorType.GENERIC;
        }

        int[] arr = calculateResize(claim.getMinX(), claim.getMaxX(),
            claim.getMinZ(), claim.getMaxZ(), start_x, start_z, end_x, end_z);

        int newMinX = arr[0];
        int newMinZ = arr[2];
        int newMaxX = arr[1];
        int newMaxZ = arr[3];

        if (checkOverLapSurroudningClaims(claim.getId(), newMaxX, newMaxZ, newMinX, newMinZ, claim.getWorld())) {
            return ErrorType.OVERLAP_EXISTING;
        }

        for (SubClaim subClaim : claim.getSubClaims()) {
            if (!MathUtils.containedInside(newMinX, newMinZ, newMaxX, newMaxZ,
                subClaim.getMinX(), subClaim.getMinZ(), subClaim.getMaxX(), subClaim.getMaxZ())) {
                return ErrorType.OVERLAP_EXISTING_SUBCLAIM;
            }
        }

        if (isTooSmall(newMaxX, newMaxZ, newMinX, newMinZ)) {
            return ErrorType.TOO_SMALL;
        }

        if (arr[4] == 1) {
            if (!CrashClaim.getPlugin().getPluginSupport().canClaim(
                new Location(Bukkit.getWorld(claim.getWorld()), newMinX, 0, newMinZ),
                new Location(Bukkit.getWorld(claim.getWorld()), newMaxX, 0, newMaxZ))) { // TODO ugh create corners higher up and pass down, dirty fix for now
                return ErrorType.OVERLAP_EXISTING_OTHER;
            }

            int area = ContributionManager.getArea(newMinX, newMinZ, newMaxX, newMaxZ);
            int originalArea = ContributionManager.getArea(claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ());

            int difference = area - originalArea;

            if (difference > 0) {
                int price = (int) Math.ceil(difference * GlobalConfig.getCostOfBlock(resizer));
                String priceString = Integer.toString(price);
                //Check price with player
                new ConfirmationMenu(resizer,
                    Localization.RESIZE__MENU__CONFIRMATION__TITLE.getMessage(resizer),
                    Localization.RESIZE__MENU__CONFIRMATION__MESSAGE.getItem(resizer, "price", priceString),
                    Localization.RESIZE__MENU__CONFIRMATION__ACCEPT.getItem(resizer, "price", priceString),
                    Localization.RESIZE__MENU__CONFIRMATION__DENY.getItem(resizer, "price", priceString),
                    (player, aBoolean) -> {
                        if (aBoolean) {
                            if (claim.isDeleted()) {
                                consumer.accept(false);
                                return "";
                            }

                            if (PermissionHelper.getPermissionHelper().hasPermission(claim, player.getUniqueId(), PermissionRoute.MODIFY_CLAIM)) {
                                CrashClaim.getPlugin().getPayment()
                                    .makeTransaction(resizer.getUniqueId(), TransactionType.WITHDRAW, "Claim Resize Up", price, (response) -> {
                                        if (response.getTransactionStatus() == TransactionResponse.SUCCESS) {
                                            Bukkit.getScheduler().runTask(plugin, () -> {
                                                boolean resized = resizeClaimCall(
                                                        claim, newMinX, newMinZ, newMaxX, newMaxZ, resizer.getUniqueId());
                                                if (!resized) {
                                                    refundCancelledResize(resizer.getUniqueId(), price);
                                                    consumer.accept(false);
                                                    return;
                                                }

                                                consumer.accept(true);
                                            });
                                            return;
                                        }

                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            player.sendMessage(Localization.RESIZE__TRANSACTION_ERROR.getMessage(player, "error", response.getTransactionError()));
                                            consumer.accept(false);
                                        });
                                    });
                            } else {
                                player.sendMessage(Localization.RESIZE__NO_LONGER_PERMISSION.getMessage(player));
                            }
                        }
                        return "";
                    },
                    player -> ""
                ).open();
            } else {
                //Need to issue a refund
                consumer.accept(resizeClaimCall(
                    claim, newMinX, newMinZ, newMaxX, newMaxZ, resizer.getUniqueId()));
            }
            return ErrorType.NONE;
        } else {
            return ErrorType.CANNOT_FLIP_ON_RESIZE;
        }
    }

    private BaseComponent[] asBungee(Component component){
        return BungeeComponentSerializer.get().serialize(component);
    }

    private synchronized boolean resizeClaimCall(
        Claim claim,
        int newMinX,
        int newMinZ,
        int newMaxX,
        int newMaxZ,
        UUID contributor
    ) {
        if (claim.isDeleted()) {
            return false;
        }

        ContributionManager.addContribution(claim, newMinX, newMinZ, newMaxX, newMaxZ, contributor);
        removeChunksForClaim(claim);

        claim.setMinCornerX(newMinX);
        claim.setMinCornerZ(newMinZ);
        claim.setMaxCornerX(newMaxX);
        claim.setMaxCornerZ(newMaxZ);

        loadChunksForClaim(claim);

        claim.setToSave(true);
        return true;
    }

    private void refundCancelledResize(UUID playerId, int price) {
        CrashClaim.getPlugin().getPayment()
            .makeTransaction(playerId, TransactionType.DEPOSIT, "Cancelled Claim Resize Refund", price, response -> { });
    }

    public ErrorType resizeSubClaim(SubClaim subClaim, int start_x, int start_z, int end_x, int end_z) {
        int[] arr = calculateResize(subClaim.getMinX(), subClaim.getMaxX(),
            subClaim.getMinZ(), subClaim.getMaxZ(), start_x, start_z, end_x, end_z);

        int newMinX = arr[0];
        int newMinZ = arr[2];
        int newMaxX = arr[1];
        int newMaxZ = arr[3];

        if (isTooSmall(newMaxX, newMaxZ, newMinX, newMinZ)) {
            return ErrorType.TOO_SMALL;
        }

        for (SubClaim tempClaim : subClaim.getParent().getSubClaims()) {
            if (tempClaim.equals(subClaim)) {
                continue;
            }

            if (MathUtils.doOverlap(tempClaim.getMinX(), tempClaim.getMinZ(), tempClaim.getMaxX(), tempClaim.getMaxZ(),
                newMinX, newMinZ, newMaxX, newMaxZ)) {
                return ErrorType.OVERLAP_EXISTING_SUBCLAIM;
            }
        }

        if (arr[4] == 1) {
            subClaim.setMinCornerX(newMinX);
            subClaim.setMinCornerZ(newMinZ);
            subClaim.setMaxCornerX(newMaxX);
            subClaim.setMaxCornerZ(newMaxZ);

            subClaim.setEditing(false);
            return ErrorType.NONE;
        } else {
            subClaim.setEditing(false);
            return ErrorType.CANNOT_FLIP_ON_RESIZE;
        }
    }

    public static int[] calculateResize(int minX, int maxX, int minZ, int maxZ, int startX, int startZ, int endX, int endZ) {
        /*
            Works for any size changes: corners or sides.
            Note:
            minX defines the West Line
            maxX defines the East Line

            minZ defines the North Line
            maxZ defines the South Line

            NW is now min
            SE is now max
         */

        int newMin_x = minX;
        int newMax_x = maxX;
        int newMin_z = minZ;
        int newMax_z = maxZ;

        int change = 0;

        if (startX == minX) {
            /*Start is West*/
            // is now south
            newMin_x = endX;
        }

        if (startX == maxX) {
            /*Start is East*/
            // is now north
            newMax_x = endX;
        }

        if (startZ == minZ) {
            /*Start is North*/
            //  is now west
            newMin_z = endZ;
        }

        if (startZ == maxZ) {
            /*Start is South*/
            // is now east
            newMax_z = endZ;
        }

        if (newMax_x > minX && newMin_x < maxX && newMax_z > minZ && newMin_z < maxZ) {
            minX = newMin_x;
            maxX = newMax_x;
            minZ = newMin_z;
            maxZ = newMax_z;
            change = 1;
        }

        int[] arr = new int[5];

        arr[0] = minX;
        arr[1] = maxX;
        arr[2] = minZ;
        arr[3] = maxZ;

        arr[4] = change;

        return arr;
    }

    public boolean isTooSmall(int maxX, int maxZ, int minX, int minZ) {
        return ((maxX - minX) < 4 || (maxZ - minZ) < 4);
    }

    public boolean checkOverLapSurroudningClaims(int claimid, int maxX, int maxZ, int minX, int minZ, UUID world) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> worldClaims = chunkLookup.get(world);
        if (worldClaims == null) {
            return false;
        }

        long northwestChunkX = minX >> 4;
        long northwestChunkZ = minZ >> 4;
        long southeastChunkX = maxX >> 4;
        long southeastChunkZ = maxZ >> 4;

        ArrayList<Claim> claims = new ArrayList<>();

        for (long chunkZ = northwestChunkZ; chunkZ <= southeastChunkZ; chunkZ++) {
            for (long chunkX = northwestChunkX; chunkX <= southeastChunkX; chunkX++) {
                ArrayList<Integer> claimIds = worldClaims.get(getChunkHash(chunkX, chunkZ));
                if (claimIds == null) {
                    continue;
                }

                for (int id : claimIds) {
                    if (id == claimid) {
                        continue;
                    }

                    Claim claim = getClaim(id);
                    if (claim != null && !claims.contains(claim)) {
                        claims.add(claim);
                    }
                }
            }
        }

        for (Claim claim : claims) {
            if (MathUtils.doOverlap(minX, minZ, maxX, maxZ,
                claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ())) {
                return true;
            }
        }
        return false;
    }

    private boolean addClaim(Claim claim) { //Should not be called from anywhere else
        provider.preInitialSave(claim);

        claimLookup.put(claim.getId(), claim);
        loadChunksForClaim(claim);

        fixupOwnerPerms(claim);

        queueClaimSave(claim);
        return true;
    }

    /**
     * @deprecated Use {@link #deleteClaimAsync(Claim)} to observe whether the database transaction committed.
     */
    @Deprecated
    public synchronized void deleteClaim(Claim claim) {
        deleteClaimAsync(claim);
    }

    public synchronized CompletableFuture<Boolean> deleteClaimAsync(Claim claim) {
        if (!claim.beginDeletion()) {
            return CompletableFuture.completedFuture(false);
        }

        deletedClaimIds.add(claim.getId());
        claimLookup.remove(Integer.valueOf(claim.getId()));
        removeClaimFromChunks(claim);

        CompletableFuture<Boolean> deletionResult = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> provider.removeClaim(claim), databaseExecutor)
            .whenComplete((removed, throwable) -> runOnMainThread(() ->
                completeClaimDeletion(claim, removed != null && removed, throwable, deletionResult)));
        return deletionResult;
    }

    private synchronized void completeClaimDeletion(
        Claim claim,
        boolean removed,
        Throwable throwable,
        CompletableFuture<Boolean> deletionResult
    ) {
        if (!removed || throwable != null) {
            deletedClaimIds.remove(claim.getId());
            claim.cancelDeletion();
            claimLookup.put(claim.getId(), claim);
            loadChunksForClaim(claim);
            logger.severe("Failed to remove claim " + claim.getId() + " from the database; restored it in memory.");
            if (throwable != null) {
                throwable.printStackTrace();
            }
            deletionResult.complete(false);
            return;
        }

        claim.completeDeletion();
        finalizeClaimDeletion(claim);
        deletionResult.complete(true);
    }

    private void removeClaimFromChunks(Claim claim) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> chunks = chunkLookup.get(claim.getWorld());
        if (chunks == null) {
            return;
        }

        for (Long chunkId : getChunksForClaim(claim).keySet()) {
            ArrayList<Integer> chunkClaims = chunks.get(chunkId.longValue());
            if (chunkClaims == null) {
                continue;
            }

            chunkClaims.remove(Integer.valueOf(claim.getId()));
            if (chunkClaims.isEmpty()) {
                chunks.remove(chunkId.longValue());
            }
        }
    }

    private void finalizeClaimDeletion(Claim claim) {
        if (claim.getSubClaims() != null) {
            new ArrayList<>(claim.getSubClaims()).iterator().forEachRemaining(this::deleteSubClaimWithoutSave);
        }

        ContributionManager.refundContributors(claim);
    }

    public synchronized void deleteSubClaimWithoutSave(SubClaim subClaim) {
        Claim parent = subClaim.getParent();
        parent.removeSubClaim(subClaim.getId());
    }

    public synchronized void deleteSubClaim(SubClaim subClaim) {
        Claim parent = subClaim.getParent();
        parent.removeSubClaim(subClaim.getId());
        parent.setToSave(true);
        queueClaimSave(parent);
    }

    public void loadChunksForClaim(Claim claim) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> map = chunkLookup.get(claim.getWorld());
        if (map == null) {
            map = new Long2ObjectOpenHashMap<>();
            chunkLookup.put(claim.getWorld(), map);
        }

        for (Map.Entry<Long, ArrayList<Integer>> entry : getChunksForClaim(claim).entrySet()) {
            map.putIfAbsent(entry.getKey().longValue(), new ArrayList<>());
            ArrayList<Integer> integers = map.get(entry.getKey().longValue());
            if (!integers.contains(claim.getId())) {
                integers.add(claim.getId());
            }
        }
    }

    public void loadChunksForUnLoadedClaim(int claim_id, int minX, int minZ, int maxX, int maxZ, UUID world) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> map = chunkLookup.get(world);

        if (map == null) {
            map = new Long2ObjectOpenHashMap<>();
            chunkLookup.put(world, map);
        }

        for (Map.Entry<Long, ArrayList<Integer>> entry : getChunksForUnLoadedClaim(minX, minZ, maxX, maxZ, claim_id).entrySet()) {
            map.putIfAbsent(entry.getKey().longValue(), new ArrayList<>());
            ArrayList<Integer> integers = map.get(entry.getKey().longValue());
            if (!integers.contains(claim_id)) {
                integers.add(claim_id);
            }
        }
    }

    private void removeChunksForClaim(Claim claim) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> map = chunkLookup.get(claim.getWorld());
        if (map == null) {
            return;
        }

        for (Map.Entry<Long, ArrayList<Integer>> entry : getChunksForClaim(claim).entrySet()) {
            ArrayList<Integer> integers = map.get(entry.getKey().longValue());
            if (integers == null) {
                continue;
            }

            integers.remove(Integer.valueOf(claim.getId()));
            if (integers.isEmpty()) {
                map.remove(entry.getKey().longValue());
            }
        }
    }

    private HashMap<Long, ArrayList<Integer>> getChunksForClaim(Claim claim) {
        return getChunksForUnLoadedClaim(claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ(), claim.getId());
    }

    private HashMap<Long, ArrayList<Integer>> getChunksForUnLoadedClaim(int minX, int minZ, int maxX, int maxZ, int claim_id) {
        HashMap<Long, ArrayList<Integer>> chunks = new HashMap<>();

        long NWChunkX = minX >> 4;
        long NWChunkZ = minZ >> 4;
        long SEChunkX = maxX >> 4;
        long SEChunkZ = maxZ >> 4;

        for (long zs = NWChunkZ; zs <= SEChunkZ; zs++) {
            for (long xs = NWChunkX; xs <= SEChunkX; xs++) {
                long identifier = getChunkHash(xs, zs);

                chunks.putIfAbsent(identifier, new ArrayList<>());

                chunks.get(identifier).add(claim_id);
            }
        }

        return chunks;
    }

    public ClaimResponse createSubClaim(Claim claim, Location loc1, Location loc2, UUID owner) {
        if (claim.isDeleted()) {
            return new ClaimResponse(false, ErrorType.GENERIC);
        }

        if (!MathUtils.containedInside(claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ(),
            loc1.getBlockX(), loc1.getBlockZ(), loc2.getBlockX(), loc2.getBlockZ())) {
            return new ClaimResponse(false, ErrorType.OUT_OF_BOUNDS);
        }

        Location max = StaticClaimLogic.calculateMaxCorner(loc1, loc2);
        Location min = StaticClaimLogic.calculateMinCorner(loc1, loc2);

        if (isTooSmall(max.getBlockX(), max.getBlockZ(), min.getBlockX(), min.getBlockZ())) {
            return new ClaimResponse(false, ErrorType.TOO_SMALL);
        }

        for (SubClaim subClaim : claim.getSubClaims()) {
            if (MathUtils.doOverlap(subClaim.getMinX(), subClaim.getMinZ(), subClaim.getMaxX(), subClaim.getMaxZ(),
                min.getBlockX(), min.getBlockZ(), max.getBlockX(), max.getBlockZ())) {
                return new ClaimResponse(false, ErrorType.OVERLAP_EXISTING_SUBCLAIM);
            }
        }

        World world = loc1.getWorld();

        if (world == null) {
            return new ClaimResponse(false, ErrorType.GENERIC);
        }

        SubClaim subClaim = new SubClaim(claim,
            requestUniqueID(),
            max.getBlockX(),
            max.getBlockZ(),
            min.getBlockX(),
            min.getBlockZ(),
            loc1.getWorld().getUID(),
            new SubPermissionGroup(null, null, null),
            new ArrayList<>());

        PermissionGroup permissionGroup = subClaim.getPerms();

        permissionGroup.setOwner(subClaim, true);

        permissionGroup.setPlayerPermissionSet(owner, permissionSetup.getOwnerPermissionSet().clone(), false);

        claim.addSubClaim(subClaim);
        claim.setToSave(true);
        CrashClaim.getPlugin().getLogger().info("setToSave " + claim.getId() + " (createSubClaim)");

        return new ClaimResponse(true, subClaim);
    }

    public Claim getClaim(Integer id) {
        Claim cachedClaim = claimLookup.get(id);
        if (cachedClaim != null || deletedClaimIds.contains(id)) {
            return cachedClaim;
        }

        return loadClaimAsync(id).join();
    }

    public void preLoadChunk(UUID world, long seed) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> map = chunkLookup.get(world);
        if (map == null) {
            Long2ObjectOpenHashMap<ArrayList<Integer>> newMap = new Long2ObjectOpenHashMap<>();
            chunkLookup.put(world, newMap);
            map = newMap;
        }

        ArrayList<Integer> claims = map.get(seed);
        if (claims != null) {
            for (Integer claimId : claims) {
                loadClaimAsync(claimId);
            }
        }
    }

    public CompletableFuture<Void> saveClaim(Claim claim) {
        return queueClaimSave(claim);
    }

    public void saveClaimsSync() {   //Force save all data - shutdown
        forceSaveClaims().join();
    }

    public CompletableFuture<Void> forceSaveClaims() {
        ArrayList<Claim> claims = new ArrayList<>(claimLookup.asMap().values());
        return CompletableFuture.runAsync(() -> {
            for (Claim claim : claims) {
                if (!saveClaimNow(claim)) {
                    throw new IllegalStateException("Unable to force-save claim " + claim.getId());
                }
            }
        }, databaseExecutor);
    }

    private CompletableFuture<Void> queueClaimSave(Claim claim) {
        if (databaseClosing || claim.isDeleted() || !claim.isToSave()) {
            return CompletableFuture.completedFuture(null);
        }

        if (!queuedClaimSaves.add(claim.getId())) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Boolean> saveAttempt = CompletableFuture.supplyAsync(() -> saveClaimNow(claim), databaseExecutor);
        saveAttempt.whenComplete((saved, throwable) -> {
            queuedClaimSaves.remove(claim.getId());
            if (throwable != null) {
                logger.severe("Failed to save claim " + claim.getId());
                throwable.printStackTrace();
            }

            if (Boolean.TRUE.equals(saved) && !databaseClosing && claim.isToSave() && !claim.isDeleted()) {
                queueClaimSave(claim);
            }
        });

        return saveAttempt.thenApply(saved -> null);
    }

    private boolean saveClaimNow(Claim claim) {
        if (claim.isDeleted() || !claim.isToSave()) {
            return true;
        }

        long saveVersion = claim.getSaveVersion();
        boolean saved = provider.saveClaim(claim);
        if (saved) {
            claim.markSaved(saveVersion);
        }
        return saved;
    }

    @EventHandler
    void onChunkLoad(ChunkLoadEvent e) {
        Chunk chunk = e.getChunk();
        long seed = getChunkHash(chunk.getX(), chunk.getZ());
        preLoadChunk(e.getWorld().getUID(), seed);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void onWorldLoad(WorldLoadEvent e) {  // make sur eon new world loads there is a cache entry
        World world = e.getWorld();
        UUID worldId = world.getUID();
        String worldName = world.getName();
        chunkLookup.putIfAbsent(worldId, new Long2ObjectOpenHashMap<>());
        CompletableFuture.runAsync(() -> provider.saveWorld(worldId, worldName), databaseExecutor)
                .exceptionally(throwable -> {
                    logger.severe("Unable to save world " + worldName + ": " + throwable.getMessage());
                    return null;
                });
    }

    @EventHandler
    void onLeave(PlayerQuitEvent e) {
        plugin.getVisualizationManager().cleanup(e.getPlayer());
    }

    public Claim getClaim(int x, int z, UUID world) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> worldClaims = chunkLookup.get(world);
        if (worldClaims == null) {
            return null;
        }

        ArrayList<Integer> integers = worldClaims.get(getChunkHashFromLocation(x, z));
        if (integers == null) {
            return null;
        }

        for (Integer id : integers) {
            Claim claim = getClaim(id);
            if (claim == null) {
                continue;
            }

            if (x >= claim.getMinX() && x <= claim.getMaxX()
                && z >= claim.getMinZ() && z <= claim.getMaxZ()) {
                return claim;
            }
        }
        return null;
    }

    public ArrayList<Claim> getClaims(long chunkX, long chunkZ, UUID world) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> worldClaims = chunkLookup.get(world);
        if (worldClaims == null) {
            return null;
        }

        ArrayList<Integer> integers = worldClaims.get(getChunkHash(chunkX, chunkZ));
        if (integers == null) {
            return null;
        }

        ArrayList<Claim> claims = new ArrayList<>(integers.size());
        for (Integer id : integers) {
            Claim claim = getClaim(id);
            if (claim != null) {
                claims.add(claim);
            }
        }

        return claims;
    }

    public CompletableFuture<Claim> getClaimAsync(int claimId) {
        return loadClaimAsync(claimId);
    }

    private CompletableFuture<Claim> loadClaimAsync(int claimId) {
        Claim cachedClaim = claimLookup.get(claimId);
        if (cachedClaim != null || deletedClaimIds.contains(claimId)) {
            return CompletableFuture.completedFuture(cachedClaim);
        }

        CompletableFuture<Claim> newLoad = new CompletableFuture<>();
        CompletableFuture<Claim> activeLoad = inFlightClaimLoads.putIfAbsent(claimId, newLoad);
        if (activeLoad != null) {
            if (Thread.currentThread() == databaseThread.get() && !activeLoad.isDone()) {
                completeClaimLoad(claimId, activeLoad);
            }
            return activeLoad;
        }

        Runnable loadTask = () -> completeClaimLoad(claimId, newLoad);
        if (Thread.currentThread() == databaseThread.get()) {
            loadTask.run();
            return newLoad;
        }

        try {
            databaseExecutor.execute(loadTask);
        } catch (RuntimeException e) {
            inFlightClaimLoads.remove(claimId, newLoad);
            newLoad.completeExceptionally(e);
        }
        return newLoad;
    }

    private void completeClaimLoad(int claimId, CompletableFuture<Claim> claimFuture) {
        if (claimFuture.isDone()) {
            inFlightClaimLoads.remove(claimId, claimFuture);
            return;
        }

        try {
            Claim claim = deletedClaimIds.contains(claimId) ? null : provider.loadClaim(claimId);
            if (claim != null) {
                fixupOwnerPerms(claim);
            }

            if (claim != null && !deletedClaimIds.contains(claimId)) {
                claimLookup.put(claimId, claim);
            } else {
                claim = null;
            }

            claimFuture.complete(claim);
        } catch (Throwable throwable) {
            claimFuture.completeExceptionally(throwable);
        } finally {
            inFlightClaimLoads.remove(claimId, claimFuture);
        }
    }

    public CompletableFuture<Claim> getClaimAsync(Location location) {
        if (location == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Claim> claimFuture = new CompletableFuture<>();
        Runnable snapshotTask = () -> {
            World world = location.getWorld();
            if (world == null) {
                claimFuture.complete(null);
                return;
            }

            int blockX = location.getBlockX();
            int blockZ = location.getBlockZ();
            ArrayList<Integer> claimIds = getClaimIds(blockX >> 4, blockZ >> 4, world.getUID());
            CompletableFuture.supplyAsync(() -> findClaim(claimIds, blockX, blockZ), databaseExecutor)
                    .whenComplete((claim, throwable) -> completeFuture(claimFuture, claim, throwable));
        };

        runOnMainThread(snapshotTask);
        return claimFuture;
    }

    public CompletableFuture<ArrayList<Claim>> getClaimsAsync(long chunkX, long chunkZ, UUID world) {
        CompletableFuture<ArrayList<Claim>> claimsFuture = new CompletableFuture<>();
        Runnable snapshotTask = () -> {
            ArrayList<Integer> claimIds = getClaimIds(chunkX, chunkZ, world);
            CompletableFuture.supplyAsync(() -> loadClaims(new HashSet<>(claimIds)), databaseExecutor)
                    .whenComplete((claims, throwable) -> completeFuture(claimsFuture, claims, throwable));
        };

        runOnMainThread(snapshotTask);
        return claimsFuture;
    }

    private ArrayList<Integer> getClaimIds(long chunkX, long chunkZ, UUID world) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> worldClaims = chunkLookup.get(world);
        if (worldClaims == null) {
            return new ArrayList<>();
        }

        ArrayList<Integer> claimIds = worldClaims.get(getChunkHash(chunkX, chunkZ));
        return claimIds == null ? new ArrayList<>() : new ArrayList<>(claimIds);
    }

    private Claim findClaim(ArrayList<Integer> claimIds, int blockX, int blockZ) {
        for (Integer claimId : claimIds) {
            Claim claim = getClaim(claimId);
            if (claim == null || claim.isDeleted()) {
                continue;
            }

            if (MathUtils.iskPointCollide(claim.getMinX(), claim.getMinZ(), claim.getMaxX(), claim.getMaxZ(), blockX, blockZ)) {
                return claim;
            }
        }

        return null;
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread() || !plugin.isEnabled()) {
            task.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, task);
    }

    private <T> void completeFuture(CompletableFuture<T> future, T value, Throwable throwable) {
        if (throwable != null) {
            future.completeExceptionally(throwable);
            return;
        }

        future.complete(value);
    }

    public Claim getClaim(Location location) {
        if (location.getWorld() == null) {
            return null;
        }

        return getClaim(location.getBlockX(), location.getBlockZ(), location.getWorld().getUID());
    }

    public void fixupOwnerPerms(Claim claim) {
        PermissionGroup group = claim.getPerms();
        group.setPlayerPermissionSet(claim.getOwner(), permissionSetup.getOwnerPermissionSet().clone(), false);
    }

    public ArrayList<Claim> getOwnedClaims(UUID uuid) {
        return getOwnedClaimsAsync(uuid).join();
    }

    public CompletableFuture<ArrayList<Claim>> getOwnedClaimsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Set<Integer> claimIds = provider.getPermittedClaims(uuid);
            permittedClaimCounts.put(uuid, claimIds.size());
            permittedClaimCountRefreshes.put(uuid, System.currentTimeMillis());
            return loadClaims(claimIds);
        }, databaseExecutor);
    }

    public int getNumberOwnedClaims(UUID uuid) {
        return getNumberOwnedClaimsAsync(uuid).join();
    }

    public CompletableFuture<Integer> getNumberOwnedClaimsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            int claimCount = provider.getPermittedClaims(uuid).size();
            permittedClaimCounts.put(uuid, claimCount);
            permittedClaimCountRefreshes.put(uuid, System.currentTimeMillis());
            return claimCount;
        }, databaseExecutor);
    }

    public ArrayList<Claim> getOwnedParentClaims(UUID uuid) {
        return getOwnedParentClaimsAsync(uuid).join();
    }

    public CompletableFuture<ArrayList<Claim>> getOwnedParentClaimsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Set<Integer> claimIds = provider.getOwnedParentClaims(uuid);
            ownedParentClaimCounts.put(uuid, claimIds.size());
            ownedParentClaimCountRefreshes.put(uuid, System.currentTimeMillis());
            return loadClaims(claimIds);
        }, databaseExecutor);
    }

    public int getNumberOwnedParentClaims(UUID uuid) {
        return getNumberOwnedParentClaimsAsync(uuid).join();
    }

    public CompletableFuture<Integer> getNumberOwnedParentClaimsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            int claimCount = provider.getOwnedParentClaims(uuid).size();
            ownedParentClaimCounts.put(uuid, claimCount);
            ownedParentClaimCountRefreshes.put(uuid, System.currentTimeMillis());
            return claimCount;
        }, databaseExecutor);
    }

    public int getCachedNumberOwnedClaims(UUID uuid) {
        if (markCountRefreshDue(permittedClaimCountRefreshes, uuid)) {
            getNumberOwnedClaimsAsync(uuid).exceptionally(throwable -> {
                logger.warning("Unable to refresh permitted claim count for " + uuid + ": " + throwable.getMessage());
                return null;
            });
        }
        return permittedClaimCounts.getOrDefault(uuid, 0);
    }

    public int getCachedNumberOwnedParentClaims(UUID uuid) {
        if (markCountRefreshDue(ownedParentClaimCountRefreshes, uuid)) {
            getNumberOwnedParentClaimsAsync(uuid).exceptionally(throwable -> {
                logger.warning("Unable to refresh owned claim count for " + uuid + ": " + throwable.getMessage());
                return null;
            });
        }
        return ownedParentClaimCounts.getOrDefault(uuid, 0);
    }

    private boolean markCountRefreshDue(Map<UUID, Long> refreshTimes, UUID uuid) {
        long refreshTime = System.currentTimeMillis();
        Long previousRefresh = refreshTimes.get(uuid);
        if (previousRefresh != null && refreshTime - previousRefresh < CLAIM_COUNT_REFRESH_MILLIS) {
            return false;
        }

        if (previousRefresh == null) {
            return refreshTimes.putIfAbsent(uuid, refreshTime) == null;
        }

        return refreshTimes.replace(uuid, previousRefresh, refreshTime);
    }

    private ArrayList<Claim> loadClaims(Set<Integer> claimIds) {
        ArrayList<Claim> claims = new ArrayList<>(claimIds.size());

        for (Integer id : claimIds) {
            Claim claim = getClaim(id);
            if (claim == null) {
                continue;
            }

            claims.add(claim);
        }

        return claims;
    }

    public CompletableFuture<Integer> updateLastLogin(UUID playerId, int loginTime) {
        return CompletableFuture.supplyAsync(() -> {
            int previousLogin = provider.getLastLoginTime(playerId);
            provider.updateLastLoginTime(playerId, loginTime);
            return previousLogin;
        }, databaseExecutor);
    }

    public void setIdCounter(int idCounter) {
        this.idCounter.set(idCounter);
    }

    public PermissionSetup getPermissionSetup() {
        return permissionSetup;
    }

    public ArrayList<Integer> getClaimIdsInChunkRange(UUID world, long minChunkX, long maxChunkX,
                                                      long minChunkZ, long maxChunkZ) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> worldClaims = chunkLookup.get(world);
        if (worldClaims == null) {
            return new ArrayList<>();
        }

        Set<Integer> claimIds = new HashSet<>();
        for (long chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (long chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ArrayList<Integer> chunkClaimIds = worldClaims.get(getChunkHash(chunkX, chunkZ));
                if (chunkClaimIds != null) {
                    claimIds.addAll(chunkClaimIds);
                }
            }
        }

        return new ArrayList<>(claimIds);
    }

    /**
     * Compatibility view for addons compiled against CrashClaim 1.0.43.
     *
     * @deprecated Use query methods such as {@link #getClaimIdsInChunkRange(UUID, long, long, long, long)}.
     *             This method returns a deep copy and never exposes the live chunk index.
     */
    @Deprecated
    public Long2ObjectOpenHashMap<ArrayList<Integer>> getClaimChunkMap(UUID world) {
        Long2ObjectOpenHashMap<ArrayList<Integer>> worldClaims = chunkLookup.get(world);
        Long2ObjectOpenHashMap<ArrayList<Integer>> chunkMapCopy = new Long2ObjectOpenHashMap<>();
        if (worldClaims == null) {
            return chunkMapCopy;
        }

        for (Map.Entry<Long, ArrayList<Integer>> entry : worldClaims.entrySet()) {
            chunkMapCopy.put(entry.getKey().longValue(), new ArrayList<>(entry.getValue()));
        }

        return chunkMapCopy;
    }

    public void cleanupAndClose() {
        databaseClosing = true;
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("Database executor did not stop within 30 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while waiting for the database executor to stop.");
        }

        chunkLookup.clear();
        inFlightClaimLoads.clear();
        deletedClaimIds.clear();
        claimLookup.clearAndClose();
    }

    public IntCache<Claim> getClaimCache() {
        return claimLookup;
    }

    public DataProvider getProvider() {
        return provider;
    }
}
