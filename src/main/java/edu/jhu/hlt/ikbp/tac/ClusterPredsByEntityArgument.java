package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * See more description in
 * /home/travis/papers/thesis-outline/daily-notes/2017-03-01.txt
 * 
 * Currently my goal is to see if I can go over all the SF query entities,
 * for each, list all of the cities which co-occur with their mentions
 * (I'm tentatively targeting per:city_of_residence).
 * 
 * I got the city names from dbpedia, see
 * /home/travis/code/fnparse/data/dbpedia/cities/Makefile
 * 
 * @author travis
 */
public class ClusterPredsByEntityArgument {
  
  private Set<String> cityWords;
//  private ComputeIdf df;
  
  public ClusterPredsByEntityArgument(File dbpediaCitiesDir, ComputeIdf df) throws IOException {
//    this.df = df;
    this.cityWords = new HashSet<>();
    File f = new File(dbpediaCitiesDir, "city-names.txt");
    Log.info("reading from " + f.getPath());
    Counts<String> ec = new Counts<>();
    Set<String> skip = new HashSet<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        ec.increment("line");
        line = line.replaceAll("[^A-Za-z0-9]+", " ");
        String[] ar = line.split("\\s+");
        
        // Take rarest word in the expression, and any other word which has a df no higher than twice the rarest
        List<Feat> dfs = new ArrayList<>();
        for (String a : ar) {
          int c = df.freq(a);
          dfs.add(new Feat(a, c));
        }
//        dfs = Feat.sortAndPrune(dfs, 0);
//        if (dfs.isEmpty())
//          continue;
//        double dfMin = dfs.get(dfs.size()-1).weight;
//        assert dfs.size() < 2 || dfs.get(0).weight >= dfMin;
        
        for (int i = 0; i < dfs.size(); i++) {
          double c = dfs.get(i).weight;
          if (c < 200_000 /*&& c < 2 * dfMin*/) {    // Hollywood is the only city word with df>200k, Philadelphia has df>100k
            ec.increment("term/kept");
            if (cityWords.add(ar[i]))
              ec.increment("term/kept/type");
          } else {
            ec.increment("term/rejected");
            if (skip.add(ar[i]))
              ec.increment("term/rejected/type");
          }
        }
      }
    }
    Log.info("counts: " + ec);
  }

  static class PotentialExtraction<Q> {
    Q query;
    PkbpMention arg0; // mention of query
    PkbpMention trigger;  // e.g. 'lives', TODO should this be a dependency arc? sub-graph? e.g. prt(lives, in)
    PkbpMention arg1; // other argument, e.g. a city name

    public PotentialExtraction(Q query, PkbpMention arg0, PkbpMention trigger, PkbpMention arg1) {
      this.query = query;
      this.arg0 = arg0;
      this.trigger = trigger;
      this.arg1 = arg1;
    }
  }
  
  public <T> List<PotentialExtraction<T>> findCities(T query, List<PkbpEntity.Mention> mentions) {
    List<PotentialExtraction<T>> out = new ArrayList<>();
    for (PkbpEntity.Mention m : mentions) {
      List<PkbpMention> cities = findCities(m);
      if (cities.isEmpty())
        continue;
      // For now I'm just taking one city if there are multiple.
      // TODO Inspect cases where there are more than one.
      PkbpMention c = cities.get(0);
      out.add(new PotentialExtraction<>(query, m, null, c));
    }
    return out;
  }
  
  public List<PkbpMention> findCities(PkbpEntity.Mention near) {
    List<PkbpMention> out = new ArrayList<>();
    int n = near.getTokenization().getTokenList().getTokenListSize();
    for (int i = 0; i < n; i++) {
      if (i == near.head)
        continue;
      String w = near.getWord(i);
      if (cityWords.contains(w)) {
        
        // TODO Check whether using NER ~= LOC* is needed/helpful
        String ner = near.getNer(i).toUpperCase();
        if (ner.startsWith("LOC")) {// || ner.equalsIgnoreCase("O")) {
          out.add(PkbpEntity.Mention.build(i, near.toks, near.getCommunication(), near.getAttrFeatFunc()));
        }
      }
    }
    return out;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));
    File dbpediaCitiesDir = config.getExistingDir("dbpediaCities", new File("data/dbpedia/cities"));
    ClusterPredsByEntityArgument c = new ClusterPredsByEntityArgument(dbpediaCitiesDir, df);
  }
}
