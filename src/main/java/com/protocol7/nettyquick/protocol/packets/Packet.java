package com.protocol7.nettyquick.protocol.packets;

import com.protocol7.nettyquick.protocol.ConnectionId;
import com.protocol7.nettyquick.protocol.LastPacketNumber;
import com.protocol7.nettyquick.protocol.PacketType;
import com.protocol7.nettyquick.protocol.Version;
import com.protocol7.nettyquick.tls.aead.AEAD;
import com.protocol7.nettyquick.tls.aead.AEADProvider;
import io.netty.buffer.ByteBuf;

import java.util.Optional;

public interface Packet {

    int PACKET_TYPE_MASK = 0b10000000;

    static Packet parse(ByteBuf bb, LastPacketNumber lastAcked, AEADProvider aeadProvider, int connidLength) {
        bb.markReaderIndex();
        int firstByte = bb.readByte() & 0xFF;

        if ((PACKET_TYPE_MASK & firstByte) == PACKET_TYPE_MASK) {
            // Long header packet

            // might be a ver neg packet, so we must check the version
            Version version = Version.read(bb);
            bb.resetReaderIndex();

            if (version == Version.VERSION_NEGOTIATION) {
                return VersionNegotiationPacket.parse(bb);
            } else if (firstByte == InitialPacket.MARKER) {
                return InitialPacket.parse(bb, aeadProvider);
            } else if (firstByte == HandshakePacket.MARKER) {
                return HandshakePacket.parse(bb, aeadProvider);
            } else if (firstByte == RetryPacket.MARKER) {
                return RetryPacket.parse(bb);
            } else {
                throw new RuntimeException("Unknown long header packet");
            }
        } else {
            // short header packet
            bb.resetReaderIndex();
            return ShortPacket.parse(bb, lastAcked, aeadProvider, connidLength);
        }
    }

    void write(ByteBuf bb, AEAD aead);

    Optional<ConnectionId> getSourceConnectionId();
    Optional<ConnectionId> getDestinationConnectionId();
}
