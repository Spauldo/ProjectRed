package mrtjp.projectred.transmission

import IWirePart._
import codechicken.lib.data.{MCDataOutput, MCDataInput}
import codechicken.lib.packet.PacketCustom
import codechicken.lib.vec.{BlockCoord, Rotation}
import codechicken.multipart.scalatraits.TRedstoneTile
import codechicken.multipart._
import cpw.mods.fml.relauncher.{SideOnly, Side}
import mrtjp.projectred.api.IConnectable
import mrtjp.projectred.core.{CoreSPH, Messenger, BasicUtils}
import net.minecraft.block.Block
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.ChatMessageComponent

trait IRedwirePart extends IWirePart with IRedwireEmitter

/**
 * Implemented by parts that emit a full-strength red alloy signal.
 */
trait IRedwireEmitter
{
    /**
     * For face parts, side is a rotation. For center parts, it is a forge
     * direction.
     *
     * @return Signal strength from 0 to 255.
     */
    def getRedwireSignal(side:Int):Int
}

trait IInsulatedRedwirePart extends IRedwirePart
{
    def getInsulatedColour:Int
}

trait TRedwireCommons extends TWireCommons with TRSAcquisitionsCommons with IRedwirePart
{
    var signal:Byte = 0

    override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        tag.setByte("signal", signal)
    }

    override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        signal = tag.getByte("signal")
    }

    override def writeDesc(packet:MCDataOutput)
    {
        super.writeDesc(packet)
        packet.writeByte(signal)
    }

    override def readDesc(packet:MCDataInput)
    {
        super.readDesc(packet)
        signal = packet.readByte
    }

    abstract override def read(packet:MCDataInput, key:Int) = key match
    {
        case 10 =>
            signal = packet.readByte
            if (useStaticRenderer) tile.markRender()
        case _ => super.read(packet, key)
    }

    override def strongPowerLevel(side:Int) = 0

    override def weakPowerLevel(side:Int) = 0

    def rsLevel =
    {
        if (WirePropagator.redwiresProvidePower) ((signal&0xFF)+16)/17
        else 0
    }

    def getRedwireSignal = signal&0xFF

    override def getRedwireSignal(side:Int) = getRedwireSignal

    override def updateAndPropagate(prev:TMultiPart, mode:Int)
    {
        if (mode == DROPPING && signal == 0) return
        val newSignal = calculateSignal
        if (newSignal < getRedwireSignal)
        {
            if (newSignal > 0) WirePropagator.propagateAnalogDrop(this)
            signal = 0
            propogate(prev, DROPPING)
        }
        else if (newSignal > getRedwireSignal)
        {
            signal = newSignal.asInstanceOf[Byte]
            if (mode == DROPPING) propogate(null, RISING)
            else propogate(prev, RISING)
        }
        else if (mode == DROPPING) propogateTo(prev, RISING)
        else if (mode == FORCE) propogate(prev, FORCED)
    }

    override def onSignalUpdate()
    {
        super.onSignalUpdate()
        tile.getWriteStream(this).writeByte(10).writeByte(signal)
    }

    def calculateSignal:Int

    override def debug(ply:EntityPlayer) =
    {
        ply.sendChatToPlayer(ChatMessageComponent.createFromTranslationKey(
            (if (world.isRemote) "Client" else "Server")+" signal strength: "+getRedwireSignal))
        true
    }

    override def test(player:EntityPlayer) =
    {
        if (BasicUtils.isClient(world)) Messenger.addMessage(x, y+.5f, z, "/#f/#c[c] = "+getRedwireSignal)
        else
        {
            val packet:PacketCustom = new PacketCustom(CoreSPH.channel, CoreSPH.messagePacket)
            packet.writeDouble(x+0.0D)
            packet.writeDouble(y+0.5D)
            packet.writeDouble(z+0.0D)
            packet.writeString("/#c[s] = "+getRedwireSignal)
            packet.sendToPlayer(player)
        }
        true
    }
}

abstract class RedwirePart extends WirePart with TRedwireCommons with TFaceRSAcquisitions
{
    override def weakPowerLevel(side:Int) =
    {
        if ((side&6) != (this.side&6) && (connMap&0x100<<Rotation.rotationTo(this.side, side)) != 0) 0
        else rsLevel
    }

    def canConnectRedstone(side:Int) = WirePropagator.redwiresConnectable

    override def discoverOpen(r:Int) =
    {
        val absDir = absoluteDir(r)
        (tile.asInstanceOf[TRedstoneTile].openConnections(absDir)&1<<Rotation.rotationTo(absDir&6, side)) != 0
    }

    override def canConnectPart(wire:IConnectable, r:Int) =
        wire.isInstanceOf[IRedwireEmitter] || wire.isInstanceOf[IRedstonePart]

    override def discoverStraightOverride(absDir:Int) =
        (RedstoneInteractions.otherConnectionMask(world, x, y, z, absDir, false)&
            RedstoneInteractions.connectionMask(this, absDir)) != 0

    override def discoverInternalOverride(p:TMultiPart, r:Int) = p match
    {
        case rsp:IFaceRedstonePart => rsp.canConnectRedstone(side)
        case _ => false
    }

    override def calcStraightSignal(r:Int) =
    {
        val partsig = super.calcStraightSignal(r)
        if (partsig > 0) partsig
        else
        {
            val absDir = absoluteDir(r)
            val pos = new BlockCoord(tile).offset(absDir)
            val blockID = world.getBlockId(pos.x, pos.y, pos.z)

            if (blockID == Block.redstoneWire.blockID) world.getBlockMetadata(pos.x, pos.y, pos.z)-1
            else RedstoneInteractions.getPowerTo(this, absDir)*17
        }
    }

    override def resolveSignal(part:TMultiPart, r:Int) = part match
    {
        case t:IRedwirePart if t.isWireSide(r) => t.getRedwireSignal(r)-1
        case t:IRedwireEmitter => t.getRedwireSignal(r)
        case t:IFaceRedstonePart =>
            val s = Rotation.rotateSide(t.getFace, r)
            Math.max(t.strongPowerLevel(s), t.weakPowerLevel(s))*17
        case _ => 0
    }

    def calculateSignal =
    {
        WirePropagator.setDustProvidePower(false)
        WirePropagator.redwiresProvidePower = false
        var s = 0
        def raise(sig:Int) {if (sig > s) s = sig}

        for (r <- 0 until 4) if (maskConnects(r))
            if (maskConnectsCorner(r)) raise(calcCornerSignal(r))
            else
            {
                if (maskConnectsStraight(r)) raise(calcStraightSignal(r))
                raise(calcInternalSignal(r)) //todo else?
            }

        raise(calcUndersideSignal)
        if (maskConnectsCenter) raise(calcCenterSignal)

        WirePropagator.setDustProvidePower(true)
        WirePropagator.redwiresProvidePower = true
        s
    }
}

abstract class FramedRedwirePart extends FramedWirePart with TRedwireCommons with TCenterRSAcquisitions with IMaskedRedstonePart
{
    override def weakPowerLevel(side:Int) = rsLevel

    override def canConnectRedstone(side:Int) = true

    override def getConnectionMask(side:Int) = 0x10

    override def canConnectPart(part:IConnectable, s:Int) = part.isInstanceOf[IRedwirePart]

    override def discoverStraightOverride(absDir:Int) =
    {
        WirePropagator.setRedwiresConnectable(false)
        val b = (RedstoneInteractions.otherConnectionMask(world, x, y, z, absDir, false)&RedstoneInteractions.connectionMask(this, absDir)) != 0
        WirePropagator.setRedwiresConnectable(true)
        b
    }

    override def discoverInternalOverride(p:TMultiPart, s:Int) = p match
    {
        case rsPart:IRedstonePart => rsPart.canConnectRedstone(s^1)
        case _ => false
    }

    override def propogateOther(mode:Int)
    {
        for (s <- 0 until 6) if (!maskConnects(s))
            WirePropagator.addNeighborChange(new BlockCoord(tile).offset(s))
    }

    def calculateSignal =
    {
        WirePropagator.setDustProvidePower(false)
        WirePropagator.redwiresProvidePower = false
        var s = 0
        def raise(sig:Int) {if (sig > s) s = sig}

        for (s <- 0 until 6) if (maskConnects(s))
        {
            if (maskConnectsOut(s)) raise(calcStraightSignal(s))
            else raise(calcInternalSignal(s))
        }

        WirePropagator.setDustProvidePower(true)
        WirePropagator.redwiresProvidePower = true
        s
    }

    override def calcStraightSignal(s:Int) = getStraight(s) match
    {
        case p:TMultiPart => resolveSignal(p, s^1)
        case _ => calcStrongSignal(s)
    }

    override def calcInternalSignal(s:Int) =
    {
        val tp = getInternal(s)
        val sig = resolveSignal(tp, s^1)
        if (sig > 0) sig
        else tp match
        {
            case rp:IRedstonePart => Math.max(rp.strongPowerLevel(s^1), rp.weakPowerLevel(s^1))<<4
            case _ => 0
        }
    }

    override def resolveSignal(part:TMultiPart, s:Int) = part match
    {
        case rw:IRedwirePart if rw.isWireSide(s) => rw.getRedwireSignal(s)-1
        case re:IRedwireEmitter => re.getRedwireSignal(s)
        case _ => 0
    }
}

trait TRedAlloyCommons extends TRedwireCommons
{
    override def getWireType = WireDef.RED_ALLOY

    override def renderHue = (signal&0xFF)/2+60<<24|0xFF
}

class RedAlloyWirePart extends RedwirePart with TRedAlloyCommons
{
    override def strongPowerLevel(side:Int) = if (side == this.side) rsLevel else 0

    override def redstoneConductionMap = 0x1F

    override def onRemoved()
    {
        super.onRemoved()
        if (!world.isRemote) tile.notifyNeighborChange(side)
    }

    override def propogateOther(mode:Int)
    {
        WirePropagator.addNeighborChange(new BlockCoord(tile).offset(side))
        WirePropagator.addNeighborChange(new BlockCoord(tile).offset(side^1))

        for (r <- 0 until 4) if (!maskConnects(r))
            WirePropagator.addNeighborChange(new BlockCoord(tile).offset(Rotation.rotateSide(side, r)))

        for (s <- 0 until 6) if (s != (side^1))
            WirePropagator.addNeighborChange(new BlockCoord(tile).offset(side).offset(s))
    }
}

class FramedRedAlloyWirePart extends FramedRedwirePart with TRedAlloyCommons

trait TInsulatedCommons extends TRedwireCommons with IInsulatedRedwirePart
{
    var colour:Byte = 0

    def getWireType = WireDef.INSULATED_WIRE(colour)

    override def preparePlacement(side:Int, meta:Int)
    {
        super.preparePlacement(side, meta)
        colour = (meta-WireDef.INSULATED_0.meta).asInstanceOf[Byte]
    }

    override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        tag.setByte("colour", colour)
    }

    override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        colour = tag.getByte("colour")
    }

    override def writeDesc(packet:MCDataOutput)
    {
        super.writeDesc(packet)
        packet.writeByte(colour)
    }

    override def readDesc(packet:MCDataInput)
    {
        super.readDesc(packet)
        colour = packet.readByte
    }

    abstract override def resolveSignal(part:TMultiPart, dir:Int) = part match
    {
        case b:IBundledCablePart => (b.getBundledSignal.apply(colour)&0xFF)-1
        case p => super.resolveSignal(p, dir)
    }

    abstract override def canConnectPart(part:IConnectable, r:Int) = part match
    {
        case b:IBundledCablePart => true
        case w:IInsulatedRedwirePart => w.getInsulatedColour == colour
        case _ => super.canConnectPart(part, r)
    }

    @SideOnly(Side.CLIENT)
    override def getIcon = getWireType.wireSprites(if (signal != 0) 1 else 0)

    override def getInsulatedColour = colour
}

class InsulatedRedAlloyPart extends RedwirePart with TInsulatedCommons
{
    override def weakPowerLevel(side:Int) =
    {
        if (this.side == side || this.side == (side^1) || !maskConnects(Rotation.rotationTo(this.side, side))) 0
        else super.weakPowerLevel(side)
    }

    override def calcUndersideSignal = 0
}

class FramedInsulatedRedAlloyPart extends FramedRedwirePart with TInsulatedCommons
{
    override def weakPowerLevel(side:Int) =
    {
        if (!maskConnects(side)) 0
        else super.weakPowerLevel(side)
    }
}