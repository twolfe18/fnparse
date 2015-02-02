package edu.jhu.hlt.fnparse.experiment.grid;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import redis.clients.jedis.Jedis;

public interface ResultReporter {
  public static final Logger LOG = Logger.getLogger(ResultReporter.class);

  public void reportResult(
      double mainResult,
      String jobName,
      Map<String, String> ancillaryInfo);

  // Use this with reportResult
  public static Map<String, String> mapToString(Map<?, ?> m) {
    Map<String, String> r = new HashMap<>();
    for (Entry<?, ?> x : m.entrySet())
      r.put(x.getKey().toString(), x.getValue().toString());
    return r;
  }

  // Use with getReporters
  public static Map<Object, Object> mapToObj(Map<?, ?> m) {
    Map<Object, Object> r = new HashMap<>();
    for (Entry<?, ?> x : m.entrySet())
      r.put(x.getKey(), x.getValue());
    return r;
  }

  /**
   * You can use the key "resultReporter" -> "redis:host,channel,port"
   * or "resultReporter" -> "none", but something should be there.
   * 
   * Multiple ResultReporters can be used by putting tabs between the entries
   * in the value.
   */
  public static List<ResultReporter> getReporters(Map<Object, Object> config) {
    String key = "resultReporter";
    String name = (String) config.get(key);
    if (name == null || "none".equalsIgnoreCase(name)) {
      if (name == null)
        LOG.warn(key + " was not specified");
      return Arrays.asList(NO_REPORTING);
    }
    // Don't have two consecutive spaces in a file path!
    // (this is very useful for pasting from the command line into eclipse)
    String[] names = name.split("\t|  ");
    List<ResultReporter> reporters = new ArrayList<>();
    for (String n : names) {
      String ln = n.toLowerCase();
      if (ln.startsWith("redis:")) {
        String[] toks = n.substring("redis:".length(), n.length()).split(",");
        // https://bugs.eclipse.org/bugs/show_bug.cgi?id=435664
        //assert toks.length == 3;
        if (toks.length != 3)
          throw new RuntimeException("name=" + name);
        reporters.add(new Redis(toks[0], toks[1], Integer.parseInt(toks[2])));
      } else if (ln.startsWith("file:")) {
        String path = n.substring(ln.indexOf(':') + 1);
        reporters.add(new File(path));
      } else {
        LOG.warn("could not parse result reporter: " + n);
      }
    }
    if (reporters.size() == 0)
      throw new RuntimeException("failed to parse any result reporters");
    return reporters;
  }

  public static final ResultReporter NO_REPORTING = new ResultReporter() {
    @Override
    public void reportResult(
        double mainResult,
        String jobName,
        Map<String, String> ancillaryInfo) {}
  };

  /**
   * Writes results to a file
   */
  public static final class File implements ResultReporter {
    private final java.io.File file;
    public File(String filename) {
      file = new java.io.File(filename);
    }
    @Override
    public void reportResult(
        double mainResult,
        String jobName,
        Map<String, String> ancillaryInfo) {
      try {
        BufferedWriter w = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(file)));
        w.write(String.format("%f\t%s\n", mainResult, jobName));
        for (Entry<String, String> x : ancillaryInfo.entrySet()) {
          w.write(x.getKey());
          w.write("\t");
          w.write(x.getValue());
          w.write("\n");
        }
        w.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Sends a result via redis pubsub.
   */
  public static class Redis implements ResultReporter {
    private String host;
    private String channel;
    private int port;

    public Redis(String host, String channel, int port) {
      this.host = host;
      this.channel = channel;
      this.port = port;
    }

    @Override
    public void reportResult(
        double mainResult,
        String jobName,
        Map<String, String> ancillaryInfo) {
      StringBuilder anc = new StringBuilder();
      List<String> keys = new ArrayList<>();
      keys.addAll(ancillaryInfo.keySet());
      Collections.sort(keys);
      for (String key : keys) {
        if (anc.length() > 0)
          anc.append("_");
        anc.append(key);
        anc.append("=");
        anc.append(ancillaryInfo.get(key));
      }
      String message = String.format("result %f\t%s\t%s",
          mainResult, jobName, anc.toString());
      Jedis r = new Jedis(host, port, 60 * 1000);
      long f = r.publish(channel, message);
      if (f == 0) {
        System.out.println("failed to phone home!");
        System.out.println(message);
      }
      r.close();
      System.out.println("success!");
    }
  }
}
