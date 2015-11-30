package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.arrays.Multinomials;

public class WeightedFrameInstance extends FrameInstance {
  private static final long serialVersionUID = 1L;

  public static class ArgTheory {
    public Span span;
    public double weight;
    public ArgTheory(Span s, double w) {
      span = s;
      weight = w;
    }
  }

  private double targetWeight;

  /**
   * Co-indexed with super.arguments.
   */
  private List<ArgTheory>[] argTheories;

  @SuppressWarnings("unchecked")
  protected WeightedFrameInstance(
      Frame frame, Span target, Span[] arguments, Sentence sent) {
    super(frame, target, arguments, sent);
    targetWeight = 1d;
    argTheories = new List[arguments.length];
    for (int i = 0; i < arguments.length; i++)
      argTheories[i] = new ArrayList<>();
  }

  public static WeightedFrameInstance withWeightOne(FrameInstance fi) {
    WeightedFrameInstance wfi =
        new WeightedFrameInstance(fi.getFrame(), fi.getTarget(), fi.getArguments(), fi.getSentence());
    int K = fi.getFrame().numRoles();
    for (int k = 0; k < K; k++)
      wfi.addArgumentTheory(k, fi.getArgument(k), 1);
    return wfi;
  }

  public static WeightedFrameInstance newWeightedFrameInstance(
      Frame f, Span t, Sentence s) {
    Span[] arguments = new Span[f.numRoles()];
    Arrays.fill(arguments, Span.nullSpan);
    return new WeightedFrameInstance(f, t, arguments, s);
  }

  public double getTargetWeight() {
    return targetWeight;
  }

  public void setTargetWeight(double w) {
    this.targetWeight = w;
  }

  public void addArgumentTheory(int k, Span arg, double confidence) {
    argTheories[k].add(new ArgTheory(arg, confidence));
  }

  public List<ArgTheory> getArgumentTheories(int k) {
    return argTheories[k];
  }

  /**
   * Sets arguments according to an ApproxF1MbrDecode.
   * Prunes the argTheories to only include the decoded ArgTheory.
   */
  public void decode(ApproxF1MbrDecoder decoder) {
    for (int k = 0; k < arguments.length; k++) {
      int n = argTheories[k].size();
      int nullSpanIdx = -1;
      double[] probs = new double[n];
      for (int i = 0; i < n; i++) {
        ArgTheory at = argTheories[k].get(i);
        probs[i] = at.weight;
        if (at.span == Span.nullSpan) {
          assert nullSpanIdx == -1;
          nullSpanIdx = i;
        }
      }
      if (decoder.isLogSpace())
        Multinomials.normalizeLogProps(probs);
      else
        Multinomials.normalizeProps(probs);
      int m = decoder.decode(probs, nullSpanIdx);
      Span s = argTheories[k].get(m).span;
      setArgument(k, s);
      argTheories[k] = Arrays.asList(argTheories[k].get(m));
    }
  }

  private static final Comparator<ArgTheory> byWeightDesc = new Comparator<ArgTheory>() {
    @Override
    public int compare(ArgTheory o1, ArgTheory o2) {
      if (o1.weight < o2.weight)
        return 1;
      if (o1.weight > o2.weight)
        return -1;
      return 0;
    }
  };

  public void sortArgumentTheories() {
    for (int k = 0; k < argTheories.length; k++)
      Collections.sort(argTheories[k], byWeightDesc);
  }

  /**
   * Takes the top-K argument theories for every role.
   * Make sure you've called sortArgumentTheories() first.
   */
  public void pruneArgumentTheories(int maxPerRole) {
    if (maxPerRole < 1)
      throw new IllegalArgumentException();
    for (int k = 0; k < argTheories.length; k++)
      if (argTheories[k].size() > maxPerRole)
        argTheories[k] = argTheories[k].subList(0, maxPerRole);
  }

  /**
   * Make sure you've called sortArgumentTheories() first.
   */
  public void setArgumentTheoriesAsValues() {
    for (int k = 0; k < argTheories.length; k++) {
      if (argTheories[k].size() == 0)
        continue;
      Span s = argTheories[k].get(0).span;
      if (s == Span.nullSpan)
        continue;
      setArgument(k, s);
    }
  }

  /**
   * Differs from numRealizedArguments() in that this counts out of argTheories,
   * can be much larger than the Frame's number of roles.
   */
  public int numRealizedTheories() {
    int s = 0;
    for (List<ArgTheory> l : argTheories)
      for (ArgTheory at : l)
        if (at.span != Span.nullSpan)
          s++;
    return s;
  }
}
