package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.HasId;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.morph.WordnetStemmer;

public class Sentence implements HasId {
  public static final Logger LOG = Logger.getLogger(Sentence.class);
  public static final Sentence nullSentence =
      new Sentence("nullSentenceDataset", "nullSentence", new String[0], new String[0], new String[0], new int[0], new String[0]);

  /**
   * a __globally__ unique identifier
   * should not overlap between datasets
   */
  private String id;

  /**
   * where did this sentence come from?
   * redundant with id, a convenience
   */
  private String dataset;

  private String[] tokens;
  private String[] pos;
  private String[] lemmas;

  private DependencyParse collapsedDeps;
  private DependencyParse basicDeps;

  private ConstituencyParse stanfordParse;

  public Sentence(
      String dataset,
      String id,
      String[] tokens,
      String[] pos,
      String[] lemmas,
      int[] gov,
      String[] depType) {
    if(id == null || tokens == null)
      throw new IllegalArgumentException();
    final int n = tokens.length;
    if(pos != null && pos.length != n)
      throw new IllegalArgumentException();
    if(lemmas != null && lemmas.length != n)
      throw new IllegalArgumentException();
    if(gov != null && gov.length != n)
      throw new IllegalArgumentException();
    if(depType != null && depType.length != n)
      throw new IllegalArgumentException();

    this.dataset = dataset;
    this.id = id;
    this.tokens = tokens;
    this.pos = pos;
    this.lemmas = lemmas;
    if (gov != null) {
      assert depType != null;
      this.collapsedDeps = new DependencyParse(gov, depType);
    }

    // upcase the POS tags for consistency (e.g. with LexicalUnit)
    for(int i=0; i<pos.length; i++)
      this.pos[i] = this.pos[i].toUpperCase().intern();
  }

  public Sentence copy() {
    throw new RuntimeException("this is not up to date!");
    //return new Sentence(dataset, id, tokens.clone(), pos.clone(),
    //    lemmas.clone(), collapsedDeps.getHeads(), collapsedDeps.getLabels());
  }

  public String getDataset() { return dataset; }
  public String getId() { return id; }

  public static final String fnStyleBadPOSstrPrefix = "couldn't convert Penn tag of ".toUpperCase();	// once in LU, this will be upcased anyway
  /**
   * Uses the lemma instead of the word, and converts POS to
   * a FrameNet style POS.
   */
  public LexicalUnit getFNStyleLU(
      int i, IDictionary dict, boolean lowercase) {
    LexicalUnit x = getFNStyleLUUnsafe(i, dict, lowercase);
    if(x == null)
      return new LexicalUnit(tokens[i], fnStyleBadPOSstrPrefix + pos[i]);
    else
      return x;
  }

  public LexicalUnit getFNStyleLUUnsafe(
      int i, IDictionary dict, boolean lowercase) {
    String fnTag = PosUtil.getPennToFrameNetTags().get(pos[i]);
    if(fnTag == null) return null;
    WordnetStemmer stemmer = new WordnetStemmer(dict);
    List<String> allStems = Collections.emptyList();
    String w = null;
    try {
      w = lowercase ? tokens[i].toLowerCase() : tokens[i];
      allStems = stemmer.findStems(w, PosUtil.ptb2wordNet(pos[i]));
    }
    catch(java.lang.IllegalArgumentException e) {
      LOG.warn("bad word? " + getLU(i));
    }
    if(allStems.isEmpty())
      return new LexicalUnit(w, fnTag);
    return new LexicalUnit(allStems.get(0), fnTag);
  }

  /**
   * Gives you a LexicalUnit using the lemma for this token rather than the
   * word. this method is safe (if you call with i=-1 or i=n, it will return
   * "<S>" and "</S>" fields).
   */
  public LexicalUnit getLemmaLU(int i) {
    if(i == -1)
      return AbstractFeatures.luStart;
    if(i == tokens.length)
      return AbstractFeatures.luEnd;
    return new LexicalUnit(lemmas[i], pos[i]);
  }

  /**
   * Gives you the original word (no case change or anything), along with the
   * POS tag (your only guarantee is that this is upper case).
   */
  public LexicalUnit getLU(int i) {
    return new LexicalUnit(tokens[i], pos[i]);
  }

  private transient IWord[] wnWords = null;
	public IWord getWnWord(int i) {
	  if (wnWords == null) {
	    wnWords = new IWord[tokens.length];
	    TargetPruningData tpd = TargetPruningData.getInstance();
	    IRAMDictionary dict = tpd.getWordnetDict();
	    WordnetStemmer stemmer = tpd.getStemmer();
	    for (int idx = 0; idx < tokens.length; idx++) {
	      edu.mit.jwi.item.POS tag = PosUtil.ptb2wordNet(getPos(idx));
	      if (tag == null)
	        continue;
	      String w = getWord(idx).trim().replace("_", "");
	      if (w.length() == 0)
	        continue;
	      List<String> stems = stemmer.findStems(w, tag);
	      if (stems == null || stems.size() == 0)
	        continue;
	      IIndexWord ti = dict.getIndexWord(stems.get(0), tag);
	      if (ti == null || ti.getWordIDs().isEmpty())
	        continue;
	      IWordID t = ti.getWordIDs().get(0);
	      wnWords[idx] = dict.getWord(t);
	    }
	  }
	  return wnWords[i];
	}

  public String[] getWords() {return Arrays.copyOf(tokens, tokens.length);}
  public String getWord(int i) { return tokens[i]; }
  public String[] getPos() { return pos; }
  public String getPos(int i) { return pos[i]; }
  public String[] getLemmas() { return lemmas; }
  public String getLemma(int i){return lemmas[i];}

  public String[] getWordFor(Span s) { return Arrays.copyOfRange(tokens, s.start, s.end); }
  public String[] getPosFor(Span s) { return Arrays.copyOfRange(pos, s.start, s.end); }
  public String[] getLemmasFor(Span s) { return Arrays.copyOfRange(lemmas, s.start, s.end); }

  public int[] childrenOf(int wordIdx) {
    return collapsedDeps.getChildren(wordIdx);
  }

  public boolean isRoot(int i) {
    return collapsedDeps.isRoot(i);
  }

  /** returns 0 if it hits a loop */
  public int depth(int i) {
    int[] gov = collapsedDeps.getHeads();
    int cur = i;
    int depth = 0;
    boolean[] seen = new boolean[gov.length];
    seen[cur] = true;
    while (cur >= 0 && cur < gov.length) {
      depth++;
      cur = gov[cur];
      if (cur >= 0 && cur < seen.length && !seen[cur])
        seen[cur] = true;
      else
        return 0;
    }
    return depth;
  }

  public int governor(int i) {
    //return gov[i];
    return collapsedDeps.getHead(i);
  }

  /**
   * Returns true if token i is a verb and is part of a passive construction
   */
  public boolean passive(int i) {
    if (!pos[i].startsWith("V"))
      return false;
    // Look for auxpass or nsubjpass while skipping over verb-like things
    for (int idx = i - 1, searched = 0; idx > 0; idx--, searched++) {
      //String label = depType[idx];
      String label = collapsedDeps.getLabel(idx);
      if ("auxpass".equals(label)
          || "nsubjpass".equals(label))
        return true;
      if (pos[idx].startsWith("N")) break;
      if (searched > 5) return false;
    }
    return false;
  }

  /**
   * Returns the index of a verb to the right of token i. Skips over aux verbs
   * and returns -1 if no verb is found.
   */
  public int nextHeadVerb(int i) {
    int lastVerb = -1;
    for (int j = i + 1; j < size(); j++) {
      boolean v = pos[i].startsWith("V");
      if (v && lastVerb >= 0)
        lastVerb++;
      if (!v && lastVerb >= 0)
        return lastVerb;
      if (v && lastVerb < 0)
        lastVerb = j;
    }
    return lastVerb;
  }

  public String dependencyType(int childIdx) {
    return collapsedDeps.getLabel(childIdx);
  }

  public List<String> wordsIn(Span s) {
    List<String> l = new ArrayList<String>();
    for(int i=s.start; i<s.end; i++)
      l.add(tokens[i]);
    return l;
  }

  public List<String> posIn(Span s) {
    List<String> l = new ArrayList<String>();
    for(int i=s.start; i<s.end; i++)
      l.add(pos[i]);
    return l;
  }

  public int size() { return tokens.length; }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("<Sentence");
    sb.append(" ");
    sb.append(id);
    for(int i=0; i<size(); i++) {
      //sb.append(String.format(" %s/%s", getWord(i), getPos(i)));
      sb.append(String.format(" %s", getWord(i)));
    }
    sb.append(">");
    return sb.toString();
  }

  @Override
  public int hashCode() { return id.hashCode(); }

  @Override
  public boolean equals(Object o) {
    if(o instanceof Sentence) {
      Sentence other = (Sentence) o;
      return id.equals(other.id)
          && Arrays.equals(tokens, other.tokens)
          && Arrays.equals(pos, other.pos);
    }
    else return false;
  }

  public DependencyParse getCollapsedDeps() {
    return collapsedDeps;
  }

  public void setCollapsedDeps(DependencyParse collapedDeps) {
    this.collapsedDeps = collapedDeps;
  }

  public DependencyParse getBasicDeps() {
    return basicDeps;
  }

  public void setBasicDeps(DependencyParse basicDeps) {
    this.basicDeps = basicDeps;
  }

  /*
  public ConstituencyParse getStanfordParse() {
    return this.stanfordParse;
  }

  public void setStanfordParse(ConstituencyParse p) {
    this.stanfordParse = p;
  }
  */
}
