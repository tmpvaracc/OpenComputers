package li.cil.oc.server.fs

import java.io
import java.net.URL
import java.util.UUID

import li.cil.oc.OpenComputers
import li.cil.oc.Settings
import li.cil.oc.api
import li.cil.oc.api.driver.EnvironmentHost
import li.cil.oc.api.fs.Label
import li.cil.oc.api.fs.Mode
import li.cil.oc.integration.Mods
import li.cil.oc.integration.computercraft.DriverComputerCraftMedia
import li.cil.oc.server.component
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraftforge.common.DimensionManager

object FileSystem extends api.detail.FileSystemAPI {
  lazy val isCaseInsensitive = Settings.get.forceCaseInsensitive || (try {
    val uuid = UUID.randomUUID().toString
    val lowerCase = new io.File(DimensionManager.getCurrentSaveRootDirectory, uuid + "oc_rox")
    val upperCase = new io.File(DimensionManager.getCurrentSaveRootDirectory, uuid + "OC_ROX")
    // This should NEVER happen but could also lead to VERY weird bugs, so we
    // make sure the files don't exist.
    lowerCase.exists() && lowerCase.delete()
    upperCase.exists() && upperCase.delete()
    lowerCase.createNewFile()
    val insensitive = upperCase.exists()
    lowerCase.delete()
    insensitive
  }
  catch {
    case t: Throwable =>
      // Among the security errors, createNewFile can throw an IOException.
      // We just fall back to assuming case insensitive, since that's always
      // safe in those cases.
      OpenComputers.log.warn("Couldn't determine if file system is case sensitive, falling back to insensitive.", t)
      true
  })

  override def fromClass(clazz: Class[_], domain: String, root: String): api.fs.FileSystem = {
    val innerPath = ("/assets/" + domain + "/" + (root.trim + "/")).replace("//", "/")

    val codeSource = clazz.getProtectionDomain.getCodeSource.getLocation.getPath
    val (codeUrl, isArchive) =
      if (codeSource.contains(".zip!") || codeSource.contains(".jar!"))
        (codeSource.substring(0, codeSource.lastIndexOf('!')), true)
      else
        (codeSource, false)

    val file = try {
      val url = new URL(codeUrl)
      try {
        new io.File(url.toURI)
      }
      catch {
        case _: Throwable => new io.File(url.getPath)
      }
    } catch {
      case _: Throwable => new io.File(codeSource)
    }

    if (isArchive) {
      ZipFileInputStreamFileSystem.fromFile(file, innerPath.substring(1))
    }
    else {
      if (!file.exists || file.isDirectory) return null
      new io.File(new io.File(file.getParent), innerPath) match {
        case fsp if fsp.exists() && fsp.isDirectory =>
          new ReadOnlyFileSystem(fsp)
        case _ =>
          System.getProperty("java.class.path").split(System.getProperty("path.separator")).
            find(cp => {
            val fsp = new io.File(new io.File(cp), innerPath)
            fsp.exists() && fsp.isDirectory
          }) match {
            case None => null
            case Some(dir) => new ReadOnlyFileSystem(new io.File(new io.File(dir), innerPath))
          }
      }
    }
  }

  override def fromSaveDirectory(root: String, capacity: Long, buffered: Boolean) = {
    val path = new io.File(DimensionManager.getCurrentSaveRootDirectory, Settings.savePath + root)
    if (!path.isDirectory) {
      path.delete()
    }
    path.mkdirs()
    if (path.exists() && path.isDirectory) {
      if (buffered) new BufferedFileSystem(path, capacity)
      else new ReadWriteFileSystem(path, capacity)
    }
    else null
  }

  def fromMemory(capacity: Long): api.fs.FileSystem = new RamFileSystem(capacity)

  def fromComputerCraft(mount: AnyRef): api.fs.FileSystem =
    if (Mods.ComputerCraft.isAvailable) {
      DriverComputerCraftMedia.createFileSystem(mount).orNull
    }
    else null

  def asManagedEnvironment(fileSystem: api.fs.FileSystem, label: Label, host: EnvironmentHost, sound: String) =
    Option(fileSystem).flatMap(fs => Some(new component.FileSystem(fs, label, Option(host), Option(sound)))).orNull

  def asManagedEnvironment(fileSystem: api.fs.FileSystem, label: String, host: EnvironmentHost, sound: String) =
    asManagedEnvironment(fileSystem, new ReadOnlyLabel(label), host, sound)

  def asManagedEnvironment(fileSystem: api.fs.FileSystem, label: Label) =
    Option(fileSystem).flatMap(fs => Some(new component.FileSystem(fs, label))).orNull

  def asManagedEnvironment(fileSystem: api.fs.FileSystem, label: String) =
    asManagedEnvironment(fileSystem, new ReadOnlyLabel(label))

  def asManagedEnvironment(fileSystem: api.fs.FileSystem) =
    asManagedEnvironment(fileSystem, null: Label)

  abstract class ItemLabel(val stack: ItemStack) extends Label

  private class ReadOnlyLabel(val label: String) extends Label {
    def setLabel(value: String) = throw new IllegalArgumentException("label is read only")

    def getLabel = label

    override def load(nbt: NBTTagCompound) {}

    override def save(nbt: NBTTagCompound) {
      if (label != null) {
        nbt.setString(Settings.namespace + "fs.label", label)
      }
    }
  }

  private class ReadOnlyFileSystem(protected val root: io.File)
    extends InputStreamFileSystem
    with FileInputStreamFileSystem

  private class ReadWriteFileSystem(protected val root: io.File, protected val capacity: Long)
    extends OutputStreamFileSystem
    with FileOutputStreamFileSystem
    with Capacity

  private class RamFileSystem(protected val capacity: Long)
    extends VirtualFileSystem
    with Volatile
    with Capacity

  private class BufferedFileSystem(protected val fileRoot: io.File, protected val capacity: Long)
    extends VirtualFileSystem
    with Buffered
    with Capacity {
    // Worst-case: we're on Windows or using a FAT32 partition mounted in *nix.
    // Note: we allow / as the path separator and expect all \s to be converted
    // accordingly before the path is passed to the file system.
    private val invalidChars = """\:*?"<>|""".toSet

    override protected def isValidFilename(name: String) = !name.exists(invalidChars.contains)

    override def makeDirectory(path: String) = super.makeDirectory(validatePath(path))

    override protected def openOutputHandle(id: Int, path: String, mode: Mode) = super.openOutputHandle(id, validatePath(path), mode)

    protected override def segments(path: String) = {
      val parts = super.segments(path)
      if (isCaseInsensitive) toCaseInsensitive(parts) else parts
    }

    private def validatePath(path: String) = {
      if (!isValidFilename(path)) {
        throw new java.io.IOException("path contains invalid characters")
      }
      path
    }

    private def toCaseInsensitive(path: Array[String]): Array[String] = {
      var node = root
      path.map(segment => {
        assert(node != null, "corrupted virtual file system")
        node.children.find(entry => entry._1.toLowerCase == segment.toLowerCase) match {
          case Some((name, child: VirtualDirectory)) =>
            node = child
            name
          case Some((name, child: VirtualFile)) =>
            node = null
            name
          case _ => segment
        }
      })
    }
  }

}
