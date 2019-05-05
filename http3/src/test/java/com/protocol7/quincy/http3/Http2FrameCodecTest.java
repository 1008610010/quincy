/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.protocol7.quincy.http3;

import static com.protocol7.quincy.http3.Http2CodecUtil.isStreamIdValid;
import static com.protocol7.quincy.http3.Http2TestUtil.anyChannelPromise;
import static com.protocol7.quincy.http3.Http2TestUtil.anyHttp2Settings;
import static com.protocol7.quincy.http3.Http2TestUtil.assertEqualsAndRelease;
import static com.protocol7.quincy.http3.Http2TestUtil.bb;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyShort;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.protocol7.quincy.http3.Http2Exception.StreamException;
import com.protocol7.quincy.http3.Http2Stream.State;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeEvent;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ReflectionUtil;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/** Unit tests for {@link Http2FrameCodec}. */
public class Http2FrameCodecTest {

  // For verifying outbound frames
  private Http2FrameWriter frameWriter;
  private Http2FrameCodec frameCodec;
  private EmbeddedChannel channel;

  // For injecting inbound frames
  private Http2FrameInboundWriter frameInboundWriter;

  private LastInboundHandler inboundHandler;

  private final Http2Headers request =
      new DefaultHttp2Headers()
          .method(HttpMethod.GET.asciiName())
          .scheme(HttpScheme.HTTPS.name())
          .authority(new AsciiString("example.org"))
          .path(new AsciiString("/foo"));
  private final Http2Headers response =
      new DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText());

  @Before
  public void setUp() throws Exception {
    setUp(Http2FrameCodecBuilder.forServer(), new Http2Settings());
  }

  @After
  public void tearDown() throws Exception {
    if (inboundHandler != null) {
      inboundHandler.finishAndReleaseAll();
      inboundHandler = null;
    }
    if (channel != null) {
      channel.finishAndReleaseAll();
      channel.close();
      channel = null;
    }
  }

  private void setUp(
      final Http2FrameCodecBuilder frameCodecBuilder, final Http2Settings initialRemoteSettings)
      throws Exception {
    /**
     * Some tests call this method twice. Once with JUnit's @Before and once directly to pass
     * special settings. This call ensures that in case of two consecutive calls to setUp(), the
     * previous channel is shutdown and ByteBufs are released correctly.
     */
    tearDown();

    frameWriter = Http2TestUtil.mockedFrameWriter();

    frameCodec =
        frameCodecBuilder
            .frameWriter(frameWriter)
            .frameLogger(new Http2FrameLogger(LogLevel.TRACE))
            .initialSettings(initialRemoteSettings)
            .build();
    inboundHandler = new LastInboundHandler();

    channel = new EmbeddedChannel();
    frameInboundWriter = new Http2FrameInboundWriter(channel);
    channel.connect(new InetSocketAddress(0));
    channel.pipeline().addLast(frameCodec);
    channel.pipeline().addLast(inboundHandler);
    channel.pipeline().fireChannelActive();

    // Handshake
    verify(frameWriter).writeSettings(eqFrameCodecCtx(), anyHttp2Settings(), anyChannelPromise());
    verifyNoMoreInteractions(frameWriter);
    channel.writeInbound(Http2CodecUtil.connectionPrefaceBuf());

    frameInboundWriter.writeInboundSettings(initialRemoteSettings);

    verify(frameWriter).writeSettingsAck(eqFrameCodecCtx(), anyChannelPromise());

    frameInboundWriter.writeInboundSettingsAck();

    final Http2SettingsFrame settingsFrame = inboundHandler.readInbound();
    assertNotNull(settingsFrame);
    final Http2SettingsAckFrame settingsAckFrame = inboundHandler.readInbound();
    assertNotNull(settingsAckFrame);
  }

  @Test
  public void stateChanges() throws Exception {
    frameInboundWriter.writeInboundHeaders(1, request, 31, true);

    final Http2Stream stream = frameCodec.connection().stream(1);
    assertNotNull(stream);
    assertEquals(State.HALF_CLOSED_REMOTE, stream.state());

    Http2FrameStreamEvent event = inboundHandler.readInboundMessageOrUserEvent();
    assertEquals(State.HALF_CLOSED_REMOTE, event.stream().state());

    final Http2StreamFrame inboundFrame = inboundHandler.readInbound();
    final Http2FrameStream stream2 = inboundFrame.stream();
    assertNotNull(stream2);
    assertEquals(1, stream2.id());
    assertEquals(inboundFrame, new DefaultHttp2HeadersFrame(request, true, 31).stream(stream2));
    assertNull(inboundHandler.readInbound());

    channel.writeOutbound(new DefaultHttp2HeadersFrame(response, true, 27).stream(stream2));
    verify(frameWriter)
        .writeHeaders(
            eqFrameCodecCtx(),
            eq(1),
            eq(response),
            anyInt(),
            anyShort(),
            anyBoolean(),
            eq(27),
            eq(true),
            anyChannelPromise());
    verify(frameWriter, never())
        .writeRstStream(eqFrameCodecCtx(), anyInt(), anyLong(), anyChannelPromise());

    assertEquals(State.CLOSED, stream.state());
    event = inboundHandler.readInboundMessageOrUserEvent();
    assertEquals(State.CLOSED, event.stream().state());

    assertTrue(channel.isActive());
  }

  @Test
  public void headerRequestHeaderResponse() throws Exception {
    frameInboundWriter.writeInboundHeaders(1, request, 31, true);

    final Http2Stream stream = frameCodec.connection().stream(1);
    assertNotNull(stream);
    assertEquals(State.HALF_CLOSED_REMOTE, stream.state());

    final Http2StreamFrame inboundFrame = inboundHandler.readInbound();
    final Http2FrameStream stream2 = inboundFrame.stream();
    assertNotNull(stream2);
    assertEquals(1, stream2.id());
    assertEquals(inboundFrame, new DefaultHttp2HeadersFrame(request, true, 31).stream(stream2));
    assertNull(inboundHandler.readInbound());

    channel.writeOutbound(new DefaultHttp2HeadersFrame(response, true, 27).stream(stream2));
    verify(frameWriter)
        .writeHeaders(
            eqFrameCodecCtx(),
            eq(1),
            eq(response),
            anyInt(),
            anyShort(),
            anyBoolean(),
            eq(27),
            eq(true),
            anyChannelPromise());
    verify(frameWriter, never())
        .writeRstStream(eqFrameCodecCtx(), anyInt(), anyLong(), anyChannelPromise());

    assertEquals(State.CLOSED, stream.state());
    assertTrue(channel.isActive());
  }

  @Test
  public void flowControlShouldBeResilientToMissingStreams() throws Http2Exception {
    final Http2Connection conn = new DefaultHttp2Connection(true);
    final Http2ConnectionEncoder enc =
        new DefaultHttp2ConnectionEncoder(conn, new DefaultHttp2FrameWriter());
    final Http2ConnectionDecoder dec =
        new DefaultHttp2ConnectionDecoder(conn, enc, new DefaultHttp2FrameReader());
    final Http2FrameCodec codec = new Http2FrameCodec(enc, dec, new Http2Settings(), false);
    final EmbeddedChannel em = new EmbeddedChannel(codec);

    // We call #consumeBytes on a stream id which has not been seen yet to emulate the case
    // where a stream is deregistered which in reality can happen in response to a RST.
    assertFalse(codec.consumeBytes(1, 1));
    assertTrue(em.finishAndReleaseAll());
  }

  @Test
  public void entityRequestEntityResponse() throws Exception {
    frameInboundWriter.writeInboundHeaders(1, request, 0, false);

    final Http2Stream stream = frameCodec.connection().stream(1);
    assertNotNull(stream);
    assertEquals(State.OPEN, stream.state());

    final Http2HeadersFrame inboundHeaders = inboundHandler.readInbound();
    final Http2FrameStream stream2 = inboundHeaders.stream();
    assertNotNull(stream2);
    assertEquals(1, stream2.id());
    assertEquals(new DefaultHttp2HeadersFrame(request, false).stream(stream2), inboundHeaders);
    assertNull(inboundHandler.readInbound());

    final ByteBuf hello = bb("hello");
    frameInboundWriter.writeInboundData(1, hello, 31, true);
    final Http2DataFrame inboundData = inboundHandler.readInbound();
    final Http2DataFrame expected =
        new DefaultHttp2DataFrame(bb("hello"), true, 31).stream(stream2);
    assertEqualsAndRelease(expected, inboundData);

    assertNull(inboundHandler.readInbound());

    channel.writeOutbound(new DefaultHttp2HeadersFrame(response, false).stream(stream2));
    verify(frameWriter)
        .writeHeaders(
            eqFrameCodecCtx(),
            eq(1),
            eq(response),
            anyInt(),
            anyShort(),
            anyBoolean(),
            eq(0),
            eq(false),
            anyChannelPromise());

    channel.writeOutbound(new DefaultHttp2DataFrame(bb("world"), true, 27).stream(stream2));
    final ArgumentCaptor<ByteBuf> outboundData = ArgumentCaptor.forClass(ByteBuf.class);
    verify(frameWriter)
        .writeData(
            eqFrameCodecCtx(),
            eq(1),
            outboundData.capture(),
            eq(27),
            eq(true),
            anyChannelPromise());

    final ByteBuf bb = bb("world");
    assertEquals(bb, outboundData.getValue());
    assertEquals(1, outboundData.getValue().refCnt());
    bb.release();
    outboundData.getValue().release();

    verify(frameWriter, never())
        .writeRstStream(eqFrameCodecCtx(), anyInt(), anyLong(), anyChannelPromise());
    assertTrue(channel.isActive());
  }

  @Test
  public void sendRstStream() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, true);

    final Http2Stream stream = frameCodec.connection().stream(3);
    assertNotNull(stream);
    assertEquals(State.HALF_CLOSED_REMOTE, stream.state());

    final Http2HeadersFrame inboundHeaders = inboundHandler.readInbound();
    assertNotNull(inboundHeaders);
    assertTrue(inboundHeaders.isEndStream());

    final Http2FrameStream stream2 = inboundHeaders.stream();
    assertNotNull(stream2);
    assertEquals(3, stream2.id());

    channel.writeOutbound(new DefaultHttp2ResetFrame(314 /* non-standard error */).stream(stream2));
    verify(frameWriter).writeRstStream(eqFrameCodecCtx(), eq(3), eq(314L), anyChannelPromise());
    assertEquals(State.CLOSED, stream.state());
    assertTrue(channel.isActive());
  }

  @Test
  public void receiveRstStream() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);

    final Http2Stream stream = frameCodec.connection().stream(3);
    assertNotNull(stream);
    assertEquals(State.OPEN, stream.state());

    final Http2HeadersFrame expectedHeaders = new DefaultHttp2HeadersFrame(request, false, 31);
    final Http2HeadersFrame actualHeaders = inboundHandler.readInbound();
    assertEquals(expectedHeaders.stream(actualHeaders.stream()), actualHeaders);

    frameInboundWriter.writeInboundRstStream(3, Http2Error.NO_ERROR.code());

    final Http2ResetFrame expectedRst =
        new DefaultHttp2ResetFrame(Http2Error.NO_ERROR).stream(actualHeaders.stream());
    final Http2ResetFrame actualRst = inboundHandler.readInbound();
    assertEquals(expectedRst, actualRst);

    assertNull(inboundHandler.readInbound());
  }

  @Test
  public void sendGoAway() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);
    final Http2Stream stream = frameCodec.connection().stream(3);
    assertNotNull(stream);
    assertEquals(State.OPEN, stream.state());

    final ByteBuf debugData = bb("debug");
    final ByteBuf expected = debugData.copy();

    final Http2GoAwayFrame goAwayFrame =
        new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR.code(), debugData.retainedDuplicate());
    goAwayFrame.setExtraStreamIds(2);

    channel.writeOutbound(goAwayFrame);
    verify(frameWriter)
        .writeGoAway(
            eqFrameCodecCtx(),
            eq(7),
            ArgumentMatchers.eq(Http2Error.NO_ERROR.code()),
            eq(expected),
            anyChannelPromise());
    assertEquals(State.CLOSED, stream.state());
    assertFalse(channel.isActive());
    expected.release();
    debugData.release();
  }

  @Test
  public void receiveGoaway() throws Exception {
    final ByteBuf debugData = bb("foo");
    frameInboundWriter.writeInboundGoAway(2, Http2Error.NO_ERROR.code(), debugData);
    final Http2GoAwayFrame expectedFrame =
        new DefaultHttp2GoAwayFrame(2, Http2Error.NO_ERROR.code(), bb("foo"));
    final Http2GoAwayFrame actualFrame = inboundHandler.readInbound();

    assertEqualsAndRelease(expectedFrame, actualFrame);

    assertNull(inboundHandler.readInbound());
  }

  @Test
  public void unknownFrameTypeShouldThrowAndBeReleased() throws Exception {
    class UnknownHttp2Frame extends AbstractReferenceCounted implements Http2Frame {
      @Override
      public String name() {
        return "UNKNOWN";
      }

      @Override
      protected void deallocate() {}

      @Override
      public ReferenceCounted touch(final Object hint) {
        return this;
      }
    }

    final UnknownHttp2Frame frame = new UnknownHttp2Frame();
    assertEquals(1, frame.refCnt());

    final ChannelFuture f = channel.write(frame);
    f.await();
    assertTrue(f.isDone());
    assertFalse(f.isSuccess());
    assertThat(f.cause(), instanceOf(UnsupportedMessageTypeException.class));
    assertEquals(0, frame.refCnt());
  }

  @Test
  public void goAwayLastStreamIdOverflowed() throws Exception {
    frameInboundWriter.writeInboundHeaders(5, request, 31, false);

    final Http2Stream stream = frameCodec.connection().stream(5);
    assertNotNull(stream);
    assertEquals(State.OPEN, stream.state());

    final ByteBuf debugData = bb("debug");
    final Http2GoAwayFrame goAwayFrame =
        new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR.code(), debugData.retainedDuplicate());
    goAwayFrame.setExtraStreamIds(Integer.MAX_VALUE);

    channel.writeOutbound(goAwayFrame);
    // When the last stream id computation overflows, the last stream id should just be set to 2^31
    // - 1.
    verify(frameWriter)
        .writeGoAway(
            eqFrameCodecCtx(),
            eq(Integer.MAX_VALUE),
            ArgumentMatchers.eq(Http2Error.NO_ERROR.code()),
            eq(debugData),
            anyChannelPromise());
    debugData.release();
    assertEquals(State.CLOSED, stream.state());
    assertFalse(channel.isActive());
  }

  @Test
  public void streamErrorShouldFireExceptionForInbound() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);

    final Http2Stream stream = frameCodec.connection().stream(3);
    assertNotNull(stream);

    final StreamException streamEx = new StreamException(3, Http2Error.INTERNAL_ERROR, "foo");
    channel.pipeline().fireExceptionCaught(streamEx);

    final Http2FrameStreamEvent event = inboundHandler.readInboundMessageOrUserEvent();
    assertEquals(Http2FrameStreamEvent.Type.State, event.type());
    assertEquals(State.OPEN, event.stream().state());
    final Http2HeadersFrame headersFrame = inboundHandler.readInboundMessageOrUserEvent();
    assertNotNull(headersFrame);

    try {
      inboundHandler.checkException();
      fail("stream exception expected");
    } catch (final Http2FrameStreamException e) {
      assertEquals(streamEx, e.getCause());
    }

    assertNull(inboundHandler.readInboundMessageOrUserEvent());
  }

  @Test
  public void streamErrorShouldNotFireExceptionForOutbound() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);

    final Http2Stream stream = frameCodec.connection().stream(3);
    assertNotNull(stream);

    final StreamException streamEx = new StreamException(3, Http2Error.INTERNAL_ERROR, "foo");
    frameCodec.onError(frameCodec.ctx, true, streamEx);

    final Http2FrameStreamEvent event = inboundHandler.readInboundMessageOrUserEvent();
    assertEquals(Http2FrameStreamEvent.Type.State, event.type());
    assertEquals(State.OPEN, event.stream().state());
    final Http2HeadersFrame headersFrame = inboundHandler.readInboundMessageOrUserEvent();
    assertNotNull(headersFrame);

    // No exception expected
    inboundHandler.checkException();

    assertNull(inboundHandler.readInboundMessageOrUserEvent());
  }

  @Test
  public void windowUpdateFrameDecrementsConsumedBytes() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);

    final Http2Connection connection = frameCodec.connection();
    final Http2Stream stream = connection.stream(3);
    assertNotNull(stream);

    final ByteBuf data = Unpooled.buffer(100).writeZero(100);
    frameInboundWriter.writeInboundData(3, data, 0, false);

    final Http2HeadersFrame inboundHeaders = inboundHandler.readInbound();
    assertNotNull(inboundHeaders);
    assertNotNull(inboundHeaders.stream());

    final Http2FrameStream stream2 = inboundHeaders.stream();

    final int before = connection.local().flowController().unconsumedBytes(stream);
    final ChannelFuture f = channel.write(new DefaultHttp2WindowUpdateFrame(100).stream(stream2));
    final int after = connection.local().flowController().unconsumedBytes(stream);
    assertEquals(100, before - after);
    assertTrue(f.isSuccess());
  }

  @Test
  public void windowUpdateMayFail() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);
    final Http2Connection connection = frameCodec.connection();
    final Http2Stream stream = connection.stream(3);
    assertNotNull(stream);

    final Http2HeadersFrame inboundHeaders = inboundHandler.readInbound();
    assertNotNull(inboundHeaders);

    final Http2FrameStream stream2 = inboundHeaders.stream();

    // Fails, cause trying to return too many bytes to the flow controller
    final ChannelFuture f = channel.write(new DefaultHttp2WindowUpdateFrame(100).stream(stream2));
    assertTrue(f.isDone());
    assertFalse(f.isSuccess());
    assertThat(f.cause(), instanceOf(Http2Exception.class));
  }

  @Test
  public void inboundWindowUpdateShouldBeForwarded() throws Exception {
    frameInboundWriter.writeInboundHeaders(3, request, 31, false);
    frameInboundWriter.writeInboundWindowUpdate(3, 100);
    // Connection-level window update
    frameInboundWriter.writeInboundWindowUpdate(0, 100);

    final Http2HeadersFrame headersFrame = inboundHandler.readInbound();
    assertNotNull(headersFrame);

    final Http2WindowUpdateFrame windowUpdateFrame = inboundHandler.readInbound();
    assertNotNull(windowUpdateFrame);
    assertEquals(3, windowUpdateFrame.stream().id());
    assertEquals(100, windowUpdateFrame.windowSizeIncrement());

    // Window update for the connection should not be forwarded.
    assertNull(inboundHandler.readInbound());
  }

  @Test
  public void streamZeroWindowUpdateIncrementsConnectionWindow() throws Http2Exception {
    final Http2Connection connection = frameCodec.connection();
    final Http2LocalFlowController localFlow = connection.local().flowController();
    final int initialWindowSizeBefore = localFlow.initialWindowSize();
    final Http2Stream connectionStream = connection.connectionStream();
    final int connectionWindowSizeBefore = localFlow.windowSize(connectionStream);
    // We only replenish the flow control window after the amount consumed drops below the following
    // threshold.
    // We make the threshold very "high" so that window updates will be sent when the delta is
    // relatively small.
    ((DefaultHttp2LocalFlowController) localFlow).windowUpdateRatio(connectionStream, .999f);

    final int windowUpdate = 1024;

    channel.write(new DefaultHttp2WindowUpdateFrame(windowUpdate));

    // The initial window size is only changed by Http2Settings, so it shouldn't change.
    assertEquals(initialWindowSizeBefore, localFlow.initialWindowSize());
    // The connection window should be increased by the delta amount.
    assertEquals(connectionWindowSizeBefore + windowUpdate, localFlow.windowSize(connectionStream));
  }

  @Test
  public void windowUpdateDoesNotOverflowConnectionWindow() {
    final Http2Connection connection = frameCodec.connection();
    final Http2LocalFlowController localFlow = connection.local().flowController();
    final int initialWindowSizeBefore = localFlow.initialWindowSize();

    channel.write(new DefaultHttp2WindowUpdateFrame(Integer.MAX_VALUE));

    // The initial window size is only changed by Http2Settings, so it shouldn't change.
    assertEquals(initialWindowSizeBefore, localFlow.initialWindowSize());
    // The connection window should be increased by the delta amount.
    assertEquals(Integer.MAX_VALUE, localFlow.windowSize(connection.connectionStream()));
  }

  @Test
  public void writeUnknownFrame() {
    final Http2FrameStream stream = frameCodec.newStream();

    final ByteBuf buffer = Unpooled.buffer().writeByte(1);
    final DefaultHttp2UnknownFrame unknownFrame =
        new DefaultHttp2UnknownFrame((byte) 20, new Http2Flags().ack(true), buffer);
    unknownFrame.stream(stream);
    channel.write(unknownFrame);

    verify(frameWriter)
        .writeFrame(
            eqFrameCodecCtx(),
            eq(unknownFrame.frameType()),
            eq(unknownFrame.stream().id()),
            eq(unknownFrame.flags()),
            eq(buffer),
            any(ChannelPromise.class));
  }

  @Test
  public void sendSettingsFrame() {
    final Http2Settings settings = new Http2Settings();
    channel.write(new DefaultHttp2SettingsFrame(settings));

    verify(frameWriter).writeSettings(eqFrameCodecCtx(), same(settings), any(ChannelPromise.class));
  }

  @Test(timeout = 5000)
  public void newOutboundStream() {
    final Http2FrameStream stream = frameCodec.newStream();

    assertNotNull(stream);
    assertFalse(isStreamIdValid(stream.id()));

    final Promise<Void> listenerExecuted = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    channel
        .writeAndFlush(
            new DefaultHttp2HeadersFrame(new DefaultHttp2Headers(), false).stream(stream))
        .addListener(
            new ChannelFutureListener() {
              @Override
              public void operationComplete(final ChannelFuture future) throws Exception {
                assertTrue(future.isSuccess());
                assertTrue(isStreamIdValid(stream.id()));
                listenerExecuted.setSuccess(null);
              }
            });
    final ByteBuf data = Unpooled.buffer().writeZero(100);
    final ChannelFuture f = channel.writeAndFlush(new DefaultHttp2DataFrame(data).stream(stream));
    assertTrue(f.isSuccess());

    listenerExecuted.syncUninterruptibly();
    assertTrue(listenerExecuted.isSuccess());
  }

  @Test
  public void newOutboundStreamsShouldBeBuffered() throws Exception {
    setUp(
        Http2FrameCodecBuilder.forServer().encoderEnforceMaxConcurrentStreams(true),
        new Http2Settings().maxConcurrentStreams(1));

    final Http2FrameStream stream1 = frameCodec.newStream();
    final Http2FrameStream stream2 = frameCodec.newStream();

    final ChannelPromise promise1 = channel.newPromise();
    final ChannelPromise promise2 = channel.newPromise();

    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream1), promise1);
    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream2), promise2);

    assertTrue(isStreamIdValid(stream1.id()));
    channel.runPendingTasks();
    assertTrue(isStreamIdValid(stream2.id()));

    assertTrue(promise1.syncUninterruptibly().isSuccess());
    assertFalse(promise2.isDone());

    // Increase concurrent streams limit to 2
    frameInboundWriter.writeInboundSettings(new Http2Settings().maxConcurrentStreams(2));

    channel.flush();

    assertTrue(promise2.syncUninterruptibly().isSuccess());
  }

  @Test
  public void multipleNewOutboundStreamsShouldBeBuffered() throws Exception {
    // We use a limit of 1 and then increase it step by step.
    setUp(
        Http2FrameCodecBuilder.forServer().encoderEnforceMaxConcurrentStreams(true),
        new Http2Settings().maxConcurrentStreams(1));

    final Http2FrameStream stream1 = frameCodec.newStream();
    final Http2FrameStream stream2 = frameCodec.newStream();
    final Http2FrameStream stream3 = frameCodec.newStream();

    final ChannelPromise promise1 = channel.newPromise();
    final ChannelPromise promise2 = channel.newPromise();
    final ChannelPromise promise3 = channel.newPromise();

    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream1), promise1);
    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream2), promise2);
    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream3), promise3);

    assertTrue(isStreamIdValid(stream1.id()));
    channel.runPendingTasks();
    assertTrue(isStreamIdValid(stream2.id()));

    assertTrue(promise1.syncUninterruptibly().isSuccess());
    assertFalse(promise2.isDone());
    assertFalse(promise3.isDone());

    // Increase concurrent streams limit to 2
    frameInboundWriter.writeInboundSettings(new Http2Settings().maxConcurrentStreams(2));
    channel.flush();

    // As we increased the limit to 2 we should have also succeed the second frame.
    assertTrue(promise2.syncUninterruptibly().isSuccess());
    assertFalse(promise3.isDone());

    frameInboundWriter.writeInboundSettings(new Http2Settings().maxConcurrentStreams(3));
    channel.flush();

    // With the max streams of 3 all streams should be succeed now.
    assertTrue(promise3.syncUninterruptibly().isSuccess());

    assertFalse(channel.finishAndReleaseAll());
  }

  @Test
  public void streamIdentifiersExhausted() throws Http2Exception {
    final int maxServerStreamId = Integer.MAX_VALUE - 1;

    assertNotNull(frameCodec.connection().local().createStream(maxServerStreamId, false));

    final Http2FrameStream stream = frameCodec.newStream();
    assertNotNull(stream);

    final ChannelPromise writePromise = channel.newPromise();
    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream), writePromise);

    final Http2GoAwayFrame goAwayFrame = inboundHandler.readInbound();
    assertNotNull(goAwayFrame);
    assertEquals(Http2Error.NO_ERROR.code(), goAwayFrame.errorCode());
    assertEquals(Integer.MAX_VALUE, goAwayFrame.lastStreamId());
    goAwayFrame.release();
    assertThat(writePromise.cause(), instanceOf(Http2NoMoreStreamIdsException.class));
  }

  @Test
  public void receivePing() throws Http2Exception {
    frameInboundWriter.writeInboundPing(false, 12345L);

    final Http2PingFrame pingFrame = inboundHandler.readInbound();
    assertNotNull(pingFrame);

    assertEquals(12345, pingFrame.content());
    assertFalse(pingFrame.ack());
  }

  @Test
  public void sendPing() {
    channel.writeAndFlush(new DefaultHttp2PingFrame(12345));

    verify(frameWriter).writePing(eqFrameCodecCtx(), eq(false), eq(12345L), anyChannelPromise());
  }

  @Test
  public void receiveSettings() throws Http2Exception {
    final Http2Settings settings = new Http2Settings().maxConcurrentStreams(1);
    frameInboundWriter.writeInboundSettings(settings);

    final Http2SettingsFrame settingsFrame = inboundHandler.readInbound();
    assertNotNull(settingsFrame);
    assertEquals(settings, settingsFrame.settings());
  }

  @Test
  public void sendSettings() {
    final Http2Settings settings = new Http2Settings().maxConcurrentStreams(1);
    channel.writeAndFlush(new DefaultHttp2SettingsFrame(settings));

    verify(frameWriter).writeSettings(eqFrameCodecCtx(), eq(settings), anyChannelPromise());
  }

  @Test
  public void iterateActiveStreams() throws Exception {
    setUp(
        Http2FrameCodecBuilder.forServer().encoderEnforceMaxConcurrentStreams(true),
        new Http2Settings().maxConcurrentStreams(1));

    frameInboundWriter.writeInboundHeaders(3, request, 0, false);

    final Http2HeadersFrame headersFrame = inboundHandler.readInbound();
    assertNotNull(headersFrame);

    final Http2FrameStream activeInbond = headersFrame.stream();

    final Http2FrameStream activeOutbound = frameCodec.newStream();
    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(activeOutbound));

    final Http2FrameStream bufferedOutbound = frameCodec.newStream();
    channel.writeAndFlush(
        new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(bufferedOutbound));

    @SuppressWarnings("unused")
    final Http2FrameStream idleStream = frameCodec.newStream();

    final Set<Http2FrameStream> activeStreams = new HashSet<Http2FrameStream>();
    frameCodec.forEachActiveStream(
        new Http2FrameStreamVisitor() {
          @Override
          public boolean visit(final Http2FrameStream stream) {
            activeStreams.add(stream);
            return true;
          }
        });

    assertEquals(2, activeStreams.size());

    final Set<Http2FrameStream> expectedStreams = new HashSet<Http2FrameStream>();
    expectedStreams.add(activeInbond);
    expectedStreams.add(activeOutbound);
    assertEquals(expectedStreams, activeStreams);
  }

  @Test
  public void streamShouldBeOpenInListener() {
    final Http2FrameStream stream2 = frameCodec.newStream();
    assertEquals(State.IDLE, stream2.state());

    final AtomicBoolean listenerExecuted = new AtomicBoolean();
    channel
        .writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()).stream(stream2))
        .addListener(
            new ChannelFutureListener() {
              @Override
              public void operationComplete(final ChannelFuture future) throws Exception {
                assertTrue(future.isSuccess());
                assertEquals(State.OPEN, stream2.state());
                listenerExecuted.set(true);
              }
            });

    assertTrue(listenerExecuted.get());
  }

  @Test
  public void upgradeEventNoRefCntError() throws Exception {
    frameInboundWriter.writeInboundHeaders(
        Http2CodecUtil.HTTP_UPGRADE_STREAM_ID, request, 31, false);
    // Using reflect as the constructor is package-private and the class is final.
    final Constructor<UpgradeEvent> constructor =
        UpgradeEvent.class.getDeclaredConstructor(CharSequence.class, FullHttpRequest.class);

    // Check if we could make it accessible which may fail on java9.
    Assume.assumeTrue(ReflectionUtil.trySetAccessible(constructor, true) == null);

    final HttpServerUpgradeHandler.UpgradeEvent upgradeEvent =
        constructor.newInstance(
            "HTTP/2", new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
    channel.pipeline().fireUserEventTriggered(upgradeEvent);
    assertEquals(1, upgradeEvent.refCnt());
  }

  @Test
  public void upgradeWithoutFlowControlling() throws Exception {
    channel
        .pipeline()
        .addAfter(
            frameCodec.ctx.name(),
            null,
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(final ChannelHandlerContext ctx, final Object msg)
                  throws Exception {
                if (msg instanceof Http2DataFrame) {
                  // Simulate consuming the frame and update the flow-controller.
                  final Http2DataFrame data = (Http2DataFrame) msg;
                  ctx.writeAndFlush(
                          new DefaultHttp2WindowUpdateFrame(data.initialFlowControlledBytes())
                              .stream(data.stream()))
                      .addListener(
                          new ChannelFutureListener() {
                            @Override
                            public void operationComplete(final ChannelFuture future)
                                throws Exception {
                              final Throwable cause = future.cause();
                              if (cause != null) {
                                ctx.fireExceptionCaught(cause);
                              }
                            }
                          });
                }
                ReferenceCountUtil.release(msg);
              }
            });

    frameInboundWriter.writeInboundHeaders(
        Http2CodecUtil.HTTP_UPGRADE_STREAM_ID, request, 31, false);

    // Using reflect as the constructor is package-private and the class is final.
    final Constructor<UpgradeEvent> constructor =
        UpgradeEvent.class.getDeclaredConstructor(CharSequence.class, FullHttpRequest.class);

    // Check if we could make it accessible which may fail on java9.
    Assume.assumeTrue(ReflectionUtil.trySetAccessible(constructor, true) == null);

    final String longString = new String(new char[70000]).replace("\0", "*");
    final DefaultFullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", bb(longString));

    final HttpServerUpgradeHandler.UpgradeEvent upgradeEvent =
        constructor.newInstance("HTTP/2", request);
    channel.pipeline().fireUserEventTriggered(upgradeEvent);
  }

  private ChannelHandlerContext eqFrameCodecCtx() {
    return eq(frameCodec.ctx);
  }
}