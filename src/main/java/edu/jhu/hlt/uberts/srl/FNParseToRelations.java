package edu.jhu.hlt.uberts.srl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
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
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.util.Alphabet;
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
public class FNParseToRelations {

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

  public static String norm(String x) {
    x = x.trim();
    x = x.replaceAll("\\s+", "_");
    x = x.replaceAll("#", "-HASH-");
    return x;
  }

  public static String join(String... tokens) {
    StringBuilder sb = new StringBuilder();
    sb.append(norm(tokens[0]));
    for (int i = 1; i < tokens.length; i++) {
      sb.append(' '); // Could replace with tab
      sb.append(norm(tokens[i]));
    }
    return sb.toString();
  }

  public static String join(Object... tokens) {
    StringBuilder sb = new StringBuilder();
    sb.append(norm(tokens[0].toString()));
    for (int i = 1; i < tokens.length; i++) {
      sb.append(' '); // Could replace with tab
      sb.append(norm(tokens[i].toString()));
    }
    return sb.toString();
  }

  public static void write(BufferedWriter w, Object... tokens) throws IOException {
    w.write(join(tokens));
    w.newLine();
  }

  private void write(Sentence s, BufferedWriter w) throws IOException {
    IWord iw;
    ISynset is;
    int n = s.size();
    for (int i = 0; i < n; i++) {
      write(w, "x", "word2", i, s.getWord(i));
      write(w, "x", "pos2", i, s.getPos(i));
      write(w, "x", "lemma2", i, s.getLemma(i).toLowerCase());
      if ((iw = s.getWnWord(i)) != null && (is = iw.getSynset()) != null)
        write(w, "x", "wnss2", i, is.getID());
    }
    write(s.getBasicDeps(false), "basic", n, w);
    write(s.getCollapsedDeps2(false), "col", w);
    write(s.getCollapsedDeps2(false), "colcc", w);
    if (csyn6Mode) {
      write2(s.getStanfordParse(false), "stanford", w, true);
      write2(s.getGoldParse(false), "gold", w, false);
    } else {
      write(s.getStanfordParse(false), "stanford", w, true);
      write(s.getGoldParse(false), "gold", w, false);
    }
  }

  private void write(StringLabeledDirectedGraph deps, String name, BufferedWriter w) throws IOException {
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
        write(w, "x ", tn, g, d, l);
      }
    }
  }

  private void write(DependencyParse deps, String name, int n, BufferedWriter w) throws IOException {
    if (deps == null) {
      Log.warn("skipping deps since they're not present: " + name);
      return;
    }
    String tn = "dsyn3-" + name;
    for (int d = 0; d < n; d++) {
      int g = deps.getHead(d);
      String l = deps.getLabel(d);
      write(w, "x", tn, g, d, l);
    }
  }

  private void write(ConstituencyParse cons, String name, BufferedWriter w, boolean skipLeaf) throws IOException {
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
          write(w, "x", tn, s.shortString(), t);
        else
          write(w, "x", tn, s.start, s.end, t);
      }
    }
  }

  private void write2(ConstituencyParse cons, String name, BufferedWriter w, boolean skipLeaf) throws IOException {
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
          write(w, "x", tn, n.id, p, h, s.shortString(), t);
        else
          write(w, "x", tn, n.id, p, h, s.start, s.end, t);
      }
    }
  }

  public void definitions(File f) throws IOException {
    Log.info("writing definitions to " + f.getPath());
    try (BufferedWriter w = FileUtil.getWriter(f)) {
      definitions(w);
    }
  }

  public void definitions(BufferedWriter w) throws IOException {
    write(w, "def", "word2", "<tokenIndex>", "<word>");
    write(w, "def", "pos2", "<tokenIndex>", "<pos>");
    write(w, "def", "lemma2", "<tokenIndex>", "<lemma>");
    write(w, "def", "wnss2", "<tokenIndex>", "<synset>");
    for (String t : Arrays.asList("basic", "col", "colcc")) {
      w.write("def dsyn3-" + t + " <tokenIndex> <tokenIndex> <edgeLabel> # gov token, dep token, edge label");
      w.newLine();
    }
    for (String t : Arrays.asList("stanford", "gold")) {
      if (csyn6Mode) {
        if (spansInsteadOfTokens)
          w.write("def csyn5-" + t + " <csyn5id> <csyn5id> <tokenIndex> <span> <phrase> # id, parent id, head token, span (inclusive-exclusive), phrase label");
        else
          w.write("def csyn6-" + t + " <csyn6id> <csyn6id> <tokenIndex> <tokenIndex> <tokenIndex> <phrase> # id, parent id, head token, start token (inclusive), end token (exclusive), phrase label");
        w.newLine();
      } else {
        if (spansInsteadOfTokens)
          w.write("def csyn2-" + t + " <span> <phrase> # span (inclusive-exclusive), phrase label");
        else
          w.write("def csyn3-" + t + " <tokenIndex> <tokenIndex> <phrase> # start token (inclusive), end token (exclusive), phrase label");
        w.newLine();
      }
    }
    if (spansInsteadOfTokens) {
      w.write("def predicate2 <span> <frame>");
      w.newLine();
      w.write("def argument4 <span> <frame> <span> <role>");
      w.newLine();
    } else {
      w.write("def srl4 <tokenIndex> <tokenIndex> <frame> <tokenIndex> <tokenIndex> <role> # pred start tok (inc), pred end tok (exc), frame, arg start tok (inc), arg end tok (exc), role");
      w.newLine();
    }
    if (outputArgPruning) {
      if (spansInsteadOfTokens)
        w.write("def xue-palmer-args2 <span> <span>");
      else
        w.write("def xue-palmer-args4 <tokenIndex> <tokenIndex> <tokenIndex> <tokenIndex> # ts, te, ss, se");
      w.newLine();
    }
  }

  private void writeArgs(FNParse y, BufferedWriter w) throws IOException {
    DeterministicRolePruning drp = new DeterministicRolePruning(
        Mode.XUE_PALMER_HERMANN, null, null);
    List<FrameInstance> frameMentions = new ArrayList<>();
    Set<Span> uniq = new HashSet<>();
    Sentence sent = y.getSentence();
    Frame f = new Frame(0, "propbank/dummyframe", null, new String[] {"ARG0", "ARG1"});
    for (int i = 0; i < sent.size(); i++) {
      Span t = Span.getSpan(i, i+1);
      frameMentions.add(FrameInstance.frameMention(f, t, sent));
      uniq.add(t);
    }
    Set<Span> gold = new HashSet<>();
    for (FrameInstance fi : y.getFrameInstances()) {
      Span t = fi.getTarget();
      if (!uniq.contains(t) && gold.add(t))
        frameMentions.add(FrameInstance.frameMention(f, t, sent));
    }
    FNTagging argsFor = new FNTagging(sent, frameMentions);
    FNParseSpanPruning args = drp.setupInference(Arrays.asList(argsFor), null).decodeAll().get(0);
    for (SpanPair ts : args.getAllArgs()) {
      Span t = ts.get1();
      Span s = ts.get2();
      if (t == Span.nullSpan || s == Span.nullSpan)
        continue;
      if (spansInsteadOfTokens)
        w.write("x xue-palmer-args2 " + t.shortString() + " " + s.shortString());
      else
        w.write("x xue-palmer-args4 " + t.start + " " + t.end + " " + s.start + " " + s.end);
      if (gold.contains(t))
        w.write(" # gold");
      w.newLine();
    }
  }

  public void write(FNParse y, BufferedWriter w) throws IOException {
    // x:
    // word(i,t) pos2(i,t), lemma(i,t)
    // dsyn3-basic(g,d,l), dsyn3-col(g,d,l), dsyn3-colcc(g,d,l)
    // csyn3-stanford(i,j,l)
    write(y.getSentence(), w);

    // arg-pruning
    if (outputArgPruning) {
      try {
        writeArgs(y, w);
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
      write(t, fs, w);
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span s = fi.getArgument(k);
        if (s == Span.nullSpan)
          continue;
        String ks = f.getRole(k);
        write(t, fs, s, ks, w);
        for (Span cs : fi.getContinuationRoleSpans(k))
          write(t, fs, cs, ks + "/C", w);
        for (Span rs : fi.getReferenceRoleSpans(k))
          write(t, fs, rs, ks + "/R", w);
      }
    }
  }

  private void write(Span t, String f, BufferedWriter w) throws IOException {
    if (spansInsteadOfTokens) {
      w.write("y predicate2 " + t.shortString() + " " + f);
      w.newLine();
    } else {
      String ts = t.start + " " + t.end;
      w.write("y event2 " + ts + " " + f);
      w.newLine();
    }
  }

  private void write(Span t, String f, Span s, String k, BufferedWriter w) throws IOException {
    if (spansInsteadOfTokens)
      write(w, "y", "argument4", t.shortString(), f, s.shortString(), k);
    else
      write(w, "y", "srl4", t.start, t.end, f, s.start, s.end, k);
  }

  /**
   * Write relation definitions and xy values to a single file, for one FNParse.
   */
  public static void singleDocExample() throws IOException {
    // Just extract a single FNParse relation set for debugging
    Iterator<FNParse> itr = FileFrameInstanceProvider.fn15trainFIP.getParsedSentences();
    itr.next();
    FNParse y = itr.next();

    ConcreteStanfordWrapper csw = ConcreteStanfordWrapper.getSingleton(false);
    Sentence s = y.getSentence();
    csw.addAllParses(s, new Alphabet<>(), true);
//    s.setStanfordParse(csw.getCParse(s));
//    s.setBasicDeps(csw.getBasicDParse(s));
    s.lemmatize();

    File output = new File("data/srl-reldata/srl-" + y.getId() + ".rel");
    FNParseToRelations fn2r = new FNParseToRelations();
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      fn2r.definitions(w);
      fn2r.write(y, w);
    }
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

    FNParseToRelations fn2r = new FNParseToRelations();
    fn2r.definitions(config.getFile("outputDefs"));

    Iterable<FNParse> l = FeaturePrecomputation.getData(dataset, addParses);

    TimeMarker tm = new TimeMarker();
    int docs = 0;
    try (BufferedWriter w = FileUtil.getWriter(outputVals)) {
      int done = 0;
      for (FNParse y : l) {
        Sentence s = y.getSentence();
        s.lemmatize();
        Log.info("sentenceLength=" + s.size());
        docs++;
        w.write("startdoc " + y.getId() + " # " + dataset);
        w.newLine();
        fn2r.write(y, w);
        done++;
        if (tm.enoughTimePassed(15)) {
          w.flush();
          Log.info("wrote out " + done + " parses in "
              + tm.secondsSinceFirstMark()
              + " seconds");
        }
      }
    }
    Log.info("done, wrote " + docs + " docs");
  }
}
