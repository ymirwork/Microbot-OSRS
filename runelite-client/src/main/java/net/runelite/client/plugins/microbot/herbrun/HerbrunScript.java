package net.runelite.client.plugins.microbot.herbrun;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingHandler;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingWorld;
import net.runelite.client.plugins.microbot.util.Rs2InventorySetup;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.cache.Rs2SkillCache;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;

import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import static net.runelite.client.plugins.microbot.Microbot.log;

@Slf4j
public class HerbrunScript extends Script {
    @Inject
    private ConfigManager configManager;
    @Inject
    private FarmingWorld farmingWorld;
    private FarmingHandler farmingHandler;
    private final HerbrunPlugin plugin;
    private final HerbrunConfig config;
    private HerbPatch currentPatch;
    @Inject
    ClientThread clientThread;
    private boolean initialized = false;

    @Inject
    public HerbrunScript(HerbrunPlugin plugin, HerbrunConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    private final List<HerbPatch> herbPatches = new ArrayList<>();

    public boolean run() {        
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!Microbot.isLoggedIn()) return;
            if (!super.run()) return;
            if (!initialized) {
                initialized = true;
                HerbrunPlugin.status = "Gearing up";
                populateHerbPatches();
                
                // Wait for herbPatches to be populated by client thread (bounded wait with 1000ms timeout)
                if (!sleepUntil(() -> !herbPatches.isEmpty(), 1000)) {
                    // If still empty after timeout, check once more to handle edge cases
                    if (herbPatches.isEmpty()) {                                        
                        plugin.reportFinished("No herb patches ready to farm",true);
                        return;
                    }
                }
                
                if (config.useInventorySetup()) {
                    var inventorySetup = new Rs2InventorySetup(config.inventorySetup(), mainScheduledFuture);
                    if (!inventorySetup.doesInventoryMatch() || !inventorySetup.doesEquipmentMatch()) {
                        Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
                        if (!inventorySetup.loadEquipment() || !inventorySetup.loadInventory()) {                        
                            plugin.reportFinished("Failed to load inventory setup",false);
                            return;
                        }
                        Rs2Bank.closeBank();
                    }
                } else {
                    // Auto banking mode
                    if (!setupAutoInventory()) {
                        plugin.reportFinished("Failed to setup inventory",false);
                        return;
                    }
                }

                log("Will visit " + herbPatches.size() + " herb patches");
            }
            

            if (Rs2Inventory.hasItem("Weeds")) {
                Rs2Inventory.drop("Weeds");
            }
            if (currentPatch == null) getNextPatch();
            if (currentPatch == null) {
                HerbrunPlugin.status = "Finishing up";
                if (config.goToBank()) {
                    Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint());
                    if (!Rs2Bank.isOpen()) Rs2Bank.openBank();
                    Rs2Bank.depositAll();
                }
                HerbrunPlugin.status = "Finished";
                plugin.reportFinished("Herb run finished",true);
                return;
            }

            // Skip patch if it's been disabled while running
            if (!currentPatch.isEnabled()) {
                currentPatch = null;
                return;
            }

            if (!currentPatch.isInRange(10)) {
                HerbrunPlugin.status = "Walking to " + currentPatch.getRegionName();
                Rs2Walker.walkTo(currentPatch.getLocation(), 20);

            }

            HerbrunPlugin.status = "Farming " + currentPatch.getRegionName();
            if (handleHerbPatch()) getNextPatch();


        }, 0, 1000, TimeUnit.MILLISECONDS);

        return true;
    }

    private void populateHerbPatches() {
        this.farmingHandler = new FarmingHandler(Microbot.getClient(), configManager);
        herbPatches.clear();
        clientThread.runOnClientThreadOptional(() -> {
            for (FarmingPatch patch : farmingWorld.getTabs().get(Tab.HERB)) {
                HerbPatch _patch = new HerbPatch(patch, config, farmingHandler);
                if (_patch.getPrediction() != CropState.GROWING && _patch.isEnabled()) herbPatches.add(_patch);
            }
            return true;
        });
    }

    private void getNextPatch() {
        if (currentPatch == null) {
            if (herbPatches.isEmpty()) {
                return;
            }

            // Start with weiss, getNearestBank doesn't like that area!
            currentPatch = herbPatches.stream()
                    .filter(patch -> patch.isEnabled() && Objects.equals(patch.getRegionName(), "Weiss"))
                    .findFirst()
                    .orElseGet(() -> herbPatches.stream()
                            .filter(HerbPatch::isEnabled)
                            .findFirst()
                            .orElse(null));
            
            if (currentPatch != null) {
                herbPatches.remove(currentPatch);
            }
        }
    }

    private boolean handleHerbPatch() {
        if (Rs2Inventory.isFull()) {
            Rs2NpcModel leprechaun = Rs2Npc.getNpc("Tool leprechaun");
            if (leprechaun != null) {
                Rs2ItemModel unNoted = Rs2Inventory.getUnNotedItem("Grimy", false);
                if (unNoted != null) {
                    Rs2Inventory.use(unNoted);
                    Rs2Npc.interact(leprechaun, "Talk-to");
                    Rs2Inventory.waitForInventoryChanges(10000);
                } else {
                    // No grimy herbs to note - try to drop weeds or empty buckets as fallback
                    if (Rs2Inventory.hasItem("Weeds")) {
                        Rs2Inventory.drop("Weeds");
                    } else if (Rs2Inventory.hasItem(ItemID.BUCKET_EMPTY)) {
                        Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
                    }
                }
            }
            return false;
        }

        Integer[] ids = {
                ObjectID.MYARM_HERBPATCH,
                ObjectID.FARMING_HERB_PATCH_2,
                ObjectID.FARMING_HERB_PATCH_4,
                ObjectID.FARMING_HERB_PATCH_8,
                ObjectID.FARMING_HERB_PATCH_6,
                ObjectID.FARMING_HERB_PATCH_3,
                ObjectID.FARMING_HERB_PATCH_1,
                ObjectID.FARMING_HERB_PATCH_7,
                ObjectID.MY2ARM_HERBPATCH,
                ObjectID.FARMING_HERB_PATCH_5
        };
        var obj = Rs2GameObject.findObject(ids);
        if (obj == null) return false;
        var state = getHerbPatchState(obj);
        switch (state) {
            case "Empty":
                // Apply compost if configured
                if (config.compostType() != CompostType.NONE) {
                    CompostType compost = config.compostType();
                    if (!Rs2Inventory.hasItem(compost.getItemId())) {
                        log("Configured compost not found in inventory: " + compost.getCompostName());
                        return false;
                    }
                    Rs2Inventory.use(compost.getItemId());
                    Rs2GameObject.interact(obj, "Compost");
                    Rs2Player.waitForXpDrop(Skill.FARMING, 10000, false);
                    
                    // Drop empty bucket if configured (not for bottomless bucket)
                    if (config.dropEmptyBuckets() && !config.compostType().isBottomless()) {
                        Rs2Inventory.drop(ItemID.BUCKET_EMPTY);
                    }
                }
                // Find the first herb seed in inventory and use its specific ID
                HerbSeedType seedInInventory = getFirstHerbSeedInInventory();
                if (seedInInventory != null) {
                    Rs2Inventory.use(seedInInventory.getItemId());
                    Rs2GameObject.interact(obj, "Plant");
                    sleepUntil(() -> getHerbPatchState(obj).equals("Growing"), 10000);
                } else {
                    log("No herb seeds found in inventory for planting");
                }
                return false;
            case "Harvestable":
                Rs2GameObject.interact(obj, "Pick");
                sleepUntil(() -> getHerbPatchState(obj).equals("Empty") || Rs2Inventory.isFull(), 20000);
                return false;
            case "Weeds":
                Rs2GameObject.interact(obj, "Rake");
                Rs2Player.waitForAnimation(10000);
                return false;
            case "Dead":
                Rs2GameObject.interact(obj, "Clear");
                sleepUntil(() -> getHerbPatchState(obj).equals("Empty"), 10000);
                return false;
            default:
                currentPatch = null;
                return true;
        }
    }

    private static String getHerbPatchState(TileObject rs2TileObject) {
        var game_obj = Rs2GameObject.convertToObjectComposition(rs2TileObject, true);
        var varbitValue = Microbot.getVarbitValue(game_obj.getVarbitId());

        if ((varbitValue >= 0 && varbitValue < 3) ||
                (varbitValue >= 60 && varbitValue <= 67) ||
                (varbitValue >= 173 && varbitValue <= 191) ||
                (varbitValue >= 204 && varbitValue <= 219) ||
                (varbitValue >= 221 && varbitValue <= 255)) {
            return "Weeds";
        }

        if ((varbitValue >= 4 && varbitValue <= 7) ||
                (varbitValue >= 11 && varbitValue <= 14) ||
                (varbitValue >= 18 && varbitValue <= 21) ||
                (varbitValue >= 25 && varbitValue <= 28) ||
                (varbitValue >= 32 && varbitValue <= 35) ||
                (varbitValue >= 39 && varbitValue <= 42) ||
                (varbitValue >= 46 && varbitValue <= 49) ||
                (varbitValue >= 53 && varbitValue <= 56) ||
                (varbitValue >= 68 && varbitValue <= 71) ||
                (varbitValue >= 75 && varbitValue <= 78) ||
                (varbitValue >= 82 && varbitValue <= 85) ||
                (varbitValue >= 89 && varbitValue <= 92) ||
                (varbitValue >= 96 && varbitValue <= 99) ||
                (varbitValue >= 103 && varbitValue <= 106) ||
                (varbitValue >= 192 && varbitValue <= 195)) {
            return "Growing";
        }

        if ((varbitValue >= 8 && varbitValue <= 10) ||
                (varbitValue >= 15 && varbitValue <= 17) ||
                (varbitValue >= 22 && varbitValue <= 24) ||
                (varbitValue >= 29 && varbitValue <= 31) ||
                (varbitValue >= 36 && varbitValue <= 38) ||
                (varbitValue >= 43 && varbitValue <= 45) ||
                (varbitValue >= 50 && varbitValue <= 52) ||
                (varbitValue >= 57 && varbitValue <= 59) ||
                (varbitValue >= 72 && varbitValue <= 74) ||
                (varbitValue >= 79 && varbitValue <= 81) ||
                (varbitValue >= 86 && varbitValue <= 88) ||
                (varbitValue >= 93 && varbitValue <= 95) ||
                (varbitValue >= 100 && varbitValue <= 102) ||
                (varbitValue >= 107 && varbitValue <= 109) ||
                (varbitValue >= 196 && varbitValue <= 197)) {
            return "Harvestable";
        }

        if ((varbitValue >= 128 && varbitValue <= 169) ||
                (varbitValue >= 198 && varbitValue <= 200)) {
            return "Diseased";
        }

        if ((varbitValue >= 170 && varbitValue <= 172) ||
                (varbitValue >= 201 && varbitValue <= 203)) {
            return "Dead";
        }

        return "Empty";
    }

    private boolean setupAutoInventory() {
        // Walk to nearest bank
        Rs2Walker.walkTo(Rs2Bank.getNearestBank().getWorldPoint(), 20);
        
        // Open bank
        if (!Rs2Bank.openBank()) {
            log("Failed to open bank");
            return false;
        }
        if (!sleepUntil(Rs2Bank::isOpen, 10000)) {
            log("Timeout waiting for bank to open after 10 seconds");
            return false;
        }
        
        // Deposit all items into bank
        Rs2Bank.depositAll();
        Rs2Inventory.waitForInventoryChanges(5000);
        
        // Count enabled patches to know how many seeds/compost we need
        int patchCount = (int) herbPatches.stream().filter(HerbPatch::isEnabled).count();
        
        // Withdraw farming tools (fail fast if missing)
        boolean toolsOk = true;
        toolsOk &= Rs2Bank.withdrawX(ItemID.RAKE, 1);
        toolsOk &= Rs2Bank.withdrawX(ItemID.SPADE, 1);
        toolsOk &= Rs2Bank.withdrawX(ItemID.DIBBER, 1);
        if (!toolsOk) {
            log("Missing farming tools in bank (rake/spade/dibber)");
            return false;
        }
        
        // Withdraw magic secateurs if available
        if (Rs2Bank.hasItem(ItemID.FAIRY_ENCHANTED_SECATEURS)) {
            Rs2Bank.withdrawX(ItemID.FAIRY_ENCHANTED_SECATEURS, 1);
        }
        
        // Withdraw teleportation runes
        boolean missingRunes = false;
        missingRunes |= !Rs2Bank.withdrawX(ItemID.LAWRUNE, 20);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.AIRRUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.EARTHRUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.FIRERUNE, 50);
        missingRunes |= !Rs2Bank.withdrawX(ItemID.WATERRUNE, 50);
        
        if (missingRunes) {
            log("Missing teleportation runes - cannot complete herb run");
            return false;
        }
        
        // Withdraw Ectophial if Morytania is enabled
        if (config.enableMorytania() && Rs2Bank.hasItem(ItemID.ECTOPHIAL)) {
            Rs2Bank.withdrawX(ItemID.ECTOPHIAL, 1);
        }
        
        // Withdraw herb seeds
        HerbSeedType seedType = config.herbSeedType();
        if (seedType == HerbSeedType.BEST) {
            // Handle dynamic seed selection based on farming level and availability
            if (!withdrawBestAvailableSeeds(patchCount)) {
                log("Failed to withdraw best available seeds for " + patchCount + " patches");
                return false;
            }
        } else {
            // Original logic for specific seed type
            int seedsNeeded = patchCount; // 1 seed per patch
            
            // Validate farming level requirement
            int farmingLevel = Rs2SkillCache.getRealSkillLevel(Skill.FARMING);
            if (!seedType.canPlant(farmingLevel)) {
                log("Cannot plant " + seedType.getSeedName() + " - requires Farming level " + 
                    seedType.getLevelRequired() + " (you have " + farmingLevel + ")");
                return false;
            }
            
            if (!Rs2Bank.withdrawX(seedType.getItemId(), seedsNeeded)) {
                if (!config.allowPartialRuns()) {
                    log("Failed to withdraw " + seedsNeeded + " " + seedType.getSeedName());
                    return false;
                }
                // Try to withdraw whatever is available
                int availableSeeds = Rs2Bank.count(seedType.getItemId());
                if (availableSeeds > 0) {
                    if (Rs2Bank.withdrawX(seedType.getItemId(), availableSeeds)) {
                        log("Partial run: withdrew " + availableSeeds + " " + seedType.getSeedName() + " instead of " + seedsNeeded);
                    } else {
                        log("Failed to withdraw any " + seedType.getSeedName());
                        return false;
                    }
                } else {
                    log("No " + seedType.getSeedName() + " available in bank");
                    return false;
                }
            }
        }
        
        // Withdraw compost if enabled
        CompostType compostType = config.compostType();
        if (compostType != CompostType.NONE) {
            if (compostType.isBottomless()) {
                // For bottomless bucket, just withdraw the bucket itself
                if (!Rs2Bank.withdrawX(compostType.getItemId(), 1)) {
                    log("Failed to withdraw bottomless compost bucket");
                    return false;
                }
            } else {
                // For regular compost, withdraw 1 bucket per patch
                int compostNeeded = patchCount;
                if (!Rs2Bank.withdrawX(compostType.getItemId(), compostNeeded)) {
                    log("Failed to withdraw " + compostNeeded + " " + compostType.getCompostName());
                    return false;
                }
            }
        }
        
        // Close bank
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        
        log("Inventory setup complete - starting herb run");
        return true;
    }

    /**
     * Helper method to find the first herb seed present in inventory
     * 
     * @return HerbSeedType of the first seed found, or null if none found
     */
    private HerbSeedType getFirstHerbSeedInInventory() {
        for (HerbSeedType herbType : HerbSeedType.values()) {
            if (herbType != HerbSeedType.BEST && Rs2Inventory.hasItem(herbType.getItemId())) {
                return herbType;
            }
        }
        return null;
    }

    /**
     * Withdraws the best available herb seeds based on farming level and bank availability
     * 
     * @param patchCount Number of patches that need seeds
     * @return true if enough seeds were withdrawn for all patches
     */
    private boolean withdrawBestAvailableSeeds(int patchCount) {
        // Get player's farming level
        int farmingLevel = Rs2SkillCache.getRealSkillLevel(Skill.FARMING);
        
        // Get all plantable herbs sorted by level (highest first)
        List<HerbSeedType> plantableHerbs = HerbSeedType.getPlantableHerbs(farmingLevel);
        
        if (plantableHerbs.isEmpty()) {
            log("No herbs can be planted at farming level " + farmingLevel);
            return false;
        }
        
        int seedsWithdrawn = 0;
        int seedsNeeded = patchCount;
        
        // Track which seed types we're withdrawing for logging
        Map<HerbSeedType, Integer> withdrawnSeeds = new HashMap<>();
        
        // Try to withdraw seeds starting from highest level herbs
        for (HerbSeedType herb : plantableHerbs) {
            if (seedsWithdrawn >= seedsNeeded) {
                break; // We have enough seeds
            }
            
            // Check how many of this seed type we have in bank
            int availableSeeds = Rs2Bank.count(herb.getItemId());
            
            if (availableSeeds > 0) {
                // Calculate how many to withdraw (up to what we need)
                int toWithdraw = Math.min(availableSeeds, seedsNeeded - seedsWithdrawn);
                
                // Withdraw the seeds
                if (Rs2Bank.withdrawX(herb.getItemId(), toWithdraw)) {
                    seedsWithdrawn += toWithdraw;
                    withdrawnSeeds.put(herb, toWithdraw);
                    log("Withdrew " + toWithdraw + " " + herb.getSeedName() + " (level " + herb.getLevelRequired() + ")");
                } else {
                    log("Failed to withdraw " + herb.getSeedName() + " despite having " + availableSeeds + " in bank");
                }
            }
        }
        
        // Check if we got enough seeds
        if (seedsWithdrawn < seedsNeeded) {
            log("Could only withdraw " + seedsWithdrawn + " seeds, need " + seedsNeeded + " for all patches");
            
            // Log what we managed to get
            if (!withdrawnSeeds.isEmpty()) {
                StringBuilder sb = new StringBuilder("Seeds withdrawn: ");
                withdrawnSeeds.forEach((herb, count) -> 
                    sb.append(count).append("x ").append(herb.getSeedName()).append(", "));
                log(sb.toString());
            }
            
            if (!config.allowPartialRuns()) {
                return false; // Require all seeds when partial runs are disabled
            }
            return seedsWithdrawn > 0; // Return true if we got at least some seeds
        }
        
        // Success - log summary
        StringBuilder summary = new StringBuilder("Successfully withdrew seeds for " + patchCount + " patches: ");
        withdrawnSeeds.forEach((herb, count) -> 
            summary.append(count).append("x ").append(herb.getSeedName()).append(" (lvl ").append(herb.getLevelRequired()).append("), "));
        log(summary.toString());
        
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        initialized = false;
    }
}
