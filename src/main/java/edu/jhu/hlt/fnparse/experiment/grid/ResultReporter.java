package edu.jhu.hlt.fnparse.experiment.grid;

import java.util.Map;
import java.util.Map.Entry;

import redis.clients.jedis.Jedis;

public interface ResultReporter {

  public void reportResult(double mainResult, Map<String, String> ancillaryInfo);

  /**
   * You can use the key "resultReporter" -> "redis:host,channel,port"
   */
  public static ResultReporter getReporter(String name) {
    if ("none".equalsIgnoreCase(name))
      return NO_REPORTING;
    String ln = name.toLowerCase();
    if (ln.startsWith("redis:")) {
      String[] toks = name.substring("redis:".length(), name.length()).split(",");
      //assert toks.length == 3;  // https://bugs.eclipse.org/bugs/show_bug.cgi?id=435664
      if (toks.length != 3)
        throw new RuntimeException("name=" + name);
      return new Redis(toks[0], toks[1], Integer.parseInt(toks[2]));
    }
    throw new RuntimeException("unknown name: " + name);
  }

  public static final ResultReporter NO_REPORTING = new ResultReporter() {
    @Override
    public void reportResult(
        double mainResult,
        Map<String, String> ancillaryInfo) {}
  };

  // TODO add a result reporter which chains result reporters
  // TODO add a file result reporter

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

    public void reportResult(
        double mainResult,
        Map<String, String> ancillaryInfo) {
      StringBuilder anc = new StringBuilder();
      for (Entry<String, String> x : ancillaryInfo.entrySet()) {
        if (anc.length() > 0)
          anc.append(" ");
        anc.append(x.getKey());
        anc.append("=");
        anc.append(x.getValue());
      }
      String message = String.format("%f\t%s", mainResult, anc.toString());
      Jedis r = new Jedis(host, port);
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