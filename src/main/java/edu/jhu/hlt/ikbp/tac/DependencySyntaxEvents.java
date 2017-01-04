package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenList;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.data.WordNetPosUtil;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.TokenizationIter;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IPointer;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;

/**
 * Extracts events by using a dependency tree
 *
 * @author travis
 */
public class DependencySyntaxEvents {

  static Integer head(int i, DependencyParse deps) {
    List<Dependency> h = new ArrayList<>(1);
    for (Dependency d : deps.getDependencyList()) {
      if (d.getDep() == i)
        h.add(d);
    }
    if (h.isEmpty())
      return null;
    //assert h.size() == 1;
    if (h.size() > 1)
      Log.info("warning: not a tree: " + deps.getMetadata());
    if (h.get(0).isSetGov())
      return h.get(0).getGov();
    return -1;
  }

  static boolean rootNNP(int i, TokenTagging pos, DependencyParse deps) {
    String p = pos.getTaggedTokenList().get(i).getTag();
    if (p.toUpperCase().startsWith("NNP")) {
      Integer h = head(i, deps);
      if (h == null)    // punctuation?
        return false;
      if (h < 0)        // root
        return true;
      String hp = pos.getTaggedTokenList().get(h).getTag();
      return !hp.toUpperCase().startsWith("NNP");
    }
    return false;
  }

  static String show(Tokenization t) {
    StringBuilder sb = new StringBuilder();
    for (Token tok : t.getTokenList().getTokenList()) {
      if (sb.length() > 0)
        sb.append(' ');
      sb.append(tok.getText());
    }
    return sb.toString();
  }

  static class Path {
    int head;
    Dependency dep;
    Path prev;
    
    private String _relStrCache = null;

    public Path(int head) {
      this.head = head;
    }

    public Path(Dependency d, Path prev) {
      this.prev = prev;
      this.dep = d;
      if (d.getDep() == prev.head)
        head = d.getGov();
      else if (d.getGov() == prev.head)
        head = d.getDep();
      else
        throw new IllegalArgumentException();
    }

    public Path getLast() {
      for (Path cur = this; cur != null; cur = cur.prev)
        if (cur.prev == null)
          return cur;
      throw new RuntimeException();
    }
    
    public String relationStr() {
      if (_relStrCache == null) {
        StringBuilder sb = new StringBuilder();
        for (Path cur = this; cur != null; cur = cur.prev) {
          if (cur.dep != null) {
            if (sb.length() > 0)
              sb.append('-');
            sb.append(cur.dep.getEdgeType());
          }
        }
        _relStrCache = sb.toString();
      }
      return _relStrCache;
    }
  }

  static class CoverArgumentsWithPredicates {
    // Input
    Communication c;
    Tokenization t;
    DependencyParse deps;
    List<Integer> args;
    
    // Optional Input
    LL<String>[] synsets;   // indexed by token (in this Tokenization)

    // Processing
    Dependency[] heads;
    Deque<Path> queue;
    Path[] seen;

    // Output
    Map<Integer, Set<String>> situation2features;    // key is predicate word index, values are features for that situation
    Map<Integer, BitSet> situation2args;

    public CoverArgumentsWithPredicates(Communication c, Tokenization t, DependencyParse deps, List<Integer> args) {
      int n = t.getTokenList().getTokenListSize();
      this.t = t;
      this.deps = deps;
      this.situation2features = new HashMap<>();
      this.situation2args = new HashMap<>();
      this.args = args;

      heads = new Dependency[n];
      for (Dependency d : deps.getDependencyList()) {
        if (heads[d.getDep()] != null)
          throw new IllegalArgumentException("not a tree: " + deps.getMetadata());
        heads[d.getDep()] = d;
      }

      seen = new Path[n];
      queue = new ArrayDeque<>();
      for (int i : args)
        queue.push(seen[i] = new Path(i));
      while (!queue.isEmpty()) {
        Path p = queue.pop();
        Dependency h = heads[p.head];
        if (h == null)
          continue;
        Path pp = new Path(h, p);
        if (pp.head < 0) {
          // root
          // no-op?
        } else if (seen[pp.head] != null) {
          unify(pp, seen[pp.head]);
        } else {
          seen[pp.head] = pp;
          queue.push(pp);
        }
      }
    }
    
    Map<Integer, List<Feat>> situation2frames = new HashMap<>();
    
    static class Foo {
      String y;
      double score, scoreExp;
      List<Feat> s;
      
      public Foo(String y, List<Feat> s) {
        this.y = y;
        this.s = s;
        this.score = Feat.sum(s);
      }
      
      public static final Comparator<Foo> BY_SCORE_DESC = new Comparator<Foo>() {
        @Override
        public int compare(Foo o1, Foo o2) {
          assert Double.isFinite(o1.score);
          assert !Double.isNaN(o1.score);
          assert Double.isFinite(o2.score);
          assert !Double.isNaN(o2.score);
          if (o1.score > o2.score)
            return -1;
          if (o1.score < o2.score)
            return +1;
          return 0;
        }
      };
    }

    public void annotateSituations(Zomg z) {
      double cover = 0.9;
      TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
      for (int pred : situation2features.keySet()) {
        List<String> fx = z.features(pred, deps, pos, t);
        List<String> ys = z.possibleFrames(pred, deps, pos, t);
        List<Foo> foos = new ArrayList<>();
        double m = Double.NEGATIVE_INFINITY;
        for (String y : ys) {
          List<Feat> sy = z.score(y, fx);
          Foo f = new Foo(y, sy);
          foos.add(f);
          m = Math.max(m, f.score);
        }
        
        // Set the max to a reasonable constant
        double Z = 0;
        for (Foo f : foos) {
          f.score = (4-m) + f.score;
          f.scoreExp = Math.exp(f.score);
          Z += f.scoreExp;
        }
        
        Collections.sort(foos, Foo.BY_SCORE_DESC);
        List<Feat> ff = new ArrayList<>();
        double p = 0;
        for (Foo f : foos) {
          p += f.scoreExp;
          ff.add(new Feat(f.y, f.score));
          if (p/Z > cover)
            break;
        }
//        System.out.println("frames=" + ff);
        Object old = situation2frames.put(pred, ff);
        assert old == null;
      }
    }
    
    /**
     * @deprecated
     * @param wn
     */
    @SuppressWarnings("unchecked")
    public void useSynsetSetFeatures(IRAMDictionary wn) {
      int n = t.getTokenList().getTokenListSize();
      synsets = new LL[n];

//      TargetPruningData tpd = TargetPruningData.getInstance();
//      WordnetStemmer stemmer = tpd.getStemmer();
      
      TokenTagging lemmas = IndexCommunications.getPreferredLemmas(t);
      TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
      for (int i = 0; i < n; i++) {
        String lemma = lemmas.getTaggedTokenList().get(i).getTag();
        assert i == lemmas.getTaggedTokenList().get(i).getTokenIndex();
        String ptbPosTag = pos.getTaggedTokenList().get(i).getTag();
        assert i == pos.getTaggedTokenList().get(i).getTokenIndex();

        edu.mit.jwi.item.POS tag = WordNetPosUtil.ptb2wordNet(ptbPosTag);
        if (tag == null) {
//          Log.info("warning: couldn't look up ptbTag=" + ptbPosTag);
          continue;
        }
        
        // TODO Try with jwi lemmatizer (vs stanford), count number of successful lookups
//        String w = word.trim().replace("_", "");
//        if (w.length() == 0)
//          continue;
//        List<String> stems = stemmer.findStems(w, tag);
//        if (stems == null || stems.size() == 0)
//          continue;
//        IRAMDictionary dict = tpd.getWordnetDict();
//        IIndexWord ti = dict.getIndexWord(stems.get(0), tag);
//        if (ti == null || ti.getWordIDs().isEmpty())
//          continue;
//        IWordID t = ti.getWordIDs().get(0);
//        IWord iw = dict.getWord(t);
        
        IIndexWord x = wn.getIndexWord(lemma, tag);
        if (x == null) {
          if (!ptbPosTag.startsWith("NNP") && !ptbPosTag.equals("CD"))
            Log.info("warning: couldn't look up lemma=" + lemma + " tag=" + tag);
        } else {
          int a = 0, b = 0, c = 0;
          Set<String> uniq = new HashSet<>();
          String s;
          for (IWordID iw : x.getWordIDs()) {
            IWord w = wn.getWord(iw);
            ISynset ss = w.getSynset();
            
            
//            w.getSenseKey().
            System.out.println("iw=" + iw + "\tgloss=" + ss.getGloss());
            
            
            // Related words
            for (Entry<IPointer, List<IWordID>> rss : w.getRelatedMap().entrySet()) {
              String rel = rss.getKey().getSymbol();
              for (IWordID rwid : rss.getValue()) {
                IWord rw = wn.getWord(rwid);
                if (uniq.add(s = "wnRW/" + rel + "/" + rw.getSynset())) {
                  synsets[i] = new LL<>(s, synsets[i]);
                  a++;
                }
              }
            }
            
            // Synset
            if (uniq.add(s = ss.getID().toString())) {
              synsets[i] = new LL<>("wnSS/" + s, synsets[i]);
              b++;
            }

            // Related synsets
            for (Entry<IPointer, List<ISynsetID>> rss : w.getSynset().getRelatedMap().entrySet()) {
              String rel = rss.getKey().getSymbol();
              for (ISynsetID rssid : rss.getValue()) {
                if (uniq.add(s = "wnRSS/" + rel + "/" + rssid.toString())) {
                  synsets[i] = new LL<>(s, synsets[i]);
                  c++;
                }
              }
            }
          }
          String word = t.getTokenList().getTokenList().get(i).getText();
          System.out.printf("word=%-16s lemma=%-14s pos=%-5s a=%d b=%d c=%d %s\n", word, lemma, tag, a, b, c, synsets[i]);
        }
      }
    }
    
    public Map<Integer, BitSet> getArguments() {
      return situation2args;
    }
    
    public Map<Integer, Set<String>> getSituations() {
      return situation2features;
    }

    private void unify(Path left, Path right) {
      assert left.head == right.head;

      Set<String> fs = situation2features.get(left.head);
      if (fs == null) {
        fs = new HashSet<>();
        situation2features.put(left.head, fs);
      }
      
      BitSet args = situation2args.get(left.head);
      if (args == null) {
        args = new BitSet();
        situation2args.put(left.head, args);
      }
      args.set(left.getLast().head);
      args.set(right.getLast().head);

      TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
      TokenTagging lemma = IndexCommunications.getPreferredLemmas(t);

      Path l = left.getLast();
      Path r = right.getLast();
      Span ll = IndexCommunications.nounPhraseExpand(l.head, deps);
      Span rr = IndexCommunications.nounPhraseExpand(r.head, deps);

      if (this.args.contains(new Integer(left.head))) {
        fs.add("type=relation");
        String a1 = AccumuloIndex.words(ll, t);
        String a2 = AccumuloIndex.words(rr, t);
        fs.add("arg1=" + a1);
        fs.add("arg2=" + a2);
        
        Deque<Dependency> dep = new ArrayDeque<>();
        for (Path cur = left; cur != null; cur = cur.prev)
          if (cur.dep != null)
            dep.addLast(cur.dep);
        for (Path cur = right; cur != null; cur = cur.prev)
          if (cur.dep != null)
            dep.addFirst(cur.dep);
        
        for (int lex = 0; lex < dep.size(); lex++) {
          StringBuilder sb = new StringBuilder();
          Dependency prev = null;
          int i = 0;
          for (Dependency cur : dep) {
            if (prev != null) {
              // figure out the word common between prev+cur
              int common;
              if (prev.isSetGov() && cur.isSetGov() && prev.getGov() == cur.getGov()) {
                common = prev.getGov();
              } else if (prev.isSetGov() && prev.getGov() == cur.getDep()) {
                common = prev.getGov();
              } else if (cur.isSetGov() && prev.getDep() == cur.getGov()) {
                common = prev.getDep();
              } else if (prev.getDep() == cur.getDep()) {
                common = prev.getDep();
              } else {
                throw new RuntimeException("cur=" + cur + " prev=" + prev);
              }
              sb.append('-');
              sb.append(i != lex ? "X" : t.getTokenList().getTokenList().get(common).getText());
              i++;
            }
            if (prev != null)
              sb.append('-');
            sb.append(cur.getEdgeType());
            prev = cur;
          }
          String deprel = sb.toString();
          fs.add("deprel=" + deprel);
          if (lex == 0) {
            fs.add("deprelA1=" + deprel + "/" + a1);
            fs.add("deprelA2=" + deprel + "/" + a2);
          }
        }

      } else {
        String predPos = pos.getTaggedTokenList().get(left.head).getTag();
        String predLemma = lemma.getTaggedTokenList().get(left.head).getTag()
            + "." + predPos.charAt(0);
        String lw = AccumuloIndex.words(ll, t);
        String rw = AccumuloIndex.words(rr, t);
        fs.add("type=predicate." + predPos.charAt(0));
        fs.add("lemma=" + predLemma);
        fs.add("word=" + t.getTokenList().getTokenList().get(left.head).getText());
        fs.add("pos=" + predPos);
        if (!predPos.startsWith("V"))
          fs.add("pos=" + predPos);
        
        for (Dependency d : deps.getDependencyList()) {
          if (d.isSetGov() && d.getGov() == left.head && !IGNORE_DEPREL.contains(d.getEdgeType())) {
            fs.add("dsyn/" + d.getEdgeType() + "=" + t.getTokenList().getTokenList().get(d.getDep()).getText());
          }
        }
        
        /*
         * I have sort of deviated from what I have with attribute features.
         * Attribute features split entities which were put into the same bucket using something like a headword (or feature-match in general)
         * With situations, entity-valued arguments are really the only positive-evidence that two situations are the same
         * And realistically, we should have things like lemma, synset, or frame splitting them
         */

        fs.add("argA/" + lw);
        fs.add("argA/" + rw);
        fs.add("argB/" + lw + "/" + predLemma);
        fs.add("argB/" + rw + "/" + predLemma);
        fs.add("argC/" + lw + "/" + left.relationStr());
        fs.add("argC/" + rw + "/" + right.relationStr());
        fs.add("argD/" + lw + "/" + left.relationStr() + "/" + predLemma);
        fs.add("argD/" + rw + "/" + right.relationStr() + "/" + predLemma);
      }
    }

    public static final Set<String> IGNORE_DEPREL = new HashSet<>(Arrays.asList("punct"));
  }

  /*
   * Arguments are *features* for event coreference which fire when we can link the arguments for other reasons.
   * I am targeting cases where these arguments are nominals, for which I have a decent xdoc linker.
   * Lets say "Joe Tucci" is an argument and we unify two SitSeachResults to the same entity which play a role in two events.
   * There is some probability that if two events involve "Joe Tucci" then they are the same event, but the evidence is low.
   * Perhaps a good proxy for this evidence value is idf(headword="Tucci" is an argument to any event).
   * 
   * What if the events have the same lemma?
   * independence: idf("Tucci" is arg) + idf(pred has given lemma)
   * joint: idf("Tucci" is arg AND pred is lemma)  # table for this is likely too large... maybe count-min sketch it
   * - How to incorporate wordnet?
   *   instead of counting occurrences of a lemma, count occurrences of synsets. 
   *   this is really just extract features and count.
   * 
   * What if the (syntactic) roles match?
   * independence: idf("Tucci" is nsubj of a predicate)
   * backoff: idf("PERSON" is nsubj of a predicate)   # questionable value, p(ner(X)|nsubj(lemma,X)) probably has almost no entropy!
   *
   * 
   * How do we formalize "part that matches" => idf("some feature")?
   * The "part that matches" can be formulated as just feature extraction + intersection.
   * The question is how to, in general, specify the weight of these features...
   * For the triageFeats entity feature question, it was just a matter of indexing features, then taking the length of the inv. index
   * I don't see why that couldn't work here... lets flesh it out.
   * emit features like nsubj(X,PERSON) and nsubj(X,Tucci)
   *   also lemma(X,says) and synset(X,foo) and time(X,2015-YY-YY) time(X,2015-08-YY)
   *   time(X,2015-08-YY) & synset(X,foo) => count is going to be small
   *
   * 
   * How to handle "events" which do not have a headword per se?
   * e.g. "the [President] of the [United States]" has a pobj edge connecting "President" and "States",
   * there is no other word along that path which we might call the predicate word.
   * Perhaps an event is just a bag of features (ignoring how to show it to users for a moment -- we can have special non-scoring features like "mentionedAt/tokUuid/tokIdx")
   * I did want to do a sort of grouping of features though, e.g. the bags of features for nsubj(X,Y) and nsubj(X,Z) should be unioned BEFORE linking
   * Ok, lets just dictate that every feature has a predicate location, and by convention for any path we choose this to be the shallowest token
   * If this shallowest token is the same, then we call this the target and union all feature bags with the same target
   */
  
  public static int[] bs2a(BitSet bs) {
    int[] a = new int[bs.cardinality()];
    int j = 0;
    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i+1))
      a[j++] = i;
    return a;
  }
  
  public static List<Integer> extractEntityHeads(Tokenization t) {
    DependencyParse d = IndexCommunications.getPreferredDependencyParse(t);
    TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
    int n = pos.getTaggedTokenListSize();
    assert n == t.getTokenList().getTokenListSize();
    List<Integer> entHeads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      assert i == pos.getTaggedTokenList().get(i).getTokenIndex();

      // Look for an NNP(S)? whose parent is not an NNP
      if (!rootNNP(i, pos, d))
        continue;
      entHeads.add(i);
    }
    return entHeads;
  }

  public static void main(String[] mainArgs) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(mainArgs);
    File modelFile = config.getFile("modelFile");
    Zomg z;
    if (modelFile.isFile()) {
      Log.info("deserializing from " + modelFile.getPath());
      z = (Zomg) FileUtil.deserialize(modelFile);
    } else {
      z = new Zomg();
      FileUtil.serialize(z, modelFile);
    }
    countFeatures(config, z);
    Log.info("done");
  }
  
  public static void countFeatures(ExperimentProperties config, Zomg z) throws Exception {
    TimeMarker tm = new TimeMarker();
    long situations = 0, comms = 0, ents = 0;
    // 4B/int * 10 * 2^20 = 40MB
    int nHash = 10;
    int logCountersPerHash = 20;
    boolean conservativeUpdates = true;
    StringCountMinSketch counts = new StringCountMinSketch(nHash, logCountersPerHash, conservativeUpdates);
    File output = config.getFile("output");
    TimeMarker outputTm = new TimeMarker();
    double outputEvery = config.getDouble("outputEvery", 120);
//    IRAMDictionary wn = TargetPruningData.getInstance().getWordnetDict();
    List<String> fs = null;
    try (AutoCloseableIterator<Communication> iter = IndexCommunications.getCommunicationsForIngest(config)) {
      iterator:
      while (iter.hasNext()) {
        Communication c = iter.next();
        comms++;
        for (Tokenization t : new TokenizationIter(c)) {
          DependencyParse d = IndexCommunications.getPreferredDependencyParse(t);
          TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
          int n = pos.getTaggedTokenListSize();
          assert n == t.getTokenList().getTokenListSize();
          List<Integer> entHeads = extractEntityHeads(t);

          if (entHeads.size() < 2)
            continue;
          ents += entHeads.size();

          CoverArgumentsWithPredicates idfk = null;
          try {
            idfk = new CoverArgumentsWithPredicates(c, t, d, entHeads);
//            idfk.useSynsetSetFeatures(wn);
            idfk.annotateSituations(z);
          } catch (IllegalArgumentException e) {
            e.printStackTrace();
            continue iterator;
          }
          for (Entry<Integer, Set<String>> e : idfk.getSituations().entrySet()) {
            situations++;
            fs = new ArrayList<>(e.getValue());
            for (Feat f : idfk.situation2frames.get(e.getKey()))
              fs.add(f.name);
            for (String f : fs)
              counts.apply(f, true);
          }
        }
        
        if (tm.enoughTimePassed(10)) {
          Log.info("comms=" + comms + " sits=" + situations
              + " ents=" + ents + " curDoc=" + c.getId() + "\t" + Describe.memoryUsage());
          System.out.println("curFeats=" + fs);
          
          if (outputTm.enoughTimePassed(outputEvery)) {
            Log.info("serializing count-min sketch to " + output.getPath());
            FileUtil.serialize(counts, output);
          }
        }
      }
    }
  }
  
  static class Zomg implements Serializable {
    private static final long serialVersionUID = -8232003611293244877L;

    private StringCountMinSketch cx, cy, cyx;
    private Map<String, List<String>> lu2fs;    // e.g. "be.VGB" -> ["framenet/foo", "probank/bar"]
    
    public Zomg() {
      this(10, 20, true);
    }
    public Zomg(int nHash, int logCountersPerHash, boolean conservativeUpdates) {
      cx = new StringCountMinSketch(nHash, logCountersPerHash, conservativeUpdates);
      cy = new StringCountMinSketch(nHash, logCountersPerHash, conservativeUpdates);
      cyx = new StringCountMinSketch(nHash, logCountersPerHash, conservativeUpdates);
      lu2fs = new HashMap<>();
      try {
        count(new File("data/srl-reldata/framenet/srl.facts.gz"));
        count(new File("data/srl-reldata/propbank-withParsey-fineFrames/srl.facts.gz"));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public List<Feat> score(String y, List<String> fx) {
      List<Feat> f = new ArrayList<>();

      double logcy = Math.log(cy.apply(y, false)+1d);
      f.add(new Feat(y, logcy));

      for (String fxi : fx) {
        String fyx = y + "/" + fxi;
        double logcyx = Math.log(cyx.apply(fyx, false)+1d);
        f.add(new Feat(fyx, logcyx - logcy));
      }
      
//      System.out.printf("y=%-32s score=%.3f syx=%s\n", y, Feat.sum(f), PkbpSearching.sortAndPrune(f, 6));

      return f;
    }
    
    public List<String> possibleFrames(int pred, DependencyParse deps, TokenTagging pos, Tokenization t) {
      String lu = t.getTokenList().getTokenList().get(pred).getText();
      List<String> fs = lu2fs.get(lu);
      if (fs == null)
        return Collections.emptyList();
      return fs;
    }

    private List<String> features(int pred, DependencyParse deps, TokenTagging pos, Tokenization t) {
      List<String> fx = new ArrayList<>();
      String predWord = t.getTokenList().getTokenList().get(pred).getText();
      String predPos = pos.getTaggedTokenList().get(pred).getTag();
      fx.add("word/" + predWord);
      fx.add("word/" + predWord + "." + predPos);
      fx.add("pos/" + predPos);
      int k = 5;
      List<Pair<Integer, LL<Dependency>>> paths = NNPSense.kHop(pred, k, deps);
      pathloop:
      for (Pair<Integer, LL<Dependency>> path : paths) {
        if (path.get2() == null)
          continue;
        StringBuilder sb = new StringBuilder();
        sb.append("dsyn/");
        int to = path.get1();
        int len = 0;
        for (LL<Dependency> cur = path.get2(); cur != null; cur = cur.next) {
          Dependency d = cur.item;
          boolean up = d.getDep() == to;
          assert up || d.getGov() == to;
          
          if (d.getEdgeType().equals("punct"))
            continue pathloop;
          
          sb.append(d.getEdgeType());
          sb.append(up ? ">" : "<");
          
          to = up ? d.getGov() : d.getDep();
          len++;
        }
        String arg = t.getTokenList().getTokenList().get(path.get1()).getText();
        fx.add(sb.toString());
        sb.append('/');
        sb.append(arg);
        fx.add(sb.toString());
        fx.add("dsyn/len" + len + "path/" + arg);
      }
      return fx;
    }
    
    private void count(File f) throws Exception {
      Log.info("reading " + f.getPath());
      TimeMarker tm = new TimeMarker();
      int nd = 0, nf = 0;
      try (RelationFileIterator rfi = new RelationFileIterator(f, false);
          ManyDocRelationFileIterator md = new ManyDocRelationFileIterator(rfi, true)) {
        while (md.hasNext()) {
          RelDoc doc = md.next();
          List<RelLine> frames = doc.findLinesOfRelation("predicate2");
          if (frames.isEmpty())
            continue;
          List<RelLine> words = doc.findLinesOfRelation("word2");
          List<RelLine> pos = doc.findLinesOfRelation("pos2");
          List<RelLine> deps = doc.findLinesOfRelation("dsyn3-parsey");
          
          Tokenization t = new Tokenization();
          t.setTokenList(new TokenList());
          for (RelLine wl : words) {
            t.getTokenList().addToTokenList(new Token()
                .setTokenIndex(Integer.parseInt(wl.tokens[2]))
                .setText(wl.tokens[3]));
          }

          DependencyParse d = new DependencyParse();
          t.addToDependencyParseList(d);
          for (RelLine dl : deps) {
            d.addToDependencyList(new Dependency()
                .setGov(Integer.parseInt(dl.tokens[2]))
                .setDep(Integer.parseInt(dl.tokens[3]))
                .setEdgeType(dl.tokens[4]));
          }

          TokenTagging p = new TokenTagging();
          t.addToTokenTaggingList(p);
          p.setTaggingType("POS");
          for (RelLine pl : pos) {
            p.addToTaggedTokenList(new TaggedToken()
                .setTokenIndex(Integer.parseInt(pl.tokens[2]))
                .setTag(pl.tokens[3]));
          }

          TokenTagging y = new TokenTagging();
          t.addToTokenTaggingList(y);
          y.setTaggingType("frames");
          for (RelLine fl : frames) {
            y.addToTaggedTokenList(new TaggedToken()
                .setTokenIndex(Span.inverseShortString(fl.tokens[2]).start)
                .setTag(fl.tokens[3]));
          }

          // Count
          for (TaggedToken frame : y.getTaggedTokenList()) {
            List<String> fx = features(frame.getTokenIndex(), d, p, t);
            for (String fxi : fx) {
//              System.out.println("cyx: " + frame.getTag() + "/" + fxi);
              cyx.apply(frame.getTag() + "/" + fxi, true);
              cy.apply(frame.getTag(), true);
              cx.apply(fxi, true);
            }

            String lu = t.getTokenList().getTokenList().get(frame.getTokenIndex()).getText();
            List<String> fs = lu2fs.get(lu);
            if (fs == null) {
              fs = new ArrayList<>();
              lu2fs.put(lu, fs);
            }
            if (!fs.contains(frame.getTag()))
              fs.add(frame.getTag());
            nf++;
          }
          nd++;
          
          if (tm.enoughTimePassed(5))
            Log.info("ndoc=" + nd + " nframe=" + nf);
        }
      }
    }
  }

}
