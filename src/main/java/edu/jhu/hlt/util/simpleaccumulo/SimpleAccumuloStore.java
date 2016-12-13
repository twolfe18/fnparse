package edu.jhu.hlt.util.simpleaccumulo;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TException;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServer.Args;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.access.StoreCommunicationService;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;

/**
 * Simple single-table {@link StoreCommunicationService} using a user-specified column family for isolation.
 *
 * @author travis
 */
public class SimpleAccumuloStore extends SimpleAccumulo implements StoreCommunicationService.Iface, AutoCloseable {

  private BatchWriter writer;
  private int numThreads;

  public SimpleAccumuloStore(SimpleAccumuloConfig config, int numThreads) {
    super(config);
    this.numThreads = numThreads;
  }

  @Override
  public void store(Communication c) throws ServicesException, TException {
    if (writer == null) {
      // BatchWriterConfig has reasonable defaults
      BatchWriterConfig bwConfig = new BatchWriterConfig();
      bwConfig.setMaxWriteThreads(numThreads);
      try {
        writer = conn.createBatchWriter(config.table, bwConfig);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    Text colFam = new Text(config.namespace);
    Value value = new Value(commSer.serialize(c));
    Mutation mutation = new Mutation(c.getId());
    mutation.put(colFam, COMM_COL_QUALIFIER, value);
    try {
      writer.addMutation(mutation);
    } catch (MutationsRejectedException e) {
      e.printStackTrace();
      throw new ServicesException(e.getMessage());
    }
  }
  
  @Override
  public void close() throws Exception {
    if (writer != null) {
      writer.close();
      writer = null;
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    SimpleAccumuloConfig saConf = SimpleAccumuloConfig.fromConfig(config);    
    int nt = config.getInt("numThreads", 4);
    int port = config.getInt("port", 9090);
    Log.info("listening on port=" + port);
    Log.info("using numThreads=" + nt);
    try (SimpleAccumuloStore serv = new SimpleAccumuloStore(saConf, nt)) {
      serv.connect(
          config.getString("accumulo.user"),
          new PasswordToken(config.getString("accumulo.password")));  // TODO better security
      
      TServerTransport serverTransport = new TServerSocket(port);
      TServer server = new TSimpleServer(new Args(serverTransport)
          .processor(new StoreCommunicationService.Processor<>(serv)));

      // Use this for a multithreaded server
      // TServer server = new TThreadPoolServer(new TThreadPoolServer.Args(serverTransport).processor(processor));

      System.out.println("Starting the simple server...");
      server.serve();
    }
  }

}
