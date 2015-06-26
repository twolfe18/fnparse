package edu.jhu.hlt.fnparse.rl.params;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.fnparse.rl.params.Params.NetworkAvg;
import edu.jhu.hlt.tutils.net.NetworkParameterAveraging;
import edu.jhu.hlt.tutils.net.NetworkParameterAveraging.Client;
import edu.jhu.hlt.tutils.net.NetworkParameterAveraging.Server;

public class NetworkAvgParamsTest {

  double tol = 1e-8;
  boolean checkAlphabetEquality = true;
  Random rand = new Random(9001);

  static class TestParams extends FeatureParams implements Params.Stateless {
    private static final long serialVersionUID = 6223766479816973615L;
    public TestParams(double l2Penalty, int numBuckets) {
      super(l2Penalty, numBuckets);
    }
    public void set(int i, double v) {
      theta.getWeights()[i] = v;
    }
    public double get(int i) {
      return theta.getWeights()[i];
    }
    public void show(String name) {
      System.out.print(name);
      double[] w = theta.getWeights();
      for (int i = 0; i < w.length; i++)
        System.out.printf("  %.2f", w[i]);
      System.out.println();
    }
  }

  @Test
  public void test0() throws UnknownHostException, IOException {

    int numBuckets = 100;
    int port = 4242;
    boolean verbose = true;

    // Setup the average (server)
    TestParams serverParams = new TestParams(0, numBuckets);
    NetworkAvg serverParamsSer = new NetworkAvg(serverParams, checkAlphabetEquality);
    Server server = new NetworkParameterAveraging.Server(serverParamsSer, port);
    server.debug = true;
    new Thread(server).start();
    if (verbose)
      serverParams.show("server@0");

    // Setup client A
    TestParams clientAParams = new TestParams(0, numBuckets);
    NetworkAvg clientAParamsSer = new NetworkAvg(clientAParams, checkAlphabetEquality);
    Client clientA = new NetworkParameterAveraging.Client(clientAParamsSer, "localhost", port);
    clientA.debug = true;
    for (int i = 0; i < numBuckets; i++)
      clientAParams.set(i, rand.nextGaussian());
    if (verbose)
      clientAParams.show("clientA@0");

    // Setup client B
    TestParams clientBParams = new TestParams(0, numBuckets);
    NetworkAvg clientBParamsSer = new NetworkAvg(clientBParams, checkAlphabetEquality);
    Client clientB = new NetworkParameterAveraging.Client(clientBParamsSer, "localhost", port);
    clientB.debug = true;
    for (int i = 0; i < numBuckets; i++)
      clientBParams.set(i, rand.nextGaussian());
    if (verbose)
      clientBParams.show("clientB@0");

    // Take average
    clientA.averageParameters();
    if (verbose)
      serverParams.show("server@1");
    clientB.averageParameters();
    if (verbose)
      serverParams.show("server@2");

    // Pull out stuff from server
    TestParams serverSum = (TestParams) serverParamsSer.get();
    TestParams serverAvg = (TestParams) serverParamsSer.getAverage();

    // Check average
    for (int i = 0; i < numBuckets; i++) {
      double avg = serverAvg.get(i);
      double sum = serverSum.get(i);
      double a = clientAParams.get(i);
      double b = clientBParams.get(i);
      assertEquals("a=" + a + " b=" + b, (a + b) / 2, avg, tol);
      assertEquals("a=" + a + " b=" + b, a + b, sum, tol);
    }
  }
}
