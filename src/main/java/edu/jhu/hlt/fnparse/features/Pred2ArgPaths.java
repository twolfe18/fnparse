package edu.jhu.hlt.fnparse.features;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path2.Entry;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.inference.heads.DependencyHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
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

  public Pred2ArgPaths() {
    pred2argHead = new Trie<>(null);
    role2pred2argHead = new Trie<String>(null);
  }

  public void add(FNParse y, HeadFinder hf) {
    for (FrameInstance fi : y.getFrameInstances())
      add(fi, hf);
  }
  public void add(FrameInstance fi, HeadFinder hf) {
    Sentence sent = fi.getSentence();
    DependencyParse deps = sent.getBasicDeps();
    int p = hf.head(fi.getTarget(), sent);
    for (Pair<String, Span> rs : fi.getRealizedRoleArgs()) {
      String role = rs.get1();
      Span s = rs.get2();
      int a = hf.head(s, sent);

//      System.out.println("[p2a] sent: " + sent.getId());
//      System.out.println("[p2a] pred: " + sent.getWord(p));
//      System.out.println("[p2a] arg:  " + Describe.span(s, sent));
//      if (a < 0) {
//        System.out.println("[p2a] couldn't compute head of arg!");
//        continue;
//      }
//      System.out.println("[p2a] head: " + sent.getWord(a));

      Path2 path = new Path2(p, a, deps, sent);
      List<String> edges = new ArrayList<>();
      for (Entry e : path.getEntries())
        if (e.isEdge())
          edges.add(e.show(null, EdgeType.DEP));
      System.out.println("[p2a] edge: " + edges);
      System.out.println("[p2a] path: " + path.getEntries());
      System.out.println();
      pred2argHead.add(edges);
      edges.add(0, role);
      role2pred2argHead.add(edges);
    }
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
    File out = config.getFile("output");
    Log.info("writing pred->arg counts to " + out.getPath());

    File outByRole = config.getFile("output.byRole", null);
    if (outByRole != null)
      Log.info("writing pred->arg counts by role to " + outByRole.getPath());

    Pred2ArgPaths paths = new Pred2ArgPaths();
    HeadFinder hf = new DependencyHeadFinder();

    String dataset = config.getString("dataset");
    boolean addParses = config.getBoolean("addParses", true);
    int n = 0;
    int target = 32;
    Iterable<FNParse> data = FeaturePrecomputation.getData(dataset, addParses);
    for (FNParse y : data) {
      paths.add(y, hf);
      n++;
      if (n >= target) {
        Log.info("added " + n + " paths");
        target *= 2;
        paths.writePathsWithCounts(out, false);
        if (outByRole != null)
          paths.writePathsWithCounts(outByRole, true);
      }
    }
    paths.writePathsWithCounts(out, false);
    if (outByRole != null)
      paths.writePathsWithCounts(outByRole, true);
    Log.info("done");
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
//    private Map<String, Trie<Path2.Edge>> role2path2arg;
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
    public static List<Span> getArgCandidates(int predicate, Sentence sent) {
      // Note: the bit about checking (role, pred->arg) can be enforced after
      // the fact by some other hard LocalFactor for argument4
      List<Span> args = new ArrayList<>();
      DependencyParse d = sent.getBasicDeps();
      for (int p = predicate; p >= 0; p = d.getHead(p)) {

        int[] ci = d.getChildren(p);
        Arrays.sort(ci);

        for (int i = 0; i < ci.length; i++) {
          String l = d.getLabel(ci[i]);
          if ("conj".equals(l) || "prep".equals(l)) {
            // Add grand-children
            int[] gci = d.getChildren(ci[i]);
            for (int j = 0; j < gci.length; j++)
              addNode(gci[j], d, args);
          }
        }

        // Add children
        for (int i = 0; i < ci.length; i++)
          addNode(ci[i], d, args);

        if (p == predicate) {
          // Partial sub-trees are only relevant to the predicate's parent and higher
          continue;
        }

        // Walk from the nearest left child leftwards
        int nearestLC = 0;
        for (int i = 1; i < ci.length; i++) {
          if (ci[i] >= p)
            break;
          if (ci[i] > ci[nearestLC])
            nearestLC = i;
        }
        for (int i = nearestLC; i >= 0; i--) {
          if (offensive(ci[i], d, sent))
            break;
          args.add(Span.getSpan(ci[i], p));
        }

        // Walk from the nearest right child righwards
        int nearestRC = 0;
        for (int i = 0; i < ci.length; i++) {
          if (ci[i] > p) {
            nearestRC = i;
            break;
          }
        }
        for (int i = nearestRC; i < ci.length; i++) {
          if (offensive(ci[i], d, sent))
            break;
          args.add(Span.getSpan(p+1, ci[i]+1));
        }
      }

      // Remove any duplicates
      Set<Span> uniq = new HashSet<>(args);
      args.clear();
      args.addAll(uniq);

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
      return !notOffensiveDeprels.contains(deprel);
    }

    private static void addNode(int i, DependencyParse d, List<Span> args) {
      int s = d.getProjLeft(i);
      int e = d.getProjRight(i) + 1;
      args.add(Span.getSpan(s, e));
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
  static class Feature implements Template {

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

    public Feature(ExperimentProperties config, boolean includeCountRefinements, boolean nullClassFeatures) {
      this(config.getExistingFile("pred2arg.feat.paths"), includeCountRefinements, nullClassFeatures);
    }

    public Feature(File countAndPathTsv, boolean includeCountRefinements, boolean nullClassFeatures) {
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
      Frame f = context.getFrame();
      if (f != null) {
        int nn = feats.size();
        if (includeNullClassFeatures) {
          List<String> f2 = new ArrayList<>(nn*2);
          for (int i = 0; i < nn; i++) {
            f2.add(feats.get(i));
            f2.add(feats.get(i) + "/" + f.getName());
          }
          feats = f2;
        } else {
          for (int i = 0; i < nn; i++)
            feats.set(i, feats.get(i) + "/" + f.getName());
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
