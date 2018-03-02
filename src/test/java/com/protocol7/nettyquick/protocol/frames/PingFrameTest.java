package com.protocol7.nettyquick.protocol.frames;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.protocol7.nettyquick.TestUtil;
import com.protocol7.nettyquick.utils.Hex;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

public class PingFrameTest {

  public static final byte[] DATA = "hello".getBytes();

  @Test(expected = IllegalArgumentException.class)
  public void validateDataLength() {
    new PingFrame(new byte[1024]);
  }

  @Test(expected = NullPointerException.class)
  public void validateNotNull() {
    new PingFrame(null);
  }

  @Test
  public void roundtrip() {
    ByteBuf bb = Unpooled.buffer();
    PingFrame frame = new PingFrame(DATA);

    frame.write(bb);

    PingFrame parsed = PingFrame.parse(bb);

    assertEquals(frame, parsed);
  }

  @Test
  public void write() {
    ByteBuf bb = Unpooled.buffer();
    PingFrame frame = new PingFrame(DATA);
    frame.write(bb);

    TestUtil.assertBuffer("070568656c6c6f", bb);
  }

  @Test
  public void parse() {
    ByteBuf bb = Unpooled.copiedBuffer(Hex.dehex("070568656c6c6f"));
    PingFrame frame = PingFrame.parse(bb);

    assertArrayEquals(DATA, frame.getData());
  }
}