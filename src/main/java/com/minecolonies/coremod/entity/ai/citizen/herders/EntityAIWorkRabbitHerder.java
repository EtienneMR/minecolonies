package com.minecolonies.coremod.entity.ai.citizen.herders;

import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.constant.TranslationConstants;
import com.minecolonies.coremod.colony.buildings.workerbuildings.BuildingRabbitHutch;
import com.minecolonies.coremod.colony.jobs.JobRabbitHerder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.IDLE;
import static com.minecolonies.api.util.constant.Constants.ONE_HUNDRED_PERCENT;

/**
 * The AI behind the {@link JobRabbitHerder} for Breeding and Killing Rabbits.
 */
public class EntityAIWorkRabbitHerder extends AbstractEntityAIHerder<JobRabbitHerder, BuildingRabbitHutch>
{
    /**
     * Carrot render meta data.
     */
    public static final String RENDER_META_CARROT = "carrot";

    /**
     * Creates the abstract part of the AI. Always use this constructor!
     *
     * @param job the job to fulfill
     */
    public EntityAIWorkRabbitHerder(@NotNull final JobRabbitHerder job)
    {
        super(job);
    }

    @Override
    protected void updateRenderMetaData()
    {
        String renderMeta = getState() == IDLE ? "" : RENDER_META_WORKING;
        if (worker.getCitizenInventoryHandler().hasItemInInventory(Items.CARROT))
        {
            renderMeta += RENDER_META_CARROT;
        }
        worker.setRenderMetadata(renderMeta);
    }

    @Override
    public Class<BuildingRabbitHutch> getExpectedBuildingClass()
    {
        return BuildingRabbitHutch.class;
    }

    @Override
    protected void butcherAnimal(@Nullable final Animal animal)
    {
        worker.getCitizenStatusHandler().setLatestStatus(Component.translatable(TranslationConstants.COM_MINECOLONIES_COREMOD_STATUS_HERDER_BUTCHERING));
        if (animal != null && !walkingToAnimal(animal) && !ItemStackUtils.isEmpty(worker.getMainHandItem()))
        {
            worker.swing(InteractionHand.MAIN_HAND);

            if (worker.getRandom().nextInt(1 + (ONE_HUNDRED_PERCENT - getPrimarySkillLevel()) / 5) <= 1)
            {
                final FakePlayer fp = FakePlayerFactory.getMinecraft((ServerLevel) worker.getCommandSenderWorld());
                final DamageSource ds = DamageSource.playerAttack(fp);
                animal.hurt(ds, (float) getButcheringAttackDamage());
                worker.getCitizenItemHandler().damageItemInHand(InteractionHand.MAIN_HAND, 1);
            }
        }
    }
}
