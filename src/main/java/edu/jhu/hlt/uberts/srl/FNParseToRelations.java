package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse.Node;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.StringLabeledDirectedGraph;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.pruning.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.io.FactWriter;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.HPair;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.IWord;

/**
 * Take {@link FNParse}s and write out the relations used by uberts/srl.
 *
 * NOTE: This currently doesn't record whether a {@link FNParse} is in the
 * train/dev/test split. This has to be managed externally (e.g. a file of ids
 * which belongs to each train/dev/test).
 *
 * @author travis
 */
public class FNParseToRelations extends FactWriter {

  // If true, run DeterministicRolePruning.XUE_PALMER
  // It treats all width-1 spans as possible targets, plus all gold targets,
  // which it marks with a "# gold" comment (if it wasn't already included b/c
  // of the width-1 rule), so you can grep them out if you need to.
  public boolean outputArgPruning = true;

  // def csyn6-stanford id parentId headToken startToken endToken label
  // NOTE: Written out in topological order, root (0,n,S) is first, so you can
  // add nodes as you go and parent (except when its -1 for root) will always
  // exist before the child.
  // TODO Make this more compact when read in (currently doesn't make for good graph walk features)
  public boolean csyn6Mode = true;

  public boolean includeEvent2 = false;

  // If true, replace all <tokenIndex> <tokenIndex> pairs which represent spans
  // with a single <span>
  public boolean spansInsteadOfTokens = true;

  // If true, then only extract possible arguments for known targets.
  public boolean targetsGiven = true;

  public boolean skipEmptySentences = true;

  // Keep track of the quality of our argument span retrieval heuristic
  private Map<DeterministicRolePruning.Mode, FPR> argHeuristic = new HashMap<>();

  private Set<DeterministicRolePruning.Mode> showArgHeuristicFNs = new HashSet<>();

  private void writeSentence(Sentence s) throws IOException {
    IWord iw;
    ISynset is;
    int n = s.size();
    for (int i = 0; i < n; i++) {
      write("x", "word2", i, s.getWord(i));
      write("x", "pos2", i, s.getPos(i));
      write("x", "lemma2", i, s.getLemma(i).toLowerCase());
      if ((iw = s.getWnWord(i)) != null && (is = iw.getSynset()) != null)
        write("x", "wnss2", i, is.getID());
    }
    writeDeps(s.getBasicDeps(false), "basic", n);
    writeDeps(s.getCollapsedDeps2(false), "col");
    writeDeps(s.getCollapsedDeps2(false), "colcc");
    if (csyn6Mode) {
      writeCons2(s.getStanfordParse(false), "stanford", true);
      writeCons2(s.getGoldParse(false), "gold", false);
    } else {
      writeCons(s.getStanfordParse(false), "stanford", true);
      writeCons(s.getGoldParse(false), "gold", false);
    }
  }

  private void writeDeps(StringLabeledDirectedGraph deps, String name) throws IOException {
    if (deps == null) {
      Log.warn("skipping deps since they're not present: " + name);
      return;
    }
    String tn = "dsyn3-" + name;
    LabeledDirectedGraph depsI = deps.get();
    Set<IntTrip> uniq = new HashSet<>();
    int n = depsI.getNumEdges();
    for (int i = 0; i < n; i++) {
      long e = depsI.getEdge(i);
      int g = LabeledDirectedGraph.unpackGov(e);
      if (g >= n) // LabeledDirectedGraph doesn't do negative indices, so root=length(sentence)
        g = -1;   // In output we use -1 as root.
      int d = LabeledDirectedGraph.unpackDep(e);
      int lab = LabeledDirectedGraph.unpackEdge(e);
      if (uniq.add(new IntTrip(g, d, lab))) {
        String l = deps.getEdge(lab);
        write("x ", tn, g, d, l);
      }
    }
  }

  private void writeDeps(DependencyParse deps, String name, int n) throws IOException {
    if (deps == null) {
      Log.warn("skipping deps since they're not present: " + name);
      return;
    }
    String tn = "dsyn3-" + name;
    for (int d = 0; d < n; d++) {
      int g = deps.getHead(d);
      String l = deps.getLabel(d);
      write("x", tn, g, d, l);
    }
  }

  private void writeCons(ConstituencyParse cons, String name, boolean skipLeaf) throws IOException {
    if (cons == null) {
      Log.warn("skipping cons since they're not present: " + name);
      return;
    }
    String tn;
    if (spansInsteadOfTokens)
      tn = "csyn2-" + name;
    else
      tn = "csyn3-" + name;
    Set<HPair<Span, String>> uniq = new HashSet<>();
    for (Node n : cons.getAllConstituents()) {
      if (n.isLeaf() && skipLeaf) {
//        Log.info("skipping what should be LEXICAL leaf cons: " + n.getTag());
        continue;
      }
      if (!n.hasSpan()) {
        Log.warn("for some reason " + n.getTag() + " in " + cons.getSentenceId() + " doesn't have a span!");
        continue;
      }
      Span s = n.getSpan();
      String t = n.getTag();
      if (uniq.add(new HPair<>(s, t))) {
        // I've seen this in gold parses for some reason, e.g.
        // grep -n 'x csyn3-gold 12 16 NP' -m 3 <file>
        // 5035:x csyn3-gold 12 16 NP
        // 5036:x csyn3-gold 12 16 NP
        if (spansInsteadOfTokens)
          write("x", tn, s.shortString(), t);
        else
          write("x", tn, s.start, s.end, t);
      }
    }
  }

  private void writeCons2(ConstituencyParse cons, String name, boolean skipLeaf) throws IOException {
    if (cons == null) {
      Log.warn("skipping cons since they're not present: " + name);
      return;
    }
    String tn;
    if (spansInsteadOfTokens)
      tn = "csyn5-" + name;
    else
      tn = "csyn6-" + name;
    boolean toposort = true;
    List<Node> nodes = cons.getAllConstituents(toposort);

    // Set the node indices
    int id = 0;
    for (int i = 0; i < nodes.size(); i++) {
      Node n = nodes.get(i);
      n.id = -1;
      if (n.isLeaf() && skipLeaf) {
//        Log.info("skipping what should be LEXICAL leaf cons: " + n.getTag());
        continue;
      }
      if (!n.hasSpan()) {
        Log.warn("for some reason " + n.getTag() + " in " + cons.getSentenceId() + " doesn't have a span!");
        continue;
      }
      n.id = id++;
    }

    Set<HashableIntArray> uniq = new HashSet<>();
    for (Node n : nodes) {
      if (n.id < 0)
        continue;
      Span s = n.getSpan();
      int h = n.getHeadToken();
      int p = n.getParent() == null ? -1 : n.getParent().id;
      String t = n.getTag();
      if (uniq.add(new HashableIntArray(n.id, p, h, s.start, s.end, t.hashCode()))) {
        if (spansInsteadOfTokens)
          write("x", tn, n.id, p, h, s.shortString(), t);
        else
          write("x", tn, n.id, p, h, s.start, s.end, t);
      }
    }
  }

  public void definitions() throws IOException {
    write("def", "startDoc", "<docid>");
    write("def", "doneAnno", "<docid>");
    write("def", "word2", "<tokenIndex>", "<word>");
    write("def", "pos2", "<tokenIndex>", "<pos>");
    write("def", "lemma2", "<tokenIndex>", "<lemma>");
    write("def", "wnss2", "<tokenIndex>", "<synset>");
    for (String t : Arrays.asList("basic", "col", "colcc")) {
      write("def", "dsyn3-" + t, "<tokenIndex>", "<tokenIndex>", "<edgeLabel>", "# gov token, dep token, edge label");
    }
    for (String t : Arrays.asList("stanford", "gold")) {
      if (csyn6Mode) {
        if (spansInsteadOfTokens)
          write("def", "csyn5-" + t, " <csyn5id>", "<csyn5id>", "<tokenIndex>", "<span>", "<phrase>", "# id, parent id, head token, span (inclusive-exclusive), phrase label");
        else
          write("def", "csyn6-" + t, " <csyn6id>", "<csyn6id>", "<tokenIndex>", "<tokenIndex>", "<tokenIndex>", "<phrase>", "# id, parent id, head token, start token (inclusive), end token (exclusive), phrase label");
      } else {
        if (spansInsteadOfTokens)
          write("def", "csyn2-" + t, " <span>", "<phrase>", "# span (inclusive-exclusive), phrase label");
        else
          write("def", "csyn3-" + t, "<tokenIndex>", "<tokenIndex>", "<phrase>", "# start token (inclusive), end token (exclusive), phrase label");
      }
    }
    if (spansInsteadOfTokens) {
      write("def", "predicate2", "<span>", "<frame>");
      write("def", "argument4", "<span>", "<frame>", "<span>", "<role>");
    } else {
      write("def", "srl4", "<tokenIndex>", "<tokenIndex>", "<frame>", "<tokenIndex>", "<tokenIndex>", "<role>", "# pred start tok (inc), pred end tok (exc), frame, arg start tok (inc), arg end tok (exc), role");
    }
    if (outputArgPruning) {
      if (spansInsteadOfTokens) {
        write("def", "xue-palmer-args2", "<span>", "<span>");
        write("def", "xue-palmer-deps-args2", "<span>", "<span>");
      } else {
        write("def", "xue-palmer-args4", "<tokenIndex>", "<tokenIndex>", "<tokenIndex>", "<tokenIndex>", "# ts, te, ss, se");
        write("def", "xue-palmer-deps-args4", "<tokenIndex>", "<tokenIndex>", "<tokenIndex>", "<tokenIndex>", "# ts, te, ss, se");
      }
    }
  }

  private void writeArgTriage(String name, DeterministicRolePruning.Mode mode, FNParse y) throws IOException {
    Sentence sent = y.getSentence();
    Frame f = new Frame(0, "propbank/dummyframe", null, new String[] {"ARG0", "ARG1"});

    List<FrameInstance> frameMentions = new ArrayList<>();
    Set<Span> uniq = new HashSet<>();
    if (!targetsGiven) {
      for (int i = 0; i < sent.size(); i++) {
        Span t = Span.getSpan(i, i+1);
        frameMentions.add(FrameInstance.frameMention(f, t, sent));
        uniq.add(t);
      }
    }
    Set<Span> goldTargets = new HashSet<>();
    for (FrameInstance fi : y.getFrameInstances()) {
      Span t = fi.getTarget();
      if (goldTargets.add(t) && uniq.add(t))
        frameMentions.add(FrameInstance.frameMention(f, t, sent));
    }
    FNTagging argsFor = new FNTagging(sent, frameMentions);

    DeterministicRolePruning drp = new DeterministicRolePruning(mode, null, null);
    FNParseSpanPruning args = drp.setupInference(Arrays.asList(argsFor), null).decodeAll().get(0);

    Set<Span> predArgs = new HashSet<>();
    for (SpanPair ts : args.getAllArgs()) {
      Span t = ts.get1();
      Span s = ts.get2();
      if (t == Span.nullSpan || s == Span.nullSpan)
        continue;
      predArgs.add(s);
      if (spansInsteadOfTokens)
        write("x", name, t.shortString(), s.shortString());
      else
        write("x", name, t.start, t.end, s.start, s.end);
//      if (goldTargets.contains(t))
//        w.write(" # gold");
    }

    Set<Span> goldArgs = new HashSet<>();
    for (FrameInstance fi : y.getFrameInstances())
      fi.getRealizedArgs(goldArgs);

    if (showArgHeuristicFNs.contains(mode)) {
      for (FrameInstance fi : y.getFrameInstances()) {
        for (Pair<String, Span> p : fi.getRealizedRoleArgs()) {
          if (!predArgs.contains(p.get2())) {
            int context = 3;
            System.out.println("missed: " + p.get1());
            System.out.println("target: " + Describe.spanWithPos(fi.getTarget(), sent, context));
            System.out.println("arg:    " + Describe.spanWithPos(p.get2(), sent, context));
            System.out.println(Describe.sentenceWithDeps(sent));
            System.out.println();
          }
        }
      }
    }

    FPR fpr = FPR.fromSets(goldArgs, predArgs);
    FPR accum = argHeuristic.get(mode);
    if (accum == null) {
      accum = new FPR(false);
      argHeuristic.put(mode, accum);
    }
    accum.accum(fpr);
  }

  public void writeFNParse(FNParse y) throws IOException {
    // x:
    // word(i,t) pos2(i,t), lemma(i,t)
    // dsyn3-basic(g,d,l), dsyn3-col(g,d,l), dsyn3-colcc(g,d,l)
    // csyn3-stanford(i,j,l)
    writeSentence(y.getSentence());

    // arg-pruning
    if (outputArgPruning) {
      try {
        writeArgTriage("xue-palmer-args2", Mode.XUE_PALMER_HERMANN, y);
        writeArgTriage("xue-palmer-deps-args2", Mode.XUE_PALMER_DEP_HERMANN, y);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    // y:
    // event1(t), event2(t,f), srl1(s), srl2(t,s), srl3(t,s,k)
    for (FrameInstance fi : y.getFrameInstances()) {
      Span t = fi.getTarget();
      Frame f = fi.getFrame();
      String fs = f.getName();
      writePredicate(t, fs);
      String fsn = SrlSchemaToRelations.norm(fs);
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span s = fi.getArgument(k);
        if (s == Span.nullSpan)
          continue;
        String ks = f.getRole(k);
        writeArgument(t, fsn, s, ks);
        for (Span cs : fi.getContinuationRoleSpans(k))
          writeArgument(t, fsn, cs, ks + "/C");
        for (Span rs : fi.getReferenceRoleSpans(k))
          writeArgument(t, fsn, rs, ks + "/R");
      }
    }
  }

  private void writePredicate(Span t, String f) throws IOException {
    if (spansInsteadOfTokens) {
      write("y", "predicate2", t.shortString(), f);
    } else {
      write("y", "event2", t.shortString(), f);
    }
  }

  private void writeArgument(Span t, String f, Span s, String k) throws IOException {
    if (spansInsteadOfTokens)
      write("y", "argument4", t.shortString(), f, s.shortString(), k);
    else
      write("y", "srl4", t.start, t.end, f, s.start, s.end, k);
  }

  /**
   * Write all of FN or PB to a single file which can be read by {@link ManyDocRelationFileIterator}.
   */
  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    String dataset = config.getString("dataset");
    boolean addParses = config.getBoolean("addParses", true);

    File outputVals = config.getFile("outputVals");
    Log.info("writing values to " + outputVals.getPath());

    try (FNParseToRelations fn2r = new FNParseToRelations()) {
      fn2r.showArgHeuristicFNs.add(DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN);

      Log.info("writing definitions...");
      fn2r.writeToFile(config.getFile("outputDefs"));
      fn2r.definitions();
      fn2r.close();

      Iterable<FNParse> l = FeaturePrecomputation.getData(dataset, addParses);

      Log.info("writing instances...");
      TimeMarker tm = new TimeMarker();
      int docs = 0, empty = 0;
      fn2r.writeToFile(outputVals);
      int done = 0;
      for (FNParse y : l) {
        if (fn2r.skipEmptySentences && y.numFrameInstances() == 0) {
          empty++;
          continue;
        }
        Sentence s = y.getSentence();
        s.lemmatize();
        Log.info("sentenceLength=" + s.size());
        docs++;
        fn2r.write("startdoc", y.getId(), "# " + dataset);
        fn2r.writeFNParse(y);
        done++;
        if (tm.enoughTimePassed(15)) {
          Log.info("wrote out " + done + " parses (" + empty + " empty ones skipped) in "
              + tm.secondsSinceFirstMark()
              + " seconds, argRetrievalHeuristic: " + fn2r.argHeuristic);
        }
      }
      fn2r.close();
      Log.info("done, wrote " + docs + " docs, skipped " + empty + " empty");
    }
  }
}
