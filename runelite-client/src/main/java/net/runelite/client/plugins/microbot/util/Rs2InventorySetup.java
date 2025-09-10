package net.runelite.client.plugins.microbot.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetup;
import net.runelite.client.plugins.microbot.inventorysetups.InventorySetupsItem;
import net.runelite.client.plugins.microbot.inventorysetups.MInventorySetupsPlugin;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2RunePouch;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.misc.Rs2Food;
import net.runelite.client.plugins.microbot.util.misc.Rs2Potion;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/**
 * Utility class for managing inventory setups in the Microbot plugin.
 * Handles loading inventory and equipment setups, verifying matches, and ensuring
 * the correct items are equipped and in the inventory.
 */
public class Rs2InventorySetup {

    InventorySetup inventorySetup;

    ScheduledFuture<?> _mainScheduler;

    /**
     * Constructor to initialize the Rs2InventorySetup with a specific setup name and scheduler.
     *
     * @param name          The name of the inventory setup to load.
     * @param mainScheduler The scheduler to monitor for cancellation.
     */
    public Rs2InventorySetup(String name, ScheduledFuture<?> mainScheduler) {
        inventorySetup = MInventorySetupsPlugin.getInventorySetups().stream().filter(Objects::nonNull).filter(x -> x.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        _mainScheduler = mainScheduler;
        if (inventorySetup == null) {
            Microbot.showMessage("Inventory load with name " + name + " not found!", 10);
			Microbot.pauseAllScripts.compareAndSet(false, true);
        }
    }

    /**
     * Constructor to initialize the Rs2InventorySetup with a specific setup and scheduler.
     * The setup can now directly be fetched from the new config selector.
     *
     * @param setup          The inventory setup to load.
     * @param mainScheduler The scheduler to monitor for cancellation.
     */
    public Rs2InventorySetup(InventorySetup setup, ScheduledFuture<?> mainScheduler) {
        inventorySetup = setup;
        _mainScheduler = mainScheduler;
        if (inventorySetup == null) {
            Microbot.showMessage("Inventory load error!", 10);
			Microbot.pauseAllScripts.compareAndSet(false, true);
        }
    }
    /**
     * Checks if an inventory setup with the specified name exists.
     *
     * @param name The name of the inventory setup to check for (case-insensitive)
     * @return {@code true} if an inventory setup with the specified name exists, {@code false} otherwise
     */
    public static boolean isInventorySetup(String name) {
        return MInventorySetupsPlugin.getInventorySetups().stream().filter(Objects::nonNull).filter(x -> x.getName().equalsIgnoreCase(name)).findFirst().orElse(null) != null;
    }

    /**
     * Checks if the main scheduler has been cancelled.
     *
     * @return true if the scheduler is cancelled, false otherwise.
     */
    public boolean isMainSchedulerCancelled() {
        return _mainScheduler != null && _mainScheduler.isCancelled();
    }

    /**
     * Loads the inventory setup from the bank.
     *
     * @return true if the inventory matches the setup after loading, false otherwise.
     */
	public boolean loadInventory() {
		Rs2Bank.openBank();
		if (!Rs2Bank.isOpen()) {
			return false;
		}

        if (!Rs2Bank.findLockedSlots().isEmpty()) {
            Rs2Bank.toggleAllLocks();
        }

		Rs2Bank.depositAllExcept(itemsToNotDeposit());

		List<InventorySetupsItem> setupItems = inventorySetup.getInventory();

		for (InventorySetupsItem item : setupItems) {
			if (isMainSchedulerCancelled()) break;
			if (InventorySetupsItem.itemIsDummy(item)) continue;

			List<InventorySetupsItem> matchingItems = setupItems.stream()
				.filter(i -> i.matches(item))
				.collect(Collectors.toList());

			int withdrawQuantity = calculateWithdrawQuantity(matchingItems, item);
			if (withdrawQuantity == 0) continue;
			Rs2ItemModel existingItem = new Rs2ItemModel(item.getId(), withdrawQuantity, withdrawQuantity);
			boolean isNoted = existingItem.isNoted(); //for noted items, we also need to use the name of the hasBankItem method, or the unnoted id
			String lowerCaseName = item.getName().toLowerCase();
			boolean isFuzzy = item.isFuzzy();
			Object identifier = isFuzzy ? lowerCaseName : item.getId();

			boolean hasBankItem = isFuzzy || isNoted
				? Rs2Bank.hasBankItem((String) identifier, withdrawQuantity, false)
				: Rs2Bank.hasBankItem((int) identifier, withdrawQuantity);

			if (!hasBankItem) {
				Microbot.pauseAllScripts.compareAndSet(false, true);
				Microbot.log("Bank is missing the following item: " + item.getName(), Level.WARN);
				return false;
			}

			withdrawItem(item, withdrawQuantity,isNoted);
		}

		List<InventorySetupsItem> itemsWithSlots = setupItems.stream()
			.filter(item -> !InventorySetupsItem.itemIsDummy(item) && item.getSlot() >= 0)
			.collect(Collectors.toList());

		sortInventoryItems(itemsWithSlots);

        if (inventorySetup.getRune_pouch() != null) {
			Map<Runes, InventorySetupsItem> inventorySetupRunes = inventorySetup.getRune_pouch().stream()
				.filter(item -> item.getId() != -1 && item.getQuantity() > 0)
				.collect(Collectors.toMap(
					item -> Runes.byItemId(item.getId()),
					item -> item
				));

			if (!Rs2RunePouch.loadFromInventorySetup(inventorySetupRunes)) {
				Microbot.log("Failed to load rune pouch.", Level.WARN);
				return false;
			}
		}

		sleep(800, 1200);

        lockLockedItemsFromSetup(inventorySetup);

		return doesInventoryMatch();
	}

    /**
     * Calculates the quantity of an item to withdraw based on the current inventory state.
     *
     * @param setupItems              List of items to consider.
     * @param setupItem The inventory setup item.
     * @return The quantity to withdraw.
     */
	private int calculateWithdrawQuantity(List<InventorySetupsItem> setupItems, InventorySetupsItem setupItem) {
		int itemId = setupItem.getId();
		String itemName = setupItem.getName().toLowerCase();
		boolean isFuzzy = setupItem.isFuzzy();

		int desiredQuantity = setupItems.stream()
			.mapToInt(InventorySetupsItem::getQuantity)
			.sum();

		int currentQuantity = isFuzzy
			? Rs2Inventory.itemQuantity(itemName)
			: Rs2Inventory.itemQuantity(itemId);

		if (currentQuantity >= desiredQuantity) {
			return 0;
		}

		boolean isStackable = (setupItems.size() == 1) && (desiredQuantity > 1);

		if (!isStackable) {
			long alreadyPresent = isFuzzy
				? Rs2Inventory.items(i -> i.getName().toLowerCase().contains(itemName)).count()
				: Rs2Inventory.items(i -> i.getId() == itemId).count();

			int missing = setupItems.size() - (int) alreadyPresent;
			return Math.max(missing, 0);
		}

		return desiredQuantity - currentQuantity;
	}

    /**
     * Withdraws an item from the bank.
     *
     * @param item     The item to withdraw.
     * @param quantity The quantity to withdraw.
     */
    private void withdrawItem(InventorySetupsItem item, int quantity,  boolean isNoted) {
		boolean useName = item.isFuzzy() || isNoted; // when notded we must use the name to withdraw or we must use the unnoted id
		Object identifier = useName ? item.getName().toLowerCase() : item.getId();
		boolean isWithdrawAs= Rs2Bank.isWithdrawAs(isNoted);
		if(!isWithdrawAs){
			Rs2Bank.setWithdrawAs(isNoted);
		}
		if (quantity > 1) {
			if (useName) {
				Rs2Bank.withdrawX((String) identifier, quantity);
			} else {
				Rs2Bank.withdrawX((int) identifier, quantity);
			}
		} else {
			if (useName) {
				Rs2Bank.withdrawItem((String) identifier);
			} else {
				Rs2Bank.withdrawItem((int) identifier);
			}
		}
		// Using wait for inventory changes here makes sure the inventory is updated more reliably to avoid withdrawing excess items.
		Rs2Inventory.waitForInventoryChanges(5000);
    }

    /**
     * Loads the equipment setup from the bank.
     *
     * @return true if the equipment matches the setup after loading, false otherwise.
     */
    public boolean loadEquipment() {
        Rs2Bank.openBank();
        if (!Rs2Bank.isOpen()) {
            return false;
        }

        //Clear inventory if full
        if (Rs2Inventory.isFull()) {
            Rs2Bank.depositAll();
        } else {
            //only deposit the items we don't need
            Rs2Bank.depositAllExcept(itemsToNotDeposit());
        }


        /*
            Check if we have extra equipment already equipped before attempting to gear
            For example, player is wearing full graceful set but your desired inventory setup does not contain boots, keeping the graceful boots equipped
         */
        boolean hasExtraGearEquipped = Rs2Equipment.isWearing(equip ->
                inventorySetup.getEquipment().stream().noneMatch(setup -> setup.isFuzzy() ?
					equip.getName().toLowerCase().contains(setup.getName().toLowerCase()) :
					equip.getName().equalsIgnoreCase(setup.getName()))
        );

        if (hasExtraGearEquipped) {
            Microbot.log("Found Extra Gear that is not contained within the setup", Level.DEBUG);
            Rs2Bank.depositEquipment();
            sleepUntil(() -> Rs2Equipment.items().stream().noneMatch(Objects::nonNull));
        }

		for (InventorySetupsItem item : inventorySetup.getEquipment()) {
			if (isMainSchedulerCancelled()) break;
			if (InventorySetupsItem.itemIsDummy(item)) continue;

			String lowerCaseName = item.getName().toLowerCase();

			boolean isFuzzy = item.isFuzzy();
			Object identifier = isFuzzy ? item.getName().toLowerCase() : item.getId();

			// Check if already equipped
			if (Rs2Equipment.isWearing(item.getName())) continue;

			// Check in inventory
			boolean inInventory = isFuzzy
				? Rs2Inventory.hasItem((String) identifier) || Rs2Inventory.hasItemAmount((String) identifier, item.getQuantity())
				: Rs2Inventory.hasItem((int) identifier) || Rs2Inventory.hasItemAmount((int) identifier, item.getQuantity());

			// Check in bank
			boolean inBank = isFuzzy
				? Rs2Bank.hasItem((String) identifier) || Rs2Bank.hasBankItem((String) identifier, item.getQuantity(), false)
				: Rs2Bank.hasItem((int) identifier) || Rs2Bank.hasBankItem((int) identifier, item.getQuantity());

			if (!inInventory && !inBank) {
				Microbot.log("Missing " + item.getName() + " in the bank and inventory. Shutting down");
				Microbot.pauseAllScripts.compareAndSet(false, true);
				continue;
			}

			if (inInventory) {
				if (isFuzzy) {
					Rs2Bank.wearItem((String) identifier);
				} else {
					Rs2Bank.wearItem((int) identifier);
				}
				sleepUntil(() -> Rs2Equipment.isWearing(item.getName()));
				continue;
			}

			if (item.getQuantity() > 1) {
				if (isFuzzy) {
					Rs2Bank.withdrawXAndEquip((String) identifier, item.getQuantity());
				} else {
					Rs2Bank.withdrawXAndEquip((int) identifier, item.getQuantity());
				}
			} else {
				if (isFuzzy) {
					Rs2Bank.withdrawAndEquip((String) identifier);
				} else {
					Rs2Bank.withdrawAndEquip((int) identifier);
				}
			}

			sleepUntil(() -> Rs2Equipment.isWearing(item.getName()));
		}

        sleep(800, 1200);

        return doesEquipmentMatch();
    }

    /**
     * Wears the equipment items defined in the inventory setup.
     * Iterates through the equipment setup and equips the items to the player.
     *
     * @return true if the equipment setup matches the current worn equipment, false otherwise.
     */
    public boolean wearEquipment() {
        for (InventorySetupsItem inventorySetupsItem : inventorySetup.getEquipment()) {
            Rs2Inventory.wield(inventorySetupsItem.getId());
        }
        return doesEquipmentMatch();
    }

    /**
     * Checks if the current inventory matches the setup defined in the inventory setup.
     * It compares the quantity and stackability of items in the current inventory
     * against the quantities required by the inventory setup.
     *
     * @return true if the inventory matches the setup, false otherwise.
     */
	public boolean doesInventoryMatch() {
		if (inventorySetup == null || inventorySetup.getInventory() == null) {
			return false;
		}

		Map<Integer, List<InventorySetupsItem>> groupedByItems = inventorySetup.getInventory().stream()
			.collect(Collectors.groupingBy(InventorySetupsItem::getId));

		boolean found = true;

		for (Map.Entry<Integer, List<InventorySetupsItem>> entry : groupedByItems.entrySet()) {
			InventorySetupsItem item = entry.getValue().get(0);
			if (item.getId() == -1) continue;

			int withdrawQuantity;
			boolean isStackable = false;
			if (entry.getValue().size() == 1) {
				withdrawQuantity = item.getQuantity();
				isStackable = withdrawQuantity > 1;
			} else {
				withdrawQuantity = entry.getValue().size();
			}

			for (InventorySetupsItem setupItem : entry.getValue()) {
				int expectedSlot = setupItem.getSlot();

				if (expectedSlot >= 0) {
					Rs2ItemModel invItem = Rs2Inventory.getItemInSlot(expectedSlot);

					boolean itemDoesntExist = invItem == null;
					boolean itemDoesntMatch = invItem != null && (setupItem.isFuzzy()
						? !invItem.getName().toLowerCase().contains(setupItem.getName().toLowerCase())
						: invItem.getId() != setupItem.getId());

					if (itemDoesntExist || itemDoesntMatch) {
						Microbot.log("Slot mismatch: expected " + setupItem.getName() + " in slot " + expectedSlot, Level.WARN);
						found = false;
						continue;
					}

					if (invItem.getQuantity() < setupItem.getQuantity()) {
						Microbot.log("Wrong quantity in slot " + expectedSlot + " for " + setupItem.getName(), Level.WARN);
						found = false;
					}
				} else {
					if (!Rs2Inventory.hasItemAmount(setupItem.getName(), withdrawQuantity, isStackable)) {
						Microbot.log("Missing item: " + setupItem.getName() + " with amount " + setupItem.getQuantity(), Level.WARN);
						found = false;
					}
				}
			}
		}

		if (inventorySetup.getRune_pouch() != null) {
			// TODO: allow each item's is fuzzy to contains(runes, isFuzzy), to allow combination rune matching
			// This creates a hash-map of the 20% of the required runes in the setup
			Map<Runes, Integer> requiredRunes = inventorySetup.getRune_pouch().stream()
				.filter(item -> item.getId() != -1 && item.getQuantity() > 0)
				.map(item -> {
					Runes rune = Runes.byItemId(item.getId());
					if (rune == null) return null;

					int originalQty = item.getQuantity();
					int minQty = Math.max(1, (int) Math.ceil(originalQty * 0.2));
					return Map.entry(rune, minQty);
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toMap(
					Map.Entry::getKey,
					Map.Entry::getValue,
					Integer::sum
				));

			if (!Rs2RunePouch.contains(requiredRunes, false)) {
				Microbot.log("Rune pouch contents do not match expected setup.", Level.WARN);
				found = false;
			}
		}

		return found;
	}

    /**
     * Checks if the current equipment setup matches the desired setup.
     * Iterates through the equipment setup items and verifies if they are equipped properly.
     *
     * @return true if all equipment items match the setup, false otherwise.
     */
    public boolean doesEquipmentMatch() {
        if (inventorySetup ==null || inventorySetup.getEquipment() == null) {
            return false;
        }
        for (InventorySetupsItem inventorySetupsItem : inventorySetup.getEquipment()) {
            if (InventorySetupsItem.itemIsDummy(inventorySetupsItem)) continue;

			boolean exact = !inventorySetupsItem.isFuzzy() && !InventorySetupsItem.isBarrowsItem(inventorySetupsItem.getName().toLowerCase());

			if (!Rs2Equipment.isWearing(inventorySetupsItem.getName(), exact)) {
				Microbot.log("Missing item " + inventorySetupsItem.getName(), Level.WARN);
				return false;
			}
        }
        return true;
    }
    /**
     * Retrieves the list of inventory items from the setup, excluding any dummy items (ID == -1).
     *
     * @return A list of valid inventory items.
     */
    public List<InventorySetupsItem> getInventoryItems() {
        return inventorySetup.getInventory().stream().filter(x -> x.getId() != -1).collect(Collectors.toList());
    }

    /**
     * Retrieves the list of equipment items from the setup, excluding any dummy items (ID == -1).
     *
     * @return A list of valid equipment items.
     */
    public List<InventorySetupsItem> getEquipmentItems() {
        return inventorySetup.getEquipment().stream().filter(x -> x.getId() != -1).collect(Collectors.toList());
    }

    /**
     * Retrieves the list of additional items from the setup, excluding any dummy items (ID == -1).
     *
     * @return A list of valid additional filtered items.
     */
    public List<InventorySetupsItem> getAdditionalItems() {
        return inventorySetup.getAdditionalFilteredItems().values().stream().filter(x -> x.getId() != -1).collect(Collectors.toList());
    }

    /**
     * Creates a list of item names that should not be deposited into the bank.
     * Combines items from both the inventory setup and the equipment setup.
     *
     * @return A list of item names that should not be deposited.
     */
    public Map<String, Boolean> itemsToNotDeposit() {
        List<InventorySetupsItem> inventorySetupItems = getInventoryItems();
        List<InventorySetupsItem> equipmentSetupItems = getEquipmentItems();

        List<InventorySetupsItem> combined = new ArrayList<>();

        combined.addAll(inventorySetupItems);
        combined.addAll(equipmentSetupItems);

        return combined.stream()
                .collect(Collectors.toMap(
                        InventorySetupsItem::getName,
                        InventorySetupsItem::isFuzzy,
                        (existing, replacement) -> existing)
                );
    }

    /**
     * Checks if the current spellbook matches the one defined in the inventory setup.
     *
     * @return true if the current spellbook matches the setup, false otherwise.
     */
    public boolean hasSpellBook() {
        return inventorySetup.getSpellBook() == Microbot.getVarbitValue(VarbitID.SPELLBOOK);
    }

	/**
	 * Sorts inventory items to match a predefined list of {@link InventorySetupsItem} objects.
	 * <p>
	 * For each item in the setup list, this method attempts to ensure that the item is located
	 * in its designated slot within the player's inventory. If the item is already in the correct
	 * slot (based on ID or fuzzy name match), it is skipped. Otherwise, the item is searched for
	 * elsewhere in the inventory and moved to its intended slot if found.
	 * <p>
	 * Fuzzy matching allows partial name matching instead of strict ID matching.
	 *
	 * <p><b>Behavior Notes:</b></p>
	 * <ul>
	 *   <li>Items already in the correct slots are not moved.</li>
	 *   <li>If {@code isMainSchedulerCancelled()} returns true during execution, sorting is aborted early.</li>
	 *   <li>After a successful move, the method waits for inventory changes to take effect.</li>
	 * </ul>
	 *
	 * @param setupItems the desired inventory setup to match; ignored if null or empty
	 */
	private void sortInventoryItems(List<InventorySetupsItem> setupItems) {
		if (setupItems == null || setupItems.isEmpty()) return;

		for (InventorySetupsItem setupItem : setupItems) {
			if (isMainSchedulerCancelled()) break;

			Set<Integer> matchingSlots = setupItems.stream()
				.filter(item -> {
					Rs2ItemModel invItem = Rs2Inventory.getItemInSlot(item.getSlot());
					return invItem != null && (
						item.isFuzzy()
							? invItem.getName().toLowerCase().contains(item.getName().toLowerCase())
							: invItem.getId() == item.getId()
					);
				})
				.map(InventorySetupsItem::getSlot)
				.collect(Collectors.toSet());

			int targetSlot = setupItem.getSlot();

			if (matchingSlots.contains(targetSlot)) {
				continue;
			}

			Predicate<Rs2ItemModel> matchPredicate = invItem -> {
				if (matchingSlots.contains(invItem.getSlot())) {
					return false;
				}

				return setupItem.isFuzzy()
					? invItem.getName().toLowerCase().contains(setupItem.getName().toLowerCase())
					: invItem.getId() == setupItem.getId();
			};

			Rs2ItemModel itemToMove = Rs2Inventory.get(matchPredicate);

			if (itemToMove != null) {
				int sourceSlot = itemToMove.getSlot();
				Microbot.log("Moving " + itemToMove.getName() + " from slot " + sourceSlot + " to slot " + targetSlot, Level.DEBUG);

				if (Rs2Inventory.moveItemToSlot(itemToMove, targetSlot)) {
					Rs2Inventory.waitForInventoryChanges(2000);
				}
			} else {
				Microbot.log("No available item found for " + setupItem.getName() + " to place in slot " + targetSlot, Level.DEBUG);
			}
		}

		Microbot.log("Inventory sorting complete", Level.DEBUG);
	}

    /**
     * Locks all inventory slots marked as “locked” in the given setup.
     *
     * Iterates through the setup’s list of items, collects the indices (slots) of those flagged locked,
     * and invokes lockAllBySlot on that array of slot indices. Returns true if any slots were processed
     * (i.e., there were locked slots to act on), false if none were found.
     *
     * Preconditions:
     * - The InventorySetup must be populated, and InventorySetup.getSetupItems(setup) returns a non-null list.
     * - Each InventorySetupsItem.getSlot() is assumed to correspond to an inventory slot index if used elsewhere.
     *
     * Postconditions:
     * - lockAllBySlot(...) is called with the locked slot indices; its result is returned.
     * - If no locked items exist, the method returns false without side effects.
     *
     * @param setup the InventorySetup whose locked items’ slots should be toggled/locked
     */
    private void lockLockedItemsFromSetup(InventorySetup setup) {
        List<InventorySetupsItem> setupItems = InventorySetup.getSetupItems(setup);
        List<Integer> lockedSlots = IntStream.range(0, setupItems.size())
                .filter(i -> {
                    InventorySetupsItem item = setupItems.get(i);
                    return item != null && item.isLocked();
                })
                .boxed()
                .collect(Collectors.toList());
        if (lockedSlots.isEmpty()) {
            return;
        }
		Rs2Bank.lockAllBySlot(lockedSlots.stream().mapToInt(Integer::intValue).toArray());
    }

	/**
	 * Prepares the player for combat by drinking boosting potions and optionally healing.
	 *
	 * This method handles the following:
	 * - Temporarily storing inventory items if the inventory is full
	 * - Drinking a "chug barrel" device if configured
	 * - Drinking boosting potions from the inventory or bank
	 * - Depositing empty potion vials
	 * - Healing with food if HP is not full
	 * - Optionally boosting HP using anglerfish
	 * - Restoring any temporarily deposited items after pre-potting
	 *
	 * @param potionsToPrePot a list of potion name substrings (e.g. "ranging", "magic") to match and consume
	 * @return true if pre-potting succeeded or completed, false if setup was invalid or cancelled
	 */
	public boolean prePot(List<String> potionsToPrePot)
	{
		List<InventorySetupsItem> additionalItems = getAdditionalItems();

		if (additionalItems.isEmpty())
		{
			Microbot.log("No additional items to pre-pot.", Level.WARN);
			return false;
		}

		List<Rs2ItemModel> storedItems = new ArrayList<>();

		if (Rs2Inventory.isFull())
		{
			Microbot.log("Inventory is full, temporarily storing items to make space", Level.INFO);
			Rs2Inventory.items()
				.sorted(Comparator.comparing(Rs2ItemModel::isStackable))
				.limit(3)
				.forEach(item -> {
					storedItems.add(item);
					Rs2Bank.depositOne(item.getId());
					Rs2Inventory.waitForInventoryChanges(1800);
				});
		}

		boolean useChugBarrel = additionalItems.stream().anyMatch(item -> item.getId() == ItemID.MM_PREPOT_DEVICE);
		if (useChugBarrel && !handleChugBarrel())
		{
			return false;
		}
		else
		{
			List<String> setupPotionNames = additionalItems.stream()
				.map(InventorySetupsItem::getName)
				.filter(Objects::nonNull)
				.map(String::toLowerCase)
				.collect(Collectors.toList());

			List<String> validPotionsToPrePot = potionsToPrePot.stream()
				.filter(pot -> setupPotionNames.stream().anyMatch(setupName -> setupName.equalsIgnoreCase(pot.toLowerCase())))
				.collect(Collectors.toList());

			findBoostingPotions(validPotionsToPrePot).stream().forEachOrdered(potion -> {
				if (isMainSchedulerCancelled() || isPotionEffectActive(potion.getName().toLowerCase()))
				{
					return;
				}

				boolean fromInventory = Rs2Inventory.hasItem(potion.getId());
				if (!fromInventory)
				{
					Rs2Bank.withdrawOne(potion.getName());
					Rs2Inventory.waitForInventoryChanges(1800);
				}

				Rs2Inventory.interact(potion.getId(), "drink");
				Rs2Random.wait(1200, 1800); // added pot delay

				if (!fromInventory)
				{
					Matcher matcher = Pattern.compile("\\((\\d)\\)").matcher(potion.getName());
					int resultingDose = matcher.find() ? Integer.parseInt(matcher.group(1)) - 1 : -1;

					if (resultingDose > 0)
					{
						String resultingName = potion.getName().replaceAll("\\(\\d\\)", "(" + resultingDose + ")");
						Rs2ItemModel resultingItem = Rs2Inventory.items()
							.filter(item -> item.getName().equalsIgnoreCase(resultingName))
							.findFirst()
							.orElse(null);

						if (resultingItem != null)
						{
							Rs2Bank.depositOne(resultingItem.getId());
							Rs2Inventory.waitForInventoryChanges(1800);
						}
					}
					else if (Rs2Inventory.hasItem(ItemID.VIAL_EMPTY))
					{
						Rs2Bank.depositAll(ItemID.VIAL_EMPTY);
						Rs2Inventory.waitForInventoryChanges(1800);
					}
				}
			});
		}

		if (Rs2Player.getHealthPercentage() < 100)
		{
			handleHealing(additionalItems);
		}

		boolean isPlayerHealthBoosted = Rs2Player.getHealthPercentage() > 100;
		boolean shouldUseAnglerfish = additionalItems.stream()
			.anyMatch(item -> item.getId() == ItemID.ANGLERFISH);

		if (!isPlayerHealthBoosted && shouldUseAnglerfish)
		{
			Rs2ItemModel anglerFishItem = Rs2Bank.getBankItem(ItemID.ANGLERFISH);
			if (anglerFishItem != null)
			{
				Rs2Bank.withdrawOne(anglerFishItem.getId());
				Rs2Inventory.waitForInventoryChanges(1800);
				Rs2Inventory.interact(anglerFishItem.getId(), "eat");
				Rs2Inventory.waitForInventoryChanges(1800);
			}
		}

		if (!storedItems.isEmpty())
		{
			Microbot.log("Restoring temporarily stored items", Level.INFO);

			for (Rs2ItemModel storedItem : storedItems)
			{
				if (isMainSchedulerCancelled())
				{
					break;
				}

				if (Rs2Inventory.isFull())
				{
					Microbot.log("Inventory full, cannot restore all stored items", Level.WARN);
					return false;
				}

				if (storedItem.isStackable() && storedItem.getQuantity() > 1)
				{
					Rs2Bank.withdrawX(storedItem.getId(), storedItem.getQuantity());
				}
				else
				{
					Rs2Bank.withdrawItem(storedItem.getId());
				}
				Rs2Inventory.waitForInventoryChanges(1800);
			}
		}

		return true;
	}

	/**
	 * Handles withdrawing and using the "chugging barrel" pre-pot device.
	 *
	 * The device is withdrawn from the bank, consumed via interaction, and then deposited back.
	 *
	 * @return true if the chug barrel was used successfully, false if the item was not available in the bank
	 */
	private boolean handleChugBarrel() {
		if (!Rs2Bank.hasItem(ItemID.MM_PREPOT_DEVICE)) {
			Microbot.log("Chugging barrel found in Inventory Setup, but not in bank", Level.WARN);
			return false;
		}
		Rs2Bank.withdrawItem(ItemID.MM_PREPOT_DEVICE);
		Rs2Inventory.waitForInventoryChanges(1800);
		Rs2Inventory.interact(ItemID.MM_PREPOT_DEVICE, "drink");
		Rs2Random.wait(1200, 1800); // added pot delay
		Rs2Bank.depositOne(ItemID.MM_PREPOT_DEVICE);
		Rs2Inventory.waitForInventoryChanges(1800);
		return true;
	}

	/**
	 * Finds boosting potions from the inventory or bank that match the given list of substrings.
	 *
	 * The result includes only one variant per potion ID (e.g., avoids duplicates like "(1)", "(2)", etc.),
	 * and is sorted to prioritize potions with higher doses if pulled from the bank.
	 *
	 * @param potionsToPrePot list of lowercase substrings to match potion names
	 * @return a list of unique matching potions from inventory or bank
	 */
	private List<Rs2ItemModel> findBoostingPotions(List<String> potionsToPrePot) {
		List<Rs2ItemModel> potions = Rs2Bank.bankItems().stream()
			.filter(item -> item.getName() != null &&
				potionsToPrePot.stream().anyMatch(name -> item.getName().toLowerCase().contains(name)))
			.collect(Collectors.toList());

		if (!potions.isEmpty()) {
			potions.sort(Comparator.comparingInt(item -> {
				String name = item.getName().toLowerCase();
				Matcher matcher = Pattern.compile("\\((\\d)\\)").matcher(name);
				return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
			}));
		}

		return potions;
	}

	/**
	 * Checks whether the effect of a potion is already active.
	 *
	 * This prevents redundant consumption of potions with effects that are still active,
	 * such as ranging, magic, divine potions, stamina, and prayer regeneration.
	 *
	 * @param potionName the lowercase name of the potion to check
	 * @return true if the effect of the potion is currently active, false otherwise
	 */
	private boolean isPotionEffectActive(String potionName) {
		boolean isRangedPotionType = Rs2Potion.getRangePotionsVariants().stream()
			.map(String::toLowerCase)
			.anyMatch(potionName::contains);
		if (isRangedPotionType && (Rs2Player.hasRangingPotionActive(3) || Rs2Player.hasDivineRangedActive())) {
			return true;
		}

		boolean isMagicPotionType = Rs2Potion.getMagicPotionsVariants().stream()
			.map(String::toLowerCase)
			.anyMatch(potionName::contains);
		if (isMagicPotionType && (Rs2Player.hasMagicActive(3) || Rs2Player.hasDivineMagicActive())) {
			return true;
		}

		boolean isCombatPotionType = Rs2Potion.getCombatPotionsVariants().stream()
			.map(String::toLowerCase)
			.anyMatch(potionName::contains);

		// TODO: Missing implementation for normal combat potions or super combats
		if (isCombatPotionType && Rs2Player.hasDivineCombatActive()) {
			return true;
		}

		boolean isPrayerRegenPotionType = potionName.contains(Rs2Potion.getPrayerRegenerationPotion().toLowerCase());
		if (isPrayerRegenPotionType && Rs2Player.hasPrayerRegenerationActive()) {
			return true;
		}

		boolean isStaminaPotionType = potionName.contains(Rs2Potion.getStaminaPotion().toLowerCase());
		if (isStaminaPotionType && Rs2Player.hasStaminaActive()) {
			return true;
		}

		return false;
	}

	/**
	 * Heals the player using food found in the bank, based on the items defined in the inventory setup.
	 *
	 * If no matching food is found in the setup, the method will select the highest-healing food available in the bank.
	 * Food types like karambwan or anglerfish are excluded here since they have special uses.
	 *
	 * @param additionalItems the list of items defined in the current inventory setup
	 */
	private void handleHealing(List<InventorySetupsItem> additionalItems) {
		Set<String> excluded = Set.of("karambwan", "anglerfish");

		Optional<Rs2Food> healingFoodFromSetup = additionalItems.stream()
			.map(InventorySetupsItem::getName)
			.filter(Objects::nonNull)
			.map(String::toLowerCase)
			.flatMap(name -> Arrays.stream(Rs2Food.values())
				.filter(food -> {
					String foodName = food.getName().toLowerCase();
					return !excluded.contains(foodName) && name.contains(foodName);
				}))
			.findFirst();

		Rs2ItemModel healingFood = healingFoodFromSetup
			.flatMap(food -> Rs2Bank.bankItems().stream()
				.filter(item -> item.getId() == food.getId())
				.findFirst())
			.orElseGet(() ->
				Rs2Bank.bankItems().stream()
					.filter(item -> Arrays.stream(Rs2Food.values())
						.anyMatch(food -> food.getId() == item.getId()))
					.max(Comparator.comparingInt(item ->
						Arrays.stream(Rs2Food.values())
							.filter(food -> food.getId() == item.getId())
							.findFirst()
							.map(Rs2Food::getHeal)
							.orElse(0)))
					.orElse(null)
			);

		if (healingFood == null) {
			Microbot.log("Unable to find highest healing food in bank", Level.WARN);
			return;
		}

		Rs2Bank.withdrawOne(healingFood.getId());
		Rs2Inventory.waitForInventoryChanges(1800);
		Rs2Inventory.interact(healingFood.getId(), "eat");
		Rs2Random.wait(1200, 1800); // added pot delay
	}

	/**
	 * Calls {@link #prePot(List)} using a standard set of boosting potions.
	 *
	 * The potion types included are:
	 * - Ranging potions
	 * - Magic potions
	 * - Combat potions
	 * - Prayer regeneration potion
	 * - Stamina potion
	 *
	 * @return true if pre-potting succeeded, false otherwise
	 */
	public boolean prePot()
	{
		List<String> boostingPotionNames =
			Stream.of(
				new ArrayList<String>() {{
					addAll(Rs2Potion.getRangePotionsVariants());
					Collections.reverse(this);
				}},
				new ArrayList<String>() {{
					addAll(Rs2Potion.getMagicPotionsVariants());
					Collections.reverse(this);
				}},
				new ArrayList<String>() {{
					addAll(Rs2Potion.getCombatPotionsVariants());
					Collections.reverse(this);
				}},
				List.of(Rs2Potion.getPrayerRegenerationPotion()),
				List.of(Rs2Potion.getStaminaPotion())
			)
			.flatMap(Collection::stream)
			.map(String::toLowerCase)
			.collect(Collectors.toList());
		return prePot(boostingPotionNames);
	}

	/**
	 * Detects items in the player's inventory that are not part of the inventory setup.
	 * Optionally excludes teleportation items from detection.
	 * 
	 * @param excludeTeleportItems Whether to exclude teleportation items from detection
	 * @return List of items in inventory that are not in the setup
	 */
	public List<Rs2ItemModel> getItemsNotInInventorySetup(boolean excludeTeleportItems) {
		if (inventorySetup == null || inventorySetup.getInventory() == null) {
			return Rs2Inventory.all(); // Return all items if no setup defined
		}

		// Get all setup item IDs and names for comparison
		Set<Integer> setupItemIds = inventorySetup.getInventory().stream()
			.filter(item -> !InventorySetupsItem.itemIsDummy(item))
			.map(InventorySetupsItem::getId)
			.collect(Collectors.toSet());

		Set<String> setupItemNames = inventorySetup.getInventory().stream()
			.filter(item -> !InventorySetupsItem.itemIsDummy(item) && item.isFuzzy())
			.map(item -> item.getName().toLowerCase())
			.collect(Collectors.toSet());

		return Rs2Inventory.all().stream()
			.filter(item -> {
				// Check if item is excluded due to teleport filter
				if (excludeTeleportItems && Rs2Walker.isTeleportItem(item.getId())) {
					return false;
				}

				// Check if item matches any setup item by ID
				if (setupItemIds.contains(item.getId())) {
					return false;
				}

				// Check if item matches any fuzzy setup item by name
				String itemName = item.getName().toLowerCase();
				for (String setupName : setupItemNames) {
					if (itemName.contains(setupName)) {
						return false;
					}
				}

				return true; // Item is not in setup
			})
			.collect(Collectors.toList());
	}

	/**
	 * Detects items in the player's equipment that are not part of the equipment setup.
	 * Optionally excludes teleportation items from detection.
	 * 
	 * @param excludeTeleportItems Whether to exclude teleportation items from detection
	 * @return List of equipment items that are not in the setup
	 */
	public List<Rs2ItemModel> getEquipmentNotInSetup(boolean excludeTeleportItems) {
		if (inventorySetup == null || inventorySetup.getEquipment() == null) {
			return Rs2Equipment.all().collect(Collectors.toList()); // Return all equipment if no setup defined
		}

		// Get all setup equipment IDs and names for comparison
		Set<Integer> setupEquipmentIds = inventorySetup.getEquipment().stream()
			.filter(item -> !InventorySetupsItem.itemIsDummy(item))
			.map(InventorySetupsItem::getId)
			.collect(Collectors.toSet());

		Set<String> setupEquipmentNames = inventorySetup.getEquipment().stream()
			.filter(item -> !InventorySetupsItem.itemIsDummy(item) && item.isFuzzy())
			.map(item -> item.getName().toLowerCase())
			.collect(Collectors.toSet());

		return Rs2Equipment.all()
			.filter(Objects::nonNull)
			.filter(item -> {
				// Check if item is excluded due to teleport filter
				if (excludeTeleportItems && Rs2Walker.isTeleportItem(item.getId())) {
					return false;
				}

				// Check if item matches any setup item by ID
				if (setupEquipmentIds.contains(item.getId())) {
					return false;
				}

				// Check if item matches any fuzzy setup item by name
				String itemName = item.getName().toLowerCase();
				for (String setupName : setupEquipmentNames) {
					if (itemName.contains(setupName)) {
						return false;
					}
				}

				return true; // Equipment is not in setup
			})
			.collect(Collectors.toList());
	}

	/**
	 * Banks all items in the inventory that are not part of the inventory setup.
	 * Optionally excludes teleportation items from banking.
	 * 
	 * @param excludeTeleportItems Whether to exclude teleportation items from banking
	 * @return true if banking was successful, false otherwise
	 */
	public boolean bankItemsNotInInventorySetup(boolean excludeTeleportItems) {
		List<Rs2ItemModel> itemsToBank = getItemsNotInInventorySetup(excludeTeleportItems);
		
		if (itemsToBank.isEmpty()) {
			Microbot.log("No items to bank from inventory - all items match setup", Level.DEBUG);
			return true;
		}

		// Ensure bank is open
		if (!Rs2Bank.isOpen()) {
			Microbot.log("Bank must be open to deposit items not in setup", Level.WARN);
			return false;
		}

		Microbot.log("Banking {} items not in inventory setup (teleport exclusion: {})", 
				itemsToBank.size(), excludeTeleportItems, Level.INFO);

		for (Rs2ItemModel item : itemsToBank) {
			if (isMainSchedulerCancelled()) {
				return false;
			}

			try {
				Microbot.log("Depositing item not in setup: {}", item.getName(), Level.DEBUG);
				Rs2Bank.depositAll(item.getId());
				sleep(Rs2Random.between(200, 400));
			} catch (Exception e) {
				Microbot.log("Failed to deposit item {}: {}", item.getName(), e.getMessage(), Level.WARN);
			}
		}

		return true;
	}

	/**
	 * Banks all equipped items that are not part of the equipment setup.
	 * Optionally excludes teleportation items from banking.
	 * 
	 * @param excludeTeleportItems Whether to exclude teleportation items from banking
	 * @return true if banking was successful, false otherwise
	 */
	public boolean bankEquipmentNotInSetup(boolean excludeTeleportItems) {
		List<Rs2ItemModel> equipmentToBank = getEquipmentNotInSetup(excludeTeleportItems);
		
		if (equipmentToBank.isEmpty()) {
			Microbot.log("No equipment to bank - all equipment matches setup", Level.DEBUG);
			return true;
		}

		// Ensure bank is open
		if (!Rs2Bank.isOpen()) {
			Microbot.log("Bank must be open to deposit equipment not in setup", Level.WARN);
			return false;
		}

		Microbot.log("Banking {} equipment items not in setup (teleport exclusion: {})", 
				equipmentToBank.size(), excludeTeleportItems, Level.INFO);

		for (Rs2ItemModel item : equipmentToBank) {
			if (isMainSchedulerCancelled()) {
				return false;
			}

			try {
				Microbot.log("Depositing equipment not in setup: {}", item.getName(), Level.DEBUG);
				// Remove equipment first, then deposit
				Rs2Equipment.unEquip(item.getId());
				sleep(Rs2Random.between(300, 500));
				if (Rs2Inventory.hasItem(item.getId())) {
					Rs2Bank.depositAll(item.getId());
					sleep(Rs2Random.between(200, 400));
				}
			} catch (Exception e) {
				Microbot.log("Failed to deposit equipment {}: {}", item.getName(), e.getMessage(), Level.WARN);
			}
		}

		return true;
	}

	/**
	 * Banks all items (both inventory and equipment) that are not part of the setup.
	 * Convenience method that calls both banking functions.
	 * 
	 * @param excludeTeleportItems Whether to exclude teleportation items from banking
	 * @return true if both inventory and equipment banking was successful, false otherwise
	 */
	public boolean bankAllItemsNotInSetup(boolean excludeTeleportItems) {
		boolean inventorySuccess = bankItemsNotInInventorySetup(excludeTeleportItems);
		boolean equipmentSuccess = bankEquipmentNotInSetup(excludeTeleportItems);
		return inventorySuccess && equipmentSuccess;
	}

	/**
	 * Checks if there are any items in inventory or equipment that are not part of the setup.
	 * This is useful for validating if the current state matches the desired setup.
	 * 
	 * @param excludeTeleportItems Whether to exclude teleportation items from the check
	 * @return true if there are items not in the setup, false if everything matches the setup
	 */
	public boolean hasNotInventorySetup(boolean excludeTeleportItems) {
		List<Rs2ItemModel> inventoryNotInSetup = getItemsNotInInventorySetup(excludeTeleportItems);
		List<Rs2ItemModel> equipmentNotInSetup = getEquipmentNotInSetup(excludeTeleportItems);
		
		return !inventoryNotInSetup.isEmpty() || !equipmentNotInSetup.isEmpty();
	}

}
