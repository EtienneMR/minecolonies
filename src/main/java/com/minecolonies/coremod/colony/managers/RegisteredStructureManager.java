package com.minecolonies.coremod.colony.managers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyTagCapability;
import com.minecolonies.api.colony.buildings.*;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.buildings.registry.IBuildingDataManager;
import com.minecolonies.api.colony.buildings.workerbuildings.ITownHall;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.managers.interfaces.IRegisteredStructureManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.quests.IQuestInstance;
import com.minecolonies.api.quests.IQuestManager;
import com.minecolonies.api.quests.IQuestObjectiveTemplate;
import com.minecolonies.api.tileentities.AbstractScarecrowTileEntity;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.BlockPosUtil;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.Network;
import com.minecolonies.coremod.blocks.huts.BlockHutTavern;
import com.minecolonies.coremod.blocks.huts.BlockHutTownHall;
import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.buildings.BuildingMysticalSite;
import com.minecolonies.coremod.colony.buildings.modules.LivingBuildingModule;
import com.minecolonies.coremod.colony.buildings.modules.TavernBuildingModule;
import com.minecolonies.coremod.colony.buildings.workerbuildings.*;
import com.minecolonies.coremod.entity.ai.citizen.builder.ConstructionTapeHelper;
import com.minecolonies.coremod.network.messages.client.colony.ColonyViewBuildingViewMessage;
import com.minecolonies.coremod.network.messages.client.colony.ColonyViewRemoveBuildingMessage;
import com.minecolonies.coremod.quests.objectives.IBuildingUpgradeObjectiveTemplate;
import com.minecolonies.coremod.tileentities.ScarecrowTileEntity;
import com.minecolonies.coremod.tileentities.TileEntityDecorationController;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

import static com.minecolonies.api.colony.IColony.CLOSE_COLONY_CAP;
import static com.minecolonies.api.util.MathUtils.RANDOM;
import static com.minecolonies.api.util.constant.NbtTagConstants.*;
import static com.minecolonies.api.util.constant.TranslationConstants.WARNING_DUPLICATE_TAVERN;
import static com.minecolonies.api.util.constant.TranslationConstants.WARNING_DUPLICATE_TOWN_HALL;

public class RegisteredStructureManager implements IRegisteredStructureManager
{
    /**
     * Mine block objective tracker.
     */
    private static final Map<BuildingEntry, List<IQuestInstance>> buildBuildingObjectives = new HashMap<>();

    /**
     * List of building in the colony.
     */
    @NotNull
    private ImmutableMap<BlockPos, IBuilding> buildings = ImmutableMap.of();

    /**
     * List of fields of the colony.
     */
    private final List<BlockPos> fields = new ArrayList<>();

    /**
     * The warehouse building position. Initially null.
     */
    private final List<IWareHouse> wareHouses = new ArrayList<>();

    /**
     * The warehouse building position. Initially null.
     */
    private final List<IMysticalSite> mysticalSites = new ArrayList<>();

    /**
     * List of leisure sites.
     */
    private ImmutableList<BlockPos> leisureSites = ImmutableList.of();

    /**
     * The townhall of the colony.
     */
    @Nullable
    private ITownHall townHall;

    /**
     * Variable to check if the buildings needs to be synched.
     */
    private boolean isBuildingsDirty = false;

    /**
     * Variable to check if the fields needs to be synched.
     */
    private boolean isFieldsDirty = false;

    /**
     * The colony of the manager.
     */
    private final Colony colony;

    /**
     * Max chunk pos where a building is placed into a certain direction.
     */
    private int minChunkX;
    private int maxChunkX;
    private int minChunkZ;
    private int maxChunkZ;

    /**
     * Creates the BuildingManager for a colony.
     *
     * @param colony the colony.
     */
    public RegisteredStructureManager(final Colony colony)
    {
        this.colony = colony;
    }

    @Override
    public void read(@NotNull final CompoundTag compound)
    {
        buildings = ImmutableMap.of();
        maxChunkX = colony.getCenter().getX() >> 4;
        minChunkX = colony.getCenter().getX() >> 4;
        maxChunkZ = colony.getCenter().getZ() >> 4;
        minChunkZ = colony.getCenter().getZ() >> 4;

        //  Buildings
        final ListTag buildingTagList = compound.getList(TAG_BUILDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingTagList.size(); ++i)
        {
            final CompoundTag buildingCompound = buildingTagList.getCompound(i);
            @Nullable final IBuilding b = IBuildingDataManager.getInstance().createFrom(colony, buildingCompound);
            if (b != null)
            {
                addBuilding(b);
                setMaxChunk(b);
            }
        }

        if (compound.contains(TAG_NEW_FIELDS))
        {
            // Fields before Buildings, because the Farmer needs them.
            final ListTag fieldTagList = compound.getList(TAG_NEW_FIELDS, Tag.TAG_COMPOUND);
            for (int i = 0; i < fieldTagList.size(); ++i)
            {
                addField(BlockPosUtil.read(fieldTagList.getCompound(i), TAG_POS));
            }
        }

        if (compound.contains(TAG_LEISURE))
        {
            final ListTag leisureTagList = compound.getList(TAG_LEISURE, Tag.TAG_COMPOUND);
            final List<BlockPos> leisureSitesList = new ArrayList<>();
            for (int i = 0; i < leisureTagList.size(); ++i)
            {
                final BlockPos pos = BlockPosUtil.read(leisureTagList.getCompound(i), TAG_POS);
                if (!leisureSitesList.contains(pos))
                {
                    leisureSitesList.add(pos);
                }
            }
            leisureSites = ImmutableList.copyOf(leisureSitesList);
        }
    }

    /**
     * Set the max chunk direction this building is in.
     *
     * @param b the max chunk dir.
     */
    private void setMaxChunk(final IBuilding b)
    {
        final int chunkX = b.getPosition().getX() >> 4;
        final int chunkZ = b.getPosition().getZ() >> 4;
        if (chunkX >= maxChunkX)
        {
            maxChunkX = chunkX + 1;
        }

        if (chunkX <= minChunkX)
        {
            minChunkX = chunkX - 1;
        }

        if (chunkZ >= maxChunkZ)
        {
            maxChunkZ = chunkZ + 1;
        }

        if (chunkZ <= minChunkZ)
        {
            minChunkZ = chunkZ - 1;
        }
    }

    @Override
    public void write(@NotNull final CompoundTag compound)
    {
        //  Buildings
        @NotNull final ListTag buildingTagList = new ListTag();
        for (@NotNull final IBuilding b : buildings.values())
        {
            @NotNull final CompoundTag buildingCompound = b.serializeNBT();
            buildingTagList.add(buildingCompound);
        }
        compound.put(TAG_BUILDINGS, buildingTagList);

        // Fields
        @NotNull final ListTag fieldTagList = new ListTag();
        for (@NotNull final BlockPos pos : fields)
        {
            @NotNull final CompoundTag fieldCompound = new CompoundTag();
            BlockPosUtil.write(fieldCompound, TAG_POS, pos);
            fieldTagList.add(fieldCompound);
        }
        compound.put(TAG_NEW_FIELDS, fieldTagList);

        // Leisure sites
        @NotNull final ListTag leisureTagList = new ListTag();
        for (@NotNull final BlockPos pos : leisureSites)
        {
            @NotNull final CompoundTag leisureCompound = new CompoundTag();
            BlockPosUtil.write(leisureCompound, TAG_POS, pos);
            leisureTagList.add(leisureCompound);
        }
        compound.put(TAG_LEISURE, leisureTagList);
    }

    @Override
    public void clearDirty()
    {
        isBuildingsDirty = false;
        buildings.values().forEach(IBuilding::clearDirty);
    }

    @Override
    public void sendPackets(final Set<ServerPlayer> closeSubscribers, final Set<ServerPlayer> newSubscribers)
    {
        sendBuildingPackets(closeSubscribers, newSubscribers);
        sendFieldPackets(closeSubscribers, newSubscribers);
        isBuildingsDirty = false;
        isFieldsDirty = false;
    }

    @Override
    public void onColonyTick(final IColony colony)
    {
        //  Tick Buildings
        for (@NotNull final IBuilding building : buildings.values())
        {
            if (WorldUtil.isBlockLoaded(colony.getWorld(), building.getPosition()))
            {
                building.onColonyTick(colony);
            }
        }
    }

    @Override
    public void markBuildingsDirty()
    {
        isBuildingsDirty = true;
    }

    @Override
    public void cleanUpBuildings(@NotNull final IColony colony)
    {
        @Nullable final List<IBuilding> removedBuildings = new ArrayList<>();

        //Need this list, we may enter here while we add a building in the real world.
        final List<IBuilding> tempBuildings = new ArrayList<>(buildings.values());

        for (@NotNull final IBuilding building : tempBuildings)
        {
            final BlockPos loc = building.getPosition();
            if (WorldUtil.isBlockLoaded(colony.getWorld(), loc) && !building.isMatchingBlock(colony.getWorld().getBlockState(loc).getBlock()))
            {
                //  Sanity cleanup
                removedBuildings.add(building);
            }
        }

        @NotNull final ArrayList<BlockPos> tempFields = new ArrayList<>(fields);

        for (@NotNull final BlockPos pos : tempFields)
        {
            if (WorldUtil.isBlockLoaded(colony.getWorld(), pos))
            {
                if (!(colony.getWorld().getBlockEntity(pos) instanceof ScarecrowTileEntity))
                {
                    removeField(pos);
                }
            }
        }

        for (@NotNull final BlockPos pos : leisureSites)
        {
            if (WorldUtil.isBlockLoaded(colony.getWorld(), pos))
            {
                if (!(colony.getWorld().getBlockEntity(pos) instanceof TileEntityDecorationController))
                {
                    removeLeisureSite(pos);
                }
            }
        }

        if (!removedBuildings.isEmpty() && removedBuildings.size() >= buildings.values().size())
        {
            Log.getLogger()
              .warn("Colony:" + colony.getID()
                      + " is removing all buildings at once. Did you just load a backup? If not there is a chance that colony data got corrupted and you want to restore a backup.");
        }

        removedBuildings.forEach(IBuilding::destroy);
    }

    @Override
    public IBuilding getBuilding(final BlockPos buildingId)
    {
        if (buildingId != null)
        {
            return buildings.get(buildingId);
        }
        return null;
    }

    @Override
    public List<BlockPos> getLeisureSites()
    {
        return leisureSites;
    }
    
    @Override
    public BlockPos getRandomLeisureSite()
    {
        BlockPos pos = null;
        final int randomDist = RANDOM.nextInt(4);
        if (randomDist < 1)
        {
            pos = getFirstBuildingMatching(b -> b instanceof BuildingTownHall && b.getBuildingLevel() >= 3);
            if (pos != null)
            {
                return pos;
            }
        }

        if (randomDist < 2)
        {
            if (RANDOM.nextBoolean())
            {
                pos = getFirstBuildingMatching(b -> b instanceof BuildingMysticalSite && b.getBuildingLevel() >= 1);
                if (pos != null)
                {
                    return pos;
                }
            }
            else
            {
                pos = getFirstBuildingMatching(b -> b instanceof BuildingLibrary && b.getBuildingLevel() >= 1);
                if (pos != null)
                {
                    return pos;
                }
            }
        }

        if (randomDist < 3)
        {
            pos = getFirstBuildingMatching(b -> b.hasModule(TavernBuildingModule.class) && b.getBuildingLevel() >= 1);
            if (pos != null)
            {
                return pos;
            }
        }

        return leisureSites.isEmpty() ? null : leisureSites.get(RANDOM.nextInt(leisureSites.size()));
    }

    @Nullable
    @Override
    public BlockPos getFirstBuildingMatching(final Predicate<IBuilding> predicate)
    {
        for (final IBuilding building : buildings.values())
        {
            if (predicate.test(building))
            {
                return building.getPosition();
            }
        }
        return null;
    }

    @Override
    public void addLeisureSite(final BlockPos pos)
    {
        final List<BlockPos> tempList = new ArrayList<>(leisureSites);
        if (!tempList.contains(pos))
        {
            tempList.add(pos);
            this.leisureSites = ImmutableList.copyOf(tempList);
            markBuildingsDirty();
        }
    }

    @Override
    public void removeLeisureSite(final BlockPos pos)
    {
        if (leisureSites.contains(pos))
        {
            final List<BlockPos> tempList = new ArrayList<>(leisureSites);
            tempList.remove(pos);
            this.leisureSites = ImmutableList.copyOf(tempList);
            markBuildingsDirty();
        }
    }

    @Nullable
    @Override
    public IWareHouse getClosestWarehouseInColony(final BlockPos pos)
    {
        IWareHouse wareHouse = null;
        double dist = 0;
        for (final IWareHouse building : wareHouses)
        {
            if (building.getBuildingLevel() > 0 && building.getTileEntity() != null)
            {
                final double tempDist = building.getPosition().distSqr(pos);
                if (wareHouse == null || tempDist < dist)
                {
                    dist = tempDist;
                    wareHouse = building;
                }
            }
        }

        return wareHouse;
    }

    @Override
    public boolean isWithinBuildingZone(final LevelChunk chunk)
    {
        final IColonyTagCapability cap = chunk.getCapability(CLOSE_COLONY_CAP, null).resolve().orElse(null);
        if (cap != null)
        {
            final Set<BlockPos> capList = cap.getAllClaimingBuildings().get(colony.getID());
            return capList != null && capList.size() >= MineColonies.getConfig().getServer().colonyLoadStrictness.get();
        }

        return false;
    }

    @Override
    public IBuilding getHouseWithSpareBed()
    {
        for (final IBuilding building : buildings.values())
        {
            if (building.hasModule(LivingBuildingModule.class))
            {
                final LivingBuildingModule module = building.getFirstModuleOccurance(LivingBuildingModule.class);
                if (HiringMode.LOCKED.equals(module.getHiringMode()))
                {
                    continue;
                }
                if (module.getAssignedCitizen().size() < module.getModuleMax())
                {
                    return building;
                }
            }
        }
        return null;
    }

    @NotNull
    @Override
    public Map<BlockPos, IBuilding> getBuildings()
    {
        return buildings;
    }

    @Nullable
    @Override
    public ITownHall getTownHall()
    {
        return townHall;
    }

    @Override
    public int getMysticalSiteMaxBuildingLevel()
    {
        int maxLevel = 0;
        if (hasMysticalSite())
        {
            for (final IMysticalSite mysticalSite : mysticalSites)
            {
                if (mysticalSite.getBuildingLevel() > maxLevel)
                {
                    maxLevel = mysticalSite.getBuildingLevel();
                }
            }
        }
        return maxLevel;
    }

    @Override
    public boolean hasWarehouse()
    {
        return !wareHouses.isEmpty();
    }

    @Override
    public boolean hasMysticalSite()
    {
        return !mysticalSites.isEmpty();
    }

    @Override
    public boolean hasTownHall()
    {
        return townHall != null;
    }

    @NotNull
    @Override
    public List<BlockPos> getFields()
    {
        return Collections.unmodifiableList(fields);
    }

    @Override
    public void addNewField(final AbstractScarecrowTileEntity tileEntity, final BlockPos pos, final Level world)
    {
        addField(pos);
        markFieldsDirty();
    }

    @Override
    public <B extends IBuilding> B getBuilding(final BlockPos buildingId, @NotNull final Class<B> type)
    {
        try
        {
            return type.cast(buildings.get(buildingId));
        }
        catch (final ClassCastException e)
        {
            Log.getLogger().warn("getBuilding called with wrong type: ", e);
            return null;
        }
    }

    @Override
    public ScarecrowTileEntity getFreeField(final int owner, final Level world)
    {
        for (@NotNull final BlockPos pos : fields)
        {
            final BlockEntity field = world.getBlockEntity(pos);
            if (field instanceof ScarecrowTileEntity && !((ScarecrowTileEntity) field).isTaken())
            {
                return (ScarecrowTileEntity) field;
            }
        }
        return null;
    }

    @Override
    public IBuilding addNewBuilding(@NotNull final AbstractTileEntityColonyBuilding tileEntity, final Level world)
    {
        tileEntity.setColony(colony);
        if (!buildings.containsKey(tileEntity.getPosition()))
        {
            @Nullable final IBuilding building = IBuildingDataManager.getInstance().createFrom(colony, tileEntity);
            if (building != null)
            {
                addBuilding(building);
                tileEntity.setBuilding(building);
                building.upgradeBuildingLevelToSchematicData();

                Log.getLogger().info(String.format("Colony %d - new AbstractBuilding for %s at %s",
                  colony.getID(),
                  tileEntity.getBlockState().getClass(),
                  tileEntity.getPosition()));

                building.setIsMirrored(tileEntity.isMirrored());
                if (tileEntity.getStructurePack() != null)
                {
                    building.setStructurePack(tileEntity.getStructurePack().getName());
                    building.setBlueprintPath(tileEntity.getBlueprintPath());
                }
                else
                {
                    building.setStructurePack(colony.getStructurePack());
                }

                if (world != null && !(building instanceof IRSComponent))
                {
                    building.onPlacement();
                    ConstructionTapeHelper.placeConstructionTape(building, world);
                }

                colony.getRequestManager().onProviderAddedToColony(building);

                setMaxChunk(building);
            }
            else
            {
                Log.getLogger().error(String.format("Colony %d unable to create AbstractBuilding for %s at %s",
                  colony.getID(),
                  tileEntity.getBlockState().getClass(),
                  tileEntity.getPosition()), new Exception());
            }

            colony.getCitizenManager().calculateMaxCitizens();
            colony.getPackageManager().updateSubscribers();
            return building;
        }
        return null;
    }

    @Override
    public void removeBuilding(@NotNull final IBuilding building, final Set<ServerPlayer> subscribers)
    {
        if (buildings.containsKey(building.getID()))
        {
            final ImmutableMap.Builder<BlockPos, IBuilding> builder = new ImmutableMap.Builder<>();
            for (final IBuilding tbuilding : buildings.values())
            {
                if (tbuilding != building)
                {
                    builder.put(tbuilding.getID(), tbuilding);
                }
            }

            buildings = builder.build();

            for (final ServerPlayer player : subscribers)
            {
                Network.getNetwork().sendToPlayer(new ColonyViewRemoveBuildingMessage(colony, building.getID()), player);
            }

            Log.getLogger().info(String.format("Colony %d - removed AbstractBuilding %s of type %s",
              colony.getID(),
              building.getID(),
              building.getSchematicName()));
        }

        if (building instanceof BuildingTownHall)
        {
            townHall = null;
        }
        else if (building instanceof BuildingWareHouse)
        {
            wareHouses.remove(building);
        }
        else if (building instanceof BuildingMysticalSite)
        {
            mysticalSites.remove(building);
        }

        //Allow Citizens to fix up any data that wasn't fixed up by the AbstractBuilding's own onDestroyed
        for (@NotNull final ICitizenData citizen : colony.getCitizenManager().getCitizens())
        {
            citizen.onRemoveBuilding(building);
            building.cancelAllRequestsOfCitizen(citizen);
        }

        colony.getRequestManager().onProviderRemovedFromColony(building);
        colony.getRequestManager().onRequesterRemovedFromColony(building.getRequester());

        colony.getCitizenManager().calculateMaxCitizens();
    }

    @Override
    public void removeField(final BlockPos pos)
    {
        this.markFieldsDirty();
        fields.remove(pos);
        colony.markDirty();
    }

    @Override
    public BlockPos getBestBuilding(final AbstractEntityCitizen citizen, final Class<? extends IBuilding> clazz)
    {
        return getBestBuilding(citizen.blockPosition(), clazz);
    }

    @Override
    public BlockPos getBestBuilding(final BlockPos citizen, final Class<? extends IBuilding> clazz)
    {
        double distance = Double.MAX_VALUE;
        BlockPos goodCook = null;
        for (final IBuilding building : buildings.values())
        {
            if (clazz.isInstance(building) && building.getBuildingLevel() > 0)
            {
                final double localDistance = building.getPosition().distSqr(citizen);
                if (localDistance < distance)
                {
                    distance = localDistance;
                    goodCook = building.getPosition();
                }
            }
        }
        return goodCook;
    }

    @Override
    public BlockPos getRandomBuilding(Predicate<IBuilding> filterPredicate)
    {
        final List<IBuilding> allowedBuildings = new ArrayList<>();
        for (final IBuilding building : buildings.values())
        {
            if (filterPredicate.test(building))
            {
                allowedBuildings.add(building);
            }
        }

        if (allowedBuildings.isEmpty())
        {
            return null;
        }

        return allowedBuildings.get(RANDOM.nextInt(allowedBuildings.size())).getPosition();
    }

    /**
     * Finds whether there is a guard building close to the given building
     *
     * @param building the building to check.
     * @return false if no guard tower close, true in other cases
     */
    @Override
    public boolean hasGuardBuildingNear(final IBuilding building)
    {
        if (building == null)
        {
            return true;
        }

        for (final IBuilding colonyBuilding : getBuildings().values())
        {
            if (colonyBuilding instanceof IGuardBuilding || colonyBuilding instanceof BuildingBarracks)
            {
                final BoundingBox guardedRegion = BlockPosUtil.getChunkAlignedBB(colonyBuilding.getPosition(), colonyBuilding.getClaimRadius(colonyBuilding.getBuildingLevel()));
                if (guardedRegion.isInside(building.getPosition()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void guardBuildingChangedAt(final IBuilding guardBuilding, final int newLevel)
    {
        final int claimRadius = guardBuilding.getClaimRadius(Math.max(guardBuilding.getBuildingLevel(), newLevel));
        final BoundingBox guardedRegion = BlockPosUtil.getChunkAlignedBB(guardBuilding.getPosition(), claimRadius);
        for (final IBuilding building : getBuildings().values())
        {
            if (guardedRegion.isInside(building.getPosition()))
            {
                building.resetGuardBuildingNear();
            }
        }
    }

    @Override
    public void setTownHall(@Nullable final ITownHall building)
    {
        this.townHall = building;
    }

    @Override
    public List<IWareHouse> getWareHouses()
    {
        return wareHouses;
    }

    @Override
    public void removeWareHouse(final IWareHouse wareHouse)
    {
        wareHouses.remove(wareHouse);
    }

    @Override
    public List<IMysticalSite> getMysticalSites()
    {
        return mysticalSites;
    }

    @Override
    public void removeMysticalSite(final IMysticalSite mysticalSite)
    {
        mysticalSites.remove(mysticalSite);
    }

    /**
     * Updates all subscribers of fields etc.
     */
    private void markFieldsDirty()
    {
        isFieldsDirty = true;
    }

    /**
     * Add a AbstractBuilding to the Colony.
     *
     * @param building AbstractBuilding to add to the colony.
     */
    private void addBuilding(@NotNull final IBuilding building)
    {
        buildings = new ImmutableMap.Builder<BlockPos, IBuilding>().putAll(buildings).put(building.getID(), building).build();

        building.markDirty();

        //  Limit 1 town hall
        if (building instanceof BuildingTownHall && townHall == null)
        {
            townHall = (ITownHall) building;
        }

        if (building instanceof BuildingWareHouse)
        {
            wareHouses.add((IWareHouse) building);
        }
        else if (building instanceof BuildingMysticalSite)
        {
            mysticalSites.add((IMysticalSite) building);
        }
    }

    /**
     * Sends packages to update the buildings.
     *
     * @param closeSubscribers the current event subscribers.
     * @param newSubscribers   the new event subscribers.
     */
    private void sendBuildingPackets(final Set<ServerPlayer> closeSubscribers, final Set<ServerPlayer> newSubscribers)
    {
        if (isBuildingsDirty || !newSubscribers.isEmpty())
        {
            final Set<ServerPlayer> players = new HashSet<>();
            if (isBuildingsDirty)
            {
                players.addAll(closeSubscribers);
            }
            players.addAll(newSubscribers);
            for (@NotNull final IBuilding building : buildings.values())
            {
                if (building.isDirty() || !newSubscribers.isEmpty())
                {
                    players.forEach(player -> Network.getNetwork().sendToPlayer(new ColonyViewBuildingViewMessage(building), player));
                }
            }
        }
    }

    /**
     * Sends packages to update the fields.
     *
     * @param closeSubscribers the current event subscribers.
     * @param newSubscribers   the new event subscribers.
     */
    private void sendFieldPackets(final Set<ServerPlayer> closeSubscribers, final Set<ServerPlayer> newSubscribers)
    {
        if (isFieldsDirty || !newSubscribers.isEmpty())
        {
            final Set<ServerPlayer> players = new HashSet<>();
            if (isFieldsDirty)
            {
                players.addAll(closeSubscribers);
            }
            players.addAll(newSubscribers);
            for (final IBuilding building : buildings.values())
            {
                if (building instanceof BuildingFarmer)
                {
                    players.forEach(player -> Network.getNetwork().sendToPlayer(new ColonyViewBuildingViewMessage(building), player));
                }
            }
        }
    }

    /**
     * Add a Field to the Colony.
     *
     * @param pos Field position to add to the colony.
     */
    private void addField(@NotNull final BlockPos pos)
    {
        if (!fields.contains(pos))
        {
            fields.add(pos);
        }
        colony.markDirty();
    }

    @Override
    public boolean canPlaceAt(final Block block, final BlockPos pos, final Player player)
    {
        if (block instanceof BlockHutTownHall)
        {
            if (colony.hasTownHall())
            {
                if (colony.getWorld() != null && !colony.getWorld().isClientSide)
                {
                    MessageUtils.format(WARNING_DUPLICATE_TOWN_HALL).sendTo(player);
                }
                return false;
            }
            return true;
        }
        else if (block instanceof BlockHutTavern)
        {
            for (final IBuilding building : buildings.values())
            {
                if (building.hasModule(TavernBuildingModule.class))
                {
                    MessageUtils.format(WARNING_DUPLICATE_TAVERN).sendTo(player);
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void onBuildingUpgradeComplete(@Nullable final IBuilding building, final int level)
    {
        if (building != null)
        {
            colony.getCitizenManager().calculateMaxCitizens();
            markBuildingsDirty();
            if (buildBuildingObjectives.containsKey(building.getBuildingType()))
            {
                for (final IQuestInstance instance : buildBuildingObjectives.get(building.getBuildingType()))
                {
                    final IQuestObjectiveTemplate objective = IQuestManager.GLOBAL_SERVER_QUESTS.get(instance.getId()).getObjective(instance.getIndex());
                    if (objective instanceof IBuildingUpgradeObjectiveTemplate)
                    {
                        ((IBuildingUpgradeObjectiveTemplate) objective).onBuildingUpgrade(instance.getCurrentObjectiveInstance(), instance, level);
                    }
                }
            }
        }
    }

    @Override
    public void trackBuildingLevelUp(final @NotNull BuildingEntry buildingEntry, final @NotNull IQuestInstance colonyQuest)
    {
        final List<IQuestInstance> currentMap = buildBuildingObjectives.getOrDefault(buildingEntry, new ArrayList<>());
        currentMap.add(colonyQuest);
        buildBuildingObjectives.put(buildingEntry, currentMap);
    }

    @Override
    public void stopTrackingBuildingLevelUp(final @NotNull BuildingEntry buildingEntry, final @NotNull IQuestInstance colonyQuest)
    {
        buildBuildingObjectives.getOrDefault(buildingEntry, new ArrayList<>()).remove(colonyQuest);
    }
}
