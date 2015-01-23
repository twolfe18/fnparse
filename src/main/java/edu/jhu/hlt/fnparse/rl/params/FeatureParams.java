package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.util.Alphabet;

public class FeatureParams implements Params.Stateful {
  public static final Logger LOG = Logger.getLogger(FeatureParams.class);

  private Alphabet<String> featureNames;
  private double[] theta;
  private FeatureVector features;

  public FeatureParams() {
    featureNames = new Alphabet<>();
    theta = new double[1024];
  }

  private void b(String featureName) {
    //LOG.info("[b] adding: " + featureName);
    int i = featureNames.lookupIndex(featureName, true);
    features.add(i, 1d);
  }

  private void bb(String featureName, String... backoffs) {
    b(featureName);
    for (String b : backoffs)
      b(featureName + "-" + b);
  }

  @Override
  public Adjoints score(State s, Action a) {

    // TODO:
    // Does this item overlap with a previously-committed-to item? Its score?
    // How many items have been committed to for this frame instance?
    // How many items have been committed to for this (frame,role)?
    // Set of roles committed to for this frame (unigrams, pairs, and count)
    // Does this item overlap with a target?
    // Number of items committed to on this side of the target.

    //LOG.info("[score] starting ");// + a.toString(s));
    //cache = new Adjoints.SparseFeatures(new FeatureVector(), theta, a);
    features = new FeatureVector();
    FrameInstance fi = s.getFrameInstance(a.t);
    Span t = fi.getTarget();
    String f = fi.getFrame().getName();
    String r = fi.getFrame().getRole(a.k);
    String fr = f + "." + r;
    String m = a.getActionType().getName();
    bb(m, f, fr, r);
    if (a.hasSpan()) {
      Span arg = a.getSpan();

      String w = "width=" + BasicFeatureTemplates.sentenceLengthBuckets(a.width());
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
    if (featureNames.size() > theta.length) {
      int n = (int) (featureNames.size() * 1.6d + 0.5d);
      LOG.info("[score] resizing theta: " + theta.length + " => " + n);
      theta = Arrays.copyOf(theta, n);
    }
    return new Adjoints.Vector(a, new IntDoubleDenseVector(theta), features);
  }

//  @Override
//  public void update(Adjoints adj, double reward) {
//    //LOG.info("[update] starting ");// + a.toString(s) + " has reward " + reward);
//    ((Adjoints.SparseFeatures) adj).update(reward, learningRate);;
//  }

  @Override
  public void doneTraining() {
    LOG.info("[doneTraining] currently doesn't support weight averaging");
  }
}
