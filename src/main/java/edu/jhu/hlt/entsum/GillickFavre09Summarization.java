package edu.jhu.hlt.entsum;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntityMentionRanker.ScoredPassage;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.util.Alphabet;
import edu.jhu.util.MultiMap;
import gurobi.GRB;
import gurobi.GRBEnv;
import gurobi.GRBException;
import gurobi.GRBLinExpr;
import gurobi.GRBModel;
import gurobi.GRBVar;

/**
 * A scalable global model for summarization
 * Dan Gillick and Benoit Favre (2009)
 * https://dl.acm.org/citation.cfm?id=1611640
 *
 * @author travis
 */
public class GillickFavre09Summarization {
  
//  static class BigramCounts extends StringCountMinSketch {
//    private static final long serialVersionUID = -1163527873691880659L;
//    public static final String BEFORE_SENT = "<s>";
//    public static final String AFTER_SENT = "</s>";
//    
//    public final boolean caseSensitive;
//
//    public BigramCounts(int nHash, int logCountersPerHash, boolean caseSensitive) {
//      super(nHash, logCountersPerHash, true);
//      this.caseSensitive = caseSensitive;
//    }
//
//    public int getCount(String word1, String word2) {
//      if (caseSensitive) {
//        word1 = word1.toLowerCase();
//        word2 = word2.toLowerCase();
//      }
//      return super.apply(word1 + "\t" + word2, false);
//    }
//
//    public int increment(String word1, String word2) {
//      if (caseSensitive) {
//        word1 = word1.toLowerCase();
//        word2 = word2.toLowerCase();
//      }
//      return super.apply(word1 + "\t" + word2, true);
//    }
//    
//    public void increment(Tokenization toks) {
//      String pre = BEFORE_SENT;
//      for (Token t : toks.getTokenList().getTokenList()) {
//        increment(pre, t.getText());
//        pre = t.getText();
//      }
//      increment(pre, AFTER_SENT);
//    }
//    
//    public void increment(Communication comm) {
//      int maxSentenceLength = 60;
//      for (Tokenization tok : new TokenizationIter(comm, maxSentenceLength))
//        increment(tok);
//    }
//  }
  
  /**
   * Given sentences, do the work of building the Occ_{ij} matrix of concept mentions.
   */
  static class Adapter {
    private Alphabet<String> conceptAlph;
    private String mid;
    private List<ScoredPassage> mentions;
    public boolean cBigrams = false;
    public boolean cCopula = false;
    public boolean cInfobox = false;
    public boolean cRelated = true;
    
    public Adapter(String mid, List<ScoredPassage> mentions, Alphabet<String> conceptAlph) {
      this.mid = mid;
      this.mentions = mentions;
      this.conceptAlph = conceptAlph;
    }
    
    private void extractRelatedEntities(int j, ScoredPassage s, List<ConceptMention> addTo) {
      for (String mid : s.sent.getAllMids(new HashSet<>())) {
        int i = conceptAlph.lookupIndex(mid);
        addTo.add(new ConceptMention(i, j));
      }
    }

    private void extractBigrams(int j, ScoredPassage s, List<ConceptMention> addTo) {
      List<String> words = s.sent.getAllWords(new ArrayList<>(), false);
      String prev = "<s>";
      for (String w : words) {
        int i = conceptAlph.lookupIndex("bi/" + prev + "_" + w);
        addTo.add(new ConceptMention(i, j));
        prev = w;
      }
      int i = conceptAlph.lookupIndex("bi/" + prev + "_</s>");
      addTo.add(new ConceptMention(i, j));
    }
    
    public List<ScoredPassage> rerank(int maxSentences) {
      List<ConceptMention> occ = new ArrayList<>();
      IntArrayList sentenceLengths = new IntArrayList();
      
      // Compute concept occurrences
      for (int j = 0; j < mentions.size(); j++) {
        ScoredPassage s_j = mentions.get(j);
        if (cBigrams)
          extractBigrams(j, s_j, occ);
        if (cRelated)
          extractRelatedEntities(j, s_j, occ);
        if (cCopula || cInfobox)
          throw new RuntimeException("implement me");
        sentenceLengths.add(s_j.sent.getTextTokenizedNumTokens());
      }
      
      // Compute concept utilities
      double[] conceptUtilities = new double[conceptAlph.size()];
      for (ConceptMention m : occ)
        conceptUtilities[m.i] += 1;
      
      // TODO Prune bigram concepts which appear fewer than 3 times
      // Not clear if this is meant to mean in entire training corpus or in the text to be summarized
      
      Log.info("nOcc=" + occ.size() + " nSent=" + sentenceLengths.size() + " nConcept=" + conceptAlph.size());
      try {
        GillickFavre09Summarization solver = new GillickFavre09Summarization(occ, sentenceLengths, conceptUtilities);
        IntArrayList keep = solver.solve(maxSentences);
        
        List<ScoredPassage> out = new ArrayList<>(keep.size());
        for (int i = 0; i < keep.size(); i++)
          out.add(mentions.get(keep.get(i)));
        return out;
        
      } catch (GRBException e) {
        throw new RuntimeException(e);
      }
    }
  }
  
  /** Specifies Occ_{ij} and w_i */
  static class ConceptMention {
    int i;      // concept
    int j;      // sentence
    
    public ConceptMention(int concept, int sentence) {
      this.i = concept;
      this.j = sentence;
    }
    
    public static final Comparator<ConceptMention> BY_CONCEPT = new Comparator<ConceptMention>() {
      @Override
      public int compare(ConceptMention o1, ConceptMention o2) {
        if (o1.i < o2.i)
          return -1;
        if (o1.i > o2.i)
          return +1;
        return 0;
      }
    };
  }
  
  private List<ConceptMention> occ;
  private IntArrayList sentenceLengths;
  private double[] conceptUtilities;
  
  public GillickFavre09Summarization(List<ConceptMention> occ, IntArrayList sentenceLengths, double[] conceptUtilities) {
    this.occ = occ;
    this.sentenceLengths = sentenceLengths;
    this.conceptUtilities = conceptUtilities;
  }

  /**
   * @return the list of sentence indices in the summary
   */
  private IntArrayList solve(int summaryLength) throws GRBException {
    Log.info("solving for summaryLength=" + summaryLength);
    GRBEnv env = new GRBEnv();
    GRBModel model = new GRBModel(env);
    
    // Create all the c_i (concept inclusion) variables
    GRBVar[] c = new GRBVar[conceptUtilities.length];
    for (int i = 0; i < c.length; i++)
      c[i] = model.addVar(0, 1, 0, GRB.BINARY, null);

    // Create all the s_j (sentence inclusion) variables
    GRBVar[] s = new GRBVar[sentenceLengths.size()];
    for (int j = 0; j < s.length; j++)
      s[j] = model.addVar(0, 1, 0, GRB.BINARY, null);
    
    // Concept utility objective
    GRBLinExpr obj = new GRBLinExpr();
    for (int i = 0; i < c.length; i++) {
      assert conceptUtilities[i] >= 0;
      if (conceptUtilities[i] != 0)
        obj.addTerm(conceptUtilities[i], c[i]);
    }
    model.setObjective(obj, GRB.MAXIMIZE);
    
    // Constraint (0)
    // sum_j l_j s_j <= L
    GRBLinExpr solutionLength = new GRBLinExpr();
    for (int j = 0; j < s.length; j++)
      solutionLength.addTerm(this.sentenceLengths.get(j), s[j]);
    model.addConstr(summaryLength, GRB.GREATER_EQUAL, solutionLength, null);
    
    // Constraint (1)
    // s_j Occ_{ij} <= c_i \forall i,j
    // if Occ_{ij} = 0, then this is trivially true, so we only need to instantiate this for i,j s.t. Occ_{ij} > 0
    for (ConceptMention m : occ) {
      GRBLinExpr left = new GRBLinExpr();
      left.addTerm(1, s[m.j]);
      model.addConstr(left, GRB.LESS_EQUAL, c[m.i], null);
    }
    
    // Constraint (2)
    // \sum_j s_j Occ_{ij} >= c_i
    MultiMap<Integer, ConceptMention> byConcept = new MultiMap<>();
    for (ConceptMention m : occ)
      byConcept.add(m.i, m);
    for (int i = 0; i < c.length; i++) {
      List<ConceptMention> ms = byConcept.get(i);
      if (ms == null || ms.isEmpty())
        throw new RuntimeException("bad?");
      GRBLinExpr sum = new GRBLinExpr();
      for (ConceptMention m : ms)
        sum.addTerm(1, s[m.j]);
      model.addConstr(sum, GRB.GREATER_EQUAL, c[i], null);
    }
    
    Log.info("optmizing...");
    model.optimize();
    
    // Read out the solution
    IntArrayList keep = new IntArrayList();
    for (int j = 0; j < s.length; j++) {
      double s_j = s[j].get(GRB.DoubleAttr.X);
      assert 0 <= s_j && s_j <= 1;
      if (s_j > 0)
        keep.add(j);
    }
    
    model.dispose();
    env.dispose();

    return keep;
  }
  
  public static void main(String[] args) throws Exception {
    Log.info("starting...");
    
//    System.out.println(System.getProperty("java.library.path"));
//    System.setProperty("java.library.path", "/home/travis/gurobi/gurobi702");///linux64/lib");///libGurobiJni70.so");
//    System.setProperty("PATH", "/home/travis/gurobi/gurobi702/linux64/lib");///libGurobiJni70.so");
//    System.out.println(System.getProperty("java.library.path"));
    System.out.println(System.getenv("PATH"));
    System.out.println(System.getenv("GUROBI_HOME"));
    
//    GRBEnv e = new GRBEnv();
//    GRBModel m = new GRBModel(e);
//    m.optimize();
//    m.dispose();
//    e.dispose();
    
    foo();

    Log.info("done");
  }
  
  public static void foo() throws Exception {
    File p = new File("../data/clueweb09-freebase-annotation/gen-for-entsum/");
    File f = new File(p, "sentences-rare4/sentences-containing-m.0gly1.txt.gz");
    List<CluewebLinkedSentence> sent;
    int maxSentenceLength = 80;
    try (CluewebLinkedSentence.ValidatorIterator iter = new CluewebLinkedSentence.ValidatorIterator(f, maxSentenceLength)) {
      sent = iter.toList();
    }
    File hashes = new File(p, "parsed-sentences-rare4/hashes.txt");
    File conll = new File(p, "parsed-sentences-rare4/parsed.conll");
    MultiAlphabet alph = new MultiAlphabet();
    ParsedSentenceMap parses = new ParsedSentenceMap(hashes, conll, alph);
    List<ScoredPassage> mentions = parses.getAllParses(sent);
    Alphabet<String> conceptAlph = new Alphabet<>();
    Adapter a = new Adapter("/m/0gly1", mentions, conceptAlph);
    int maxWordsInSummary = 200;
    int words = 0;
    List<ScoredPassage> summary = a.rerank(maxWordsInSummary);
    for (int i = 0; i < summary.size(); i++) {
      System.out.println("sentence " + i);
      System.out.println(summary.get(i).sent.getMarkup());
      System.out.println();
      words += summary.get(i).sent.getTextTokenizedNumTokens();
    }
    System.out.println("tokensInSummary=" + words);
  }
}
