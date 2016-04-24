package edu.jhu.hlt.uberts.auto;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * A module for taking task labels (e.g. srl4(ts,te,f,ss,se,k) and a transition
 * grammar (which has already been fully type checked, see {@link TypeInference}),
 * and inducing intermediate labels like srl2(srl1Head,event1Head) based on the
 * transition system.
 *
 * Does this by assuming that every rule is necessary. Meaning if a label shows
 * up in the RHS of a rule, then we assume that every term in the LHS *must* be
 * true. This is an approximation since there may be more than one way to derive
 * a fact.
 *
 * @author travis
 */
public class TransitionGeneratorBackwardsParser {
  public static boolean DEBUG = true;

  /**
   * Takes {@link RelDoc} with items:List<RelLine> populated, expands and moves
   * them over to facts:List<HypEdge>.
   */
  public static class Iter implements Iterator<RelDoc> {
    private RelDoc cur;
    private Iterator<RelDoc> wrapped;
    private TypeInference typeInf;
    public Counts<String> counts;
    public Counts<String> countsByRelation;
    public Set<String> relationsToIgnore;

    // Anything Uberts-based which is not pure IO (e.g. training/inference) will
    // break if this is false. True requires a lot of memory.
    public boolean lookupHypNodes = true;

    public Iter(Iterator<RelDoc> wrapped, Uberts u, Collection<Rule> untypedRules) {
      this(wrapped, u, untypedRules, null);
    }
    public Iter(Iterator<RelDoc> wrapped, Uberts u, Collection<Rule> untypedRules, Collection<String> relationsToIgnore) {
      this.counts = new Counts<>();
      this.countsByRelation = new Counts<>();
      this.relationsToIgnore = new HashSet<>();
      if (relationsToIgnore != null)
        this.relationsToIgnore.addAll(relationsToIgnore);
      this.wrapped = wrapped;
      this.typeInf = new TypeInference(u);
      for (Rule r : untypedRules)
        typeInf.add(r);
      typeInf.runTypeInference();
      cur = advance();
    }
    public Iter(Iterator<RelDoc> wrapped, TypeInference alreadyAddedRules) {
      this(wrapped, alreadyAddedRules, null);
    }
    public Iter(Iterator<RelDoc> wrapped, TypeInference alreadyAddedRules, Collection<String> relationsToIgnore) {
      this.counts = new Counts<>();
      this.countsByRelation = new Counts<>();
      this.relationsToIgnore = new HashSet<>();
      if (relationsToIgnore != null)
        this.relationsToIgnore.addAll(relationsToIgnore);
      this.wrapped = wrapped;
      this.typeInf = alreadyAddedRules;
      cur = advance();
    }

    /**
     * Convert {@link RelLine}s in doc.items, put them into doc.facts, and
     * expand all the facts in facts.
     */
    private RelDoc advance() {
      if (!wrapped.hasNext())
        return null;
      RelDoc d = wrapped.next();
      assert d.facts.isEmpty();
      Set<HashableHypEdge> seen = new HashSet<>();
      Uberts u = typeInf.getUberts();

      // Move
      for (RelLine l : d.items) {
        HypEdge e = u.makeEdge(l, lookupHypNodes);
        if (seen.add(new HashableHypEdge(e))) {
          long mask = 0;
          if (l.isX())
            mask |= HypEdge.IS_X;
          if (l.isY())
            mask |= HypEdge.IS_Y;
          d.facts.add(new HypEdge.WithProps(e, mask));
        }
      }
      d.items.clear();
      counts.update("facts-input", d.facts.size());

      // Expand
      for (int i = 0; i < d.facts.size(); i++) {
        HypEdge.WithProps fact = d.facts.get(i);
        for (HypEdge e : typeInf.expand(fact)) {
          String rel = e.getRelation().getName();
          if (relationsToIgnore.contains(rel)) {
            counts.increment("facts-skipped");
            countsByRelation.increment("skipped-" + rel);
            continue;
          }
          if (seen.add(new HashableHypEdge(e))) {
            long mask = fact.getProperties();
            mask |= HypEdge.IS_DERIVED;
            d.facts.add(new HypEdge.WithProps(e, mask));
            counts.increment("facts-derived");
          }
        }
      }
      counts.update("facts-output", d.facts.size());
      return d;
    }

    @Override
    public boolean hasNext() {
      return cur != null;
    }

    @Override
    public RelDoc next() {
      RelDoc r = cur;
      cur = advance();
      return r;
    }
  }

  private Map<Relation, List<Rule>> howToMake = new HashMap<>();
  private boolean verbose = false;

  public void add(Rule r) {
    Log.info("adding " + r);
    Relation key = r.rhs.rel;
    List<Rule> vals = howToMake.get(key);
    if (vals == null) {
      vals = new ArrayList<>();
      howToMake.put(key, vals);
    }
    vals.add(r);
  }

  // e.g. fact = srl4(3-5, fn/foo, 5-9, fn/foo/bar)
  public List<HypEdge> expand(HypEdge fact) {
    List<Rule> rules = howToMake.get(fact.getRelation());
    if (rules == null)
      return Collections.emptyList();

    List<HypEdge> mustBeTrue = new ArrayList<>();
    for (Rule r : rules)
      expand(fact, r, mustBeTrue);
    return mustBeTrue;
  }

  private Set<Arg> alreadyWarnedAbout = new HashSet<>();

  /**
   * Assume that every term in wayToDerive.lhs must be true to prove fact, even
   * though there may be others paths. This is used to deriving training data
   * w.r.t. a transition grammar from training data that doesn't care about the
   * grammar.
   *
   * @deprecated see {@link TypeInference#expand(HypEdge)}
   */
  private void expand(HypEdge fact, Rule wayToDerive, List<HypEdge> addTo) {
    assert false : "should be using TypeInference!!!";
    if (verbose)
      Log.info("back-chaining from " + fact);
    assert wayToDerive.rhs.rel == fact.getRelation();
    assert wayToDerive.rhs.argNames.length == fact.getNumTails();
    lhsTerm:
    for (int lhsTermIdx = 0; lhsTermIdx < wayToDerive.lhs.length; lhsTermIdx++) {
      // We are using the assumption !lhsTerm => !rhs,
      // which is not really true, since there could be other ways to prove rhs.
      Term lhsTerm = wayToDerive.lhs[lhsTermIdx];
      if (verbose)
        Log.info("assuming this is true: " + lhsTerm);

      // Take values from fact, project them into wayToDerive.lhs's terms.
      HypNode[] assumedTrueArgs = new HypNode[lhsTerm.argNames.length];
      for (int argIdx = 0; argIdx < lhsTerm.argNames.length; argIdx++) {
        int rhsArgIdx = wayToDerive.lhs2rhs[lhsTermIdx][argIdx];
        if (rhsArgIdx < 0) {
          Arg a = new Arg(lhsTerm.relName, argIdx);
          if (alreadyWarnedAbout.add(a)) {
            Log.warn(a + " is not used in RHS of " + wayToDerive
                + ", which means that we can't derive a complete "
                + lhsTerm.relName + " fact. Not showing any more warnings"
                + " for this argument.");
          }
          // TODO When Relations have a schema:boolean flag, check that here.
//          assert lhsTerm.rel instanceof HypEdge.Schema;
          continue lhsTerm;
        }
        Object value = fact.getTail(rhsArgIdx).getValue();
        NodeType nt = fact.getTail(rhsArgIdx).getNodeType();
        assumedTrueArgs[argIdx] = new HypNode(nt, value);
        if (verbose)
          Log.info("  arg[" + argIdx + "]=" + assumedTrueArgs[argIdx]);
      }
      HypEdge assumedTrue = new HypEdge(lhsTerm.rel, null, assumedTrueArgs);
      if (verbose)
        Log.info("  " + assumedTrue);
      addTo.add(assumedTrue);
    }
  }

  /**
   * A very simple run of the backwards generation.
   * This example DOES NOT include cases with LHS terms which contain variables
   * not bound on the RHS, e.g.
   *   event1(ts,te) & lemma(ts,lemma) & frameTriage(lemma,frame) => event2(ts,te,frame)
   * Where this method will necessarily fail.
   */
  public static void demo() {
    // e.g. event2(t,f) & srl2(t,s) & role(f,k) => srl3(t,f,s,k)
    NodeType spanNT = new NodeType("span");
    NodeType frameNT = new NodeType("frame");
    NodeType roleNT = new NodeType("role");
    Relation event2 = new Relation("event2", spanNT, frameNT);
    Relation srl2 = new Relation("srl2", spanNT, spanNT);
    Relation role = new Relation("role", frameNT, roleNT);
    Relation srl3 = new Relation("srl3", spanNT, frameNT, spanNT, roleNT);
    Term event2Term = new Term(event2, "t", "f");
    Term srl2Term = new Term(srl2, "t", "s");
    Term roleTerm = new Term(role, "f", "k");
    Term srl3Term = new Term(srl3, "t", "f", "s", "k");
    Rule r = new Rule(Arrays.asList(event2Term, srl2Term, roleTerm), srl3Term);
    System.out.println("rule: " + r);

    TransitionGeneratorBackwardsParser tgp = new TransitionGeneratorBackwardsParser();
    tgp.add(r);

    HypNode[] srl3FactArgs = new HypNode[4];
    srl3FactArgs[0] = new HypNode(spanNT, "3-5");           // t
    srl3FactArgs[1] = new HypNode(frameNT, "Commerce_buy"); // f
    srl3FactArgs[2] = new HypNode(spanNT, "0-3");           // s
    srl3FactArgs[3] = new HypNode(roleNT, "Buyer");         // k
    HypEdge srl3Fact = new HypEdge(srl3, null, srl3FactArgs);

    List<HypEdge> assumed = tgp.expand(srl3Fact);
    System.out.println("fact: " + srl3Fact);
    for (HypEdge e : assumed)
      System.out.println("gen: " + e);
  }

  /**
   * Expands a set of necessary facts according to a transition system to
   * add implied labels for intermediate Relations introduced by the transition
   * system.
   */
  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    File outfile = config.getFile("output");
    Log.info("writing expanded facts to " + outfile.getPath());

    File grammarFile = config.getExistingFile("grammar");
    Log.info("using grammar in " + grammarFile.getPath());

    Uberts u = new Uberts(new Random(9001));

    // Define the succTok(i,j) relation and populate it
    u.addSuccTok(1000);

    // Read in relation definitions for x and y data.
    // This will contain the definitions for final/output variables like
    // srl4(ts,te,f,ss,se,k), but DOES NOT NEED to have definitions for
    // intermediate relations like srl2(event1head,srl1head) which are inferred
    // from the grammar (next step).
    for (String defFileName : config.getString("defs").split(","))
      u.readRelData(new File(defFileName));
    if (DEBUG)
      Log.info("defined: " + u.getAllEdgeTypes());

    File multiRefVals = config.getExistingFile("instances");
    boolean includeProvidence = true;
    boolean dedupInputLines = true;
    TimeMarker tm = new TimeMarker();
    try (RelationFileIterator itr = new RelationFileIterator(multiRefVals, includeProvidence);
        ManyDocRelationFileIterator m = new ManyDocRelationFileIterator(itr, dedupInputLines);
        BufferedWriter w = FileUtil.getWriter(outfile)) {
      List<Rule> untypedRules = Rule.parseRules(grammarFile, null);
      Collection<String> ignore = Arrays.asList("succTok");
      Iter expanded = new Iter(m, u, untypedRules, ignore);
      while (expanded.hasNext()) {
        RelDoc d = expanded.next();
        w.write(d.def.toLine());
        w.newLine();

        assert d.items.isEmpty() && !d.facts.isEmpty()
            : "Iter should have moved them into facts";

        Set<String> uniqKeys = new HashSet<>();
        for (HypEdge.WithProps e : d.facts) {
          String dt = HypEdge.getRelLineDataType(e);
          if (dt == null) {
            dt = "???";
            assert false : "unknown datatype for: " + e;
          }
          String rfs = e.getRelFileString(dt);
          if (uniqKeys.add(rfs)) {
            w.write(rfs);
            w.newLine();
          }
        }
//        Function<RelLine, String> keyFunc = rl -> StringUtils.join("\t", rl.tokens);
//        for (RelLine line : d.items) {
//          if (uniqKeys.add(keyFunc.apply(line))) {
//            w.write(line.toLine());
//            w.newLine();
//          }
//        }

        if (tm.enoughTimePassed(15)) {
          Log.info(expanded.counts + " in " + tm.secondsSinceFirstMark() + " sec");
          w.flush();
        }
      }
      Log.info("done, " + expanded.counts);
      Log.info(expanded.countsByRelation);
    }
  }
}
