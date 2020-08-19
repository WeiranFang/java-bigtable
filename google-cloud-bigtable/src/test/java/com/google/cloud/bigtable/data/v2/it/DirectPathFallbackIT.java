/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.data.v2.it;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;

import com.google.api.core.ApiFunction;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.test_helpers.env.TestEnvRule;
import com.google.common.base.Stopwatch;
import io.grpc.ManagedChannelBuilder;
import io.grpc.alts.ComputeEngineChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelDuplexHandler;
import io.grpc.netty.shaded.io.netty.channel.ChannelFactory;
import io.grpc.netty.shaded.io.netty.channel.ChannelHandlerContext;
import io.grpc.netty.shaded.io.netty.channel.ChannelPromise;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.grpc.netty.shaded.io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test DirectPath fallback behavior by injecting a ChannelHandler into the netty stack that will
 * disrupt IPv6 communications.
 *
 * <p>WARNING: this test can only be run on a GCE VM and will explicitly ignore
 * GOOGLE_APPLICATION_CREDENTIALS and use the service account associated with the VM.
 */
@RunWith(JUnit4.class)
public class DirectPathFallbackIT {
  // A threshold of completed read calls to observe to ascertain IPv6 is working.
  // This was determined experimentally to account for both gRPC-LB RPCs and Bigtable api RPCs.
  private static final int MIN_COMPLETE_READ_CALLS = 40;
  private static final int NUM_RPCS_TO_SEND = 20;

  // IP address prefixes allocated for DirectPath backends.
  private static final String DP_IPV6_PREFIX = "2001:4860:8040";
  private static final String DP_IPV4_PREFIX = "34.126";

  // grpcLB DNS names used for DirectPath
  private static final String GRPCLB_HOST = "grpclb.directpath.google.internal.";
  private static final String GRPCLB_DUALSTACK_HOST = "grpclb-dualstack.directpath.google.internal.";

  @ClassRule public static TestEnvRule testEnvRule = new TestEnvRule();

  private AtomicBoolean dropDpLbCalls = new AtomicBoolean();
  private AtomicInteger numDpLbCallsBlocked = new AtomicInteger();
  //private AtomicInteger numDpLbAddrRead = new AtomicInteger();

  private AtomicBoolean dropDpBackendCalls = new AtomicBoolean();
  private AtomicInteger numDpBackendCallsBlocked = new AtomicInteger();
  //private AtomicInteger numDpBackendAddrRead = new AtomicInteger();

  private AtomicBoolean dropDpCalls = new AtomicBoolean();
  private AtomicInteger numDpCallsBlocked = new AtomicInteger();
  private AtomicInteger numDpCallsRead = new AtomicInteger();

  private ChannelFactory<NioSocketChannel> channelFactory;
  private EventLoopGroup eventLoopGroup;
  //private BigtableDataSettings bigtableDataSettings;
  private BigtableDataClient instrumentedClient;

  public DirectPathFallbackIT() {
    // Create a transport channel provider that can intercept ipv6 packets.
    channelFactory = new MyChannelFactory();
    eventLoopGroup = new NioEventLoopGroup();
  }

  @Before
  public void setup() throws IOException {
    assume()
        .withMessage("DirectPath integration tests can only run against DirectPathEnv")
        .that(testEnvRule.env().isDirectPathEnabled())
        .isTrue();

    BigtableDataSettings defaultSettings = testEnvRule.env().getDataClientSettings();
    InstantiatingGrpcChannelProvider defaultTransportProvider =
        (InstantiatingGrpcChannelProvider)
            defaultSettings.getStubSettings().getTransportChannelProvider();
    InstantiatingGrpcChannelProvider instrumentedTransportChannelProvider =
        defaultTransportProvider
            .toBuilder()
            .setAttemptDirectPath(true)
            .setPoolSize(1)
            .setChannelConfigurator(
                new ApiFunction<ManagedChannelBuilder, ManagedChannelBuilder>() {
                  @Override
                  public ManagedChannelBuilder apply(ManagedChannelBuilder builder) {
                    injectNettyChannelHandler(builder);

                    // Fail fast when blackhole is active
                    builder.keepAliveTime(1, TimeUnit.SECONDS);
                    builder.keepAliveTimeout(1, TimeUnit.SECONDS);
                    return builder;
                  }
                })
            .build();

    // Inject the instrumented transport provider into a new client
    BigtableDataSettings.Builder settingsBuilder =
        testEnvRule.env().getDataClientSettings().toBuilder();

    settingsBuilder
        .stubSettings()
        .setTransportChannelProvider(instrumentedTransportChannelProvider)
        // Forcefully ignore GOOGLE_APPLICATION_CREDENTIALS
        .setCredentialsProvider(FixedCredentialsProvider.create(ComputeEngineCredentials.create()));

    //bigtableDataSettings = settingsBuilder.build();
    instrumentedClient = BigtableDataClient.create(settingsBuilder.build());
  }

  @After
  public void teardown() {
    if (instrumentedClient != null) {
      instrumentedClient.close();
    }
    if (eventLoopGroup != null) {
      eventLoopGroup.shutdownGracefully();
    }
  }

  @Ignore
  @Test
  public void testFallbackOnOpenChannel() throws InterruptedException, TimeoutException, IOException {
    // Precondition: wait for DirectPath to connect
    assertWithMessage("Failed to observe RPCs over DirectPath").that(exerciseDirectPath())
        .isTrue();

    // Drop any future DirectPath calls on existing channel.
    //dropDpCalls.set(true);
    dropDpLbCalls.set(true);
    dropDpBackendCalls.set(true);

    // New requests should be routed over IPv4 and CFE.
    instrumentedClient.readRow(testEnvRule.env().getTableId(), "nonexistent-row");

    // Verify that the above check was meaningful, by verifying that the blackhole actually dropped
    // packets.
    assertWithMessage("Failed to detect any DirectPath packets in blackhole")
        .that(numDpLbCallsBlocked.get() + numDpBackendCallsBlocked.get())
        .isGreaterThan(0);

    // Make sure that the client will start reading from DirectPath again by sending new requests
    // and checking the injected DirectPath counter has been updated.
    //dropDpCalls.set(false);
    dropDpLbCalls.set(false);
    dropDpBackendCalls.set(false);

    assertWithMessage("Failed to upgrade back to DirectPath").that(exerciseDirectPath()).isTrue();
  }

  @Ignore
  @Test
  public void testFallbackAtBalancerConnection()
      throws InterruptedException, TimeoutException, IOException {
    // Set connections to DirectPath grpcLB to fail.
    dropDpLbCalls.set(true);

    // New connections should fallback to use IPv4 and CFE.
    instrumentedClient.readRow(testEnvRule.env().getTableId(), "nonexistent-row");

    // Verify that the above check was meaningful, by verifying that the grpcLB connection
    // actually failed.
    assertWithMessage("Failed to detect any LB packets got dropped")
        .that(numDpLbCallsBlocked.get())
        .isGreaterThan(0);

    // Make sure that new connections will use DirectPath again by creating new client with read
    // request and checking the injected DirectPath counter has been updated.
    dropDpLbCalls.set(false);

    assertWithMessage("Failed to upgrade back to DirectPath").that(exerciseDirectPath()).isTrue();
  }

  @Test
  public void testFallbackAtBackendConnection()
      throws InterruptedException, TimeoutException, IOException {
    // Set connections to DirectPath backend to fail.
    dropDpBackendCalls.set(true);

    // New connections should fallback to use IPv4 and CFE.
    instrumentedClient.readRow(testEnvRule.env().getTableId(), "nonexistent-row");

    // Verify that the above check was meaningful, by verifying that the grpcLB connection
    // actually failed.
    assertWithMessage("Failed to detect any LB packets got dropped")
        .that(numDpBackendCallsBlocked.get())
        .isGreaterThan(0);

    // Make sure that new connections will use DirectPath again by creating new client with read
    // request and checking the injected DirectPath counter has been updated.
    dropDpBackendCalls.set(false);

    //assertWithMessage("Failed to upgrade back to DirectPath").that(exerciseDirectPath()).isTrue();
  }

  private boolean exerciseDirectPath() throws InterruptedException, TimeoutException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    numDpCallsRead.set(0);

    boolean seenEnough = false;

    while (!seenEnough && stopwatch.elapsed(TimeUnit.MINUTES) < 2) {
      for (int i = 0; i < NUM_RPCS_TO_SEND; i++) {
        instrumentedClient.readRow(testEnvRule.env().getTableId(), "nonexistent-row");
      }
      Thread.sleep(100);
      seenEnough = numDpCallsRead.get() >= MIN_COMPLETE_READ_CALLS;
    }
    return seenEnough;
  }

  /**
   * This is a giant hack to enable testing DirectPath CFE fallback.
   *
   * <p>It unwraps the {@link ComputeEngineChannelBuilder} to inject a NettyChannelHandler to signal
   * IPv6 packet loss.
   */
  private void injectNettyChannelHandler(ManagedChannelBuilder<?> channelBuilder) {
    try {
      // Extract the delegate NettyChannelBuilder using reflection
      Field delegateField = ComputeEngineChannelBuilder.class.getDeclaredField("delegate");
      delegateField.setAccessible(true);

      ComputeEngineChannelBuilder gceChannelBuilder =
          ((ComputeEngineChannelBuilder) channelBuilder);
      Object delegateChannelBuilder = delegateField.get(gceChannelBuilder);

      NettyChannelBuilder nettyChannelBuilder = (NettyChannelBuilder) delegateChannelBuilder;
      nettyChannelBuilder.channelFactory(channelFactory);
      nettyChannelBuilder.eventLoopGroup(eventLoopGroup);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException("Failed to inject the netty ChannelHandler", e);
    }
  }

  /** @see com.google.cloud.bigtable.data.v2.it.DirectPathFallbackIT.MyChannelHandler */
  private class MyChannelFactory implements ChannelFactory<NioSocketChannel> {
    @Override
    public NioSocketChannel newChannel() {
      NioSocketChannel channel = new NioSocketChannel();
      channel.pipeline().addLast(new MyChannelHandler());

      return channel;
    }
  }

  /**
   * A netty {@link io.grpc.netty.shaded.io.netty.channel.ChannelHandler} that can be instructed to
   * make DirectPath packets disappear
   */
  private class MyChannelHandler extends ChannelDuplexHandler {
    private boolean isDpAddr;
    private boolean isDpLbAddr;
    private boolean isDpBackendAddr;

    private SocketAddress remoteAddress;

    @Override
    public void connect(
        ChannelHandlerContext ctx,
        SocketAddress remoteAddress,
        SocketAddress localAddress,
        ChannelPromise promise)
        throws Exception {
      this.remoteAddress = remoteAddress;

      if (remoteAddress instanceof InetSocketAddress) {
        InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteAddress;
        InetAddress inetAddress = inetSocketAddress.getAddress();
        String addr = inetAddress.getHostAddress();
        isDpAddr = addr.startsWith(DP_IPV6_PREFIX) || addr.startsWith(DP_IPV4_PREFIX);
        if (isDpAddr) {
          String hostname = inetAddress.getHostName();
          if (hostname.equals(GRPCLB_HOST) || hostname.equals(GRPCLB_DUALSTACK_HOST)) {
            isDpLbAddr = true;
          } else {
            isDpBackendAddr = true;
          }
        }
      }

      //boolean failConnection = (isDpLbAddr && failDpLbConnection.get())
      //    || (isDpBackendAddr && failDpBackendConnection.get());

      //if (failConnection) {
      //  // Fail the connection fast
      //  if (isDpLbAddr) {
      //    numDpLbConnectionFailed.incrementAndGet();
      //  }
      //  if (isDpBackendAddr) {
      //    numDpBackendConnectionFailed.incrementAndGet();
      //  }
      //  promise.setFailure(new IOException("fake error"));
      //} else {
      //  super.connect(ctx, remoteAddress, localAddress, promise);
      //}
      if (isDpBackendAddr && dropDpBackendCalls.get()) {
	System.out.println("===== fail connection: " + this.remoteAddress);
        promise.setFailure(new IOException("fake error"));
      } else {
	super.connect(ctx, remoteAddress, localAddress, promise);
      }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      boolean dropDpLbCall = isDpLbAddr && dropDpLbCalls.get();
      boolean dropDpBackendCall = isDpBackendAddr && dropDpBackendCalls.get();
      //boolean dropCall = isDpAddr && dropDpCalls.get();
      if (dropDpLbCall) {
	System.out.println("===== drop channelRead: " + this.remoteAddress);
        // Don't notify the next handler and increment counter
        numDpLbCallsBlocked.incrementAndGet();
        ReferenceCountUtil.release(msg);
      } else if (dropDpBackendCall) {
	System.out.println("===== drop channelRead: " + this.remoteAddress);
        numDpBackendCallsBlocked.incrementAndGet();
        ReferenceCountUtil.release(msg);
      } else {
	System.out.println("===== pass channelRead: " + this.remoteAddress);
        super.channelRead(ctx, msg);
      }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      boolean dropDpLbCall = isDpLbAddr && dropDpLbCalls.get();
      boolean dropDpBackendCall = isDpBackendAddr && dropDpBackendCalls.get();
      //boolean dropCall = isDpAddr && dropDpCalls.get();

      if (dropDpLbCall) {
	System.out.println("===== drop channelReadComplete: " + this.remoteAddress);
        // Don't notify the next handler and increment counter
        numDpLbCallsBlocked.incrementAndGet();
      } else if (dropDpBackendCall) {
	System.out.println("===== drop channelReadComplete: " + this.remoteAddress);
        numDpBackendCallsBlocked.incrementAndGet();
      } else {
	System.out.println("===== pass channelReadComplete: " + this.remoteAddress);
        if (isDpAddr) {
          numDpCallsRead.incrementAndGet();
        }
        super.channelReadComplete(ctx);
      }
    }
  }
}
