package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;

/**
 * This specifies a set of frames marked in text (FNTagging) as well as a pruned
 * set of spans that are allowable for every frame.
 * 
 * @author travis
 */
public class FNParseSpanPruning extends FNTagging implements Serializable {
  private static final long serialVersionUID = -8606045163978711124L;

  // Irrespective of role.
  // Every key should be a FrameInstance.frameMention
  private Map<FrameInstance, List<Span>> possibleArgs;

  public void addSpan(FrameInstance key, Span value) {
    possibleArgs.get(key).add(value);
  }

  public FNParseSpanPruning(
      Sentence s,
      List<FrameInstance> frameMentions,
      Map<FrameInstance, List<Span>> possibleArgs) {
    super(s, frameMentions);
    this.possibleArgs = possibleArgs;
    for (FrameInstance fi : frameMentions) {
      // fi may have arguments, whereas the keys in possibleArgs will not,
      // and will only represent a (frame,target) pair.
      FrameInstance key = FrameInstance.frameMention(
          fi.getFrame(), fi.getTarget(), sent);
      //if (!possibleArgs.containsKey(key))
      //  assert false;
      List<Span> spans = possibleArgs.get(key);
      assert spans != null; // see above
      Set<Span> seen = new HashSet<>();
      boolean foundNS = false;
      for (Span sp : spans) {
        foundNS |= sp == Span.nullSpan;
        if (sp.end > s.size()) {
          Log.warn(sp + " cannot fit in a sentence of length "+ s.size() + " (" + s.getId() + ")");
          //assert false;
        }
        assert seen.add(sp);
      }
      assert foundNS;
    }
  }

  // TODO fix/remove me
  public double getTargetWeight(Frame f, Span target) {
    double d = 1d;
    for (FrameInstance fi : frameInstances) {
      if (fi.getFrame().equals(f) && fi.getTarget().equals(target)) {
        if (fi instanceof WeightedFrameInstance)
          d = ((WeightedFrameInstance) fi).getTargetWeight();
      }
    }
    return d;
  }

  /**
    * For each FrameRoleInstance, if we included the correct span in the pruning
    * mask, did we predict the correct span in hyp?
    * Only counts roles/args where the role is realized in the gold parse.
    * 
    * This is implemented by adding to perf, where a TP counts as a case where
    * we predicted the correct span and FP counts as a case we didn't. Both of
    * these counts are in the case where the correct span was in the pruning
    * mask. FNs are not counted.
   */
  public static void precisionOnProperlyPrunedFrameRoleInstances(
      FNParseSpanPruning pruning,
      FNParse hyp,
      FNParse gold,
      FPR perf) {
    Map<FrameRoleInstance, Set<Span>> prune = pruning.getMapRepresentation();
    Map<FrameRoleInstance, Span> hypE = hyp.getMapRepresentation();
    Map<FrameRoleInstance, Span> goldE = gold.getMapRepresentation();
    for (FrameRoleInstance fri : goldE.keySet()) {
      Span goldS = goldE.get(fri);
      Set<Span> reachable = prune.get(fri);
      if (reachable != null && reachable.contains(goldS)) {
        Span hypS = hypE.get(fri);
        if (hypS == goldS)
          perf.accumTP();
        else
          perf.accumFP();
      }
    }
  }

  public Map<FrameRoleInstance, Set<Span>> getMapRepresentation() {
    Map<FrameRoleInstance, Set<Span>> explicit = new HashMap<>();
    for (Entry<FrameInstance, List<Span>> x : possibleArgs.entrySet()) {
      FrameInstance fi = x.getKey();
      Set<Span> possible = new HashSet<>();
      possible.addAll(x.getValue());
      for (int k = 0; k < fi.getFrame().numRoles(); k++) {
        FrameRoleInstance key =
            new FrameRoleInstance(fi.getFrame(), fi.getTarget(), k);
        Set<Span> old = explicit.put(key, possible);
        assert old == null;
      }
    }
    return explicit;
  }

  /** Returns the number of span variables that are permitted by this mask */
  public int numPossibleArgs() {
    int numPossibleArgs = 0;
    for (Map.Entry<FrameInstance, List<Span>> x : possibleArgs.entrySet()) {
      Frame f = x.getKey().getFrame();
      List<Span> l = x.getValue();
      numPossibleArgs += f.numRoles() * l.size();
    }
    return numPossibleArgs;
  }

  /** Returns the number of span variables possible without any pruning */
  public int numPossibleArgsNaive() {
    int n = sent.size();
    int numPossibleSpans = (n*(n-1))/2 + n + 1;
    int numPossibleRoles = 0;
    for (FrameInstance fi : this.frameInstances)
      numPossibleRoles += fi.getFrame().numRoles();
    int numPossibleArgs = numPossibleRoles * numPossibleSpans;
    return numPossibleArgs;
  }

  public Frame getFrame(int frameInstanceIndex) {
    return getFrameInstance(frameInstanceIndex).getFrame();
  }

  public Span getTarget(int frameInstanceIndex) {
    return getFrameInstance(frameInstanceIndex).getTarget();
  }

  public List<Span> getPossibleArgs(int frameInstanceIndex) {
    FrameInstance fi = getFrameInstance(frameInstanceIndex);
    FrameInstance key = FrameInstance.frameMention(
        fi.getFrame(), fi.getTarget(), fi.getSentence());
    return getPossibleArgs(key);
  }

  public List<Span> getPossibleArgs(FrameInstance key) {
    List<Span> args = possibleArgs.get(key);
    if (args == null)
      throw new IllegalStateException();
    return args;
  }

  /** Returns the i^{th} (frame,target) */
  public FrameInstance getFrameTarget(int i) {
    FrameInstance fi = frameInstances.get(i);
    return FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), fi.getSentence());
  }

  /** Returns the i^{th} (frame,target,args) where args may be null */
  public FrameInstance getFrameTargetWithArgs(int i) {
    return frameInstances.get(i);
  }

  public String describe() {
    StringBuilder sb = new StringBuilder("<AlmostFNParse of ");
    sb.append(sent.getId());
    sb.append("\n");
    for (int i = 0; i < numFrameInstances(); i++) {
      FrameInstance fi = getFrameTarget(i);
      sb.append(Describe.frameInstance(fi));
      Collection<Span> keep = possibleArgs.get(fi);
      if (keep == null) {
        sb.append(" NULL LIST OF SPANS\n");
      } else if (keep.size() == 0) {
        sb.append(" NO SPANS POSSIBLE\n");
      } else {
        for (Span s : keep)
          sb.append(String.format(" %d-%d", s.start, s.end));
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  /**
   * @return The recall attainable by this pruning mask if it were applied
   * during inference on the gold (frame,target)s in the given parse.
   */
  public FPR recall(FNParse p) {
    boolean macro = false;
    FPR fpr = new FPR(macro);
    perf(p, fpr);
    return fpr;
  }

  public void perf(FNParse p, FPR perf) {
    Set<FrameArgInstance> gold = new HashSet<>();
    for (FrameInstance fi : p.getFrameInstances()) {
      Frame f = fi.getFrame();
      for (int k = 0; k < f.numRoles(); k++) {
        Span s = fi.getArgument(k);
        if (s == Span.nullSpan)
          continue;
        gold.add(new FrameArgInstance(f, fi.getTarget(), k, s));
      }
    }
    Set<FrameArgInstance> hyp = new HashSet<>();
    for (Entry<FrameInstance, List<Span>> x : possibleArgs.entrySet()) {
      FrameInstance fi = x.getKey();
      for (int k = 0; k < fi.getFrame().numRoles(); k++)
        for (Span s : x.getValue())
          hyp.add(new FrameArgInstance(fi.getFrame(), fi.getTarget(), k, s));
    }
    Set<FrameArgInstance> all = new HashSet<>();
    all.addAll(gold);
    all.addAll(hyp);
    for (FrameArgInstance fai : all) {
      boolean g = gold.contains(fai);
      boolean h = hyp.contains(fai);
      if (g && h)
        perf.accumTP();
      if (g && !h)
        perf.accumFN();
      if (!g && h)
        perf.accumFP();
    }
  }

  public static List<FNParseSpanPruning> noisyPruningOf(
      List<FNParse> parses,
      double pIncludeNegativeSpan,
      Random r) {
    List<FNParseSpanPruning> out = new ArrayList<>();
    for (FNParse p : parses)
      out.add(noisyPruningOf(p, pIncludeNegativeSpan, r));
    return out;
  }

  /**
   * Creates a pruning that includes all of the spans in the given parse, plus
   * some randomly included others. Every span that is not used in the given
   * parse is included with probability pIncludeNegativeSpan. Span.nullSpan is
   * also always included as an allowable span in the pruning.
   */
  public static FNParseSpanPruning noisyPruningOf(
      FNParse p,
      double pIncludeNegativeSpan,
      Random r) {
    Map<FrameInstance, List<Span>> possibleArgs = new HashMap<>();
    int n = p.getSentence().size();
    for (FrameInstance fi : p.getFrameInstances()) {
      Set<Span> args = new HashSet<>();
      for (int k = 0; k < fi.getFrame().numRoles(); k++)
        args.add(fi.getArgument(k));
      args.add(Span.nullSpan);
      for (int start = 0; start < n; start++)
        for (int end = start + 1; end <= n; end++)
          if (r.nextDouble() < pIncludeNegativeSpan)
            args.add(Span.getSpan(start, end));
      List<Span> argsList = new ArrayList<>();
      argsList.addAll(args);
      FrameInstance key = FrameInstance.frameMention(
          fi.getFrame(), fi.getTarget(), fi.getSentence());
      List<Span> old = possibleArgs.put(key, argsList);
      if (old != null)
        throw new RuntimeException();
    }
    return new FNParseSpanPruning(
        p.getSentence(), p.getFrameInstances(), possibleArgs);
  }

  public static List<FNParseSpanPruning> optimalPrune(List<FNParse> ps) {
    List<FNParseSpanPruning> prunes = new ArrayList<>();
    for (FNParse p : ps)
      prunes.add(optimalPrune(p));
    return prunes;
  }

  /**
   * @return an AlmostParse that represents the minimal set of arguments
   * required to cover all of the arguments for each frame instance in the
   * given parse. The set of possible argument spans for each (frame,target,role)
   * will contain Span.nullSpan.
   */
  public static FNParseSpanPruning optimalPrune(FNParse p) {
    Map<FrameInstance, List<Span>> possibleArgs = new HashMap<>();
    for (FrameInstance fi : p.getFrameInstances()) {
      Set<Span> args = new HashSet<>();
      for (int k = 0; k < fi.getFrame().numRoles(); k++)
        args.add(fi.getArgument(k));
      args.add(Span.nullSpan);
      List<Span> argsList = new ArrayList<>();
      argsList.addAll(args);
      FrameInstance key = FrameInstance.frameMention(
          fi.getFrame(), fi.getTarget(), fi.getSentence());
      List<Span> old = possibleArgs.put(key, argsList);
      if (old != null)
        throw new RuntimeException();
    }
    return new FNParseSpanPruning(
        p.getSentence(), p.getFrameInstances(), possibleArgs);
  }
}
