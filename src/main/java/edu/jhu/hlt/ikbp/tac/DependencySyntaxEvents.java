package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.TokenizationIter;

/**
 * Extracts events by using a dependency tree
 *
 * @author travis
 */
public class DependencySyntaxEvents {

  static class PredArg {
    int pred;
    String predLemma;

    int arg;
    String argHead;
    String argPhrase;

    String depPathStr;
    List<Dependency> depPath;

    double score;       // for external use

    public PredArg(int pred, int arg, List<Dependency> depPath) {
      this.pred = pred;
      this.arg = arg;
      this.depPath = depPath;
    }

    @Override
    public String toString() {
      return "(PA p=" + pred + " a=" + arg + ")";
    }

    public static final Comparator<PredArg> BY_PRED_ASC = new Comparator<PredArg>() {
      @Override
      public int compare(PredArg o1, PredArg o2) {
        assert o1.pred >= 0;
        assert o2.pred >= 0;
        if (o1.pred < o2.pred)
          return -1;
        if (o1.pred > o2.pred)
          return +1;
        return 0;
      }
    };
  }

  public static String depListToStr(int startPoint, List<Dependency> path, Tokenization t) {
    StringBuilder sb = new StringBuilder();
    int prev = startPoint;
    for (int i = 0; i < path.size(); i++) {
      Dependency d = path.get(i);
      if (d.getDep() == prev) {
        sb.append('-');
        sb.append(d.getEdgeType());
        sb.append("->");
        assert d.isSetGov();
        prev = d.getGov();
      } else if (d.isSetGov() && d.getGov() == prev) {
        sb.append("<-");
        sb.append(d.getEdgeType());
        sb.append('-');
        prev = d.getDep();
      } else {
        throw new RuntimeException("prev=" + prev + " d=" + d + " path=" + sb.toString());
      }

      if (i < path.size()-1) {
        if (prev < 0)
          sb.append("ROOT");
        else
          sb.append(t.getTokenList().getTokenList().get(prev).getText());
      }
    }
    return sb.toString();
  }

  public static List<PredArg> extract(int argHead, DependencyParse deps, Tokenization toks) {
    List<PredArg> ex = new ArrayList<>();

    int k = 5;
    List<Pair<Integer, LL<Dependency>>> paths = NNPSense.kHop(argHead, k, deps);

    TokenTagging pos = IndexCommunications.getPreferredPosTags(toks);

    for (Pair<Integer, LL<Dependency>> path : paths) {
      if (path.get2() == null)
        continue; // length 0 path

      // An endpoint may be V* or NNP*
      int endpoint = path.get1();
      String endpointPos = pos.getTaggedTokenList().get(endpoint).getTag().toUpperCase();
      if (endpointPos.startsWith("V") || endpointPos.startsWith("NNP")) {

        // Reverse the path
        List<Dependency> dpath = new ArrayList<>();
        for (LL<Dependency> cur = path.get2(); cur != null; cur = cur.next)
          dpath.add(cur.item);
        Collections.reverse(dpath);

        PredArg pa = new PredArg(endpoint, argHead, dpath);
        pa.depPathStr = depListToStr(argHead, pa.depPath, toks);
        ex.add(pa);
      }
    }

    return ex;
  }

  public static class GroupByPred implements Iterator<List<PredArg>> {
    List<PredArg> paSortedByPred;
    int paIdx;

    public GroupByPred(List<PredArg> pas) {
      this.paSortedByPred = pas;
      Collections.sort(paSortedByPred, PredArg.BY_PRED_ASC);
      this.paIdx = 0;
    }

    @Override
    public boolean hasNext() {
      return paIdx < paSortedByPred.size();
    }
    @Override
    public List<PredArg> next() {
      // Grab all PAs with this pred
      int p = paSortedByPred.get(paIdx).pred;
      List<PredArg> pa = new ArrayList<>();
      for (; paIdx < paSortedByPred.size(); paIdx++) {
        int pp = paSortedByPred.get(paIdx).pred;
        if (pp == p)
          pa.add(paSortedByPred.get(paIdx));
      }
      return pa;
    }
  }

  static Integer head(int i, DependencyParse deps) {
    List<Dependency> h = new ArrayList<>(1);
    for (Dependency d : deps.getDependencyList()) {
      if (d.getDep() == i)
        h.add(d);
    }
    if (h.isEmpty())
      return null;
    assert h.size() == 1;
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

  static boolean debugSentence(Tokenization t) {
    boolean china = false;
    boolean coke = false;
    for (Token tok : t.getTokenList().getTokenList()) {
      coke |= tok.getText().toLowerCase().contains("coke");
      china |= tok.getText().toLowerCase().contains("chinese");
      if (china && coke)
        return true;
    }
    return false;
  }

  static class Path {
    int head;
    Dependency dep;
    Path prev;

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
      StringBuilder sb = new StringBuilder();
      for (Path cur = this; cur != null; cur = cur.prev) {
        if (cur.dep != null) {
          if (sb.length() > 0)
            sb.append('-');
          sb.append(cur.dep.getEdgeType());
        }
      }
      return sb.toString();
    }
  }

  static class Idfk {
    // Input
    Tokenization t;
    DependencyParse deps;
    List<Integer> args;

    // Processing
    Dependency[] heads;
    Deque<Path> queue;
    Path[] seen;

    // Output
    Map<Integer, Set<String>> situation2features;    // key is predicate word index, values are features for that situation

    public Idfk(Tokenization t, DependencyParse deps, List<Integer> args) {
      int n = t.getTokenList().getTokenListSize();
      this.t = t;
      this.deps = deps;
      this.situation2features = new HashMap<>();
      this.args = args;

      heads = new Dependency[n];
      for (Dependency d : deps.getDependencyList()) {
        if (heads[d.getDep()] != null)
          throw new IllegalArgumentException("not a tree");
        heads[d.getDep()] = d;
      }

      seen = new Path[n];
      queue = new ArrayDeque<>();
      for (int i : args)
        queue.push(seen[i] = new Path(i));
      while (!queue.isEmpty()) {
        Path p = queue.pop();
        Dependency h = heads[p.head];
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
    
    public Map<Integer, Set<String>> getSituations() {
      return situation2features;
    }

    private String words(Span s) {
      StringBuilder sb = new StringBuilder();
      for (int i = s.start; i < s.end; i++) {
        if (i > s.start)
          sb.append('_');
        sb.append(t.getTokenList().getTokenList().get(i).getText());
      }
      return sb.toString();
    }

    private void unify(Path left, Path right) {
      assert left.head == right.head;

      Set<String> fs = situation2features.get(left.head);
      if (fs == null) {
        fs = new HashSet<>();
        situation2features.put(left.head, fs);
      }

      TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
      TokenTagging lemma = IndexCommunications.getPreferredLemmas(t);

      Path l = left.getLast();
      Path r = right.getLast();
      Span ll = IndexCommunications.nounPhraseExpand(l.head, deps);
      Span rr = IndexCommunications.nounPhraseExpand(r.head, deps);

      if (this.args.contains(new Integer(left.head))) {
        fs.add("type=relation");
        fs.add("arg1=" + words(ll));
        fs.add("arg2=" + words(rr));
        
        Deque<Dependency> dep = new ArrayDeque<>();
        for (Path cur = left; cur != null; cur = cur.prev)
          if (cur.dep != null)
            dep.addLast(cur.dep);
        for (Path cur = right; cur != null; cur = cur.prev)
          if (cur.dep != null)
            dep.addFirst(cur.dep);
        
        for (int lex = 0; lex < dep.size(); lex++) {
          StringBuilder sb = new StringBuilder();
          sb.append("deprel=");
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
          fs.add(sb.toString());
        }

      } else {
        String predPos = pos.getTaggedTokenList().get(left.head).getTag();
        fs.add("type=predicate." + predPos.charAt(0));
        fs.add("lemma=" + lemma.getTaggedTokenList().get(left.head).getTag());
        if (!predPos.startsWith("V"))
          fs.add("pos=" + predPos);
        
        for (Dependency d : deps.getDependencyList()) {
          if (d.isSetGov() && d.getGov() == left.head && !IGNORE_DEPREL.contains(d.getEdgeType())) {
            fs.add("dsyn/" + d.getEdgeType() + "=" + t.getTokenList().getTokenList().get(d.getDep()).getText());
          }
        }

        fs.add("arg/" + words(ll));
        fs.add("arg/" + words(rr));
        fs.add("argD/" + left.relationStr() + "/" + words(ll));
        fs.add("argD/" + right.relationStr() + "/" + words(rr));
      }
    }

    public static final Set<String> IGNORE_DEPREL = new HashSet<>(Arrays.asList("punct"));

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
  }

  public static void main(String[] mainArgs) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(mainArgs);

//    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

    boolean debug = false;
    try (AutoCloseableIterator<Communication> iter = IndexCommunications.getCommunicationsForIngest(config)) {
      comms:
      while (iter.hasNext()) {
        Communication c = iter.next();
        for (Tokenization t : new TokenizationIter(c)) {

          if (t.getTokenList().getTokenListSize() > 35)
            break;

          if (debug && !debugSentence(t))
            continue;

          DependencyParse d = IndexCommunications.getPreferredDependencyParse(t);
          TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
          TokenTagging lemma = IndexCommunications.getPreferredLemmas(t);
          int n = pos.getTaggedTokenListSize();
          assert n == t.getTokenList().getTokenListSize();
          List<Integer> entHeads = new ArrayList<>();
          List<PredArg> allPas = new ArrayList<>();
          for (int i = 0; i < n; i++) {
            assert i == pos.getTaggedTokenList().get(i).getTokenIndex();

            // Look for an NNP(S)? whose parent is not an NNP
            if (!rootNNP(i, pos, d))
              continue;
            entHeads.add(i);

            // Extract possible pred/roles
            List<PredArg> pas = extract(i, d, t);
            allPas.addAll(pas);

            for (PredArg pa : pas) {
              pa.predLemma = lemma.getTaggedTokenList().get(pa.pred).getTag();
              pa.argHead = t.getTokenList().getTokenList().get(pa.arg).getText();
            }
          }

          if (entHeads.size() < 2)
            continue;

          System.out.println(show(t));
          for (int ent : entHeads) {
            System.out.println("ent: " + ent + "\t" + t.getTokenList().getTokenList().get(ent).getText());
          }
          System.out.println();

          Idfk idfk = new Idfk(t, d, entHeads);
          for (Entry<Integer, Set<String>> e : idfk.getSituations().entrySet()) {
            List<String> fs = new ArrayList<>(e.getValue());
            Collections.sort(fs);
            for (String f : fs) {
              assert f.indexOf('=') > 0;
              System.out.println(e.getKey() + "\t" + f);
            }
          }
          System.out.println();

          //          for (PredArg pa : allPas) {
          //            System.out.printf("%02d %-24s %-54s %02d %s\n", pa.arg, pa.argHead, pa.depPathStr, pa.pred, pa.predLemma);
          //          }
          //          System.out.println();

          //          // Show events
          //          GroupByPred gbp = new GroupByPred(allPas);
          //          System.out.println("allPas: " + allPas);
          //          while (gbp.hasNext()) {
          //            List<PredArg> args = gbp.next();
          //            System.out.println("args: " + args);
          //            if (args.size() < 2)
          //              continue;
          //            System.out.println("pred: " + args.get(0).predLemma + " at " + args.get(0).pred);
          //            for (PredArg a : args) {
          //              System.out.println("arg: " + a.argHead + "\t" + a.depPathStr);
          //              assert a.pred == args.get(0).pred;
          //            }
          //            System.out.println();
          //          }

          System.out.println();

          if (debug && debugSentence(t))
            break comms;
        }
      }
    }
  }

}
