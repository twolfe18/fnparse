package edu.jhu.hlt.fnparse.experiment;

import java.util.Arrays;

import redis.clients.jedis.Jedis;

public class ForwardSelectionWorker {

  public static void main(String[] args) {
    long start = System.currentTimeMillis();
    System.out.println("[ForwardSelectionWorker] args: "
        + Arrays.toString(args));
    assert args.length == 5;
    final String channel = args[0];
    final String server = args[1];
    final int port = Integer.parseInt(args[2]);
    final String workingDir = args[3];
    final String config = args[4];
	System.out.println("channel=" + channel);
	System.out.println("server=" + server);
	System.out.println("port=" + port);

    double perf = -1d;
    try {
      String[] trainerArgs = new String[] {
          "frameId",
          "34",
          workingDir,
          config,
          "regular"
      };
      perf = new ParserTrainer().run(trainerArgs);
    } catch (Exception e) {
      e.printStackTrace();
    }

    // phone home
    String message = perf + "\t" + config;
    Jedis r = new Jedis(server, port);
    long f = r.publish(channel, message);
    if (f == 0) {
      System.out.println("failed to phone home!");
      System.out.println(message);
    }
    r.close();
    System.out.printf("success! took %.2f seconds.\n",
        (System.currentTimeMillis() - start) / 1000d);
  }
}
