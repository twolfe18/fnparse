package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;

public class PkbpSearchingSituation {
  Map<String, Double> feat2score;
  List<PkbpSearchingSitMention> mentions;
  
  public PkbpSearchingSituation(PkbpSearchingSitMention canonical) {
    this.mentions = new ArrayList<>();
    this.mentions.add(canonical);
    this.feat2score = new HashMap<>();
    if (canonical.feat2score != null)
      this.feat2score.putAll(canonical.feat2score);
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
  
  public void addMention(PkbpSearchingSitMention m) {
    this.mentions.add(m);
    for (Entry<String, Double> e : m.feat2score.entrySet()) {
      double p = feat2score.getOrDefault(e.getKey(), 0d);
      feat2score.put(e.getKey(), e.getValue() + p);
    }
  }
}