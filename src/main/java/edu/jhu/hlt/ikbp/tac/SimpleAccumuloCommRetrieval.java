package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.TNonblockingServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.access.FetchCommunicationService.Processor;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.services.NotImplementedException;
import edu.jhu.hlt.concrete.services.ServiceInfo;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloFetch;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ForwardedFetchCommunicationRetrieval;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;

/**
 * Gets {@link Communication}s given an id. Similar to {@link ForwardedFetchCommunicationRetrieval}.
 * 
 * Note, the reason to use this instead of {@link SimpleAccumuloFetch} is that this pulls
 * from all different namespaces, so if you have twolfe-cag1 and twolfe-cawiki1, this can
 * retrieve from both simultaneously.
 */
public class SimpleAccumuloCommRetrieval implements FetchCommunicationService.Iface {
  //    // Old way: use simpleaccumulo, only supports a single namespace
  //    private SimpleAccumuloFetch fetch;
  // New way: directly use accumulo, multiple namespaces
  private Connector conn;
  private TDeserializer deser;
  
  public boolean debug = false;

  public SimpleAccumuloCommRetrieval() throws Exception {
    String zks = SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS;
    String i = SimpleAccumuloConfig.DEFAULT_INSTANCE;
    Log.info("connecting to: inst=" + i + " zks=" + zks);
    Instance inst = new ZooKeeperInstance(i, zks);
    Log.info("connecting to " + inst);
    conn = inst.getConnector("reader", new PasswordToken("an accumulo reader"));
    deser = new TDeserializer(SimpleAccumulo.COMM_SERIALIZATION_PROTOCOL);
  }

  public Communication get(String commId) {
    //      return getSimpleAccumulo(commId);
    return getAccumulo(commId);
  }

  private Communication getAccumulo(String commId) {
    if (debug)
      Log.info("commId=" + commId);
    AccumuloIndex.TIMER.start("commRet/acc/scan");
    try (Scanner s = conn.createScanner(SimpleAccumuloConfig.DEFAULT_TABLE, new Authorizations())) {
      s.setRange(Range.exact(commId));
      Iterator<Entry<Key, Value>> iter = s.iterator();
      if (!iter.hasNext()) {
        AccumuloIndex.TIMER.stop("commRet/acc/scan");
        if (debug)
          Log.info("no values!");
        return null;
      }
      Entry<Key, Value> e = iter.next();
      if (iter.hasNext()) {
        Log.info("WARNING: more than one result (returning first) for commId=" + commId
          + " firstKey=" + e.getKey() + " secondKey=" + iter.next().getKey());
      }
      AccumuloIndex.TIMER.stop("commRet/acc/scan");

      AccumuloIndex.TIMER.start("commRet/acc/deser");
      Communication c = new Communication();
      deser.deserialize(c, e.getValue().get());
      AccumuloIndex.TIMER.stop("commRet/acc/deser");

      return c;
    } catch (Exception e) {
      if (debug)
        Log.info("Exception while looking up comm: "  + e.getMessage());
      //throw new RuntimeException(e);
      return null;
    }
  }

  @Override
  public ServiceInfo about() throws TException {
    return null;
  }

  @Override
  public boolean alive() throws TException {
    return true;
  }

  @Override
  public FetchResult fetch(FetchRequest arg0) throws ServicesException, TException {
    // TODO batch scanning
    FetchResult r = new FetchResult();
    r.setCommunications(new ArrayList<>());
    for (String id : arg0.getCommunicationIds()) {
      Communication c = getAccumulo(id);
      if (c != null)
        r.addToCommunications(c);
    }
    return r;
  }

  //    private Communication getSimpleAccumulo(String commId) {
  //      TIMER.start("accCommRet");
  //      FetchRequest fr = new FetchRequest();
  //      fr.addToCommunicationIds(commId);;
  //      try {
  //        FetchResult r = fetch.fetch(fr);
  //        TIMER.stop("accCommRet");
  //        if (!r.isSetCommunications() || r.getCommunicationsSize() == 0)
  //          return null;
  //        return r.getCommunications().get(0);
  //      } catch (Exception e) {
  //        e.printStackTrace();
  //        TIMER.stop("accCommRet");
  //        return null;
  //      }
  //    }

  @Override
  public long getCommunicationCount() throws NotImplementedException, TException {
    throw new NotImplementedException();
  }

  @Override
  public List<String> getCommunicationIDs(long arg0, long arg1) throws NotImplementedException, TException {
    throw new NotImplementedException();
  }
  
  /** Start up a server */
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    int port = config.getInt("port");

    SimpleAccumuloCommRetrieval i = new SimpleAccumuloCommRetrieval();

    i.debug = config.getBoolean("debug", false);
    Log.info("debug=" + i.debug);

    String[] ids = config.getStrings("debugCommIds", new String[] {});
    for (String id : ids) {
      Communication c = i.get(id);
      Log.info(id + "\t" + (c != null));
    }

    // TODO switch to FetchServiceWrapper
    Processor<FetchCommunicationService.Iface> p = new FetchCommunicationService.Processor<>(i);
    TNonblockingServerTransport transport = new TNonblockingServerSocket(port);
    TNonblockingServer.Args serverArgs = new TNonblockingServer.Args(transport);
    serverArgs = serverArgs.processorFactory(new TProcessorFactory(p));
    serverArgs = serverArgs.protocolFactory(new TCompactProtocol.Factory());
    serverArgs = serverArgs.transportFactory(new TFramedTransport.Factory(Integer.MAX_VALUE));
    serverArgs.maxReadBufferBytes = Long.MAX_VALUE;
    TNonblockingServer server = new TNonblockingServer(serverArgs);
    Log.info("Starting the server...");
    server.serve();
  }

}
