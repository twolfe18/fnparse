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
import edu.jhu.hlt.fnparse.rl.State.SpanLL;
import edu.jhu.hlt.fnparse.rl.params.TemplatedFeatureParams;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.tuple.Pair;

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
  private double[] weights;
  private TemplatedFeatureParams features;

  public void train(State x, FNParse y) {
    nTrain++;
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
