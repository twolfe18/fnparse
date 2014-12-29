package edu.jhu.hlt.fnparse.rl;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.rl.Learner.Adjoints;
import edu.jhu.hlt.fnparse.rl.Learner.Params;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

public class FeatureParams implements Params {
  public static final Logger LOG = Logger.getLogger(FeatureParams.class);

  class Adj implements Adjoints {
    private final FeatureVector features;
    private final Action action;
    public Adj(FeatureVector features, Action action) {
      this.features = features;
      this.action = action;
    }
    @Override
    public double getScore() {
      return features.dot(theta);
    }
    @Override
    public Action getAction() {
      return action;
    }
  }

  private Alphabet<String> features;
  private double[] theta;
  private double learningRate = 0.1d;
  private Adj cache;

  public FeatureParams() {
    features = new Alphabet<>();
    theta = new double[1024];
  }

  private void b(String featureName) {
    //LOG.info("[b] adding: " + featureName);
    int i = features.lookupIndex(featureName, true);
    cache.features.add(i, 1d);
  }

  private void bb(String featureName, String... backoffs) {
    b(featureName);
    for (String b : backoffs)
      b(featureName + "-" + b);
  }

  @Override
  public Adjoints score(State s, Action a) {
    //LOG.info("[score] starting ");// + a.toString(s));
    cache = new Adj(new FeatureVector(), a);
    FrameInstance fi = s.getFrameInstance(a.t);
    Span t = fi.getTarget();
    String f = fi.getFrame().getName();
    String r = fi.getFrame().getRole(a.k);
    String fr = f + "." + r;
    String m = a.mode == Action.COMMIT ? "mode=COMMIT" : "mode=unknown";
    bb(m, f, fr, r);
    if (a.aspan.isNormalSpan()) {
      Span arg = a.aspan.getSpan();

      String w = "width=" + BasicFeatureTemplates.sentenceLengthBuckets(a.aspan.width());
      bb(w, f, fr, r);

      String rel = "rel=" + BasicFeatureTemplates.spanPosRel(t, arg);
      bb(rel, f, fr, r);

      String dist = null;
      if (arg.after(t))
        dist = BasicFeatureTemplates.sentenceLengthBuckets(arg.start - t.end);
      else if (arg.before(t))
        dist = BasicFeatureTemplates.sentenceLengthBuckets(t.start - arg.end);
      else
        dist = "overlap";
      dist = "dist=" + dist;

      bb(dist, f, fr, r);
      bb(rel + "-" + dist, f, fr, r);
    } else {
      b("abnormalSpan");
    }
    // Check that theta is big enough
    if (features.size() > theta.length) {
      int n = (int) (features.size() * 1.25d + 0.5d);
      theta = Arrays.copyOf(theta, n);
    }
    return cache;
  }

  @Override
  public void update(Adjoints adj, double reward) {
    //LOG.info("[update] starting ");// + a.toString(s) + " has reward " + reward);
    ((Adj) adj).features.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        theta[arg0] += learningRate * reward * arg1;
        return arg1;
      }
    });
  }

}
