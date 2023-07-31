package daripher.skilltree.item.gem;

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class VacuciteItem extends GemItem {
	public VacuciteItem() {
		super();
	}

	@Override
	public void insertInto(Player player, ItemStack itemStack, int gemstoneSlot, double gemstoneStrength) {
		GemHelper.removeGems(itemStack);
	}

	@Override
	public boolean canInsertInto(Player currentPlayer, ItemStack baseItem, int gemstoneSlot) {
		return GemHelper.hasGem(baseItem, 0);
	}

	@Override
	public Optional<Pair<Attribute, AttributeModifier>> getGemBonus(Player player, ItemStack itemStack) {
		return Optional.empty();
	}

	@Override
	protected void appendBonusesTooltip(List<Component> components) {
		MutableComponent slot = Component.translatable("gem_class.anything").withStyle(ChatFormatting.GRAY);
		MutableComponent bonus = Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.BLUE);
		components.add(slot.append(bonus));
	}
}
