package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.thrift.TDeserializer;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

public class AccumuloBenchmark {
  
  private TDeserializer deser = new TDeserializer(SimpleAccumulo.COMM_SERIALIZATION_PROTOCOL);
  private String instanceName = SimpleAccumuloConfig.DEFAULT_INSTANCE;
  private String zookeepers = SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS;
  private Instance inst;
  private Connector conn;
  private Authorizations auth;

  private List<String> commIds;
  
  private Map<String, Average> avgs;
  
  public AccumuloBenchmark() {
    commIds = new ArrayList<>();
    init();
  }
  
  public int numIds() {
    return commIds.size();
  }
  
  void init() {
    auth = new Authorizations();
    inst = new ZooKeeperInstance(instanceName, zookeepers);
    try {
      conn = inst.getConnector("reader", new PasswordToken("an accumulo reader"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  private void record(String key, double valueToAvg) {
    if (avgs == null)
      avgs = new HashMap<>();
    Average a = avgs.get(key);
    if (a == null) {
      a = new Average.Uniform();
      avgs.put(key, a);
    }
    a.add(valueToAvg);
  }
  
  public double getAvg(String key) {
    return avgs.get(key).getAverage();
  }
  
  public void add(String commId) {
    commIds.add(commId);
  }
  
  public void timeBatch(boolean reinit, int numQueryThreads) throws TableNotFoundException {
    long start = System.currentTimeMillis();
    
    if (reinit)
      init();
    
    List<Range> ranges = new ArrayList<>(commIds.size());
    for (String id : commIds)
      ranges.add(Range.exact(id));
    
    List<String> ids = new ArrayList<>();

    try (BatchScanner bs = conn.createBatchScanner(SimpleAccumuloConfig.DEFAULT_TABLE, auth, numQueryThreads)) {
      bs.setRanges(ranges);
      batch(bs, ids);
    }
    
    long ms = System.currentTimeMillis() - start;
    double rate = ((double) ids.size()) / ms;
    String key = "batch/reinit=" + reinit + "_nQueryThreads=" + numQueryThreads;
    record(key, rate);
    System.out.printf("%.4f %s\n", getAvg(key), key);
  }
  
  private void batch(BatchScanner bs, List<String> ids) {
    for (Entry<Key, Value> e : bs) {
      Communication c = new Communication();
      try {
        deser.deserialize(c, e.getValue().get());
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
      ids.add(c.getId());
    }
  }
  
  public void timeSerial(boolean reinit, boolean rescanner) throws TableNotFoundException {
    long start = System.currentTimeMillis();
    
    if (reinit)
      init();

    List<String> ids = new ArrayList<>();

    if (rescanner) {
      for (String id : commIds) {
        try (Scanner s = conn.createScanner(SimpleAccumuloConfig.DEFAULT_TABLE, auth)) {
          Communication c = serial(s, id);
          ids.add(c.getId());
        }
      }
    } else {
      try (Scanner s = conn.createScanner(SimpleAccumuloConfig.DEFAULT_TABLE, auth)) {
        for (String id : commIds) {
          Communication c = serial(s, id);
          ids.add(c.getId());
        }
      }
    }
    
    long ms = System.currentTimeMillis() - start;
    double rate = ((double) ids.size()) / ms;
    String key = "serial/reinit=" + reinit + "_rescanner=" + rescanner;
    record(key, rate);
    System.out.printf("%.4f %s\n", getAvg(key), key);
  }
  
  private Communication serial(Scanner s, String id) {
    s.setRange(Range.exact(id));
    Entry<Key, Value> e = s.iterator().next();
    Communication c = new Communication();
    try {
      deser.deserialize(c, e.getValue().get());
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    return c;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    AccumuloBenchmark b = new AccumuloBenchmark();
    File f = config.getFile("commIds", new File("sample-docs.txt"));
    for (String line : FileUtil.getLines(f)) {
      System.out.println("id=" + line);
      b.add(line);
    }
    Log.info("added " + b.numIds() + " ids");
    
    int nr = config.getInt("numRounds", 10);
    for (int t = 0; t < nr; t++) {
      System.out.println("round " + (t+1));

      b.timeSerial(true, true);
      b.timeSerial(true, false);
      b.timeSerial(false, true);
      b.timeSerial(false, false);

      b.timeBatch(true, 1);
      b.timeBatch(true, 2);
      b.timeBatch(true, 4);
      b.timeBatch(true, 8);
      b.timeBatch(true, 16);

      b.timeBatch(false, 1);
      b.timeBatch(false, 2);
      b.timeBatch(false, 4);
      b.timeBatch(false, 8);
      b.timeBatch(false, 16);

      System.out.println();
    }
    
  }
}
