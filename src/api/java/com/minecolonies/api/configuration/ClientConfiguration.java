package com.minecolonies.api.configuration;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod client configuration. Loaded clientside, not synced.
 */
public class ClientConfiguration extends AbstractConfiguration
{
    public final ForgeConfigSpec.BooleanValue citizenVoices;
    public final ForgeConfigSpec.BooleanValue neighborbuildingrendering;
    public final ForgeConfigSpec.BooleanValue colonyteamborders;

    /**
     * Builds client configuration.
     *
     * @param builder config builder
     */
    protected ClientConfiguration(final ForgeConfigSpec.Builder builder)
    {
        createCategory(builder, "gameplay");
        citizenVoices = defineBoolean(builder, "enablecitizenvoices", true);
        neighborbuildingrendering = defineBoolean(builder, "neighborbuildingrendering", true);
        colonyteamborders = defineBoolean(builder, "colonyteamborders", true);

        swapToCategory(builder, "pathfinding");

        finishCategory(builder);
    }
}
