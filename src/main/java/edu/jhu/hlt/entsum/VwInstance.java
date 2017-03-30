package edu.jhu.hlt.entsum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
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
public class VwInstance {
  public StreamingDistSupFeatEx.Fact loc;
  public List<String> labels;
  public DoubleArrayList scores;
  
  // TODO Could add fx:List<String> field for features
  // ldf: these would be the shared features
  // binary: only one sense of features
  
  public VwInstance(StreamingDistSupFeatEx.Fact loc) {
    this.loc = loc;
    this.labels = new ArrayList<>();
    this.scores = new DoubleArrayList();
  }

  public double maxScore() {
    double m = scores.get(0);
    for (int i = 1; i < scores.size(); i++)
      m = Math.max(m, scores.get(i));
    return m;
  }
  
  public double minCost() {
    double m = scores.get(0);
    for (int i = 1; i < scores.size(); i++)
      m = Math.min(m, scores.get(i));
    return m;
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
    List<Feat> fs = new ArrayList<>();
    for (int i = 0; i < labels.size(); i++)
      fs.add(new Feat(labels.get(i), scores.get(i)));
    return "(VwLdf " + loc + " " + Feat.showScore(fs, 160, Feat.BY_SCORE_ASC) + ")";
//    return "(VwLdf " + loc + " n=" + labels.size() + ")";
  }
}