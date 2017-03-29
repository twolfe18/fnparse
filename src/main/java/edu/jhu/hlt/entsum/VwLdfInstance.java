package edu.jhu.hlt.entsum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx;
import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.prim.tuple.Pair;

/**
 * A joined element with pieces from
 *  $ENTITY/distsup*.csoaa_ldf.x      (what are the labels/relations/verbs we are trying to score)
 *  $ENTITY/distsup*.csoaa_ldf.yhat   (what are the model scores/costs)
 *  $ENTITY/distsup*.locations.txt    (where are these relations/verbs located in a sequence of sentences)
 *
 * @author travis
 */
public class VwLdfInstance {
  public StreamingDistSupFeatEx.Fact loc;
  public List<String> labels;
  public DoubleArrayList scores;
  
  public VwLdfInstance(StreamingDistSupFeatEx.Fact loc) {
    this.loc = loc;
    this.labels = new ArrayList<>();
    this.scores = new DoubleArrayList();
  }
  
  public void add(String y, double cost) {
    this.labels.add(y);
    this.scores.add(cost);
  }
  
  public int getSentenceIdx() {
    return loc.sentIdx;
  }
  
  public String getSubjMid(EffSent sent) {
    return sent.mention(loc.subjMention).getFullMid();
  }

  public String getObjMid(EffSent sent) {
    return sent.mention(loc.objMention).getFullMid();
  }

  public List<Pair<String, Double>> getMostLikelyLabels(int k) {
    int n = labels.size();
    assert n == scores.size();
    List<Pair<String, Double>> a = new ArrayList<>(n);
    for (int i = 0; i < n; i++)
      a.add(new Pair<>(labels.get(i), scores.get(i)));
    Collections.sort(a, new Comparator<Pair<?, Double>>() {
      @Override
      public int compare(Pair<?, Double> o1, Pair<?, Double> o2) {
        double s1 = o1.get2();
        double s2 = o2.get2();
        if (s1 < s2)                // These are costs, minimize cost
          return -1;
        if (s2 < s1)
          return +1;
        return 0;
      }
    });
    if (n > k) {
//      a = a.subList(0, k);    // NotSerializableException
      List<Pair<String, Double>> aa = new ArrayList<>(k);
      for (int i = 0; i < k; i++)
        aa.add(a.get(i));
      return aa;
    }
    return a;
  }
  
  @Override
  public String toString() {
    return "(VwLdf " + loc + " n=" + labels.size() + ")";
  }
}