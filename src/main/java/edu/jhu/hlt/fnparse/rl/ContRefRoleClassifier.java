package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance.PropbankDataException;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.rl.State.SpanLL;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.Beam.Beam1;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

/**
 * In PB, during decoding we train R-ARG-X/C-ARG-X to look like just plain
 * ARG-X mentions, and when we run the transition system we may end up with
 * multiple ARG-X mentions. This is responsible for finding the base ARG-X
 * mention and clasifying the others in ref/cont roles, and building the final
 * FrameInstances/FNParse.
 *
 * Contains a model and needs to be trained.
 *
 * @author travis
 */
public class ContRefRoleClassifier {

  public static enum MentionType {
    BASE,           // Must be at least one of these for ref/cont roles
    PRUNE,          // Means do not include this mention in the final decode
    REFERENCE,
    CONTINUATION,
  }

  public static class ContRefRole {
    private Frame t;
    private int k;
    private MentionType type;
    private Span span;

    public ContRefRole(Frame t, int k, MentionType type, Span span) {
      this.t = t;
      this.k = k;
      this.type = type;
      this.span = span;
    }

    public void setMentionType(MentionType t) {
      this.type = t;
    }

    public boolean isPruned() {
      return type == MentionType.PRUNE;
    }

    public Span getSpan() {
      return span;
    }

    public String getFullRoleName() {
      switch (this.type) {
      case BASE:
        return t.getRole(k);
      case PRUNE:
        return null;
      case REFERENCE:
        return "R-" + t.getRole(k);
      case CONTINUATION:
        return "C-" + t.getRole(k);
      default:
        throw new RuntimeException("unknown type: " + type);
      }
    }
  }

  private int nTrain = 0;
  private LazyL2UpdateVector[] weights;
  private CachedFeatures.Params features;

  /**
   * Borrows features from {@link CachedFeatures.Params}.
   */
  public ContRefRoleClassifier(CachedFeatures.Params features) {
    this.features = features;
    int updateInterval = features.getLazyL2UpdateInterval();
    int D = features.getDimension();
    this.weights = new LazyL2UpdateVector[MentionType.values().length];
    for (int i = 0; i < weights.length; i++)
      weights[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(D), updateInterval);
  }

  public void train(State x, FNParse y) {
    nTrain++;
    
    // TODO Do 4 independent SVM updates, one-vs-all
    
    throw new RuntimeException("implement me");
  }

  public List<ContRefRole> classify(State st, int t, int k) {
    SpanLL mentions = st.getCommitted()[t][k];

    // Easy/un-ambiguous cases
    if (mentions == null)
      return Collections.emptyList();
    if (mentions.next == null) {
      return Arrays.asList(
          new ContRefRole(st.getFrame(t), k, MentionType.BASE, mentions.span));
    }

    if (nTrain == 0)
      Log.warn("not trained to do this!");

    // TODO Take only the spans which have a score of at least max_{spans} score(prune,span)
    // Choose the best base role by span score
    // If any remain, choose best r/c role by score, taking at most 1.


    // What about relative features, like "this is the left-most mention"?
    // I can't get these from CachedFeatures.Params...

    List<ContRefRole> roles = new ArrayList<>();
    Beam1<Span> bestBase = new Beam1<>();
    Beam1<Span> bestRC = new Beam1<>();
    for (SpanLL cur = mentions; cur != null; cur = cur.next) {
      IntDoubleUnsortedVector fv = features.getFeatures(st.getFrames(), t, cur.span);
      double[] scores = new double[weights.length];
      for (int i = 0; i < scores.length; i++)
        scores[i] = weights[i].weights.dot(fv);
    }

    // TODO check that there is 0 or 1 nullSpans in the LL,
    // and that if there is 1 it is at the head
    throw new RuntimeException("implement me");
  }

  public FNParse decode(State st) {
    Sentence s = st.getSentence();
    SpanLL[][] committed = st.getCommitted();
    List<FrameInstance> fis = new ArrayList<>();
    int T = st.numFrameInstance();
    for (int t = 0; t < T; t++) {
      List<Pair<String, Span>> args = new ArrayList<>();
      int K = committed[t].length;
      for (int k = 0; k < K; k++) {
        List<ContRefRole> atk = classify(st, t, k);
        for (ContRefRole cr : atk)
          if (!cr.isPruned())
            args.add(new Pair<>(cr.getFullRoleName(), cr.getSpan()));
      }
      try {
        FrameInstance fi = FrameInstance.buildPropbankFrameInstance(
            st.getFrame(t), st.getTarget(t), args, s);
        fis.add(fi);
      } catch (PropbankDataException pde) {
        throw new RuntimeException(pde);
      }
    }
    return new FNParse(s, fis);
  }
}
