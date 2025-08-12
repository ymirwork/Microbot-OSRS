package net.runelite.client.plugins.microbot.util.cache;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.cache.serialization.CacheSerializationManager;
import net.runelite.client.plugins.microbot.util.cache.util.LogOutputMode;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central manager for all Rs2UnifiedCache instances in the Microbot framework.
 * Handles lifecycle coordination, EventBus registration, cache persistence, and provides unified cache statistics.
 * Updated to work with the new unified cache architecture where caches are static utility classes.
 */
@Slf4j
public class Rs2CacheManager implements AutoCloseable {
    private static AtomicBoolean isEventRegistered = new AtomicBoolean (false); // Flag to track if event handlers are registered
    private static Rs2CacheManager instance;
    private static EventBus eventBus;
    
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicBoolean isShutdown;
    
    // Profile management - similar to Rs2Bank
    private static String rsProfileKey = null;
    private static AtomicBoolean loggedInCacheStateKnown = new AtomicBoolean(false);
    
    // Cache loading retry configuration
    private static final int MAX_CACHE_LOAD_ATTEMPTS = 10; // Configurable max retry attempts
    private static final long CACHE_LOAD_RETRY_DELAY_MS = 1000; // 1 second between retries
    private static final AtomicBoolean cacheLoadingInProgress = new AtomicBoolean(false);
    
    public static boolean isCacheDataVaild() {
        return loggedInCacheStateKnown.get();
    }
 
    
    /**
     * Private constructor for singleton pattern.
     */
    private Rs2CacheManager() {
        this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "Rs2CacheCleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.isShutdown = new AtomicBoolean(false);
        
        log.debug("Rs2CacheManager (Unified) initialized");
    }
    
    /**
     * Gets the singleton instance of Rs2CacheManager.
     * 
     * @return The singleton instance
     */
    public static synchronized Rs2CacheManager getInstance() {
        if (instance == null) {
            instance = new Rs2CacheManager();
        }
        return instance;
    }
    
    /**
     * Sets the EventBus instance and registers all cache event handlers.
     * This method should be called during plugin startup to ensure all cache events are properly handled.
     * Does NOT load persistent caches - that should be done when the profile is available.
     * 
     * @param eventBus The RuneLite EventBus instance
     */
    public static void setEventBus(EventBus eventBus) {
        Rs2CacheManager.eventBus = eventBus;
        
        
    }
    
    /**
     * Registers all cache event handlers with the EventBus.
     */
    public static void registerEventHandlers() {
        if (eventBus == null || isEventRegistered.get()) {
            log.warn("EventBus is null, cannot register cache event handlers");
            return;
        }
        
        try {
            // Register NPC cache events
        
            eventBus.register(Rs2NpcCache.getInstance());
            //Rs2NpcCache.getInstance().update();
            // Register Object cache events
            eventBus.register(Rs2ObjectCache.getInstance());
            Rs2ObjectCache.getInstance().update(600*10);
            // Register GroundItem cache events            
            eventBus.register(Rs2GroundItemCache.getInstance());
            //Rs2GroundItemCache.getInstance().update();
            // Register Varbit cache events            
            eventBus.register(Rs2VarbitCache.getInstance());
            
            // Register VarPlayer cache events            
            eventBus.register(Rs2VarPlayerCache.getInstance());
            
            // Register Skill cache events            
            eventBus.register(Rs2SkillCache.getInstance());
            
            // Register Quest cache events            
            eventBus.register(Rs2QuestCache.getInstance());
            
            // Register SpiritTree cache events
            eventBus.register(Rs2SpiritTreeCache.getInstance());
            Rs2CacheManager.isEventRegistered.set(true); // Set registration flag
            log.info("All cache event handlers registered with EventBus");
        } catch (Exception e) {
            log.error("Failed to register cache event handlers", e);
        }
    }
    
    /**
     * Unregisters all cache event handlers from the EventBus.
     */
    public static void unregisterEventHandlers() {
        if (eventBus == null || !Rs2CacheManager.isEventRegistered.get()) {
            return;
        }
        
        try {
            eventBus.unregister(Rs2NpcCache.getInstance());
            eventBus.unregister(Rs2ObjectCache.getInstance());
            eventBus.unregister(Rs2GroundItemCache.getInstance());
            eventBus.unregister(Rs2VarbitCache.getInstance());
            eventBus.unregister(Rs2VarPlayerCache.getInstance());
            eventBus.unregister(Rs2SkillCache.getInstance());
            eventBus.unregister(Rs2QuestCache.getInstance());
            eventBus.unregister(Rs2SpiritTreeCache.getInstance());
            Rs2CacheManager.isEventRegistered.set(false); // Reset registration flag 
            log.debug("All cache event handlers unregistered from EventBus");
        } catch (Exception e) {
            log.error("Failed to unregister cache event handlers", e);
        }
    }
    
    /**
     * Invalidates all known unified caches.
     */
    public static void invalidateAllCaches(boolean savePersistentCaches) {
        try {
            if (savePersistentCaches) {
                savePersistentCaches(Microbot.getConfigManager().getRSProfileKey());
            }
            Rs2NpcCache.getInstance().invalidateAll();
            Rs2GroundItemCache.getInstance().invalidateAll();
            Rs2ObjectCache.getInstance().invalidateAll();            
            Rs2VarbitCache.getInstance().invalidateAll();
            Rs2VarPlayerCache.getInstance().invalidateAll();
            Rs2SkillCache.getInstance().invalidateAll();
            Rs2QuestCache.getInstance().invalidateAll();
            Rs2SpiritTreeCache.getInstance().invalidateAll();            
        } catch (Exception e) {
            log.error("Error invalidating caches: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Triggers scene scans for all entity caches to repopulate them after clearing.
     * This ensures caches are immediately synchronized with the current game scene.
     * Should be called after invalidating caches to provide immediate data availability.
     */
    public static void triggerSceneScansForAllCaches() {
        try {
            if (!Microbot.loggedIn || Microbot.getClient() == null || Microbot.getClient().getLocalPlayer() == null) {
                log.debug("Cannot trigger scene scans - not logged in");
                return;
            }
            
            log.debug("Triggering scene scans for all entity caches after cache invalidation");
            
            // Trigger scene scans for all entity caches with small delays to stagger the operations
            Rs2NpcCache.requestSceneScan();
            Rs2GroundItemCache.requestSceneScan();
            Rs2ObjectCache.requestSceneScan();
            
            log.debug("Scene scan requests sent to all entity caches");
        } catch (Exception e) {
            log.error("Error triggering scene scans: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Gets the total number of entries across all unified caches.
     * 
     * @return The total cache entry count
     */
    public int getTotalCacheSize() {
        try {
            return Rs2NpcCache.getInstance().size() +
                   Rs2GroundItemCache.getInstance().size() +
                   Rs2ObjectCache.getInstance().size() +
                   Rs2VarbitCache.getInstance().size() +
                   Rs2VarPlayerCache.getInstance().size() +
                   Rs2SkillCache.getInstance().size() +
                   Rs2QuestCache.getInstance().size() +
                   Rs2SpiritTreeCache.getInstance().size();
        } catch (Exception e) {
            log.error("Error calculating total cache size: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * Gets the total estimated memory usage across all unified caches.
     * 
     * @return The total estimated memory usage in bytes
     */
    public long getTotalMemoryUsage() {
        try {
            return Rs2NpcCache.getInstance().getEstimatedMemorySize() +
                   Rs2GroundItemCache.getInstance().getEstimatedMemorySize() +
                   Rs2ObjectCache.getInstance().getEstimatedMemorySize() +
                   Rs2VarbitCache.getInstance().getEstimatedMemorySize() +
                   Rs2VarPlayerCache.getInstance().getEstimatedMemorySize() +
                   Rs2SkillCache.getInstance().getEstimatedMemorySize() +
                   Rs2QuestCache.getInstance().getEstimatedMemorySize() +
                   Rs2SpiritTreeCache.getInstance().getEstimatedMemorySize();
        } catch (Exception e) {
            log.error("Error calculating total memory usage: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * Provides unified cache statistics for debugging.
     * 
     * @return A string containing cache statistics
     */
    public String getCacheStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("Rs2CacheManager (Unified) Statistics:\n");
        
        try {
            int npcCount = Rs2NpcCache.getInstance().size();
            int groundItemCount = Rs2GroundItemCache.getInstance().size();
            int objectCount = Rs2ObjectCache.getInstance().size();
            int varbitCount = Rs2VarbitCache.getInstance().size();
            int varPlayerCount = Rs2VarPlayerCache.getInstance().size();
            int skillCount = Rs2SkillCache.getInstance().size();
            int questCount = Rs2QuestCache.getInstance().size();
            int spiritTreeCount = Rs2SpiritTreeCache.getInstance().size();
            
            int totalEntries = npcCount + groundItemCount + objectCount + varbitCount + varPlayerCount + skillCount + questCount + spiritTreeCount;
            
            stats.append("Total entries: ").append(totalEntries).append("\n");
            stats.append("Individual cache sizes:\n");
            stats.append("  NpcCache (EVENT_DRIVEN): ").append(npcCount).append(" entries\n");
            stats.append("  GroundItemCache (EVENT_DRIVEN): ").append(groundItemCount).append(" entries\n");
            stats.append("  ObjectCache (EVENT_DRIVEN): ").append(objectCount).append(" entries\n");
            stats.append("  VarbitCache (AUTO_INVALIDATION): ").append(varbitCount).append(" entries\n");
            stats.append("  VarPlayerCache (EVENT_DRIVEN): ").append(varPlayerCount).append(" entries\n");
            stats.append("  SkillCache (AUTO_INVALIDATION): ").append(skillCount).append(" entries\n");
            stats.append("  QuestCache (AUTO_INVALIDATION): ").append(questCount).append(" entries\n");
            stats.append("  SpiritTreeCache (EVENT_DRIVEN_ONLY): ").append(spiritTreeCount).append(" entries\n");
            
        } catch (Exception e) {
            stats.append("Error collecting statistics: ").append(e.getMessage()).append("\n");
            log.error("Error collecting cache statistics: {}", e.getMessage(), e);
        }
        
        return stats.toString();
    }
    
    /**
     * Gets detailed cache statistics for a specific cache.
     * 
     * @param cacheName The name of the cache to get statistics for
     * @return Cache statistics or null if cache not found
     */
    public Rs2Cache.CacheStatistics getCacheStatistics(String cacheName) {
        try {
            switch (cacheName.toLowerCase()) {
                case "npccache":
                    return Rs2NpcCache.getInstance().getStatistics();
                case "grounditemcache":
                    return Rs2GroundItemCache.getInstance().getStatistics();
                case "objectcache":
                    return Rs2ObjectCache.getInstance().getStatistics();
                case "varbitcache":
                    return Rs2VarbitCache.getInstance().getStatistics();
                case "varplayercache":
                    return Rs2VarPlayerCache.getInstance().getStatistics();
                case "skillcache":
                    return Rs2SkillCache.getInstance().getStatistics();
                case "questcache":
                    return Rs2QuestCache.getInstance().getStatistics();
                case "spirittreecache":
                    return Rs2SpiritTreeCache.getInstance().getStatistics();
                default:
                    log.warn("Unknown cache name: {}", cacheName);
                    return null;
            }
        } catch (Exception e) {
            log.error("Error getting statistics for cache {}: {}", cacheName, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Triggers cache cleanup for all known caches that support periodic cleanup.
     */
    public void triggerCacheCleanup() {
        // Note: With the unified architecture, caches handle their own cleanup
        // based on their CacheMode. This method is kept for compatibility.
        log.debug("Cache cleanup triggered (unified caches handle their own cleanup)");
    }
    
    /**
     * Shuts down the cache manager and all managed caches.
     */
    @Override
    public void close() {
        if (isShutdown.compareAndSet(false, true)) {
            log.info("Shutting down Rs2CacheManager");
            
            // 
            // Empty cache state and Save persistent caches before shutdown
            emptyCacheState();            
            // Unregister event handlers first
            unregisterEventHandlers();            
            // Close all cache instances to ensure proper resource cleanup
            closeAllCaches();
            
            
            
            // Shutdown executor
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            log.debug("Rs2CacheManager shutdown complete");
        }
    }
    
    /**
     * Closes all cache instances to ensure proper resource cleanup.
     * This includes shutting down any schedulers or background tasks.
     */
    private void closeAllCaches() {
        try {
            log.debug("Closing all cache instances");
            
            // Close object cache (includes ObjectUpdateStrategy shutdown)
            Rs2ObjectCache.getInstance().close();
            
            // Close other caches
            Rs2NpcCache.getInstance().close();
            Rs2GroundItemCache.getInstance().close();
            Rs2VarbitCache.getInstance().close();
            Rs2VarPlayerCache.getInstance().close();
            Rs2SkillCache.getInstance().close();
            Rs2QuestCache.getInstance().close();
            Rs2SpiritTreeCache.getInstance().close();
            
            log.debug("All cache instances closed successfully");
        } catch (Exception e) {
            log.error("Error closing cache instances", e);
        }
    }
    
    /**
     * Resets the singleton instance. Used for testing.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
    
    /**
     * Loads all persistent caches from RuneLite profile configuration.
     * This method should be called when the RS profile is available (on login or profile change).
     * Currently unused but kept for potential future use.
     */
    @SuppressWarnings("unused")
    private static void loadPersistentCaches() {
        try {
            rsProfileKey = Microbot.getConfigManager().getRSProfileKey();
            loadPersistentCaches(rsProfileKey);
        } catch (Exception e) {
            log.error("Failed to load persistent caches", e);
        }
    }
    
    /**
     * Loads persistent caches for a specific profile.
     * 
     * @param profileKey The RuneLite profile key to load caches for
     */
    private static void loadPersistentCaches(String profileKey) {
        try {
            if (profileKey == null) {
                log.warn("Cannot load persistent caches: profile key is null");
                return;
            }
            
            Rs2CacheManager.rsProfileKey = profileKey;
            
            log.info("Loading persistent caches from configuration for profile: {}", profileKey);
            
            // Load Skills cache
            if (Rs2SkillCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.loadCache(Rs2SkillCache.getCache(), Rs2SkillCache.getCache().getConfigKey(), profileKey,false);
                log.info("Loaded Skills cache from configuration, new cache size: {}", 
                          Rs2SkillCache.getCache().size());
            }
            
            // Load Quest cache  
            if (Rs2QuestCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.loadCache(Rs2QuestCache.getCache(), Rs2QuestCache.getCache().getConfigKey(), profileKey,false);
                // Schedule an async update to populate quest states from client without blocking initialization
                //Rs2QuestCache.updateAllFromClientAsync();
                log.debug ("Loaded Quest cache from configuration, new cache size: {}", 
                          Rs2QuestCache.getCache().size());
            }
            
            // Load Varbit cache
            if (Rs2VarbitCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.loadCache(Rs2VarbitCache.getCache(), Rs2VarbitCache.getCache().getConfigKey(), profileKey,false);
                log.debug ("Loaded Varbit cache from configuration, new cache size: {}", 
                          Rs2VarbitCache.getCache().size());
            }
            
            // Load VarPlayer cache
            if (Rs2VarPlayerCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.loadCache(Rs2VarPlayerCache.getCache(), Rs2VarPlayerCache.getCache().getConfigKey(), profileKey, false);
                log.debug ("Loaded VarPlayer cache from configuration, new cache size: {}", 
                          Rs2VarPlayerCache.getCache().size());
            }
            if (Rs2SpiritTreeCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.loadCache(Rs2SpiritTreeCache.getCache(), Rs2SpiritTreeCache.getCache().getConfigKey(), profileKey,false);
                 // Update spirit tree cache with current farming handler data after initial load
                try {
                    Rs2SpiritTreeCache.getInstance().update();
                    if(Microbot.isDebug()) Rs2SpiritTreeCache.logState(LogOutputMode.CONSOLE_ONLY);
                    log.debug("Spirit tree cache updated from FarmingHandler after initial load");
                } catch (Exception e) {
                    log.warn("Failed to update spirit tree cache from FarmingHandler after initial load: {}", e.getMessage());
                }
                log.debug ("Loaded SpiritTree cache from configuration, new cache size: {}", 
                          Rs2SpiritTreeCache.getCache().size());
            }
            
            log.info("Successfully loaded all persistent caches from configuration for profile: {}", profileKey);
        } catch (Exception e) {
            log.error("Failed to load persistent caches from configuration for profile: {}", profileKey, e);
        }
    }
    
   
    /**
     * Loads the initial cache state from config. Should be called when a player logs in.
     * Similar to Rs2Bank.loadInitialCacheFromCurrentConfig().
     * This method handles both Rs2Bank and other cache systems.
     */
    public static void loadCacheStateFromCurrentProfile() {        
        String rsProfileKey = Microbot.getConfigManager().getRSProfileKey();
        loadCacheStateFromConfig(rsProfileKey);
        
    }
    
    /**
     * Loads the initial cache state from config. Should be called when a player logs in.
     * Similar to Rs2Bank.loadCacheFromConfig().
     * This method handles both Rs2Bank and other cache systems.
     * Implements retry logic to ensure player is valid before loading.
     */
    public static void loadCacheStateFromConfig(String newRsProfileKey) {
        if (!isCacheDataVaild()) {
            // Start retry task if not already in progress
            if (cacheLoadingInProgress.compareAndSet(false, true)) {
                // Schedule retry task in background thread
                getInstance().cleanupExecutor.schedule(() -> {
                    retryLoadCacheWithValidation(newRsProfileKey, 0);
                }, 0, TimeUnit.MILLISECONDS);
                log.info("Starting cache loading with player validation for profile: {}", newRsProfileKey);
            } else {
                log.debug("Cache loading already in progress, skipping duplicate request");
            }
        }
    }
    
    /**
     * Retries loading cache with player validation up to MAX_CACHE_LOAD_ATTEMPTS times.
     * 
     * @param newRsProfileKey The profile key to load cache for
     * @param attemptCount Current attempt number (0-based)
     */
    private static void retryLoadCacheWithValidation(String newRsProfileKey, int attemptCount) {
        try {
            // Check if player is valid
            Player localPlayer = Microbot.getClient() != null ? Microbot.getClient().getLocalPlayer() : null;
            String playerName = localPlayer != null ? localPlayer.getName() : null;
            
            if (localPlayer != null && playerName != null && !playerName.trim().isEmpty()) {
                log.info("Player validation successful on attempt {}, loading cache state for player: {}", 
                        attemptCount + 1, playerName);
                loadCaches(newRsProfileKey);
                cacheLoadingInProgress.set(false); // Reset flag on success
                return;
            }
            
            // Player not valid yet, check if we should retry
            if (attemptCount < MAX_CACHE_LOAD_ATTEMPTS - 1) {
                log.debug("Player not valid on attempt {} (player: {}), retrying in {}ms", 
                        attemptCount + 1, localPlayer != null ? "not null but no name" : "null", CACHE_LOAD_RETRY_DELAY_MS);
                
                // Schedule next retry
                getInstance().cleanupExecutor.schedule(() -> {
                    retryLoadCacheWithValidation(newRsProfileKey, attemptCount + 1);
                }, CACHE_LOAD_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            } else {
                log.warn("Failed to load cache after {} attempts - player validation failed", MAX_CACHE_LOAD_ATTEMPTS);
                cacheLoadingInProgress.set(false); // Reset flag on failure
            }
            
        } catch (Exception e) {
            log.error("Error during cache loading retry attempt {}: {}", attemptCount + 1, e.getMessage(), e);
            cacheLoadingInProgress.set(false); // Reset flag on error
        }
    }

    /**
     * Sets the initial cache state as unknown. Called when logging out or changing profiles.
     * Similar to Rs2Bank.setUnknownInitialCacheState().
     * This method handles both Rs2Bank and other cache systems.
     */
    public static void setUnknownInitialCacheState() {
        if (    isCacheDataVaild() 
                && rsProfileKey != null 
                && Microbot.getConfigManager() != null 
                && rsProfileKey == Microbot.getConfigManager().getRSProfileKey()) {
            log.info("In Setting initial cache state as unknown for profile \'{}\', saving current cache state", rsProfileKey);
            savePersistentCaches(rsProfileKey);
        }
        // Also handle Rs2Bank cache state
        Rs2Bank.setUnknownInitialCacheState();        
        loggedInCacheStateKnown.set( false);
        rsProfileKey = null;
    }
    
    /**
     * Loads cache state from config, handling profile changes.     
     * This method handles both Rs2Bank and other cache systems.
     */
    private static void loadCaches(String newRsProfileKey) {
        // Only re-load from config if loading from a new profile
        if (newRsProfileKey != null && !newRsProfileKey.equals(rsProfileKey)) {
            // If we've hopped between profiles, save current state first
            if (rsProfileKey != null && isCacheDataVaild()) {
                log.info("Saving current cache state before loading new profile: {}, we have valid cache", rsProfileKey);
                savePersistentCaches(rsProfileKey);
            }            
            // Load persistent caches
            loadPersistentCaches(newRsProfileKey);                        
            // Also handle Rs2Bank cache loading                
            Rs2Bank.loadCacheFromConfig(newRsProfileKey);            
            loggedInCacheStateKnown.set(true);
        }
    }

    /**
     * Handles cache state during profile changes.
     * Saves current cache state before loading new profile caches.
     * Similar to Rs2Bank.handleProfileChange().
     */
    public static void handleProfileChange(String newRsProfileKey, String prvProfile) {
        // Save current cache state before loading new profile
        savePersistentCaches(prvProfile);
        setUnknownInitialCacheState();
        // Load cache state for new profile
        loadCacheStateFromConfig(newRsProfileKey);        
        // Update spirit tree cache with current farming handler data after profile change
       
    }
    
    /**
     * Clears all cache state. Called when logging out.
     * Similar to Rs2Bank.emptyBankState().
     * This method handles both Rs2Bank and other cache systems.
     */
    public static void emptyCacheState() {
        // Save current state before clearing
        if (rsProfileKey != null && isCacheDataVaild()) {
            savePersistentCaches(rsProfileKey);
        }        
        // Clear Rs2Bank state        
        Rs2Bank.emptyCacheState();
        // Clear cache manager state
        rsProfileKey = null;
        loggedInCacheStateKnown.set(false);
        Rs2CacheManager.invalidateAllCaches(false);
        log.info("Emptied all cache states");
    }
    
    /**
     * Saves all persistent caches to RuneLite profile configuration.
     * This method handles both Rs2Bank and other cache systems.
     */
    public static void savePersistentCaches() {
        try {
            if (rsProfileKey != null) {
                savePersistentCaches(rsProfileKey);
            }
        } catch (Exception e) {
            log.error("Failed to save persistent caches", e);
        }
    }
    
    /**
     * Saves persistent caches for a specific profile.
     * This method handles both Rs2Bank and other cache systems.
     * 
     * @param profileKey The RuneLite profile key to save caches for
     */
    public static void savePersistentCaches(String profileKey) {
        try {
            if (!isCacheDataVaild() ) {
                log.warn("Cache data is not valid, cannot save persistent caches");
                return;
            }
            if (profileKey == null) {
                log.warn("Cannot save persistent caches: profile key is null");
                return;
            }
            
            log.info("Saving all persistent caches to configuration for profile: {}", profileKey);
            
            // Save Rs2Bank cache first
            Rs2Bank.saveCacheToConfig(profileKey);
            
            // Save other persistent caches
            savePersistentCachesInternal(profileKey);
            
            log.info("Successfully saved all persistent caches to configuration for profile: {}", profileKey);
        } catch (Exception e) {
            log.error("Failed to save persistent caches to configuration for profile: {}", profileKey, e);
        }
    }
    
    /**
     * Internal method to save persistent caches (excluding Rs2Bank).
     * 
     * @param profileKey The RuneLite profile key to save caches for
     */
    private static void savePersistentCachesInternal(String profileKey) {
        try {
            // Save Skills cache
            if (Rs2SkillCache.getCache().isPersistenceEnabled()) {
                log.info("Saving Skills cache to configuration, current size: {}", 
                          Rs2SkillCache.getCache().size());
                CacheSerializationManager.saveCache(Rs2SkillCache.getCache(), Rs2SkillCache.getCache().getConfigKey(), profileKey);
            }
            
            // Save Quest cache  
            if (Rs2QuestCache.getCache().isPersistenceEnabled()) {
                log.info("Saving Quest cache to configuration, current size: {}", 
                          Rs2QuestCache.getCache().size());
                
                CacheSerializationManager.saveCache(Rs2QuestCache.getCache(), Rs2QuestCache.getCache().getConfigKey(), profileKey);
            }
            
            // Save Varbit cache
            if (Rs2VarbitCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.saveCache(Rs2VarbitCache.getCache(), Rs2VarbitCache.getCache().getConfigKey(), profileKey);
                Rs2VarbitCache.printDetailedVarbitInfo();
                log.info("Saving Varbit cache to configuration, current size: {}", 
                          Rs2VarbitCache.getCache().size());
            }
            
            // Save VarPlayer cache
            if (Rs2VarPlayerCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.saveCache(Rs2VarPlayerCache.getCache(), Rs2VarPlayerCache.getCache().getConfigKey(), profileKey);
                log.info("Saving VarPlayer cache to configuration, current size: {}", 
                          Rs2VarPlayerCache.getCache().size());
            }
            // Save SpiritTree cache
            if (Rs2SpiritTreeCache.getCache().isPersistenceEnabled()) {
                CacheSerializationManager.saveCache(Rs2SpiritTreeCache.getCache(), Rs2SpiritTreeCache.getCache().getConfigKey(), profileKey);
                log.info("Saving SpiritTree cache to configuration, current size: {}", 
                          Rs2SpiritTreeCache.getCache().size());
            }
        } catch (Exception e) {
            log.error("Failed to save internal persistent caches for profile: {}", profileKey, e);
        }
    }
    
    /**
     * Gets statistics for all unified caches as a formatted string with memory usage.
     * @return Formatted string containing statistics for all caches including memory usage
     */
    public static String getAllCacheStatisticsString() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("=== MICROBOT CACHE STATISTICS ===\n");
            
            // Individual cache statistics with memory usage
            appendCacheStats(sb, "NPC", Rs2NpcCache.getInstance());
            appendCacheStats(sb, "GroundItems", Rs2GroundItemCache.getInstance());
            appendCacheStats(sb, "Objects", Rs2ObjectCache.getInstance());
            appendCacheStats(sb, "Varbits", Rs2VarbitCache.getInstance());
            appendCacheStats(sb, "VarPlayers", Rs2VarPlayerCache.getInstance());
            appendCacheStats(sb, "Skills", Rs2SkillCache.getInstance());
            appendCacheStats(sb, "Quests", Rs2QuestCache.getInstance());
            appendCacheStats(sb, "SpiritTrees", Rs2SpiritTreeCache.getInstance());
            
            sb.append("\n=== SUMMARY ===\n");
            
            // Calculate totals
            int totalEntries = getInstance().getTotalCacheSize();
            long totalMemoryBytes = getInstance().getTotalMemoryUsage();
            String formattedMemory = MemorySizeCalculator.formatMemorySize(totalMemoryBytes);
            
            sb.append("Total Cache Entries: ").append(totalEntries).append("\n");
            sb.append("Total Memory Usage: ").append(formattedMemory)
              .append(" (").append(totalMemoryBytes).append(" bytes)\n");
            
            // Memory breakdown by cache type
            sb.append("\n=== MEMORY BREAKDOWN ===\n");
            appendMemoryBreakdown(sb);
            
        } catch (Exception e) {
            log.error("Error getting cache statistics: {}", e.getMessage(), e);
            return "Error retrieving cache statistics: " + e.getMessage();
        }
        return sb.toString();
    }
    
    /**
     * Appends formatted cache statistics for a single cache.
     */
    private static void appendCacheStats(StringBuilder sb, String cacheName, Rs2Cache<?, ?> cache) {
        try {
            Rs2Cache.CacheStatistics stats = cache.getStatistics();
            sb.append(String.format("%-12s: Size=%-4d | Hits=%-6d | Hit Rate=%5.1f%% | Memory=%s\n",
                    cacheName,
                    stats.currentSize,
                    stats.cacheHits,
                    stats.getHitRate() * 100,
                    stats.getFormattedMemorySize()));
        } catch (Exception e) {
            sb.append(String.format("%-12s: ERROR - %s\n", cacheName, e.getMessage()));
        }
    }
    
    /**
     * Appends memory usage breakdown by cache type.
     */
    private static void appendMemoryBreakdown(StringBuilder sb) {
        try {
            // Entity caches (volatile)
            long entityMemory = Rs2NpcCache.getInstance().getEstimatedMemorySize() +
                               Rs2ObjectCache.getInstance().getEstimatedMemorySize() +
                               Rs2GroundItemCache.getInstance().getEstimatedMemorySize();
            
            // Player caches (persistent)  
            long playerMemory = Rs2VarbitCache.getInstance().getEstimatedMemorySize() +
                               Rs2VarPlayerCache.getInstance().getEstimatedMemorySize() +
                               Rs2SkillCache.getInstance().getEstimatedMemorySize() +
                               Rs2QuestCache.getInstance().getEstimatedMemorySize() +
                               Rs2SpiritTreeCache.getInstance().getEstimatedMemorySize();
            
            sb.append("Entity Caches (Volatile): ").append(MemorySizeCalculator.formatMemorySize(entityMemory)).append("\n");
            sb.append("Player Caches (Persistent): ").append(MemorySizeCalculator.formatMemorySize(playerMemory)).append("\n");
            
            // Memory efficiency metrics
            long totalMemory = entityMemory + playerMemory;
            int totalEntries = getInstance().getTotalCacheSize();
            
            if (totalEntries > 0) {
                long avgMemoryPerEntry = totalMemory / totalEntries;
                sb.append("Average Memory per Entry: ").append(MemorySizeCalculator.formatMemorySize(avgMemoryPerEntry)).append("\n");
            }
            
        } catch (Exception e) {
            sb.append("Memory breakdown calculation failed: ").append(e.getMessage()).append("\n");
        }
    }
}
