//  GPars (formerly GParallelizer)
//
//  Copyright © 2008-9  The original author or authors
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

package groovyx.gpars.remote.netty;

import groovyx.gpars.remote.BroadcastDiscovery;
import groovyx.gpars.remote.LocalHost;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Transport provider using Netty
 *
 * @author Alex Tkachman
 */
public class NettyTransportProvider extends LocalHost {

  private final Map<UUID, Client> clients = new HashMap<UUID, Client>();

  final Server server = new Server();

  final BroadcastDiscovery broadcastDiscovery;

  public NettyTransportProvider() {
    server.start(this);

    broadcastDiscovery = new BroadcastDiscovery(getId(), server.getAddress()) {
      @Override
      protected void onDiscovery(UUID uuid, SocketAddress address) {
        if (uuid.equals(getId())) {
          return;
        }

        synchronized (clients) {
          Client client = clients.get(uuid);
          if (client == null) {
            clients.put(uuid, new Client(NettyTransportProvider.this, address, uuid));
          }
        }
      }
    };

    broadcastDiscovery.start();
  }

  @Override
  public void disconnect() {
    broadcastDiscovery.stop();

    super.disconnect();

    server.stop();

    for (Client client : clients.values()) {
      client.stop();
    }
  }

  public static class Server {
    private InetSocketAddress address;

    ChannelFactory factory;

    ServerBootstrap bootstrap;

    Channel channel;
    private ServerPipelineFactory pipelineFactory;

    public Server() {
      factory = new NioServerSocketChannelFactory(
              Executors.newCachedThreadPool(MyThreadFactory.instance),
              Executors.newCachedThreadPool(MyThreadFactory.instance));
      bootstrap = new ServerBootstrap(factory);
    }

    public InetSocketAddress getAddress() {
      return address;
    }

    public void start(NettyTransportProvider provider) {
      pipelineFactory = new ServerPipelineFactory(provider);
      bootstrap.setPipelineFactory(pipelineFactory);
      bootstrap.setOption("child.tcpNoDelay", true);
      bootstrap.setOption("child.keepAlive", true);

      channel = bootstrap.bind(new InetSocketAddress(0));
      InetAddress inetAddress;
      try {
        inetAddress = InetAddress.getLocalHost();
      } catch (UnknownHostException e) { //
        inetAddress = ((InetSocketAddress) channel.getLocalAddress()).getAddress();
      }
      address = new InetSocketAddress(inetAddress, ((InetSocketAddress) channel.getLocalAddress()).getPort());
    }

    public void stop() {
      final CountDownLatch latch = new CountDownLatch(1);
      channel.close().addListener(new ChannelFutureListener() {
        public void operationComplete(ChannelFuture future) throws Exception {
          bootstrap.getFactory().releaseExternalResources();
          latch.countDown();
        }
      });
      try {
        latch.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

  }

  public static class Client {
    private final NettyTransportProvider provider;

    ChannelFuture channelFuture;

    final ChannelFactory factory;

    public Client(NettyTransportProvider provider, SocketAddress address, UUID id) {
      this.provider = provider;
      factory = new NioClientSocketChannelFactory(
              Executors.newCachedThreadPool(MyThreadFactory.instance),
              Executors.newCachedThreadPool(MyThreadFactory.instance));

      ClientBootstrap bootstrap = new ClientBootstrap(factory);

      NettyHandler handler = new ClientHandler(this.provider, id);

      bootstrap.getPipeline().addLast("handler", handler);
      bootstrap.setOption("tcpNoDelay", true);
      bootstrap.setOption("keepAlive", true);

      channelFuture = bootstrap.connect(address);
    }

    public void stop() {
      channelFuture.getChannel().close().addListener(new ChannelFutureListener() {
        public void operationComplete(ChannelFuture future) throws Exception {
          factory.releaseExternalResources();
        }
      });
    }
  }

  public static class ServerPipelineFactory implements ChannelPipelineFactory {
    private final NettyTransportProvider provider;

    public ServerPipelineFactory(NettyTransportProvider provider) {
      this.provider = provider;
    }

    public ChannelPipeline getPipeline() throws Exception {
      ChannelPipeline pipeline = org.jboss.netty.channel.Channels.pipeline();
      pipeline.addLast("handler", new NettyHandler(provider));
      return pipeline;
    }
  }

  @ChannelPipelineCoverage("one")
  public static class ClientHandler extends NettyHandler {
    private UUID id;

    private NettyTransportProvider provider;

    public ClientHandler(NettyTransportProvider provider, UUID id) {
      super(provider);
      this.id = id;
      this.provider = provider;
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
      provider.clients.remove(id);
      super.channelDisconnected(ctx, e);
    }
  }

  private static class MyThreadFactory implements ThreadFactory {
    static MyThreadFactory instance = new MyThreadFactory();

    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r);
      thread.setDaemon(true);
      thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
          e.printStackTrace();
        }
      });
      return thread;
    }
  }
}
