package li.cil.oc.integration.opencomputers

import li.cil.oc.api
import li.cil.oc.api.driver.EnvironmentHost
import li.cil.oc.api.driver.item.Container
import li.cil.oc.common.Slot
import li.cil.oc.common.Tier
import li.cil.oc.common.init.Items
import li.cil.oc.common.item
import net.minecraft.item.ItemStack

object DriverContainerUpgrade extends Item with Container {
  override def worksWith(stack: ItemStack) =
    isOneOf(stack, api.Items.get("upgradeContainer1"), api.Items.get("upgradeContainer2"), api.Items.get("upgradeContainer3"))

  override def createEnvironment(stack: ItemStack, host: EnvironmentHost) = null

  override def slot(stack: ItemStack) = Slot.Container

  override def providedSlot(stack: ItemStack) = Slot.Upgrade

  override def providedTier(stack: ItemStack) = tier(stack)

  override def tier(stack: ItemStack) =
    Items.multi.subItem(stack) match {
      case Some(container: item.UpgradeContainerUpgrade) => container.tier
      case _ => Tier.One
    }
}
