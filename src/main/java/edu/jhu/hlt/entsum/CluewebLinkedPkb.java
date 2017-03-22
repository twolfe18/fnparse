package edu.jhu.hlt.entsum;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntityMentionRanker.ScoredPassage;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.Link;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.PkbpSearching;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.util.Alphabet;

/**
 * Build a summary for every spoke in a PKB (i.e. an entity related to the query).
 * PKBs are built from FACC1 entity links instead of {@link PkbpSearching}.
 * 
 * This just builds the PKB and calls
 * {@link GillickFavre09Summarization.Adapter}
 * to build the summaries for each edge.
 *
 * TODO Jointly optimize over summaries for all related entities.
 * @see ~/papers/thesis-outline/daily-notes/2017-03-16.txt
 *
 * @author travis
 */
public class CluewebLinkedPkb {
  
  static class Entity {
    public final String mid;
    List<Feat> relatedness;
    private List<CluewebLinkedSentence> mentions;
    private Counts<String> names;
    
    public Entity(String mid) {
      this.mid = mid;
      this.relatedness = new ArrayList<>();
      this.mentions = new ArrayList<>();
      this.names = new Counts<>();
    }
    
    public List<CluewebLinkedSentence> getMentionsDedupped(ComputeIdf df, double cosineThresh) {
      List<CluewebLinkedSentence> d = new ArrayList<>();
      DeduplicatingIterator<CluewebLinkedSentence> iter =
          new DeduplicatingIterator<>(mentions.iterator(), x -> x, df, cosineThresh);
      while (iter.hasNext())
        d.add(iter.next());
      return d;
    }
    
    public List<String> getMostFreqNames(int k) {
      List<String> out = names.getKeysSortedByCount(true);
      if (out.size() > k)
        out = out.subList(0, k);
      return out;
    }

    public void add(CluewebLinkedSentence sent) {
      mentions.add(sent);
      int n = sent.numLinks();
      int a = 0;
      for (int i = 0; i < n; i++) {
        Link l = sent.getLink(i);
        String mid = l.getMid(sent.getMarkup());
        if (this.mid.equals(mid)) {
          a++;
          names.increment(l.getMention(sent.getMarkup()));
        }
      }
      assert a > 0;
    }
    
    @Override
    public int hashCode() {
      return mid.hashCode();
    }
    
    @Override
    public boolean equals(Object other) {
      if (other instanceof Entity) {
        Entity e = (Entity) other;
        return mid.equals(e.mid);
      }
      return false;
    }
    
    public static final Comparator<Entity> BY_NUM_MENTIONS_DESC = new Comparator<Entity>() {
      @Override
      public int compare(Entity o1, Entity o2) {
        int n1 = o1.mentions.size();
        int n2 = o2.mentions.size();
        if (n1 > n2)
          return -1;
        if (n1 < n2)
          return +1;
        return 0;
      }
    };
  }
  
  private String queryMid;
  private Map<String, Entity> related;

  public CluewebLinkedPkb(String queryMid, List<CluewebLinkedSentence> sentences) {
    this.queryMid = queryMid;
    this.related = new HashMap<>();
    for (CluewebLinkedSentence sent : sentences) {
      int nl = sent.numLinks();
      for (int i = 0; i < nl; i++) {
        String mid = sent.getLink(i).getMid(sent.getMarkup());
        if (mid.equals(queryMid))
          continue;
        Entity midE = related.get(mid);
        if (midE == null) {
          midE = new Entity(mid);
          related.put(mid, midE);
        }
        midE.add(sent);
      }
    }
  }

  /**
   * Perhaps this is where I can play around with Ben/Tim's "interestingness" information theory metrics.
   * For now I'll just do it by frequency.
   * TODO next, discount based on related entity frequency (IDF weighting might be good enough, may not get rid of news agencies).
   */
  public List<Entity> getMostRelatedEntities(int k) {
    List<Entity> es = new ArrayList<>();
    es.addAll(related.values());
    Collections.sort(es, Entity.BY_NUM_MENTIONS_DESC);
    if (es.size() > k)
      es = es.subList(0, k);
    return es;
  }
  
  /**
   * Build a PKB for a given query/entity.
   * For the k most related entities, build a d word summary.
   */
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    int k = config.getInt("k", 8);      // num related entities
    int d = config.getInt("d", 100);    // words per (query, related) entity pair
    
    // This should be the part after the "/m/", e.g. "0gly1" instead of "/m/0gly1"
    String mid = config.getString("mid", "0gly1");
    File rare4 = config.getExistingDir("workingDir", new File("/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum"));
    
    File sentF = new File(rare4, "sentences-rare4/sentences-containing-m." + mid + ".txt.gz");
    int maxWordsPerSentence = 80;
    List<CluewebLinkedSentence> sent = CluewebLinkedSentence.readAll(sentF, maxWordsPerSentence);
    
    MultiAlphabet parseAlph = new MultiAlphabet();
    File hashes = new File(rare4, "parsed-sentences-rare4/hashes.txt");
    File conll = new File(rare4, "parsed-sentences-rare4/parsed.conll");
    ParsedSentenceMap parses = new ParsedSentenceMap(hashes, conll, parseAlph);
    
    ComputeIdf df = new ComputeIdf(new File("data/idf/cms/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser"));
    double cosineThresh = 0.5;
    
    CluewebLinkedPkb pkb = new CluewebLinkedPkb("/m/" + mid, sent);
    for (Entity related : pkb.getMostRelatedEntities(k)) {
//      List<ScoredPassage> mentions = parses.getAllParses(related.mentions);
      List<ScoredPassage> mentions = parses.getAllParses(related.getMentionsDedupped(df, cosineThresh));
      Alphabet<String> conceptAlph = new Alphabet<>();
      GillickFavre09Summarization.Adapter a = new GillickFavre09Summarization.Adapter(mentions, conceptAlph);
      List<ScoredPassage> summary = a.rerank(d);
      
      Map<String, String> mid2tag = new HashMap<>();
      mid2tag.put("/m/" + mid, "query");
      mid2tag.put(related.mid, "related");
      
      System.out.println("related: " + related.mid + "\t" + related.getMostFreqNames(3));
      for (ScoredPassage s : summary)
        System.out.println("summary: " + s.sent.getResultsHighlighted2(mid2tag));
      System.out.println();
    }
  }
}
