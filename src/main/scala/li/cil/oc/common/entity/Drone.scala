package li.cil.oc.common.entity

import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import li.cil.oc.Localization
import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.api
import li.cil.oc.api.Driver
import li.cil.oc.api.Machine
import li.cil.oc.api.driver.item
import li.cil.oc.api.driver.item.Memory
import li.cil.oc.api.driver.item.Processor
import li.cil.oc.api.internal
import li.cil.oc.api.machine.Context
import li.cil.oc.api.machine.MachineHost
import li.cil.oc.api.network._
import li.cil.oc.common.GuiType
import li.cil.oc.common.Slot
import li.cil.oc.common.inventory.ComponentInventory
import li.cil.oc.common.inventory.Inventory
import li.cil.oc.common.inventory.MultiTank
import li.cil.oc.common.item.data.MicrocontrollerData
import li.cil.oc.server.component
import li.cil.oc.util.BlockPosition
import li.cil.oc.util.ExtendedNBT._
import li.cil.oc.util.ExtendedWorld._
import li.cil.oc.util.InventoryUtils
import net.minecraft.block.material.Material
import net.minecraft.entity.Entity
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.Vec3
import net.minecraft.world.World
import net.minecraftforge.common.util.ForgeDirection
import net.minecraftforge.fluids.IFluidTank

// internal.Rotatable is also in internal.Drone, but it wasn't since the start
// so this is to ensure it is implemented here, in the very unlikely case that
// someone decides to ship that specific version of the API.
// TODO Remove internal.Tiered in 1.5, only here for compatibility if someone ships an older 1.4 API.
class Drone(val world: World) extends Entity(world) with MachineHost with internal.Drone with internal.Rotatable with internal.Tiered with Analyzable with Context {
  // Some basic constants.
  val gravity = 0.05f
  // low for slow fall (float down)
  val drag = 0.8f
  val maxAcceleration = 0.1f
  val maxVelocity = 0.4f
  val maxInventorySize = 8
  setSize(12 / 16f, 6 / 16f)
  isImmuneToFire = true

  // Rendering stuff, purely eyecandy.
  val targetFlapAngles = Array.fill(4, 2)(0f)
  val flapAngles = Array.fill(4, 2)(0f)
  var nextFlapChange = 0
  var bodyAngle = math.random.toFloat * 90
  var angularVelocity = 0f
  var nextAngularVelocityChange = 0
  var lastEnergyUpdate = 0

  // Logic stuff, components, machine and such.
  val info = new MicrocontrollerData()
  val machine = if (!world.isRemote) {
    val m = Machine.create(this)
    m.node.asInstanceOf[Connector].setLocalBufferSize(0)
    m
  } else null
  val control = if (!world.isRemote) new component.Drone(this) else null
  val components = new ComponentInventory {
    override def host = Drone.this

    override def items = info.components.map(Option(_))

    override def getSizeInventory = info.components.length

    override def markDirty() {}

    override def isItemValidForSlot(slot: Int, stack: ItemStack) = true

    override def isUseableByPlayer(player: EntityPlayer) = true

    override def node = Option(machine).map(_.node).orNull

    override def onConnect(node: Node) {}

    override def onDisconnect(node: Node) {}

    override def onMessage(message: Message) {}
  }
  val inventory = new Inventory {
    val items = Array.fill[Option[ItemStack]](8)(None)

    override def getSizeInventory = inventorySize

    override def getInventoryStackLimit = 64

    override def markDirty() {} // TODO update client GUI?

    override def isItemValidForSlot(slot: Int, stack: ItemStack) = slot >= 0 && slot < getSizeInventory

    override def isUseableByPlayer(player: EntityPlayer) = player.getDistanceSqToEntity(Drone.this) < 64
  }
  val tank = new MultiTank {
    override def tankCount = components.components.count {
      case Some(tank: IFluidTank) => true
      case _ => false
    }

    override def getFluidTank(index: Int): IFluidTank = components.components.collect {
      case Some(tank: IFluidTank) => tank
    }.apply(index)
  }
  var selectedTank = 0

  override def tier = info.tier

  // ----------------------------------------------------------------------- //
  // Forward context stuff to our machine. Interface needed for some components
  // to work correctly (such as the chunkloader upgrade).

  override def node = machine.node

  override def canInteract(player: String) = machine.canInteract(player)

  override def isPaused = machine.isPaused

  override def start() = machine.start()

  override def pause(seconds: Double) = machine.pause(seconds)

  override def stop() = machine.stop()

  override def signal(name: String, args: AnyRef*) = machine.signal(name, args: _*)

  // ----------------------------------------------------------------------- //

  override def getTarget = Vec3.createVectorHelper(targetX, targetY, targetZ)

  override def setTarget(value: Vec3): Unit = {
    targetX = value.xCoord.toFloat
    targetY = value.yCoord.toFloat
    targetZ = value.zCoord.toFloat
  }

  override def getVelocity = Vec3.createVectorHelper(motionX, motionY, motionZ)

  // ----------------------------------------------------------------------- //

  override def canBeCollidedWith = true

  override def canBePushed = true

  // ----------------------------------------------------------------------- //

  override def xPosition = posX

  override def yPosition = posY

  override def zPosition = posZ

  override def markChanged() {}

  // ----------------------------------------------------------------------- //

  override def facing() = ForgeDirection.SOUTH

  override def toLocal(value: ForgeDirection) = value

  override def toGlobal(value: ForgeDirection) = value

  // ----------------------------------------------------------------------- //

  override def onAnalyze(player: EntityPlayer, side: Int, hitX: Float, hitY: Float, hitZ: Float) = {
    machine.lastError match {
      case value if value != null =>
        player.addChatMessage(Localization.Analyzer.LastError(value))
      case _ =>
    }
    player.addChatMessage(Localization.Analyzer.Components(machine.componentCount, maxComponents))
    val list = machine.users
    if (list.size > 0) {
      player.addChatMessage(Localization.Analyzer.Users(list))
    }
    Array(machine.node)
  }

  // ----------------------------------------------------------------------- //

  override def cpuArchitecture = info.components.map(stack => (stack, Driver.driverFor(stack, getClass))).collectFirst {
    case (stack, driver: Processor) if driver.slot(stack) == Slot.CPU => driver.architecture(stack)
  }.orNull

  override def callBudget = info.components.foldLeft(0.0)((sum, item) => sum + (Option(item) match {
    case Some(stack) => Option(Driver.driverFor(stack, getClass)) match {
      case Some(driver: Processor) if driver.slot(stack) == Slot.CPU => Settings.get.callBudgets(driver.tier(stack))
      case _ => 0
    }
    case _ => 0
  }))

  override def installedMemory = info.components.foldLeft(0)((sum, item) => sum + (Option(item) match {
    case Some(stack) => Option(Driver.driverFor(stack, getClass)) match {
      case Some(driver: Memory) => driver.amount(stack)
      case _ => 0
    }
    case _ => 0
  }))

  override def maxComponents = 32

  override def componentSlot(address: String) = -1 // TODO

  override def markForSaving() {}

  override def onMachineConnect(node: Node) {}

  override def onMachineDisconnect(node: Node) {}

  def computeInventorySize() = math.min(maxInventorySize, info.components.foldLeft(0)((acc, component) => acc + (Option(component) match {
    case Some(stack) => Option(Driver.driverFor(stack, getClass)) match {
      case Some(driver: item.Inventory) => math.max(1, driver.inventoryCapacity(stack) / 4)
      case _ => 0
    }
    case _ => 0
  })))

  // ----------------------------------------------------------------------- //

  override def entityInit() {
    // Running or not.
    dataWatcher.addObject(2, byte2Byte(0: Byte))
    // Target position.
    dataWatcher.addObject(3, float2Float(0f))
    dataWatcher.addObject(4, float2Float(0f))
    dataWatcher.addObject(5, float2Float(0f))
    // Max acceleration.
    dataWatcher.addObject(6, float2Float(0f))
    // Selected inventory slot.
    dataWatcher.addObject(7, byte2Byte(0: Byte))
    // Current and maximum energy.
    dataWatcher.addObject(8, int2Integer(0))
    dataWatcher.addObject(9, int2Integer(100))
    // Status text.
    dataWatcher.addObject(10, "")
    // Inventory size for client.
    dataWatcher.addObject(11, byte2Byte(0: Byte))
    // Light color.
    dataWatcher.addObject(12, int2Integer(0x66DD55))
  }

  def initializeAfterPlacement(stack: ItemStack, player: EntityPlayer, position: Vec3) {
    info.load(stack)
    control.node.changeBuffer(info.storedEnergy - control.node.localBuffer)
    wireThingsTogether()
    inventorySize = computeInventorySize()
    setPosition(position.xCoord, position.yCoord, position.zCoord)
  }

  def preparePowerUp() {
    targetX = math.floor(posX).toFloat + 0.5f
    targetY = math.floor(posY).toFloat + 0.5f
    targetZ = math.floor(posZ).toFloat + 0.5f
    targetAcceleration = maxAcceleration

    wireThingsTogether()
  }

  private def wireThingsTogether(): Unit = {
    api.Network.joinNewNetwork(machine.node)
    machine.node.connect(control.node)
    machine.setCostPerTick(Settings.get.droneCost)
    components.connectComponents()
  }

  def isRunning = dataWatcher.getWatchableObjectByte(2) != 0

  def targetX = dataWatcher.getWatchableObjectFloat(3)

  def targetY = dataWatcher.getWatchableObjectFloat(4)

  def targetZ = dataWatcher.getWatchableObjectFloat(5)

  def targetAcceleration = dataWatcher.getWatchableObjectFloat(6)

  def selectedSlot = dataWatcher.getWatchableObjectByte(7) & 0xFF

  def globalBuffer = dataWatcher.getWatchableObjectInt(8)

  def globalBufferSize = dataWatcher.getWatchableObjectInt(9)

  def statusText = dataWatcher.getWatchableObjectString(10)

  def inventorySize = dataWatcher.getWatchableObjectByte(11) & 0xFF

  def lightColor = dataWatcher.getWatchableObjectInt(12)

  def setRunning(value: Boolean) = dataWatcher.updateObject(2, byte2Byte(if (value) 1: Byte else 0: Byte))

  // Round target values to low accuracy to avoid floating point errors accumulating.
  def targetX_=(value: Float): Unit = dataWatcher.updateObject(3, float2Float(math.round(value * 4) / 4f))

  def targetY_=(value: Float): Unit = dataWatcher.updateObject(4, float2Float(math.round(value * 4) / 4f))

  def targetZ_=(value: Float): Unit = dataWatcher.updateObject(5, float2Float(math.round(value * 4) / 4f))

  def targetAcceleration_=(value: Float): Unit = dataWatcher.updateObject(6, float2Float(math.max(0, math.min(maxAcceleration, value))))

  def selectedSlot_=(value: Int) = dataWatcher.updateObject(7, byte2Byte(value.toByte))

  def globalBuffer_=(value: Int) = dataWatcher.updateObject(8, int2Integer(value))

  def globalBufferSize_=(value: Int) = dataWatcher.updateObject(9, int2Integer(value))

  def statusText_=(value: String) = dataWatcher.updateObject(10, Option(value).map(_.lines.map(_.take(10)).take(2).mkString("\n")).getOrElse(""))

  def inventorySize_=(value: Int) = dataWatcher.updateObject(11, byte2Byte(value.toByte))

  def lightColor_=(value: Int) = dataWatcher.updateObject(12, int2Integer(value))

  @SideOnly(Side.CLIENT)
  override def setPositionAndRotation2(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, data: Int) {
    // Only set exact position if we're too far away from the server's
    // position, otherwise keep interpolating. This removes jitter and
    // is good enough for drones.
    if (!isRunning || getDistanceSq(x, y, z) > 1) {
      super.setPositionAndRotation(x, y, z, yaw, pitch)
    }
    else {
      targetX = x.toFloat
      targetY = y.toFloat
      targetZ = z.toFloat
    }
  }

  override def setDead() {
    super.setDead()
    if (!world.isRemote) {
      machine.stop()
      machine.node.remove()
      components.disconnectComponents()
      components.saveComponents()
      val stack = api.Items.get("drone").createItemStack(1)
      info.storedEnergy = control.node.localBuffer.toInt
      info.save(stack)
      val entity = new EntityItem(world, posX, posY, posZ, stack)
      entity.delayBeforeCanPickup = 15
      world.spawnEntityInWorld(entity)
      InventoryUtils.dropAllSlots(BlockPosition(this: Entity), inventory)
    }
  }

  override def onUpdate() {
    super.onUpdate()

    if (!world.isRemote) {
      if (isInsideOfMaterial(Material.water) || isInsideOfMaterial(Material.lava)) {
        // We're not water-proof!
        machine.stop()
      }
      machine.update()
      components.updateComponents()
      setRunning(machine.isRunning)

      val buffer = math.round(machine.node.asInstanceOf[Connector].globalBuffer).toInt
      if (math.abs(lastEnergyUpdate - buffer) > 1 || world.getTotalWorldTime % 200 == 0) {
        lastEnergyUpdate = buffer
        globalBuffer = buffer
        globalBufferSize = machine.node.asInstanceOf[Connector].globalBufferSize.toInt
      }
    }
    else {
      if (isRunning) {
        // Client side update; occasionally update wing pitch and rotation to
        // make the drones look a bit more dynamic.
        val rng = world.rand
        nextFlapChange -= 1
        nextAngularVelocityChange -= 1

        if (nextFlapChange < 0) {
          nextFlapChange = 5 + rng.nextInt(10)
          for (i <- 0 until 2) {
            val flap = rng.nextInt(targetFlapAngles.length)
            targetFlapAngles(flap)(0) = math.toRadians(rng.nextFloat() * 4 - 2).toFloat
            targetFlapAngles(flap)(1) = math.toRadians(rng.nextFloat() * 4 - 2).toFloat
          }
        }

        if (nextAngularVelocityChange < 0) {
          if (angularVelocity != 0) {
            angularVelocity = 0
            nextAngularVelocityChange = 20
          }
          else {
            angularVelocity = if (rng.nextBoolean()) 0.1f else -0.1f
            nextAngularVelocityChange = 100
          }
        }

        // Interpolate wing rotations.
        (flapAngles, targetFlapAngles).zipped.foreach((f, t) => {
          f(0) = f(0) * 0.7f + t(0) * 0.3f
          f(1) = f(1) * 0.7f + t(1) * 0.3f
        })

        // Update body rotation.
        bodyAngle += angularVelocity
      }
    }

    prevPosX = posX
    prevPosY = posY
    prevPosZ = posZ
    noClip = func_145771_j(posX, (boundingBox.minY + boundingBox.maxY) / 2, posZ)

    if (isRunning) {
      val toTarget = Vec3.createVectorHelper(targetX - posX, targetY - posY, targetZ - posZ)
      val distance = toTarget.lengthVector()
      val velocity = Vec3.createVectorHelper(motionX, motionY, motionZ)
      if (distance > 0 && (distance > 0.005f || velocity.dotProduct(velocity) > 0.005f)) {
        val acceleration = math.min(targetAcceleration, distance) / distance
        velocity.xCoord += toTarget.xCoord * acceleration
        velocity.yCoord += toTarget.yCoord * acceleration
        velocity.zCoord += toTarget.zCoord * acceleration
        motionX = math.max(-maxVelocity, math.min(maxVelocity, velocity.xCoord))
        motionY = math.max(-maxVelocity, math.min(maxVelocity, velocity.yCoord))
        motionZ = math.max(-maxVelocity, math.min(maxVelocity, velocity.zCoord))
      }
      else {
        motionX = 0
        motionY = 0
        motionZ = 0
        posX = targetX
        posY = targetY
        posZ = targetZ
      }
    }
    else {
      // No power, free fall: engage!
      motionY -= gravity
    }

    moveEntity(motionX, motionY, motionZ)

    // Make sure we don't get infinitely faster.
    if (isRunning) {
      motionX *= drag
      motionY *= drag
      motionZ *= drag
    }
    else {
      val groundDrag = worldObj.getBlock(BlockPosition(this: Entity).offset(ForgeDirection.DOWN)).slipperiness * drag
      motionX *= groundDrag
      motionY *= drag
      motionZ *= groundDrag
      if (onGround) {
        motionY *= -0.5
      }
    }
  }

  override def hitByEntity(entity: Entity) = {
    if (isRunning) {
      val direction = Vec3.createVectorHelper(entity.posX - posX, entity.posY + entity.getEyeHeight - posY, entity.posZ - posZ).normalize()
      if (!world.isRemote) {
        if (Settings.get.inputUsername)
          machine.signal("hit", double2Double(direction.xCoord), double2Double(direction.zCoord), double2Double(direction.yCoord), entity.getCommandSenderName)
        else
          machine.signal("hit", double2Double(direction.xCoord), double2Double(direction.zCoord), double2Double(direction.yCoord))
      }
      motionX = (motionX - direction.xCoord) * 0.5f
      motionY = (motionY - direction.yCoord) * 0.5f
      motionZ = (motionZ - direction.zCoord) * 0.5f
    }
    super.hitByEntity(entity)
  }

  override def interactFirst(player: EntityPlayer) = {
    if (player.isSneaking) {
      kill()
    }
    else if (!world.isRemote) {
      player.openGui(OpenComputers, GuiType.Drone.id, world, getEntityId, 0, 0)
    }
    true
  }

  // ----------------------------------------------------------------------- //

  override def travelToDimension(dimension: Int) {
    // Store relative target and update after teleportation, because our frame
    // of reference most certainly changed (i.e. we'll spawn at different
    // coordinates than the ones we started traveling from).
    val relativeTarget = Vec3.createVectorHelper(targetX - posX, targetY - posY, targetZ - posZ)
    super.travelToDimension(dimension)
    targetX = (posX + relativeTarget.xCoord).toFloat
    targetY = (posY + relativeTarget.yCoord).toFloat
    targetZ = (posZ + relativeTarget.zCoord).toFloat
  }

  override def getCommandSenderName = Localization.localizeImmediately("entity.oc.Drone.name")

  override def handleWaterMovement() = {
    inWater = worldObj.handleMaterialAcceleration(boundingBox, Material.water, this)
    inWater
  }

  override def readEntityFromNBT(nbt: NBTTagCompound) {
    info.load(nbt.getCompoundTag("info"))
    inventorySize = computeInventorySize()
    if (!world.isRemote) {
      machine.load(nbt.getCompoundTag("machine"))
      control.load(nbt.getCompoundTag("control"))
      components.load(nbt.getCompoundTag("components"))
      inventory.load(nbt.getCompoundTag("inventory"))

      wireThingsTogether()
    }
    targetX = nbt.getFloat("targetX")
    targetY = nbt.getFloat("targetY")
    targetZ = nbt.getFloat("targetZ")
    targetAcceleration = nbt.getFloat("targetAcceleration")
    selectedSlot = nbt.getByte("selectedSlot") & 0xFF
    selectedTank = nbt.getByte("selectedTank") & 0xFF
    statusText = nbt.getString("statusText")
    lightColor = nbt.getInteger("lightColor")
  }

  override def writeEntityToNBT(nbt: NBTTagCompound) {
    components.saveComponents()
    info.storedEnergy = control.node.localBuffer.toInt
    nbt.setNewCompoundTag("info", info.save)
    if (!world.isRemote) {
      nbt.setNewCompoundTag("machine", machine.save)
      nbt.setNewCompoundTag("control", control.save)
      nbt.setNewCompoundTag("components", components.save)
      nbt.setNewCompoundTag("inventory", inventory.save)
    }
    nbt.setFloat("targetX", targetX)
    nbt.setFloat("targetY", targetY)
    nbt.setFloat("targetZ", targetZ)
    nbt.setFloat("targetAcceleration", targetAcceleration)
    nbt.setByte("selectedSlot", selectedSlot.toByte)
    nbt.setByte("selectedTank", selectedTank.toByte)
    nbt.setString("statusText", statusText)
    nbt.setInteger("lightColor", lightColor)
  }
}
