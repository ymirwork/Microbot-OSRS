package net.runelite.client.plugins.microbot.gabplugs.sandminer;

import net.runelite.client.config.*;

@ConfigGroup("GabulhasSandMiner")
@ConfigInformation(
        "Mines sandstone and deposits it in the Quarry.<br/><br/>"+
        "Just start next to the grinder with a pickaxe. <br/><br/>"+
        "The scripts supports handling the heat using waterskins +"+
        "humidify spell (Lunar spells) or using the circlet of water.<br/><br/>"+
        "<b>Turbo mode enables the plugin to mine and deposit faster, without clicking on minimap to walk, "+
        "clicking directly on the ideal ore or grinder instead and also removes long pauses between actions. "+
        "Use at your own risk, some may see 'turbo' mode as how a normal player would mine sandstone, some don't.</b><br/><br/>"+
        "If you get below 25% health, it will bank and logout for safety.<br/><br/>Updated by TaF and Bolado"
)
public interface GabulhasSandMinerConfig extends Config {
    @ConfigSection(
            name = "Settings",
            description = "Settings for the Sand Miner plugin.",
            position = 0
    )
    String settingsSection = "settingsSection";

    @ConfigItem(
            keyName = "turboMode",
            name = "Turbo Mode",
            description = "Enables Turbo Mode for faster mining and depositing. Can look more like how someone would sand mine.",
            position = 0,
            section = settingsSection
    )
    default boolean turboMode() {
        return false;
    }

    @ConfigItem(
            keyName = "useHumidify",
            name = "Use Humidify",
            description = "Enables the use of the Humidify spell to keep your waterskins full. Requires the Lunar spellbook to be active.",
            position = 1,
            section = settingsSection
    )
    default boolean useHumidify() {
        return false;
    }

    @ConfigSection(
            name = "Starting State",
            description = "Starting State",
            position = 1
    )
    String startingStateSection = "startingStateSection";

    @ConfigItem(
            keyName = "startingState",
            name = "(Debug) Starting State",
            description = "Starting State. Only used for development.",
            position = 1,
            section = startingStateSection
    )
    default GabulhasSandMinerInfo.states STARTINGSTATE() {
        return GabulhasSandMinerInfo.states.Mining;
    }

}


