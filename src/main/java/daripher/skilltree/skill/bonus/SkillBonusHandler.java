package daripher.skilltree.skill.bonus;

import daripher.skilltree.SkillTreeMod;
import daripher.skilltree.api.EquipmentContainer;
import daripher.skilltree.capability.skill.PlayerSkillsProvider;
import daripher.skilltree.effect.SkillBonusEffect;
import daripher.skilltree.item.ItemBonusProvider;
import daripher.skilltree.item.ItemHelper;
import daripher.skilltree.mixin.AbstractArrowAccessor;
import daripher.skilltree.skill.PassiveSkill;
import daripher.skilltree.skill.bonus.item.FoodHealingBonus;
import daripher.skilltree.skill.bonus.item.ItemBonus;
import daripher.skilltree.skill.bonus.item.ItemSkillBonus;
import daripher.skilltree.skill.bonus.player.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.EntityDamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.StringUtils;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.event.CurioAttributeModifierEvent;

@Mod.EventBusSubscriber(modid = SkillTreeMod.MOD_ID)
public class SkillBonusHandler {
  @SubscribeEvent
  public static void applyBreakSpeedMultiplier(PlayerEvent.BreakSpeed event) {
    Player player = event.getEntity();
    float multiplier =
        getSkillBonuses(player, BlockBreakSpeedBonus.class).stream()
            .filter(bonus -> bonus.getPlayerCondition().met(player))
            .map(BlockBreakSpeedBonus::getMultiplier)
            .reduce(Float::sum)
            .orElse(1f);
    event.setNewSpeed(event.getNewSpeed() * multiplier);
  }

  @SubscribeEvent
  public static void applyFallReductionMultiplier(LivingFallEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    float multiplier = getJumpHeightMultiplier(player);
    if (multiplier <= 1) return;
    event.setDistance(event.getDistance() / multiplier);
  }

  @SubscribeEvent
  public static void applyRepairEfficiency(AnvilUpdateEvent event) {
    Player player = event.getPlayer();
    ItemStack stack = event.getLeft();
    float efficiency =
        getSkillBonuses(player, RepairEfficiencyBonus.class).stream()
            .filter(bonus -> bonus.getItemCondition().met(stack))
            .map(RepairEfficiencyBonus::getMultiplier)
            .reduce(Float::sum)
            .orElse(1f);
    if (efficiency == 1) return;
    if (!stack.isDamageableItem() || !stack.isDamaged()) return;
    ItemStack material = event.getRight();
    if (!stack.getItem().isValidRepairItem(stack, material)) return;
    ItemStack result = stack.copy();
    int durabilityPerMaterial = (int) (result.getMaxDamage() * 12 * (1 + efficiency) / 100);
    int durabilityRestored = durabilityPerMaterial;
    int materialsUsed;
    int cost = 0;
    for (materialsUsed = 0;
        durabilityRestored > 0 && materialsUsed < material.getCount();
        materialsUsed++) {
      result.setDamageValue(result.getDamageValue() - durabilityRestored);
      cost++;
      durabilityRestored = Math.min(result.getDamageValue(), durabilityPerMaterial);
    }
    if (event.getName() != null && !StringUtils.isBlank(event.getName())) {
      if (!event.getName().equals(stack.getHoverName().getString())) {
        cost++;
        result.setHoverName(Component.literal(event.getName()));
      }
    } else if (stack.hasCustomHoverName()) {
      cost++;
      result.resetHoverName();
    }
    event.setMaterialCost(materialsUsed);
    event.setCost(cost);
    event.setOutput(result);
  }

  @SubscribeEvent
  public static void tickSkillBonuses(TickEvent.PlayerTickEvent event) {
    if (event.player.isDeadOrDying()) return;
    if (!(event.player instanceof ServerPlayer player)) return;
    if (event.phase == TickEvent.Phase.END) return;
    getSkillBonuses(player, SkillBonus.Ticking.class).forEach(bonus -> bonus.tick(player));
  }

  @SubscribeEvent(priority = EventPriority.HIGH)
  public static void applyFlatDamageBonus(LivingHurtEvent event) {
    if (!(event.getSource().getEntity() instanceof Player player)) return;
    player.getPersistentData().putInt("LastAttackTarget", event.getEntity().getId());
    float bonus =
        getDamageBonus(
            player, event.getSource(), event.getEntity(), AttributeModifier.Operation.ADDITION);
    event.setAmount(event.getAmount() + bonus);
  }

  @SubscribeEvent
  public static void applyBaseDamageMultipliers(LivingHurtEvent event) {
    if (!(event.getSource().getEntity() instanceof Player player)) return;
    float bonus =
        getDamageBonus(
            player,
            event.getSource(),
            event.getEntity(),
            AttributeModifier.Operation.MULTIPLY_BASE);
    event.setAmount(event.getAmount() * (1 + bonus));
  }

  @SubscribeEvent(priority = EventPriority.LOW)
  public static void applyTotalDamageMultipliers(LivingHurtEvent event) {
    if (!(event.getSource().getEntity() instanceof Player player)) return;
    float bonus =
        getDamageBonus(
            player,
            event.getSource(),
            event.getEntity(),
            AttributeModifier.Operation.MULTIPLY_TOTAL);
    event.setAmount(event.getAmount() * (1 + bonus));
  }

  private static float getDamageBonus(
      Player player,
      DamageSource damageSource,
      LivingEntity target,
      AttributeModifier.Operation operation) {
    List<DamageBonus> bonuses = getSkillBonuses(player, DamageBonus.class);
    float amount = 0;
    for (DamageBonus bonus : bonuses) {
      amount += bonus.getDamageBonus(operation, damageSource, player, target);
    }
    return amount;
  }

  @SubscribeEvent
  public static void applyCritBonuses(CriticalHitEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    if (!(event.getTarget() instanceof LivingEntity target)) return;
    DamageSource damageSource = DamageSource.playerAttack(player);
    float critChance = getCritChance(player, damageSource, event.getEntity());
    if (player.getRandom().nextFloat() >= critChance) return;
    float critMultiplier = getCritDamageMultiplier(player, damageSource, target);
    event.setDamageModifier(event.getDamageModifier() + critMultiplier);
    if (!event.isVanillaCritical()) {
      event.setDamageModifier(event.getDamageModifier() + 0.5F);
      event.setResult(Event.Result.ALLOW);
    }
  }

  @SubscribeEvent(priority = EventPriority.LOW)
  public static void applyCritBonuses(LivingHurtEvent event) {
    // direct damage, ignoring
    if (event.getSource().getClass() == EntityDamageSource.class
        && event.getSource().msgId.equals("player")) return;
    if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
    float critChance = getCritChance(player, event.getSource(), event.getEntity());
    if (player.getRandom().nextFloat() >= critChance) return;
    float critMultiplier = getCritDamageMultiplier(player, event.getSource(), event.getEntity());
    event.setAmount(event.getAmount() * critMultiplier);
  }

  private static float getCritDamageMultiplier(
      ServerPlayer player, DamageSource source, LivingEntity target) {
    List<CritDamageBonus> damageBonuses = getSkillBonuses(player, CritDamageBonus.class);
    float multiplier = 1.5f;
    for (CritDamageBonus bonus : damageBonuses) {
      multiplier += bonus.getDamageBonus(source, player, target);
    }
    return multiplier;
  }

  private static float getCritChance(
      ServerPlayer player, DamageSource source, LivingEntity target) {
    float critChance = 0f;
    List<CritChanceBonus> chanceBonuses = getSkillBonuses(player, CritChanceBonus.class);
    for (CritChanceBonus bonus : chanceBonuses) {
      critChance += bonus.getChanceBonus(source, player, target);
    }
    return critChance;
  }

  @SubscribeEvent
  public static void addAdditionalSocketTooltip(ItemTooltipEvent event) {
    ItemStack stack = event.getItemStack();
    int sockets = ItemHelper.getAdditionalSockets(stack);
    if (sockets > 0) {
      String key = "gem.additional_socket_" + sockets;
      Component socketTooltip = Component.translatable(key).withStyle(ChatFormatting.YELLOW);
      event.getToolTip().add(1, socketTooltip);
    }
  }

  @SubscribeEvent
  public static void setCraftedItemBonus(PlayerEvent.ItemCraftedEvent event) {
    itemCrafted(event.getEntity(), event.getCrafting());
  }

  @SubscribeEvent
  public static void setCraftedItemBonus(PlayerEvent.ItemSmeltedEvent event) {
    itemCrafted(event.getEntity(), event.getSmelting());
  }

  @SubscribeEvent
  public static void applyCraftedItemAttributeBonuses(ItemAttributeModifierEvent event) {
    ItemStack stack = event.getItemStack();
    if (event.getSlotType() != Player.getEquipmentSlotForItem(stack)) return;
    addAttributeModifiers(event::addModifier, stack);
  }

  @SubscribeEvent
  public static void applyCraftedCurioAttributeBonuses(CurioAttributeModifierEvent event) {
    ItemStack stack = event.getItemStack();
    if (!CuriosApi.getCuriosHelper().isStackValid(event.getSlotContext(), stack)) return;
    addAttributeModifiers(event::addModifier, stack);
  }

  @SubscribeEvent
  public static void applyFoodHealing(LivingEntityUseItemEvent.Finish event) {
    ItemStack stack = event.getItem();
    if (stack.getFoodProperties(event.getEntity()) == null) return;
    float healing =
        ItemHelper.getItemBonuses(stack, FoodHealingBonus.class).stream()
            .map(FoodHealingBonus::getAmount)
            .reduce(Float::sum)
            .orElse(0f);
    event.getEntity().heal(healing);
  }

  @SubscribeEvent
  public static void applyIncomingHealingBonus(LivingHealEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    float multiplier = 1f;
    multiplier +=
        getSkillBonuses(player, IncomingHealingBonus.class).stream()
            .map(b -> b.getHealingMultiplier(player))
            .reduce(Float::sum)
            .orElse(1f);
    event.setAmount(event.getAmount() * multiplier);
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void applyLootDuplicationChanceBonus(LivingDropsEvent event) {
    // shouldn't multiply player's loot
    if (event.getEntity() instanceof Player) return;
    if (!(event.getSource().getEntity() instanceof Player player)) return;
    float multiplier = getLootMultiplier(player, LootDuplicationBonus.LootType.MOBS);
    while (multiplier > 1) {
      event.getDrops().addAll(getDrops(event));
      multiplier--;
    }
    if (player.getRandom().nextFloat() < multiplier) {
      event.getDrops().addAll(getDrops(event));
    }
  }

  @SubscribeEvent
  public static void applyExperienceFromMobsBonus(LivingExperienceDropEvent event) {
    Player player = event.getAttackingPlayer();
    if (player == null) return;
    float multiplier = 1f;
    multiplier += getExperienceMultiplier(player, GainedExperienceBonus.ExperienceSource.MOBS);
    event.setDroppedExperience((int) (event.getDroppedExperience() * multiplier));
  }

  @SubscribeEvent
  public static void applyExperienceFromOreBonus(BlockEvent.BreakEvent event) {
    if (!event.getState().is(Tags.Blocks.ORES)) return;
    float multiplier = 1f;
    multiplier +=
        getExperienceMultiplier(event.getPlayer(), GainedExperienceBonus.ExperienceSource.ORE);
    event.setExpToDrop((int) (event.getExpToDrop() * multiplier));
  }

  @SubscribeEvent
  public static void applyFishingExperienceBonus(ItemFishedEvent event) {
    Player player = event.getEntity();
    float multiplier =
        getExperienceMultiplier(player, GainedExperienceBonus.ExperienceSource.FISHING);
    if (multiplier == 0) return;
    int exp = (int) ((player.getRandom().nextInt(6) + 1) * multiplier);
    if (exp == 0) return;
    ExperienceOrb expOrb =
        new ExperienceOrb(
            player.level, player.getX(), player.getY() + 0.5D, player.getZ() + 0.5D, exp);
    player.level.addFreshEntity(expOrb);
  }

  private static float getExperienceMultiplier(
      Player player, GainedExperienceBonus.ExperienceSource source) {
    return getSkillBonuses(player, GainedExperienceBonus.class).stream()
        .filter(b -> b.getSource() == source)
        .map(GainedExperienceBonus::getMultiplier)
        .reduce(Float::sum)
        .orElse(0f);
  }

  @SubscribeEvent
  public static void applyChanceToIgnite(LivingHurtEvent event) {
    if (!(event.getSource().getEntity() instanceof Player player)) return;
    Map<Integer, Float> chances = new HashMap<>();
    getSkillBonuses(player, IgniteChanceBonus.class)
        .forEach(
            b ->
                chances.computeIfPresent(
                    b.getDuration(), (d, c) -> c + b.getChance(player, event.getEntity())));
    int duration = 0;
    for (Map.Entry<Integer, Float> entry : chances.entrySet()) {
      Integer d = entry.getKey();
      Float c = entry.getValue();
      while (c > 1) {
        duration += d;
        c--;
      }
      if (player.getRandom().nextFloat() < c) {
        duration += d;
      }
    }
    if (duration == 0) return;
    event.getEntity().setSecondsOnFire(duration);
  }

  @SubscribeEvent
  public static void applyArrowRetrievalBonus(LivingHurtEvent event) {
    if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) return;
    if (!(event.getSource().getEntity() instanceof Player player)) return;
    float chance =
        getSkillBonuses(player, ArrowRetrievalBonus.class).stream()
            .map(ArrowRetrievalBonus::getChance)
            .reduce(Float::sum)
            .orElse(0f);
    if (player.getRandom().nextFloat() >= chance) return;
    LivingEntity target = event.getEntity();
    CompoundTag targetData = target.getPersistentData();
    ListTag stuckArrowsTag = targetData.getList("StuckArrows", new CompoundTag().getId());
    AbstractArrowAccessor arrowAccessor = (AbstractArrowAccessor) arrow;
    stuckArrowsTag.add(arrowAccessor.invokeGetPickupItem().save(new CompoundTag()));
    targetData.put("StuckArrows", stuckArrowsTag);
  }

  @SubscribeEvent
  public static void retrieveArrows(LivingDeathEvent event) {
    LivingEntity entity = event.getEntity();
    ListTag arrowsTag =
        entity.getPersistentData().getList("StuckArrows", new CompoundTag().getId());
    if (arrowsTag.isEmpty()) return;
    arrowsTag.stream()
        .map(CompoundTag.class::cast)
        .map(ItemStack::of)
        .forEach(entity::spawnAtLocation);
  }

  public static float getLootMultiplier(Player player, LootDuplicationBonus.LootType lootType) {
    Map<Float, Float> multipliers = new HashMap<>();
    getSkillBonuses(player, LootDuplicationBonus.class).stream()
        .filter(b -> b.getLootType() == lootType)
        .forEach(b -> multipliers.computeIfPresent(b.getMultiplier(), (m, c) -> c + b.getChance()));
    float multiplier = 0f;
    for (Map.Entry<Float, Float> entry : multipliers.entrySet()) {
      Float m = entry.getKey();
      Float c = entry.getValue();
      while (c > 1) {
        multiplier += m;
        c--;
      }
      if (player.getRandom().nextFloat() < c) {
        multiplier += m;
      }
    }
    return multiplier;
  }

  protected static List<ItemEntity> getDrops(LivingDropsEvent event) {
    List<ItemEntity> drops = new ArrayList<>();
    event.getDrops().stream().map(ItemEntity::copy).forEach(drops::add);
    if (event.getEntity() instanceof EquipmentContainer entity) drops.removeIf(entity::equipped);
    return drops;
  }

  private static void addAttributeModifiers(
      BiConsumer<Attribute, AttributeModifier> addFunction, ItemStack stack) {
    ItemHelper.getItemBonuses(stack).stream()
        .filter(ItemSkillBonus.class::isInstance)
        .map(ItemSkillBonus.class::cast)
        .map(ItemSkillBonus::getBonus)
        .filter(AttributeBonus.class::isInstance)
        .map(AttributeBonus.class::cast)
        .forEach(bonus -> addFunction.accept(bonus.getAttribute(), bonus.getModifier()));
  }

  public static void itemCrafted(Player player, ItemStack stack) {
    getSkillBonuses(player, CraftedItemBonus.class).forEach(bonus -> bonus.itemCrafted(stack));
    ItemHelper.getItemBonuses(stack, ItemBonus.class).forEach(bonus -> bonus.itemCrafted(stack));
  }

  public static float getJumpHeightMultiplier(Player player) {
    float multiplier = 1f;
    multiplier +=
        getSkillBonuses(player, JumpHeightBonus.class).stream()
            .map(b -> b.getJumpHeightMultiplier(player))
            .reduce(Float::sum)
            .orElse(0f);
    return multiplier;
  }

  public static void amplifyEnchantments(
      List<EnchantmentInstance> enchantments, RandomSource random, Player player) {
    enchantments.replaceAll(
        enchantmentInstance -> amplifyEnchantment(enchantmentInstance, random, player));
  }

  private static EnchantmentInstance amplifyEnchantment(
      EnchantmentInstance enchantment, RandomSource random, Player player) {
    if (enchantment.enchantment.getMaxLevel() == 1) {
      return enchantment;
    }
    float amplificationChance = getAmplificationChance(enchantment, player);
    if (amplificationChance == 0) return enchantment;
    int levelBonus = (int) amplificationChance;
    amplificationChance -= levelBonus;
    int enchantmentLevel = enchantment.level + levelBonus;
    if (random.nextFloat() < amplificationChance) enchantmentLevel++;
    return new EnchantmentInstance(enchantment.enchantment, enchantmentLevel);
  }

  public static int adjustEnchantmentCost(int cost, Player player) {
    return (int) Math.max(1, cost * getEnchantmentCostMultiplier(player));
  }

  public static float getFreeEnchantmentChance(Player player) {
    return SkillBonusHandler.getSkillBonuses(player, FreeEnchantmentBonus.class).stream()
        .map(FreeEnchantmentBonus::getChance)
        .reduce(Float::sum)
        .orElse(1f);
  }

  private static double getEnchantmentCostMultiplier(Player player) {
    return SkillBonusHandler.getSkillBonuses(player, EnchantmentRequirementBonus.class).stream()
        .map(EnchantmentRequirementBonus::getMultiplier)
        .reduce(Float::sum)
        .orElse(1f);
  }

  private static float getAmplificationChance(EnchantmentInstance enchantment, Player player) {
    return SkillBonusHandler.getSkillBonuses(player, EnchantmentAmplificationBonus.class).stream()
        .filter(bonus -> bonus.getCondition().met(enchantment.enchantment.category))
        .map(EnchantmentAmplificationBonus::getChance)
        .reduce(Float::sum)
        .orElse(0f);
  }

  public static <T> List<T> getSkillBonuses(Player player, Class<T> type) {
    List<T> bonuses = new ArrayList<>();
    PlayerSkillsProvider.get(player).getPlayerSkills().stream()
        .map(PassiveSkill::getBonuses)
        .forEach(b -> b.stream().filter(type::isInstance).map(type::cast).forEach(bonuses::add));
    bonuses.addAll(getEffectBonuses(player, type));
    bonuses.addAll(getCurioBonuses(player, type));
    return bonuses;
  }

  private static <T> List<T> getEffectBonuses(Player player, Class<T> type) {
    List<T> bonuses = new ArrayList<>();
    player.getActiveEffects().stream()
        .filter(e -> e.getEffect() instanceof SkillBonusEffect)
        .forEach(
            effect -> {
              SkillBonusEffect skillEffect = (SkillBonusEffect) effect.getEffect();
              SkillBonus<?> bonus = skillEffect.getBonus();
              if (type.isInstance(bonus)) {
                bonus = bonus.multiply(effect.getAmplifier());
                bonuses.add(type.cast(bonus));
              }
            });
    return bonuses;
  }

  private static <T> List<T> getCurioBonuses(Player player, Class<T> type) {
    List<T> bonuses = new ArrayList<>();
    CuriosApi.getCuriosHelper()
        .findCurios(player, s -> s.getItem() instanceof ItemBonusProvider)
        .stream()
        .map(SlotResult::stack)
        .map(ItemStack::getItem)
        .map(ItemBonusProvider.class::cast)
        .forEach(
            item ->
                item.getItemBonuses(
                    b -> {
                      if (type.isInstance(b)) bonuses.add(type.cast(b));
                    }));
    return bonuses;
  }
}