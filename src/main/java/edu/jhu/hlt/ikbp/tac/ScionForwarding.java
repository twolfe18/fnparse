package edu.jhu.hlt.ikbp.tac;

import java.util.List;

import org.apache.thrift.TException;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.TNonblockingServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.services.NotImplementedException;
import edu.jhu.hlt.concrete.services.ServiceInfo;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.scion.concrete.server.FetchCommunicationServiceImpl;
import edu.jhu.hlt.scion.core.accumulo.ConnectorFactory;
import edu.jhu.hlt.scion.core.accumulo.ScionConnector;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;

/**
 * This is for when you want {@link Communication}s and are not running on the COE grid.
 * This runs on a COE machine, e.g. test2, and talks to accumulo (arbitrary machines on the COE grid).
 * You connect to this machine via an ssh tunnel.
 */
public class ScionForwarding {

  public static class Forward implements FetchCommunicationService.Iface {
    private FetchCommunicationServiceImpl impl;

    public Forward() {
      System.setProperty("scion.accumulo.zookeepers", "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181");
      System.setProperty("scion.accumulo.instanceName", "minigrid");
      System.setProperty("scion.accumulo.user", "reader");
      System.setProperty("scion.accumulo.password", "an accumulo reader");
      try {
        ConnectorFactory cf = new ConnectorFactory();
        ScionConnector sc = cf.getConnector();
        impl = new FetchCommunicationServiceImpl(sc);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public ServiceInfo about() throws TException {
      return impl.about();
    }

    @Override
    public boolean alive() throws TException {
      return impl.alive();
    }

    @Override
    public FetchResult fetch(FetchRequest arg0) throws ServicesException, TException {
      return impl.fetch(arg0);
    }

    @Override
    public long getCommunicationCount() throws NotImplementedException, TException {
      return impl.getCommunicationCount();
    }

    @Override
    public List<String> getCommunicationIDs(long arg0, long arg1) throws NotImplementedException, TException {
      return impl.getCommunicationIDs(arg0, arg1);
    }
  }
  

  public static class Runner implements AutoCloseable, Runnable {
    private final TNonblockingServerTransport serverXport;
    private final TServer server;
    private final TNonblockingServer.Args servArgs;

    public Runner(FetchCommunicationService.Iface impl, int port) throws TException {
      Log.info("starting on port " + port);
      this.serverXport = new TNonblockingServerSocket(port);
      FetchCommunicationService.Processor<FetchCommunicationService.Iface> proc = new FetchCommunicationService.Processor<>(impl);
      TFramedTransport.Factory transFactory = new TFramedTransport.Factory(Integer.MAX_VALUE);

      TNonblockingServer.Args args = new TNonblockingServer.Args(this.serverXport);
      args.protocolFactory(new TCompactProtocol.Factory());
      args.transportFactory(transFactory);
      args.processorFactory(new TProcessorFactory(proc));
      args.maxReadBufferBytes = Long.MAX_VALUE;

      this.servArgs = args;
      this.server = new TNonblockingServer(this.servArgs);
    }

    @Override
    public void run() {
      this.server.serve();
    }

    @Override
    public void close() {
      this.server.stop();
      this.serverXport.close();
    }
  }

  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Forward forward = new Forward();
    int localPort = config.getInt("port", 34343);
    Runner runner = new Runner(forward, localPort);
    Thread t = new Thread(runner);
    t.start();
    t.join();
  }
}
