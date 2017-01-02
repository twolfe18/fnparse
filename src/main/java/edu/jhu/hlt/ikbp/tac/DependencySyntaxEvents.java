package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.util.TokenizationIter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

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

  static class CoverArgumentsWithPredicates {
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

    public CoverArgumentsWithPredicates(Tokenization t, DependencyParse deps, List<Integer> args) {
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

  public static void main(String[] mainArgs) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(mainArgs);
    // redis-server --dir data/sit-search/sit-feat-counts-redis --port 7379
    TimeMarker tm = new TimeMarker();
    long situations = 0, comms = 0, args = 0;
    String host = config.getString("redis.host");
    int port = config.getInt("redis.port");
    try (AutoCloseableIterator<Communication> iter = IndexCommunications.getCommunicationsForIngest(config);
        Jedis j = new Jedis(host, port)) {
      while (iter.hasNext()) {
        Communication c = iter.next();
        comms++;
        Pipeline p = j.pipelined();       // pipeline/batch for each communication
        for (Tokenization t : new TokenizationIter(c)) {
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

          if (entHeads.size() < 2)
            continue;

//          System.out.println(show(t));
//          for (int ent : entHeads) {
//            System.out.println("ent: " + ent + "\t" + t.getTokenList().getTokenList().get(ent).getText());
//          }
//          System.out.println();

          CoverArgumentsWithPredicates idfk = new CoverArgumentsWithPredicates(t, d, entHeads);
          for (Entry<Integer, Set<String>> e : idfk.getSituations().entrySet()) {
            situations++;
            args++;
            List<String> fs = new ArrayList<>(e.getValue());
            Collections.sort(fs);
            for (String f : fs) {
//              System.out.println(e.getKey() + "\t" + f);
              p.incr(f);
            }
          }
//          System.out.println();
        }
        p.sync();
        
        if (tm.enoughTimePassed(10)) {
          Log.info("comms=" + comms + " sits=" + situations
              + " args=" + args + " curDoc=" + c.getId() + "\t" + Describe.memoryUsage());
        }
      }
    }
  }

}
