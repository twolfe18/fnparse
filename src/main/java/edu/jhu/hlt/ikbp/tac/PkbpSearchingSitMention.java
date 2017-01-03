package edu.jhu.hlt.ikbp.tac;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.tutils.Span;

public class PkbpSearchingSitMention {
  Communication c;
  Tokenization t;
  DependencyParse d;
  int pred;
  int[] args;   // sorted
  Map<String, Double> feat2score;
  
  public static PkbpSearchingSitMention convert(int pred, DependencySyntaxEvents.CoverArgumentsWithPredicates events, Function<String, Double> featureScoring) {
    BitSet args = events.getArguments().get(pred);
    int[] a = new int[args.cardinality()];
    for (int i=0, aa = args.nextSetBit(0); aa >= 0; aa = args.nextSetBit(aa+1), i++)
      a[i] = aa;
    PkbpSearchingSitMention sm = new PkbpSearchingSitMention(pred, a, events.deps, events.t, events.c);
    sm.feat2score = new HashMap<>();
    for (String feat : events.getSituations().get(pred)) {
      double s = featureScoring.apply(feat);
      Object old = sm.feat2score.put(feat, s);
      assert old == null;
    }
    return sm;
  }
  
  public PkbpSearchingSitMention(int pred, int[] args, DependencyParse d, Tokenization t, Communication c) {
    this.pred = pred;
    this.args = args;
    this.d = d;
    this.t = t;
    this.c = c;
  }
  
  public String showPredInContext() {
    return "(SM " + PkbpSearching.showWithTag(t, Span.widthOne(pred), "PRED") + ")";
  }
}