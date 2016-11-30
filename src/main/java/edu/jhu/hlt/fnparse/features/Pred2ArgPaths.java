package edu.jhu.hlt.fnparse.features;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path2.Entry;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.inference.heads.DependencyHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.prim.tuple.Pair;

/**
 * Implementation of the context locations described in section 3.1 of:
 * http://www.dipanjandas.com/files/acl2014frames.pdf
 *
 * Briefly: we look for all dependency paths between a predicate and an argument
 * head in the training data.
 *
 * @author travis
 */
public class Pred2ArgPaths {

  static class LL<T> {
    public final T cur;
    public final LL<T> next;
    public LL(T cur, LL<T> next) {
      this.cur = cur;
      this.next = next;
    }
    public LL(List<T> l) {
      this(l, 0);
    }
    public LL(List<T> l, int i) {
      this.cur = l.get(i);
      if (i+1 < l.size())
        this.next = new LL<>(l, i+1);
      else
        this.next = null;
    }
  }

  static class Trie<T> {
    public final T cur;
    private int nObjs;
    private Map<T, Trie<T>> children;

    public Trie(T cur) {
      this.cur = cur;
      this.children = new HashMap<>();
    }

    public void writeToDisk(File f) throws IOException {
      try (BufferedWriter w = FileUtil.getWriter(f)) {
        visit((count, edges) -> {
          try {
            w.write(count + "\t");
            w.write(StringUtils.join("\t", edges));
            w.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
    }

    public void readFromDisk(File countAndPathTsv, Function<String, T> deserialize) throws IOException {
      Log.info("[main] reading trie counts from " + countAndPathTsv.getPath());
      try (BufferedReader r = FileUtil.getReader(countAndPathTsv)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] tok = line.split("\t");
          int c = Integer.parseInt(tok[0]);
          List<T> path = new ArrayList<>(tok.length-1);
          for (int i = 1; i < tok.length; i++)
            path.add(deserialize.apply(tok[i]));
          add(path);
          getNode(path).nObjs = c;
        }
      }
    }

    public int sumOfNObjOfChildren() {
      int c = 0;
      for (Trie<T> t : children.values())
        c += t.nObjs;
      return c;
    }

    public void add(List<T> path) {
      if (!path.isEmpty())
        add(new LL<>(path));
    }
    public void add(LL<T> path) {
      Trie<T> c = children.get(path.cur);
      if (c == null) {
        c = new Trie<T>(path.cur);
        children.put(path.cur, c);
      }
      if (path.next == null)
        c.nObjs++;
      else
        c.add(path.next);
    }

    public void visit(BiConsumer<Integer, List<T>> f) {
      List<T> context = new ArrayList<>();
      if (cur != null)
        context.add(cur);
      for (Trie<T> t : children.values())
        t.visit(f, context);
    }
    private void visit(BiConsumer<Integer, List<T>> f, List<T> context) {
      context.add(cur);
      if (nObjs > 0)
        f.accept(nObjs, context);
      for (Trie<T> t : children.values())
        t.visit(f, context);
      context.remove(context.size() - 1);
    }

    public int getCount(List<T> path) {
      return getCount(new LL<>(path));
    }
    public int getCount(LL<T> path) {
      if (path == null)
        return nObjs;
      Trie<T> c = children.get(path.cur);
      if (c == null)
        return 0;
      return c.getCount(path.next);
    }

    public Trie<T> getNode(List<T> path) {
      Trie<T> cur = this;
      int n = path.size();
      for (int i = 0; i < n; i++) {
        T key = path.get(i);
        cur = cur.children.get(key);
        if (cur == null)
          return null;
      }
      return cur;
    }

    /** returns the old count */
    public int setCount(int c, LL<T> path) {
      Trie<T> child = children.get(path.cur);
      if (child == null) {
        child = new Trie<>(path.cur);
        children.put(path.cur, child);
      }
      if (path.next == null) {
        int old = child.nObjs;
        child.nObjs = c;
        return old;
      } else {
        return child.setCount(c, path.next);
      }
    }
    public int setCount(int c, List<T> path) {
      return setCount(c, new LL<>(path));
    }
  }

  // A set of paths from pred -> arg head
  private Trie<String> pred2argHead;

  // First item is the role, e.g. ARG0, followed by the pred -> arg head path, similar to above
  private Trie<String> role2pred2argHead;

  Trie<String> argHead2argStart2Role;
  Trie<String> argHead2argEnd2Role;

  private DependencyHeadFinder hf;

  public Pred2ArgPaths() {
    pred2argHead = new Trie<>(null);
    role2pred2argHead = new Trie<String>(null);
    argHead2argStart2Role = new Trie<String>(null);
    argHead2argEnd2Role = new Trie<String>(null);
    hf = new DependencyHeadFinder(DependencyHeadFinder.Mode.PARSEY);
  }

  public void add(FNParse y) {
    for (FrameInstance fi : y.getFrameInstances())
      add(fi);
  }
  public void add(FrameInstance fi) {
    Sentence sent = fi.getSentence();
    for (Pair<String, Span> rs : fi.getRealizedRoleArgs()) {
      String role = rs.get1();
      Span s = rs.get2();
      add(fi.getTarget(), s, role, sent);
    }
  }

  public void add(HypEdge argument4Fact, Sentence sent) {
    Span t = Span.inverseShortString((String) argument4Fact.getTail(0).getValue());
    Span s = Span.inverseShortString((String) argument4Fact.getTail(2).getValue());
    String k = (String) argument4Fact.getTail(3).getValue();
    add(t, s, k, sent);
  }

  public void add(Span t, Span s, String k, Sentence sent) {
    DependencyParse deps = hf.getDeps(sent);
    int p = hf.head(t, sent);
    int a = hf.head(s, sent);
    List<String> edges = getPath(p, a, deps, sent);
    pred2argHead.add(edges);
    edges.add(0, k);
    role2pred2argHead.add(edges);

    List<String> as = getPath(a, s.start, deps, sent);
    as.add(k);
    argHead2argStart2Role.add(as);
    List<String> ae = getPath(a, s.end-1, deps, sent);
    ae.add(k);
    argHead2argEnd2Role.add(ae);
  }

  public static List<String> getPath(int predHead, int argHead, DependencyParse deps, Sentence sent) {
    Path2 path = new Path2(predHead, argHead, deps, sent);
    List<String> edges = new ArrayList<>();
    for (Entry e : path.getEntries())
      if (e.isEdge())
        edges.add(e.show(null, EdgeType.DEP));
    return edges;
  }

  /**
   * Writes out a TSV where the count comes before the path.
   * @param includeRole if true, pred->arg path counts will be stratified by role
   */
  private void writePathsWithCounts(File out, boolean includeRole) throws IOException {
    Log.info("writing pred->arg counts to file=" + out.getPath() + " includeRole=" + includeRole);
    try (BufferedWriter w = FileUtil.getWriter(out)) {
      Trie<String> p = includeRole ? role2pred2argHead : pred2argHead;
      p.visit((count, edges) -> {
        try {
          w.write(count + "\t");
          w.write(StringUtils.join("\t", edges));
          w.newLine();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    // PREDICATE TO ARGUMENT HEAD PATHS
    File out = config.getFile("output");
    Log.info("writing pred->arg counts to " + out.getPath());

    File outByRole = config.getFile("output.byRole", null);
    if (outByRole != null)
      Log.info("writing pred->arg counts by role to " + outByRole.getPath());

    // ARGUMENT HEAD TO START/END PATHS
    File a2sFile = config.getFile("output.a2s", new File("/tmp/a2s.txt"));
    File a2eFile = config.getFile("output.a2e", new File("/tmp/a2e.txt"));

    Pred2ArgPaths paths = new Pred2ArgPaths();
    int n = 0;
    int target = 32;

    File mdf = config.getFile("srl.facts", null);
    if (mdf != null) {
      Log.info("reading rel data from " + mdf.getPath());
      Uberts u = new Uberts(new Random(9001));
      u.readRelData(config.getExistingFile("relations.def", new File("data/srl-reldata/propbank/relations.def")));
      Relation a4 = u.getEdgeType("argument4");
      boolean dedup = true;
      try (RelationFileIterator i1 = new RelationFileIterator(mdf, false);
          ManyDocRelationFileIterator i2 = new ManyDocRelationFileIterator(i1, dedup)) {
        while (i2.hasNext()) {
          RelDoc d = i2.next();
          assert d.facts.isEmpty();
          u.readRelData(d);
          Sentence sent = OldFeaturesWrapper.readSentenceFromState(u);
//          edu.jhu.hlt.tutils.LL<HypEdge> a4fs = u.getState().match2(a4);
//          assert a4fs != null : "no argument4 facts?";
//          for (edu.jhu.hlt.tutils.LL<HypEdge> cur = a4fs; cur != null; cur = cur.next)
//            paths.add(cur.item, sent);
          boolean includeNilFacts = false;
          Collection<HashableHypEdge> a4fs = u.getLabels().getGoldEdges(a4, includeNilFacts);
          for (HashableHypEdge he : a4fs)
            paths.add(he.getEdge(), sent);
          u.clearNonSchemaNodes();
          u.clearLabels();
          u.dbgSentenceCache = null;

          // Write out intermediate results
          n++;
          if (n >= target) {
            Log.info("added " + n + " sentences");
            target *= 2;
            paths.writePathsWithCounts(out, false);
            if (outByRole != null)
              paths.writePathsWithCounts(outByRole, true);

            paths.argHead2argStart2Role.writeToDisk(a2sFile);
            paths.argHead2argEnd2Role.writeToDisk(a2eFile);
          }
        }
      }
    } else {
      String dataset = config.getString("dataset");
      Log.info("re-parsing " + dataset + " data");
      boolean addParses = config.getBoolean("addParses", true);
      Iterable<FNParse> data = FeaturePrecomputation.getData(dataset, addParses);
      for (FNParse y : data) {
        paths.add(y);

        // Write out intermediate results
        n++;
        if (n >= target) {
          Log.info("added " + n + " sentences");
          target *= 2;
          paths.writePathsWithCounts(out, false);
          if (outByRole != null)
            paths.writePathsWithCounts(outByRole, true);

          paths.argHead2argStart2Role.writeToDisk(a2sFile);
          paths.argHead2argEnd2Role.writeToDisk(a2eFile);
        }
      }
    }

    // Write out final results
    paths.writePathsWithCounts(out, false);
    if (outByRole != null)
      paths.writePathsWithCounts(outByRole, true);
    paths.argHead2argStart2Role.writeToDisk(a2sFile);
    paths.argHead2argEnd2Role.writeToDisk(a2eFile);

    Log.info("done");
  }

  // In the future, use the Trie methods for IO
  public static Trie<String> getPathTrie(File countAndPathTsv, boolean byRole) throws IOException {
    Trie<String> paths = new Trie<>(null);
    try (BufferedReader r = FileUtil.getReader(countAndPathTsv)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] tok = line.split("\t");
        int c = Integer.parseInt(tok[0]);
        List<String> e = new ArrayList<>(tok.length - 1);
        if (byRole) {
          String role = tok[1];
          for (int i = 2; i < tok.length; i++)
            e.add(Path2.Edge.fromString(tok[i]).toString());
          e.add(role);
        } else {
          for (int i = 1; i < tok.length; i++)
            e.add(Path2.Edge.fromString(tok[i]).toString());
        }
        int oldCount = paths.setCount(c, e);
        assert oldCount == 0 : "not uniq? path=" + e + " c=" + c + " oldCount=" + oldCount;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return paths;
  }

  /**
   * Given a predicate head token p, return all role, spans pairs (k,a = (s,e,h))
   * s.t.
   *     count(k, path(p,h)) >= k1
   * and count(k, path(h,s)) >= k2
   * and count(k, path(h,e)) >= k3
   */
  public static class DepDecompArgCandidiates {
    private Trie<String> pred2head;
    private Trie<String> head2start;
    private Trie<String> head2end;
    public int k1 = 1;
    public int k2 = 1;
    public int k3 = 1;
    public int c = 1;

    public DepDecompArgCandidiates(File p2h, File h2s, File h2e) {
      pred2head = new Trie<String>(null);
      head2start = new Trie<String>(null);
      head2end = new Trie<String>(null);
      try {
        pred2head.readFromDisk(p2h, x -> x);
        head2start.readFromDisk(h2s, x -> x);
        head2end.readFromDisk(h2e, x -> x);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public List<Pair<Span, String>> getArgCandidates2(int predicate, Sentence sent) {
      DependencyParse deps = sent.getParseyDeps();
      int n = sent.size();
      List<Pair<Span, String>> args = new ArrayList<>();
      assert k1 > 0;
      assert k2 > 0;
      assert k3 > 0;

      for (int aHead = 0; aHead < n; aHead++) {
        List<String> ph = getPath(predicate, aHead, deps, sent);
        Trie<String> x1 = pred2head.getNode(ph);
        int c1 = x1 == null ? 0 : x1.sumOfNObjOfChildren();
        if (c1 < k1)
          continue;

        for (int start = 0; start <= aHead; start++) {
          List<String> hs = getPath(aHead, start, deps, sent);
          Trie<String> x2 = head2start.getNode(hs);
          int c2 = x2 == null ? 0 : x2.sumOfNObjOfChildren();
          if (c2 < k2)
            continue;
          for (int end = aHead; end < n; end++) {
            List<String> he = getPath(aHead, end, deps, sent);
            Trie<String> x3 = head2end.getNode(he);
            int c3 = x3 == null ? 0 : x3.sumOfNObjOfChildren();
            if (c3 < k3)
              continue;

            // A role may have appeared with any 2/3 of the three paths
            Counts<String> roles = new Counts<>();
            for (Trie<String> xx : Arrays.asList(x1, x2, x3))  {
              for (Trie<String> t : xx.children.values()) {
                if (t.nObjs > 0)
                  roles.increment(t.cur);
              }
            }
            Span s = Span.getSpan(start, end+1);
            for (String k : roles.countIsAtLeast(c))
              args.add(new Pair<>(s, k));
          }
        }
      }
      return args;
    }

  }

  /**
   * Implements the argument candidate selection algorithm based on dependency
   * trees described in section 6.3 of
   *   Efficient Inference and Structured Learning for Semantic Role Labeling
   *   Tackstrom, Ganchev, and Das (2015)
   *   https://static.googleusercontent.com/media/research.google.com/en//pubs/archive/43251.pdf
   *
   * They claim to get R=88.2 and P=38.2 on Ontonotes 5 (Propbank, CoNLL 2012).
   * They DO NOT use this method for Framenet, instead using the dependency-based
   * method of Hermann et al. (2014), achieving R=72.6 and P=25.1.
   */
  public static class ArgCandidates {
    public static boolean DEBUG = false;
    public static boolean EXTRA_SPANS = true;

    public static Counts<String> EVENTS = null; // = new Counts<>();
    public static TimeMarker tm = null; //new TimeMarker();
    
    private boolean noParsey;

    private Trie<String> pathThenRole;
    private DependencyHeadFinder hf;

    public ArgCandidates(File f) throws IOException {
      Log.info(f.getPath());
      
      ExperimentProperties config = ExperimentProperties.getInstance();
      noParsey = config.getBoolean("tackstromArgsNoParsey");
      Log.info("noParsey=" + noParsey);

      pathThenRole = getPathTrie(f, true);
      if (noParsey)
        hf = new DependencyHeadFinder(DependencyHeadFinder.Mode.BASIC);
      else
        hf = new DependencyHeadFinder(DependencyHeadFinder.Mode.PARSEY);
    }

    public List<Pair<Span, String>> getArgCandidates2(int predicate, Sentence sent) {
      List<Span> spans = getArgCandidates(predicate, sent, noParsey);
      List<Pair<Span, String>> spansWithRoles = new ArrayList<>();
      DependencyParse deps = hf.getDeps(sent);
      for (Span s : spans) {
        int shead = hf.head(s, sent);
        List<String> path = getPath(predicate, shead, deps, sent);
        Trie<String> node = pathThenRole.getNode(path);
        if (node == null)
          continue;
        for (java.util.Map.Entry<String, Trie<String>> x : node.children.entrySet()) {
          boolean terminal = x.getValue().nObjs > 0;
          if (!terminal)
            continue;
          String role = x.getKey();
//          int count = x.getValue().nObjs; // TODO consider count restriction
          spansWithRoles.add(new Pair<>(s, role));
        }
      }
      return spansWithRoles;
    }

    /*
      In our candidate argument extraction algorithm,
      first, we select all the children subtrees of a given
      predicate as potential arguments; if a child word
      is connected via the conj (conjunction) or the prep
      (preposition) label, we also select the corresponding
      grand-children subtrees. Next, we climb up to the
      predicate’s syntactic parent and add any partial subtrees
      headed by it that could serve as constituents in
      the corresponding phrase-structure tree. To capture
      such constructions, we select partial subtrees for a
      head word by first adding the head word, then adding
      contiguous child subtrees from the head word’s rightmost
      left child towards the leftmost left child until we
      either reach the predicate word or an offensive dependency
      label.11 This procedure is then symmetrically
      applied to the head word’s right children. Once a partial
      subtree has been added, we add the parent word’s
      children subtrees — and potentially grand-children
      subtrees in case of children labeled as conj or prep —
      to the candidate list, akin to the first step. We apply
      this parent operation recursively for all the ancestors
      of the predicate. Finally, we consider the predicate’s
      syntactic parent word as a candidate argument if the
      predicate is connected to it via the amod label.
     *
      The candidates are further filtered to only keep
      those where the role of the argument, conjoined with
      the path from its head to the predicate, has been observed
      in the training data. This algorithm obtains an
      unlabeled argument recall of 88.2% on the OntoNotes
      5.0 development data, with a precision of 38.2%.
     */

    /**
     * Returns a uniq set of argument candidates for a predicate headed at the
     * given position, not including Span.nullSpan.
     */
    public static List<Span> getArgCandidates(int predicate, Sentence sent, boolean noParsey) {
      if (EVENTS != null)
        EVENTS.increment("calls");
      DependencyParse d = noParsey ? sent.getBasicDeps() : sent.getParseyDeps();
      assert d != null;
      if (DEBUG) {
        System.out.println(Describe.spanWithPos(Span.widthOne(predicate), sent, 3));
        System.out.println(Describe.sentenceWithDeps(sent, d));
        System.currentTimeMillis();
      }
      // Note: the bit about checking (role, pred->arg) can be enforced after
      // the fact by some other hard LocalFactor for argument4
      List<Span> args = new ArrayList<>();
      for (int p = predicate; p >= 0; p = d.getHead(p)) {

        int[] ci = d.getChildren(p);
        Arrays.sort(ci);

        if (DEBUG) {
          System.out.println();
          System.out.printf("p=%-16s @ %-2s proj=%s\n", sent.getWord(p), p, d.getProj(p).shortString());
          for (int i = 0; i < ci.length; i++) {
            System.out.printf("child.%-8s%-12s @ %-2s proj=%s\n",
                d.getLabel(ci[i]), sent.getWord(ci[i]), ci[i], d.getProj(ci[i]).shortString());
          }
        }

        for (int i = 0; i < ci.length; i++) {
          String l = d.getLabel(ci[i]);
          if ("conj".equals(l) || "prep".equals(l)) {
            // Add grand-children
            int[] gci = d.getChildren(ci[i]);
            for (int j = 0; j < gci.length; j++)
              addNode(gci[j], sent, d, args);
          }
        }

        // Add children
        for (int i = 0; i < ci.length; i++)
          addNode(ci[i], sent, d, args);

        if (EXTRA_SPANS) {
          // Their algorithm is very poorly explained, and as implemented doesn't
          // seem to work. I think its good enough if we get arguments of the parent.
          if (p == predicate) {
            // Partial sub-trees are only relevant to the predicate's parent and higher
            continue;
          }

          // Walk from the nearest left child leftwards
          int nearestLC = -1;
          for (int i = 1; i < ci.length; i++) {
            if (ci[i] >= p)
              break;
            if (nearestLC < 0 || ci[i] > ci[nearestLC])
              nearestLC = i;
          }
          for (int i = nearestLC; i >= 0; i--) {
            if (ci[i] == predicate || offensive(ci[i], d, sent))
              break;
            int left = d.getProjLeft(ci[i]);
            assert left <= ci[i];
            assert left < p;
            args.add(Span.getSpan(left, p+1));
          }

          // Walk from the nearest right child rightwards
          int nearestRC = ci.length;
          for (int i = 0; i < ci.length; i++) {
            if (ci[i] > p) {
              nearestRC = i;
              break;
            }
          }
          for (int i = nearestRC; i < ci.length; i++) {
            if (ci[i] == predicate || offensive(ci[i], d, sent))
              break;
            int right = d.getProjRight(ci[i]);
            args.add(Span.getSpan(p, right+1));
          }
        }

      }

      // Remove any duplicates
      Set<Span> uniq = new HashSet<>(args);
      args.clear();
      args.addAll(uniq);

      if (DEBUG) {
        System.out.println("[Pred2ArgPaths.ArgCandidates] nArgs=" + args.size()
            + " nTok=" + sent.size() + " EXTRA_SPANS=" + EXTRA_SPANS + " sent.id=" + sent.getId());
      }
      if (EVENTS != null) {
        EVENTS.update("args", args.size());
        if (tm.enoughTimePassed(15))
          Log.info("EXTRA_SPANS=" + EXTRA_SPANS + "\t" + EVENTS.toString());
      }

      return args;
    }

    private static Set<String> notOffensiveDeprels;
    static {
      String s = "advmod, amod, appos, aux, auxpass, cc, conj, dep, det, mwe, neg, nn,"
          + "npadvmod, num, number, poss, preconj, predet, prep, prt, ps, quantmod, tmod";
      notOffensiveDeprels = new HashSet<>();
      for (String d : s.split(","))
        notOffensiveDeprels.add(d.trim());
    }
    private static boolean offensive(int i, DependencyParse d, Sentence s) {
      String deprel = d.getLabel(i);
      return !notOffensiveDeprels.contains(deprel.toLowerCase());
    }

    private static void addNode(int i, Sentence sent, DependencyParse d, List<Span> args) {
      if (!sent.isPunc(i)) {
        Span s = d.getProj(i);
        if (DEBUG)
          System.out.println("adding arg: " + s.shortString());
        args.add(s);
      }
    }

  }

  /**
   * A {@link Template} which implements the feature described in the paper.
   * This is a drop-in replacement for LogLinearWords, which did almost as well
   * as their method and doesn't require any Wsabie training.
   *
   * It assumes a predicate head token is given, and it takes the intersection
   * of observed pred->arg paths in training data with the given dependency
   * parse. For each path, the word at the end of the path is conjoined with the
   * path as a binary feature.
   */
  static class PredDisambFeature implements Template {

    private Trie<Path2.Edge> paths;
    private boolean includeCountsRefinements = false;
    private boolean includePred2Pred = true;

    /*
     * If true, then output (slower):
     *   1*f(x) ++ y*f(x)
     * instead of (faster):
     *   y*f(x)
     *
     * This is useful when there is a null class (e.g. a target may not have a
     * frame, but a token index always has a POS tag) and the decision rule for
     * the null frame is whether the best score is <=0.
     *
     * If there is no null class, then this effectively shrinks all the weights
     * towards each other (assuming some L_p regularization on the weights).
     */
    private boolean includeNullClassFeatures = true;

    private boolean quadratic = false;

    public PredDisambFeature(ExperimentProperties config, boolean includeCountRefinements, boolean nullClassFeatures) {
      this(config.getExistingFile("pred2arg.feat.paths"), includeCountRefinements, nullClassFeatures);
    }

    public PredDisambFeature(File countAndPathTsv, boolean includeCountRefinements, boolean nullClassFeatures) {
      Log.info("countAndPathTsv=" + countAndPathTsv.getPath()
          + " includeCountRefinements=" + includeCountRefinements
          + " nullClassFeatures=" + nullClassFeatures
          + " quadratic=" + quadratic);
      this.includeNullClassFeatures = nullClassFeatures;
      paths = new Trie<Path2.Edge>(null);
      try (BufferedReader r = FileUtil.getReader(countAndPathTsv)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] tok = line.split("\t");
          int c = Integer.parseInt(tok[0]);
          List<Path2.Edge> e = new ArrayList<>();
          for (int i = 1; i < tok.length; i++)
            e.add(Path2.Edge.fromString(tok[i]));
          int oldCount = paths.setCount(c, e);
          assert oldCount == 0 : "not uniq? path=" + e + " c=" + c + " oldCount=" + oldCount;
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      this.includeCountsRefinements = includeCountRefinements;
    }

    /**
     * TODO Currently I'm going over every pred-arg pair of tokens, and checking
     * if it is in the trie. More efficient would to be start from the pred and
     * perform the intersection on the fly.
     */
    private void slow(List<String> addTo, int t, int a, DependencyParse deps, Sentence sent) {
      if (sent.isPunc(a))
        return;
      Path2 path = new Path2(t, a, deps, sent);
      if (!path.connected())
        return;

//      if (sent.getPos(t).startsWith("V")) {
//        System.out.println("sent: " + Arrays.toString(sent.getWords()));
//        System.out.println("pred: " + sent.getWord(t));
//        System.out.println("arg:  " + sent.getWord(a));
//        System.out.println("check this!");
//      }

      List<Path2.Edge> edges = new ArrayList<>();
      for (Entry e : path.getEntries()) {
        boolean x = e instanceof Path2.Edge;
        boolean y = e.isEdge();
        assert x == y;
        if (x)
          edges.add((Path2.Edge) e);
      }
      int c;
      if (edges.isEmpty()) {
        c = includePred2Pred ? 1 : 0;
      } else {
        c = paths.getCount(edges);
      }

      // Build a string for the List<Edge>
      String edgeString1, edgeString2 = null, edgeString3 = null;
      if (c == 0) {
        return;
      } else {
        if (edges.isEmpty()) {
          edgeString1 = "emptyPath";
        } else {
          StringBuilder sb = new StringBuilder();
          for (Path2.Edge e : edges)
            sb.append(e.show(null, EdgeType.DEP));
          edgeString1 = sb.toString();
        }
        if (includeCountsRefinements) {
          edgeString2 = c >= 64 ? "c64/" + edgeString1 : "c64/no";
          edgeString3 = c >= 512 ? "c512/" + edgeString1 : "c512/no";
        }
      }

      addTo.add(sent.getWord(a));
      addTo.add(edgeString1 + "/" + sent.getWord(a));
      if (includeCountsRefinements) {
        if (edgeString2 != null)
          addTo.add(edgeString2 + "/" + sent.getWord(a));
        if (edgeString3 != null)
          addTo.add(edgeString3 + "/" + sent.getWord(a));
      }
    }

    private void directDependents(List<String> addTo, int t, DependencyParse deps, Sentence sent) {
      int[] ci = deps.getChildren(t);
      for (int i = 0; i < ci.length; i++) {
        String deprel = deps.getLabel(ci[i]);
        addTo.add(deprel);
        addTo.add(deprel + "/" + sent.getWord(ci[i]));
      }
      String deprel = deps.getLabel(t);
      int p = deps.getHead(t);
      addTo.add("p/" + deprel);
      if (p >= 0) {
        addTo.add("p/" + deprel + "/" + sent.getWord(p));
        String gp = deps.getLabel(p);
        addTo.add("p/" + deprel + "/" + gp);
        int gpi = deps.getHead(p);
        addTo.add("p/" + deprel + "/" + gp + "/" + (gpi < 0 ? "ROOT" : sent.getWord(gpi)));
      } else {
        addTo.add("p/" + deprel + "/ROOT");
      }
    }

    @Override
    public Iterable<String> extract(TemplateContext context) {
      int t = context.getTargetHead();
      Sentence sent = context.getSentence();
      DependencyParse deps = sent.getBasicDeps();
      if (t < 0 || deps == null)
        return null;

      List<String> feats = new ArrayList<>();
      if (sent.isPunc(t)) {
        feats.add("predIsPunc");
      } else {
        int n = sent.size();
        for (int i = 0; i < n; i++)
          slow(feats, t, i, deps, sent);
        directDependents(feats, t, deps, sent);
      }

      // Frame refinements
      String f = context.getFrameStr();
      if (f != null) {
        int nn = feats.size();
        if (includeNullClassFeatures) {
          List<String> f2 = new ArrayList<>(nn*2);
          for (int i = 0; i < nn; i++) {
            f2.add(feats.get(i));
            f2.add(feats.get(i) + "/" + f);
          }
          feats = f2;
        } else {
          for (int i = 0; i < nn; i++)
            feats.set(i, feats.get(i) + "/" + f);
        }
      }

      if (quadratic) {
        Collections.sort(feats);
        int n = feats.size();
        for (int i = 0; i < n-1; i++)
          for (int j = i+1; j < n; j++)
            feats.add("q/" + feats.get(i) + "/" + feats.get(j));
      }
      return feats;
    }
  }
}
