package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Span;

public class PkbpSituation implements Serializable, Iterable<PkbpSituation.Mention> {
  private static final long serialVersionUID = 8496181770153129315L;

  public static class Mention extends PkbpMention implements Serializable {
    private static final long serialVersionUID = 6371979856235311899L;

    int[] args;   // sorted

    /**
     * @param pred is a predicate work index (may be an entity in the case where there isn't one, e.g. appos relation
     * @param events stores the arguments of this predicate and features
     * @param featureScoring assigns weights to features, tied to feature extraction
     */
    public static Mention convert(int pred, DependencySyntaxEvents.CoverArgumentsWithPredicates events, Function<String, Double> featureScoring) {
      BitSet args = events.getArguments().get(pred);
      int[] a = new int[args.cardinality()];
      for (int i=0, aa = args.nextSetBit(0); aa >= 0; aa = args.nextSetBit(aa+1), i++)
        a[i] = aa;
      Mention sm = new Mention(pred, a, events.deps, events.t, events.c);
      for (String feat : events.getSituations().get(pred)) {
        double s = featureScoring.apply(feat);
        sm.addFeature(feat, s);
      }
      return sm;
    }

    public Mention(int pred, int[] args, DependencyParse d, Tokenization t, Communication c) {
      super(pred, t, d, c);
      this.args = args;
    }

    public int getPred() {
      return head;
    }

    public String showPredInContext() {
      List<String> a = new ArrayList<>();
      for (int aa : args)
        a.add(getTokenization().getTokenList().getTokenList().get(aa).getText() + "@" + aa);
      return "(SM args=" + a + " " + PkbpSearching.showWithTag(toks, Span.widthOne(head), "PRED") + ")";
    }
  }

  /**
   * For now: a union/sum of the features at the mention level.
   * See DependencySyntaxEvents.CoverArgumentsWithPredicates for feature extraction.
   */
  Map<String, Double> feat2score;

  /** Instances of this situation */
  List<Mention> mentions;

  /**
   * When determining whether a sitMention is an instance of this situation, consider that
   * the arguments to that sitMention should probably be link-able to these entities
   */
  List<PkbpEntity> coreArguments;
  
  public PkbpSituation(Mention canonical) {
    this.mentions = new ArrayList<>();
    this.mentions.add(canonical);
    this.feat2score = new HashMap<>();
    for (Feat f : canonical.getFeatures()) {
      Object old = this.feat2score.put(f.name, f.weight);
      assert old == null;
    }
  }
  
  public void addCoreArgument(PkbpEntity e) {//, Map<PkbpEntity, LL<PkbpSituation>> e2s_inverseMapping) {
    if (coreArguments == null)
      coreArguments = new ArrayList<>();
    coreArguments.add(e);
//    e2s_inverseMapping.put(e, new LL<>(this, e2s_inverseMapping.get(e)));
  }
  
  @Override
  public String toString() {
    return String.format("(Sit heads=%s feats=%s)", getHeads(), Feat.sortAndPrune(feat2score, 4));
  }
  
  public List<String> getHeads() {
    List<String> h = new ArrayList<>();
    for (PkbpSituation.Mention sm : mentions)
      h.add(sm.getHeadString());
    return h;
  }
  
  public String getCanonicalHeadString() {
    if (mentions.isEmpty())
      return "NA";
    return mentions.get(0).getHeadString();
  }
  
  public List<Feat> similarity(Map<String, Double> feat2score) {
    List<Feat> f = new ArrayList<>();
    for (Entry<String, Double> e : feat2score.entrySet()) {
      Double alt = this.feat2score.get(e.getKey());
      if (alt != null) {
        f.add(new Feat(e.getKey(), Math.sqrt(alt * e.getValue())));
      }
    }
    return f;
  }
  
  public void addMention(PkbpSituation.Mention m) {//, Map<PkbpEntity, LL<PkbpSituation>> inverseMapping) {
    for (Feat f : m.getFeatures()) {
      double p = feat2score.getOrDefault(f.name, 0d);
      feat2score.put(f.name, f.weight + p);
    }
    this.mentions.add(m);
  }

  @Override
  public Iterator<Mention> iterator() {
    return mentions.iterator();
  }
}