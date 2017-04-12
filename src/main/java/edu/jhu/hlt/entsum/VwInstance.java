package edu.jhu.hlt.entsum;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.prim.list.DoubleArrayList;

/**
 * A joined element with pieces from
 *  $ENTITY/distsup*.csoaa_ldf.x      (what are the labels/relations/verbs we are trying to score)
 *  $ENTITY/distsup*.csoaa_ldf.yhat   (what are the model scores/costs)
 *  $ENTITY/distsup*.locations.txt    (where are these relations/verbs located in a sequence of sentences)
 *
 * @author travis
 */
public class VwInstance implements Serializable {
  private static final long serialVersionUID = 1502000850452735623L;

  public StreamingDistSupFeatEx.Fact loc;
  public List<String> labels;
  public DoubleArrayList scores;
  
  // TODO Could add fx:List<String> field for features
  // ldf: these would be the shared features
  // binary: only one sense of features
  public VwLine fx;
  
  public VwInstance mapSentenceIndex(int newSentenceIdx) {
    StreamingDistSupFeatEx.Fact newLoc = new StreamingDistSupFeatEx.Fact(newSentenceIdx, loc.subjMention, loc.objMention, loc.verb);
    VwInstance i = new VwInstance(newLoc, fx);
    i.labels = labels;
    i.scores = scores;
    return i;
  }
  
  public VwInstance(StreamingDistSupFeatEx.Fact loc, VwLine fx) {
    this.fx = fx;
    this.loc = loc;
    this.labels = new ArrayList<>();
    this.scores = new DoubleArrayList();
  }

  public double maxScore() {
    if (scores.size() == 0)
      return Double.NEGATIVE_INFINITY;
    double m = scores.get(0);
    for (int i = 1; i < scores.size(); i++)
      m = Math.max(m, scores.get(i));
    return m;
  }
  
  public double minCost() {
    if (scores.size() == 0)
      return Double.POSITIVE_INFINITY;
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

  public List<Feat> getMostLikelyLabels(int k) {
    int n = labels.size();
    assert n == scores.size();
    List<Feat> a = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      double cost = scores.get(i);
      a.add(new Feat(labels.get(i), cost));
    }
    Collections.sort(a, Feat.BY_SCORE_ASC);   // These are costs, minimize cost
    return Feat.take(k, a);
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