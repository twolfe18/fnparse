package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class FrameInstanceCorrupter {
  private double pKeepGoldFrame = 0.8d;
  private double pAddBadRole = 0.1d;
  private double pRemoveGoodRole = 0.2d;
  private double expectedNumArgs = 3d;
  private Random rand;
  private List<Frame> allFrames;

  public FrameInstanceCorrupter() {
    rand = new Random(9001);
    allFrames = new ArrayList<>(FrameIndex.getInstance().allFrames());
    Collections.shuffle(allFrames, rand);
  }

  public FNParse corrupt(FNParse gold) {
    Sentence sent = gold.getSentence();
    List<FrameInstance> hyp = new ArrayList<FrameInstance>();
    for(FrameInstance fi : gold.getFrameInstances()) {
      if(rand.nextDouble() < pKeepGoldFrame) {
        // corrupt the roles of the gold frame
        int K = fi.getFrame().numRoles();
        Span[] arguments = new Span[K];
        Arrays.fill(arguments, Span.nullSpan);
        for(int k=0; k<K; k++) {
          Span s = fi.getArgument(k);
          if(s == Span.nullSpan) {
            if(rand.nextDouble() < pAddBadRole)
              arguments[k] = Span.widthOne(rand.nextInt(sent.size()));
          }
          else {
            if(rand.nextDouble() < pRemoveGoodRole)
              arguments[k] = Span.widthOne(rand.nextInt(sent.size()));
            else
              arguments[k] = s;
          }
        }
        hyp.add(FrameInstance.newFrameInstance(fi.getFrame(), fi.getTarget(), arguments, sent));
      }
      else {
        // make up all roles
        Frame f = allFrames.get(rand.nextInt(allFrames.size()));
        int K = f.numRoles();
        Span[] arguments = new Span[K];
        Arrays.fill(arguments, Span.nullSpan);
        int numRealizedArgs = poissonSample(expectedNumArgs, rand);
        double pRealizedArg = ((double) numRealizedArgs) / K;
        for(int k=0; k<K; k++) {
          if(rand.nextDouble() < pRealizedArg)
            arguments[k] = Span.widthOne(rand.nextInt(sent.size()));
        }
        hyp.add(FrameInstance.newFrameInstance(f, fi.getTarget(), arguments, sent));
      }
    }
    return new FNParse(sent, hyp);
  }

  public static int poissonSample(double lambda, Random rand) {
    // CDF method
    // http://en.wikipedia.org/wiki/Poisson_distribution
    int max = 2 + (int) (lambda * 3);
    double[] density = new double[max];
    double z = 0d;
    double el = Math.exp(-lambda);
    double lp = 1d;
    int kf = 1;
    for(int i=0; i<max; i++) {
      density[i] = el * lp / kf;
      z += density[i];
      lp *= lambda;
      if(i > 0) kf *= i;
    }
    double p = rand.nextDouble();
    double sum = 0d;
    for(int i=0; i<max; i++) {
      sum += density[i];
      if(p <= sum / z)
        return i;
    }
    System.err.println("got to the end of poisson sample range! last density = " + density[max-1]);
    return max;
  }

}