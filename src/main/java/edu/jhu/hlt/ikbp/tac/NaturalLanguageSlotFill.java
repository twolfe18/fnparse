package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ConllxToDocument;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.ConstituentItr;
import edu.jhu.hlt.tutils.Document.TokenItr;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;

public class NaturalLanguageSlotFill {
  
  // 1) convert Jacquie's text into conll
  // 2) parse with PMP
  // 3) read in (relation, parsed question) values
  // 4) given a (sentence, entityMention), align nodes in the sentence with nodes in the question
  // 5) quality of the match is essentially cost of the alignment
  
  public static final Set<String> PATTERN_STOPWORDS = new HashSet<>();
  public static final Set<String> PATTERN_STOPPOS = new HashSet<>();
  static {
    PATTERN_STOPWORDS.add("is");
    PATTERN_STOPWORDS.add("for");
    PATTERN_STOPWORDS.add("to");
    PATTERN_STOPWORDS.add("of");
    PATTERN_STOPWORDS.add("a");
    
    PATTERN_STOPPOS.add("DT");
    PATTERN_STOPPOS.add("PDT");
    PATTERN_STOPPOS.add("IN");
    PATTERN_STOPPOS.add(":");
    PATTERN_STOPPOS.add(",");
    PATTERN_STOPPOS.add(".");
    PATTERN_STOPPOS.add("POS");
    PATTERN_STOPPOS.add("CC");
    PATTERN_STOPPOS.add("PRP");
    PATTERN_STOPPOS.add("PRP$");
    PATTERN_STOPPOS.add("SYM");
    PATTERN_STOPPOS.add("WDT");
    PATTERN_STOPPOS.add("WP");
    PATTERN_STOPPOS.add("WP$");
    PATTERN_STOPPOS.add("WRB");
    PATTERN_STOPPOS.add("TO");
    PATTERN_STOPPOS.add("EX");
    PATTERN_STOPPOS.add("LS");
  }
  
  private MultiAlphabet alph;
  private Document patterns;        // one sentence per pattern
  private List<String> relations;   // one string per pattern
  
  public NaturalLanguageSlotFill(File nlSfConllx, File nlSfRels) {
    Log.info("conllx=" + nlSfConllx.getPath() + " rels=" + nlSfRels.getPath());
    alph = new MultiAlphabet();
    ConllxToDocument c2d = new ConllxToDocument(alph);
    patterns = c2d.parseSafe("", nlSfConllx);
    relations = FileUtil.getLines(nlSfRels);
    
    // Check that there is one sentence per relation
    int n = 0;
    ConstituentItr si = patterns.getConstituentItr(patterns.cons_sentences);
    while (si.isValid()) {
      n++;
      si.gotoRightSib();
    }
    if (n != relations.size()) {
      throw new IllegalArgumentException(
          "there are " + n + " patterns but only " + relations.size() + " labels");
    }
  }
  
  public int numPatterns() {
    return relations.size();
  }
  
  public String getSfRelation(int pattern) {
    return relations.get(pattern);
  }
  
  public List<String> getPattern(int pattern) {
    // Get to the correct pattern
    ConstituentItr si = patterns.getConstituentItr(patterns.cons_sentences);
    for (int i = 0; i < pattern; i++)
      si.gotoRightSib();
    List<String> p = new ArrayList<>();
    TokenItr ti = patterns.getTokenItr(si.getFirstToken());
    while (ti.getIndex() <= si.getLastToken()) {
      p.add(ti.getWordStr());
      ti.forwards();
    }
    return p;
  }
  
  static class Alignment {
    public static final String DEL = "DEL";
    public static final String MATCH_W = "MATCH_W";
    public static final String PAT_WILDCARD = "PAT_WILDCARD";
    
    int patternToken;   // must be valid, indexed into entire Document
    int passageToken;   // may be -1 indicating delete/unaligned
    String type;
    double cost;
    
    // TODO remove type and add features
    // First feature is always a type, like "t:DEL"
    // Other features may characterize an node in situ, e.g. "nsubj(X,loves)" means that the node X is the subject of loves
    List<String> features;
  }
  
  public class Match {
    Sentence passage;               // contains parsey deps
    int pattern;                    // index into patterns/relations
    List<Alignment> alignment;
    
    public Match(int pattern, Sentence passage) {
      this.pattern = pattern;
      this.passage = passage;
      this.alignment = new ArrayList<>();
    }
    
    public String getRelation() {
      return getSfRelation(pattern);
    }
    
    public void add(Alignment a) {
      alignment.add(a);
    }
    
    public double getTotalCost() {
      double c = 0;
      for (Alignment a : alignment)
        c += a.cost;
      return c;
    }
    
    public int numAlignedWords() {
      int c = 0;
      for (Alignment a : alignment)
        if (a.type == Alignment.MATCH_W)
          c++;
      return c;
    }
    
    public String alignmentScript() {
      Counts<String> ct = new Counts<>();
      int nm = 0;
      StringBuilder as = new StringBuilder("[");
      for (Alignment a : alignment) {
        if (a.type == Alignment.MATCH_W) {
          if (nm > 0)
            as.append(", ");
          as.append(a.type + "(" + passage.getWord(a.passageToken) + ", " + patterns.getWordStr(a.patternToken) + ")");
          nm++;
        } else {
          ct.increment(a.type);
        }
      }
      as.append("], others: " + ct);
      return as.toString();
    }
    
    @Override
    public String toString() {
//      return "(Match pat=" + getPattern(pattern) + " edit=" + nonDelAlignmentScript() + ")";
      return "(Match " + alignmentScript() + ")";
    }
  }
  

  
  
  
  
  /**
   * TODO code to find entities, take their heads
   * 
   * 
   * Dijkstra can give me the set of shortest paths from entityHead to every other node in the tree.
   * This set can be expressed as a tree.
   * 
   */
  public double passageSpanningTreeFeature(Match m, edu.jhu.hlt.fnparse.datatypes.DependencyParse deps, int entityHead) {
    
    // Figure out which passage nodes need to be covered by the shortest-path tree
    List<Integer> sinks = new ArrayList<>();
    for (Alignment a : m.alignment) {
      if (a.type == Alignment.MATCH_W) {
        assert a.passageToken >= 0;
        sinks.add(a.passageToken);
      }
    }
    
    // Compute the shortest-path tree over the passage and relevant words
    LabeledDirectedGraph g = fromFnparse(deps);
    LabeledDirectedGraph shortestPaths = getShortestPathTree(g, entityHead, sinks);
    
    // Characterize the goodness of this passage by how small the tree is.
    // In the future, I may be able to featurize and learn which edges/paths are acceptable/not,
    // but for now I will use goodness = 2 * numAlignedWords / numEdgesInSpanningTree
    int nEdge = shortestPaths.getNumEdges() / 2;
    return 2d * sinks.size() / nEdge;
  }
  
  /**
   * TODO move to tutils.
   */
  public static LabeledDirectedGraph fromFnparse(edu.jhu.hlt.fnparse.datatypes.DependencyParse deps) {
    throw new RuntimeException("implement me");
  }
  
  /**
   * Runs Dijkstra's algorithm to construct the shortest-paths tree whose
   * root is the given source node and whose set of leaves includes all of the
   * given sinks nodes.
   *
   * TODO move to tutils.
   */
//  public static LabeledDirectedGraph getShortestPathTree(LabeledDirectedGraph graph, int source, int... sinks) {
  public static LabeledDirectedGraph getShortestPathTree(LabeledDirectedGraph graph, int source, List<Integer> sinks) {
    
    throw new RuntimeException("implement me");
  }
  
  
  /*
   * I think my claim that "the spanning tree over the passage should be small"
   * claim may be equivalent to a statement about the cost of the alignment.
   * "small tree" <=?=> theta * f(e(n1,n2)) is high,
   * where in f(n1,n2) the n are nodes in the dependency parse on either side
   * the feature function looks at whether the nodes appear in similar context
   * e.g. "John" in "John loves Mary" has features like nsubj(John, love) and John-nsubj->loves-dobj->Mary
   * cmp to "John" in "John 's dog loves biscuits" like poss(dog, John)
   */
  
  

  
  public List<Match> scoreAll(Sentence passage) {
    List<Match> matches = new ArrayList<>();
    for (int i = 0; i < numPatterns(); i++) {
      Match m = score(passage, i);
      if (m.numAlignedWords() > 0)
        matches.add(m);
    }
    return matches;
  }
  
  /**
   * Greedily builds an alignment between a pattern and the passage.
   * Every token in the pattern must be aligned.
   * 
   * TODO Characterize the minimum spanning tree over aligned passage tokens.
   */
  public Match score(Sentence passage, int pattern) {
    // Get to the correct pattern
    ConstituentItr si = patterns.getConstituentItr(patterns.cons_sentences);
    for (int i = 0; i < pattern; i++)
      si.gotoRightSib();
    
    // EXACT match based on words
    Match m = new Match(pattern, passage);
    for (TokenItr ti = patterns.getTokenItr(si.getFirstToken());
        ti.getIndex() <= si.getLastToken();
        ti.forwards()) {
      // Search for the pattern word in the passage
      Alignment a = new Alignment();
      a.patternToken = ti.getIndex();
      a.passageToken = -1;
      String patWord = patterns.getWordStr(ti.getIndex());
      String patPos = alph.pos(patterns.getPosH(ti.getIndex()));
      if (PATTERN_STOPWORDS.contains(patWord.toLowerCase())
          || PATTERN_STOPPOS.contains(patPos)) {
        a.cost = 0;
        a.type = Alignment.PAT_WILDCARD;
      } else {
        for (int i = 0; i < passage.size(); i++) {
          String passWord = passage.getWord(i);
          if (patWord.equalsIgnoreCase(passWord)) {
            a.passageToken = i;
          }
        }
        if (a.passageToken < 0) {
          a.cost = 1;
          a.type = Alignment.DEL;
        } else {
          a.cost = 0;
          a.type = Alignment.MATCH_W;
        }
      }
      m.add(a);
    }
    
    // TODO Softer matches?
    
    // TODO Build a spanning tree over the aligned passage nodes
    
    return m;
  }
  
  private static Sentence parseConllxToSentence(File conllxOneSentence) {
    List<String> c = FileUtil.getLines(conllxOneSentence);
    List<String[]> cn = new ArrayList<>(c.size());
    for (String s : c)
      if (!s.isEmpty())
        cn.add(s.split("\t"));
    boolean takeDepsAsParsey = true;
    Sentence passage = Sentence.convertFromConllX("", "", cn, takeDepsAsParsey);
    return passage;
  }
  
  public static NaturalLanguageSlotFill build(ExperimentProperties config) {
    File nsSfParent = new File("data/tackbp/natural-language-slot-fill");
    File nlSfConllx = config.getExistingFile("nlsf.conllx", new File(nsSfParent, "tac_kbp_QA_questions_inverses.parsey.conll"));
    File nlSfRels = config.getExistingFile("nlsf.relations", new File(nsSfParent, "tac_kbp_QA_questions_inverses.parsey.conll.relations"));
    NaturalLanguageSlotFill nlsf = new NaturalLanguageSlotFill(nlSfConllx, nlSfRels);
    return nlsf;
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    NaturalLanguageSlotFill nlsf = build(config);

    File nsSfParent = new File("data/tackbp/natural-language-slot-fill");
    Sentence ex1 = parseConllxToSentence(new File(nsSfParent, "sample-passages/ex1.parsey.conll"));
    System.out.println("passage:");
    System.out.println(Describe.sentenceWithDeps(ex1, ex1.getParseyDeps()));

    for (int i = 0; i < nlsf.numPatterns(); i++) {
      Match m = nlsf.score(ex1, i);
      if (m.numAlignedWords() > 0) {
        System.out.println("pattern(" + i + "): " + nlsf.getPattern(i) + "\t" + nlsf.getSfRelation(i));
        System.out.println("passage(" + i + "): " + Arrays.toString(ex1.getWords()));
        System.out.println("match(" + i + "): " + m);
        System.out.println();
      }
    }
  }
}
