package li.cil.oc.util.mods

import cpw.mods.fml.common.versioning.VersionParser
import cpw.mods.fml.common.{ModAPIManager, Loader}

object Mods {
  val BattleGear2 = new SimpleMod("battlegear2")
  val BuildCraftPower = new SimpleMod("BuildCraftAPI|power")
  val ComputerCraft = new Mod {
    val isAvailable = try Class.forName("dan200.computercraft.api.ComputerCraftAPI") != null catch {
      case _: Throwable => false
    }
  }
  val ForgeMultipart = new SimpleMod("ForgeMultipart")
  val GregTech = new SimpleMod("gregtech_addon")
  val IndustrialCraft2 = new SimpleMod("IC2")
  val MineFactoryReloaded = new SimpleMod("MineFactoryReloaded")
  val NotEnoughItems = new SimpleMod("NotEnoughItems")
  val PortalGun = new SimpleMod("PortalGun")
  val ProjectRed = new SimpleMod("ProjRed|Transmission")
  val RedLogic = new SimpleMod("RedLogic")
  val StargateTech2 = new SimpleMod("StargateTech2@[0.6.0,)")
  val ThermalExpansion = new SimpleMod("ThermalExpansion")
  val UniversalElectricity = new SimpleMod("UniversalElectricity@[3.1,)")

  trait Mod {
    def isAvailable: Boolean
  }

  class SimpleMod(val id: String) {
    val isAvailable = {
      val version = VersionParser.parseVersionReference(id)
      if (Loader.isModLoaded(version.getLabel))
        version.containsVersion(Loader.instance.getIndexedModList.get(version.getLabel).getProcessedVersion)
      else ModAPIManager.INSTANCE.hasAPI(version.getLabel)
    }
  }

}