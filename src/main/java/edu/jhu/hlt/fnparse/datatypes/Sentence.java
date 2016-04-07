package edu.jhu.hlt.fnparse.datatypes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.HasId;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.Document.ConstituentItr;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TokenToConstituentIndex;
import edu.jhu.hlt.tutils.data.WordNetPosUtil;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.morph.WordnetStemmer;
import edu.stanford.nlp.process.Morphology;

public class Sentence implements HasId, Serializable {
  private static final long serialVersionUID = 4441193252111939157L;

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

  private String[] shapes;

  /**
   * @deprecated real collapsed dependences are not trees (may have more than
   * one parent), and cannot be represented by this type. This is at best a copy
   * of the basic parse (see {@link PropbankReader}).
   */
  private DependencyParse collapsedDeps;
  private DependencyParse basicDeps;
  private ConstituencyParse stanfordParse;
  private ConstituencyParse goldParse;
  private boolean hideSyntax = false;

  private StringLabeledDirectedGraph colDeps2;
  private StringLabeledDirectedGraph colCCDeps2;

  /**
   * Helps map back into concrete.Communications.
   *
   * If this Sentence was constructed from a {@link Communication} via a
   * {@link ConcreteDocumentMapping}, then you can use this field to point to
   * the Consituent in a tutils.Document corresponding to this sentence. With
   * this information, you can use {@link ConcreteDocumentMapping} to get a
   * {@link Tokenization} UUID, and add back annotations into their proper
   * location (primarily {@link TokenRefSequence}).
   *
   * Using this field lets {@link ConcreteToDocument} construct the mapping from
   * {@link Communication} elements (some of which may be skipped over for
   * example) to tutils.Document, and thus Sentences. Letting {@link ConcreteToDocument}
   * be in charge is much cleaner than making assumptions like "there is one
   * FNParse per concrete.Sentence"...
   */
  public int tutilsSentenceConsIdx = -1;

  /**
   * Stanford col-cc and UD parses are not supported right now.
   * @param firstToken inclusive
   * @param lastToken inclusive
   */
  public static Sentence convertFromTutils(
      String dataset, String id,
      edu.jhu.hlt.tutils.Document doc,
      int firstToken, int lastToken,
      boolean addGoldParse,
      boolean addStanfordCParse,
      boolean addStandordBasicDParse,
      boolean addStanfordColDParse,
      boolean takeGoldPos) {
    if (firstToken < 0)
      throw new IllegalArgumentException("firstToken=" + firstToken + " lastToken=" + lastToken);
    if (lastToken < 0 || lastToken < firstToken)
      throw new IllegalArgumentException("firstToken=" + firstToken + " lastToken=" + lastToken);

    MultiAlphabet a = doc.getAlphabet();
    int width = (lastToken - firstToken) + 1;
    String[] tokens = new String[width];
    String[] pos = new String[width];
    String[] lemmas = new String[width];
    for (int i = 0; i < width; i++) {
      edu.jhu.hlt.tutils.Document.Token t = doc.getToken(firstToken + i);
      tokens[i] = t.getWordStr();
      int p = takeGoldPos ? t.getPosG() : t.getPosH();
      assert p >= 0 : "takeGoldPos=" + takeGoldPos + " posG=" + t.getPosG() + " posH=" + t.getPosH();
      pos[i] = a.pos(p);
      if (t.getLemma() >= 0)
        lemmas[i] = doc.getAlphabet().lemma(t.getLemma());
    }

    Sentence s = new Sentence(dataset, id, tokens, pos, lemmas);

    // Leave bread crumbs to get back to tutils elements, and thus concrete elements.
    TokenToConstituentIndex t2c = doc.getT2cSentence();
    s.tutilsSentenceConsIdx = t2c.getParent(firstToken);
    assert s.tutilsSentenceConsIdx >= 0;
    assert s.tutilsSentenceConsIdx == t2c.getParent(lastToken);

    if (addStandordBasicDParse) {
      assert doc.stanfordDepsBasic != null;
      s.basicDeps = new DependencyParse(doc.stanfordDepsBasic, a, firstToken, lastToken);
    }
    if (addStanfordColDParse) {
      assert doc.stanfordDepsCollapsed != null;
      s.collapsedDeps = new DependencyParse(doc.stanfordDepsCollapsed, a, firstToken, lastToken);
    }
    if (addGoldParse) {
      assert doc.cons_ptb_gold >= 0;
      ConstituentItr p = doc.getConstituentItr(doc.cons_ptb_gold);
      while (p.isValid() && !(p.getFirstToken() == firstToken && p.getLastToken() == lastToken))
        p.gotoRightSib();
      if (!p.isValid())
        throw new RuntimeException("didn't find parse");
      s.goldParse = new ConstituencyParse(id, firstToken, p);
      s.goldParse.buildPointers();
    }
    if (addStanfordCParse) {
      assert doc.cons_ptb_auto >= 0;
      ConstituentItr p = doc.getConstituentItr(doc.cons_ptb_auto);
      while (p.isValid() && !(p.getFirstToken() == firstToken && p.getLastToken() == lastToken))
        p.gotoRightSib();
      if (!p.isValid())
        throw new RuntimeException("didn't find parse");
      s.stanfordParse = new ConstituencyParse(id, firstToken, p);
      s.stanfordParse.buildPointers();
    }

    return s;
  }

  public Sentence(
      String dataset,
      String id,
      String[] tokens,
      String[] pos,
      String[] lemmas) {
    if(id == null || tokens == null)
      throw new IllegalArgumentException();
    final int n = tokens.length;
    if(pos != null && pos.length != n)
      throw new IllegalArgumentException();
    if(lemmas != null && lemmas.length != n)
      throw new IllegalArgumentException();

    this.dataset = dataset;
    this.id = id;
    this.tokens = tokens;
    this.pos = pos;
    this.lemmas = lemmas;

    // upcase the POS tags for consistency (e.g. with LexicalUnit)
    for (int i = 0; i < pos.length; i++)
      this.pos[i] = this.pos[i].toUpperCase().intern();
  }

  public Sentence copy() {
    throw new RuntimeException("this is not up to date!");
  }

  public void stripSyntaxDown() {
    assert false : "update this method for new parses";
    if (collapsedDeps != null)
      collapsedDeps.stripEdgeLabels();
    if (basicDeps != null)
      basicDeps.stripEdgeLabels();
    if (stanfordParse != null)
      stanfordParse.stripCategories();
    if (goldParse != null)
      goldParse.stripCategories();
  }

  public void lemmatize() {
    boolean lowercase = true;
    for (int i = 0; i < lemmas.length; i++)
      lemmas[i] = Morphology.lemmaStatic(tokens[i], pos[i], lowercase);
  }

  public int size() { return tokens.length; }

  public String getDataset() { return dataset; }

  public String getId() { return id; }

  public static final String fnStyleBadPOSstrPrefix =
      "couldn't convert Penn tag of ".toUpperCase();
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
    String fnTag = WordNetPosUtil.getPennToFrameNetTags().get(pos[i]);
    if(fnTag == null) return null;
    WordnetStemmer stemmer = new WordnetStemmer(dict);
    List<String> allStems = Collections.emptyList();
    String w = null;
    try {
      w = lowercase ? tokens[i].toLowerCase() : tokens[i];
      allStems = stemmer.findStems(w, WordNetPosUtil.ptb2wordNet(pos[i]));
    }
    catch(java.lang.IllegalArgumentException e) {
      Log.warn("bad word? " + getLU(i));
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
        edu.mit.jwi.item.POS tag = WordNetPosUtil.ptb2wordNet(getPos(idx));
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

  public String getCoarsePos(int i) {
    return pos[i].substring(0, 1).toUpperCase();
  }

  public String[] getWordFor(Span s) {
    return Arrays.copyOfRange(tokens, s.start, s.end);
  }
  public String[] getPosFor(Span s) {
    return Arrays.copyOfRange(pos, s.start, s.end);
  }
  public String[] getLemmasFor(Span s) {
    return Arrays.copyOfRange(lemmas, s.start, s.end);
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

  public List<String> wordsIn(Span s) {
    List<String> l = new ArrayList<String>();
    for(int i=s.start; i<s.end; i++)
      l.add(tokens[i]);
    return l;
  }

  public String getShape(int i) {
    if (shapes == null)
      return null;
    return shapes[i];
  }

  public void setShape(int i, String shape) {
    if (shapes == null)
      shapes = new String[this.size()];
    shapes[i] = shape;
  }

  public List<String> posIn(Span s) {
    List<String> l = new ArrayList<String>();
    for(int i=s.start; i<s.end; i++)
      l.add(pos[i]);
    return l;
  }

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

  public boolean syntaxIsHidden() {
    return hideSyntax;
  }

  public void hideSyntax() {
    hideSyntax(true);
  }
  public void hideSyntax(boolean hidden) {
    this.hideSyntax = hidden;
  }

  /** @deprecated */
  public DependencyParse getCollapsedDeps() {
    return getCollapsedDeps(true);
  }
  /** @deprecated */
  public DependencyParse getCollapsedDeps(boolean askPermission) {
    if (askPermission && hideSyntax)
      return null;
    return collapsedDeps;
  }
  /** @deprecated */
  public void setCollapsedDeps(DependencyParse collapedDeps) {
    this.collapsedDeps = collapedDeps;
  }

  public StringLabeledDirectedGraph getCollapsedDeps2() {
    return getCollapsedDeps2(true);
  }
  public StringLabeledDirectedGraph getCollapsedDeps2(boolean askPermission) {
    if (askPermission && hideSyntax)
      return null;
    return colDeps2;
  }
  public void setCollapsedDeps2(StringLabeledDirectedGraph deps) {
    this.colDeps2 = deps;
  }

  public StringLabeledDirectedGraph getCollapsedCCDeps2() {
    return getCollapsedCCDeps2(true);
  }
  public StringLabeledDirectedGraph getCollapsedCCDeps2(boolean askPermission) {
    if (askPermission && hideSyntax)
      return null;
    return colCCDeps2;
  }
  public void setCollapsedCCDeps2(StringLabeledDirectedGraph deps) {
    this.colCCDeps2 = deps;
  }

  public DependencyParse getBasicDeps() {
    return getBasicDeps(true);
  }
  public DependencyParse getBasicDeps(boolean askPermission) {
    if (askPermission && hideSyntax)
      return null;
    return basicDeps;
  }

  public void setBasicDeps(DependencyParse basicDeps) {
    this.basicDeps = basicDeps;
  }

  public ConstituencyParse getStanfordParse() {
    return getStanfordParse(true);
  }
  public ConstituencyParse getStanfordParse(boolean askPermission) {
    if (askPermission && hideSyntax)
      return null;
    return this.stanfordParse;
  }

  public void setStanfordParse(ConstituencyParse p) {
    this.stanfordParse = p;
  }

  public ConstituencyParse getGoldParse() {
    return getGoldParse(true);
  }
  public ConstituencyParse getGoldParse(boolean askPermission) {
    if (askPermission && hideSyntax)
      return null;
    return this.goldParse;
  }

  public void setGoldParse(ConstituencyParse p) {
    this.goldParse = p;
  }
}
