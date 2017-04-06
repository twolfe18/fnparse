package edu.jhu.hlt.entsum;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntityMentionRanker.ScoredPassage;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.prim.list.DoubleArrayList;
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
 * Make sure you have these in the environment:
 * LD_LIBRARY_PATH=/home/travis/gurobi/gurobi702/linux64/lib
 * GUROBI_HOME=/home/travis/gurobi/gurobi702/linux64
 * GRB_LICENSE_FILE=/home/travis/code/gurobi/keys/gurobi.lic
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
  public static class Adapter {
    private Alphabet<String> conceptAlph;
    private List<ScoredPassage> mentions;
    public boolean cBigrams = false;
    public boolean cCopula = false;
    public boolean cInfobox = false;
    public boolean cRelated = true;
    
    public Adapter(List<ScoredPassage> mentions, Alphabet<String> conceptAlph) {
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
    
    public List<ScoredPassage> rerank(int summaryLength) {
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
        DoubleArrayList sentenceCosts = null;
        GillickFavre09Summarization solver = new GillickFavre09Summarization(occ, sentenceLengths, sentenceCosts, conceptUtilities);
        IntArrayList keep = solver.solve(summaryLength);
        
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
    public final int i;      // concept
    public final int j;      // sentence
    
    public ConceptMention(int concept, int sentence) {
      this.i = concept;
      this.j = sentence;
    }
    
    public ConceptMention changeSentence(int newSentence) {
      return new ConceptMention(concept(), newSentence);
    }
    
    public int sentence() { return j; }
    public int concept() { return i; }

    @Override
    public String toString() {
      return "(concept=" + i + " sentence=" + j + ")";
    }
    
    @Override
    public int hashCode() {
      return Hash.mix(i, j);
    }
    
    @Override
    public boolean equals(Object other) {
      if (other instanceof ConceptMention) {
        ConceptMention cm = (ConceptMention) other;
        return i == cm.i && j == cm.j;
      }
      return false;
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
  
  static class SoftConceptMention extends ConceptMention {
    public final double costOfEvokingConcept;
    public SoftConceptMention(int concept, int sentence, double costOfEvokingConcept) {
      super(concept, sentence);
      this.costOfEvokingConcept = costOfEvokingConcept;
    }
    
    @Override
    public SoftConceptMention changeSentence(int newSentence) {
      return new SoftConceptMention(concept(), newSentence, costOfEvokingConcept);
    }

    @Override
    public String toString() {
      return "(concept=" + i + " sentence=" + j + " cost=" + costOfEvokingConcept + ")";
    }
    
    @Override
    public int hashCode() {
      return Hash.mix(super.hashCode(), j);
    }
    
    @Override
    public boolean equals(Object other) {
      if (other instanceof SoftConceptMention) {
        SoftConceptMention cm = (SoftConceptMention) other;
        return super.equals(other) && costOfEvokingConcept == cm.costOfEvokingConcept;
      }
      return false;
    }
  }
  
  private List<? extends ConceptMention> occ;
  private IntArrayList sentenceLengths;
  private DoubleArrayList sentenceCosts;  // may be null
  private double[] conceptUtilities;
  
  public GillickFavre09Summarization(List<? extends ConceptMention> occ, IntArrayList sentenceLengths, DoubleArrayList sentenceCosts, double[] conceptUtilities) {
    if (sentenceCosts != null && sentenceCosts.size() != sentenceLengths.size())
      throw new IllegalArgumentException();
    Log.info("nOcc=" + occ.size() + " nSentence=" + sentenceLengths.size() + " nConcept=" + conceptUtilities.length);
    this.occ = occ;
    this.sentenceLengths = sentenceLengths;
    this.sentenceCosts = sentenceCosts;
    this.conceptUtilities = conceptUtilities;
    
    // check that all utilities are non-negative
    for (int i = 0; i < conceptUtilities.length; i++) {
      if (Double.isNaN(conceptUtilities[i]))
        throw new IllegalArgumentException("nan");
      if (Double.isInfinite(conceptUtilities[i]))
        throw new IllegalArgumentException("inf");
      if (conceptUtilities[i] < 0)
        throw new IllegalArgumentException("neg");
    }
  }
  
  /* NOTE: This doesn't seem to work...
   * 
   * Returns a mapping from the new sentence indices (used by the pruned problem) to the original indices.
   * Concept indices are not modified.
  public IntIntHashMap pruneBySentence(int topSentences) {
    // Compute the MAX possible utility supported by any sentence
    double[] maxU = new double[sentenceLengths.size()];
    for (ConceptMention cm : occ)
      maxU[cm.sentence()] += conceptUtilities[cm.concept()];
    Beam<Integer> keep = Beam.getMostEfficientImpl(topSentences);
    double totalU = 0;
    for (int i = 0; i < maxU.length; i++) {
      keep.push(i, maxU[i]);
      totalU += maxU[i];
    }
    double keptU = 0;
    IntHashSet keepS = new IntHashSet();
    while (keep.size() > 0) {
      Beam.Item<Integer> i = keep.popItem();
      keepS.add(i.getItem());
      keptU += i.getScore();
    }
    Log.info("kept=" + topSentences + " all=" + sentenceLengths.size() + " keptU=" + (100d*keptU)/totalU + "%");

    IntIntHashMap old2new = new IntIntHashMap();
    IntIntHashMap new2old = new IntIntHashMap();
    List<ConceptMention> occNew = new ArrayList<>();
    IntArrayList sentLenNew = new IntArrayList();
    for (ConceptMention cm : occ) {
      if (!keepS.contains(cm.sentence()))
        continue;
      int newSent = old2new.getWithDefault(cm.sentence(), -1);
      if (newSent < 0) {
        newSent = old2new.size();
        old2new.put(cm.sentence(), newSent);
        new2old.put(newSent, cm.sentence());
        sentLenNew.add(sentenceLengths.get(cm.sentence()));
      }
      occNew.add(cm.changeSentence(newSent));
    }
    assert keepS.size() == sentLenNew.size();
    Log.info("nOccBefore=" + occ.size() + " nOccAfter=" + occNew.size());
    this.occ = occNew;
    this.sentenceLengths = sentLenNew;

    assert old2new.size() == new2old.size();
//    return old2new;
    return new2old;
  }
   */
  
  static class SoftSolution {
    IntArrayList sentences;   // TODO IntArrayList b/c of extreme sparsity
    IntArrayList concepts;
    List<SoftConceptMention> mentions;  // vars set to 1 in solution
    int summaryLength;
    int summaryLengthLimit;
    
    public SoftSolution(int summaryLengthLim) {
      sentences = new IntArrayList();
      concepts = new IntArrayList();
      mentions = new ArrayList<>();
      this.summaryLengthLimit = summaryLengthLim;
    }
    
    public List<SoftConceptMention> mentionsIn(int sentenceIdx) {
      List<SoftConceptMention> l = new ArrayList<>();
      for (SoftConceptMention m : mentions)
        if (m.sentence() == sentenceIdx)
          l.add(m);
      return l;
    }
    
    public void addSentence(int sentenceIndex, int sentenceLength) {
      sentences.add(sentenceIndex);
      summaryLength += sentenceLength;
    }
    
    public double totalConceptEvokingCost() {
      double c = 0;
      for (SoftConceptMention cm : mentions)
        c += cm.costOfEvokingConcept;
      return c;
    }
    
    @Override
    public String toString() {
      return "(Sol nSent=" + sentences.size()
          + " nConcept=" + concepts.size()
          + " nMentions=" + mentions.size()
          + " costMentions=" + totalConceptEvokingCost()
          + " length=" + summaryLength + "/" + summaryLengthLimit
          + ")";
    }
  }
  
  /**
   * Casts all {@link ConceptMention}s as {@link SoftConceptMention}s.
   */
  public SoftSolution solveSoft(int summaryLength) throws GRBException {
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

    // First half of objective: concept utility
    GRBLinExpr obj = new GRBLinExpr();
    for (int i = 0; i < c.length; i++) {
      assert conceptUtilities[i] >= 0;
      if (conceptUtilities[i] != 0)
        obj.addTerm(conceptUtilities[i], c[i]);
    }
    
    // Create all e_{ij} ...
    // AND second half of objective: evoking costs
    MultiMap<Integer, GRBVar> e_i = new MultiMap<>();
    MultiMap<Integer, GRBVar> e_j = new MultiMap<>();
    Map<ConceptMention, GRBVar> c2e = new HashMap<>();
    int nnz = 0;
    for (ConceptMention cm : occ) {
      SoftConceptMention scm = (SoftConceptMention) cm;
      GRBVar e_ij = model.addVar(0, 1, 0, GRB.BINARY, null);
      e_i.add(scm.i, e_ij);
      e_j.add(scm.j, e_ij);
      
      assert scm.costOfEvokingConcept >= 0;
      if (scm.costOfEvokingConcept > 0)
        nnz++;
      obj.addTerm(-scm.costOfEvokingConcept, e_ij);
      
      c2e.put(cm, e_ij);
    }
    Log.info("nOcc=" + occ.size() + " nOccWithPosCost=" + nnz);
    
    if (this.sentenceCosts != null) {
      Log.info("adding sentence costs");
      for (int j = 0; j < s.length; j++) {
        double sc = sentenceCosts.get(j);
        assert sc >= 0;
        if (sc > 0)
          obj.addTerm(-sc, s[j]);
      }
    }
    
    model.setObjective(obj, GRB.MAXIMIZE);

//    setConceptObjective(model, c);
    addLengthConstraint(model, s, summaryLength);
    
    // sum_j e_ij >= c_i
    for (int i = 0; i < c.length; i++) {
      GRBLinExpr sum_j = new GRBLinExpr();
      List<GRBVar> ee = e_i.get(i);
      if (ee.isEmpty())
        continue;
      for (GRBVar v : ee)
        sum_j.addTerm(1, v);
      model.addConstr(sum_j, GRB.GREATER_EQUAL, c[i], null);
    }
    
    // e_ij <= c_i
    for (int i = 0; i < c.length; i++)
      for (GRBVar e_ij : e_i.get(i))
        model.addConstr(e_ij, GRB.LESS_EQUAL, c[i], null);
    
    // s_j >= e_ij
    for (int j = 0; j < s.length; j++)
      for (GRBVar e_ij : e_j.get(j))
        model.addConstr(s[j], GRB.GREATER_EQUAL, e_ij, null);

    Log.info("optmizing...");
    model.optimize();
    
    // Read out the solution
    SoftSolution sol = new SoftSolution(summaryLength);
    for (int j = 0; j < s.length; j++) {
      double s_j = s[j].get(GRB.DoubleAttr.X);
      assert 0 <= s_j && s_j <= 1;
      if (s_j > 0)
        sol.addSentence(j, sentenceLengths.get(j));
    }
    for (int i = 0; i < c.length; i++) {
      double c_i = c[i].get(GRB.DoubleAttr.X);
      assert 0 <= c_i && c_i <= 1;
      if (c_i > 0)
        sol.concepts.add(i);
    }
    for (ConceptMention cm : occ) {
      GRBVar e_ij = c2e.get(cm);
      double e_ij_val = e_ij.get(GRB.DoubleAttr.X);
      assert 0 <= e_ij_val && e_ij_val <= 1;
      if (e_ij_val > 0)
        sol.mentions.add((SoftConceptMention) cm);
    }
    
    model.dispose();
    env.dispose();
    
    return sol;
  }
  
  private void setConceptObjective(GRBModel model, GRBVar[] c) throws GRBException {
    GRBLinExpr obj = new GRBLinExpr();
    for (int i = 0; i < c.length; i++) {
      assert conceptUtilities[i] >= 0;
      if (conceptUtilities[i] != 0)
        obj.addTerm(conceptUtilities[i], c[i]);
    }
    model.setObjective(obj, GRB.MAXIMIZE);
  }
  
  private void addLengthConstraint(GRBModel model, GRBVar[] s, int summaryLength) throws GRBException {
    GRBLinExpr solutionLength = new GRBLinExpr();
    for (int j = 0; j < s.length; j++) {
      int n = this.sentenceLengths.get(j);
      assert n > 0;
      solutionLength.addTerm(n, s[j]);
    }
    model.addConstr(summaryLength, GRB.GREATER_EQUAL, solutionLength, null);
  }

  /**
   * @return the list of sentence indices in the summary
   */
  public IntArrayList solve(int summaryLength) throws GRBException {
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
    setConceptObjective(model, c);
    
    // Constraint (0)
    // sum_j l_j s_j <= L
    addLengthConstraint(model, s, summaryLength);
    
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
  
  public static void buildPkbSummary() throws Exception {
    File p = new File("../data/clueweb09-freebase-annotation/gen-for-entsum/");
    File f = new File(p, "sentences-rare4/sentences-containing-m.0gly1.txt.gz");
    int maxSentenceLength = 80;
    List<CluewebLinkedSentence> sent = CluewebLinkedSentence.readAll(f, maxSentenceLength);
    File hashes = new File(p, "parsed-sentences-rare4/hashes.txt");
    File conll = new File(p, "parsed-sentences-rare4/parsed.conll");
    MultiAlphabet alph = new MultiAlphabet();
    ParsedSentenceMap parses = new ParsedSentenceMap(hashes, conll, alph);
    List<ScoredPassage> mentions = parses.getAllParses(sent);
    Alphabet<String> conceptAlph = new Alphabet<>();
//    Adapter a = new Adapter("/m/0gly1", mentions, conceptAlph);
    Adapter a = new Adapter(mentions, conceptAlph);
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
  
  public static void softTest0() throws Exception {
    
    // John loves Mary.
    // Mary is the CEO of Exxon Mobil.
    // One time Mary went to the market on a Saturday afternoon.
    IntArrayList sentenceLengths = new IntArrayList();
    sentenceLengths.add(3);
    sentenceLengths.add(7);
    sentenceLengths.add(11);
    // loves, loves-arg1, ceo, coe-arg0, go, go-arg0
    double[] conceptUtilities = new double[] {
        1d, 0.75d,        10d, 12d,     0.1d, 0.25d,
    };
    List<SoftConceptMention> occ = new ArrayList<>();
    occ.add(new SoftConceptMention(0, 0, 1)); // loves
    occ.add(new SoftConceptMention(1, 0, 1)); // loves-arg0
    occ.add(new SoftConceptMention(2, 1, 1)); // ceo
    occ.add(new SoftConceptMention(3, 1, 1)); // ceo-arg0
    occ.add(new SoftConceptMention(4, 2, 1)); // loves
    occ.add(new SoftConceptMention(5, 2, 1)); // loves-arg0

    DoubleArrayList sentenceCosts = null;
    GillickFavre09Summarization s = new GillickFavre09Summarization(occ, sentenceLengths, sentenceCosts, conceptUtilities);
    SoftSolution sol = s.solveSoft(10);
    System.out.println(sol);
    System.out.println("e_ij: " + sol.mentions);
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
    
//    buildPkbSummary();
    softTest0();

    Log.info("done");
  }
}
