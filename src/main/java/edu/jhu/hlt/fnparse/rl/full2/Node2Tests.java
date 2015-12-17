package edu.jhu.hlt.fnparse.rl.full2;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.Primes;
import edu.jhu.hlt.fnparse.rl.full.State;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.prim.tuple.Pair;

public class Node2Tests {

//  private DebugTransitionSystem mod;
  private FNParseTransitionScheme mod;
  private List<FNParse> examples;
  private Config conf = Config.FAST_SETTINGS;
  private DeterministicRolePruning drp = new DeterministicRolePruning(
      DeterministicRolePruning.Mode.XUE_PALMER_HERMANN, null, null);

  @BeforeClass
  public static void init() {
    ExperimentProperties.init(new String[] {
        "data.ontonotes5", "data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations",
        "data.propbank.conll", "../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data",
        "data.propbank.frames", "data/ontonotes-release-5.0-fixed-frames/frames",
    });
  }

  @Before
  public void setupModule() {
//    mod = new DebugTransitionSystem();
    conf.frPacking = new FrameRolePacking(FrameIndex.getFrameNet());
    Primes p = new Primes(new File("data/primes/primes1.byLine.txt.gz"));
    mod = new FNParseTransitionScheme(null, p);
  }

  @Before
  public void getParses() {
    examples = State.getParse();
  }

  private Info getOracleInfo(int i) {
    FNParse y = examples.get(i);
    for (int j = 0; y.numFrameInstances() == 0 && i + j < examples.size(); j++)
      y = examples.get(i + j);
    assert y.numFrameInstances() > 0;
    Info inf = new Info(conf)
        .setOracleCoefs()
        .setLabel(y, mod);
    inf.setTargetPruningToGoldLabels();
    boolean includeGoldSpansIfMissing = true;
    inf.setArgPruningUsingSyntax(drp, includeGoldSpansIfMissing);
    return inf;
  }

  @Test
  public void test0() {
    System.out.println("eggs for empty prefix:");
    Info i = getOracleInfo(0);
    System.out.println(mod.genEggs(null, i));

    NodeWithSignature root = mod.newNode(null, mod.genEggs(null, i), null, null);
    System.out.println("root:");
    System.out.println(root);

    Node2 c1 = mod.hatch(root, i);
    System.out.println("c1:");
    System.out.println(c1);

    System.out.println("root.sig=" + root.getSignature());
    System.out.println("c1.sig=" + ((NodeWithSignature) c1).getSignature());

    root.dbgSantityCheckE();
    c1.dbgSantityCheckE();
  }

  @Test
  public void test1() {
    // Lets see if we can get action generation working
    System.out.println("  <<<<<<<<<<<<<<< TEST 1 >>>>>>>>>>>>>>>  ");
//    addSomeLabels();
    Info info = getOracleInfo(0);
    State2<Info> root = mod.genRootState(info);
    List<State2<Info>> next = mod.dbgNextStatesL(root);
    for (int i = 0; i < next.size(); i++) {
      System.out.println("next after root " + i + ": " + next.get(i));
    }
  }

  public static LL<TV> lltvSugar(int... tvPairs) {
    LL<TV> l = null;
    int n = tvPairs.length / 2;
    if (2 * n != tvPairs.length)
      throw new IllegalArgumentException("you must provide pairs of (type,value)");
    for (int i = n-1; i >= 0; i--) {
      TV tv = new TV(tvPairs[2*i], tvPairs[2*i + 1]);
      l = new LL<>(tv, l);
    }
//    System.out.println(Arrays.toString(tvPairs));
//    System.out.println(l);
    return l;
  }

//  private void addSomeLabels() {
//    List<LL<TV>> y = new ArrayList<>();
//    y.add(lltvSugar(TFKS.S, 0, TFKS.K, 0, TFKS.F, 0, TFKS.T, 0));
//    y.add(lltvSugar(TFKS.S, 2, TFKS.K, 1, TFKS.F, 1, TFKS.T, 1));
//    y.add(lltvSugar(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, 2));
//    mod.provideLabel(y);
//  }
//  private void addALabel(int... tvPairs) {
//    List<LL<TV>> y = new ArrayList<>();
//    y.add(lltvSugar(tvPairs));
//    mod.provideLabel(y);
//  }

  @Test
  public void test2() {
    // Actions should have losses
    Info inf = getOracleInfo(0);
//    for (boolean hatchIsLossy : Arrays.asList(true, false)) {
//      System.out.println("hatchIsLossy=" + hatchIsLossy);
      State2<Info> root = mod.genRootState(inf);

      // Get the first egg from root and then ensure this is either in y or not
      // based on hatchIsLossy
//      TV t = root.getNode().eggs.car();
//      assert t.getType() == TFKS.T;
////      if (hatchIsLossy)
////        addALabel(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, t.getValue() + 1);
////      else
////        addALabel(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, t.getValue());
//      assert false : "this test is broken since its not as easy to monkey-wrench in Counts<HashableIntArray> any more";

      List<State2<Info>> next = mod.dbgNextStatesL(root);
      for (int i = 0; i < next.size(); i++) {
        State2<Info> n = next.get(i);
        double l = n.getStepScores().constraintObjectivePlusConstant();
        System.out.println("loss of next state after root " + i + ": " + l);
      }
//    }
  }

  @Test
  public void test3() {
    // Actions should have losses AND scores
    test2();  // (already does this)
  }

  @Test
  public void test4() {
    // Need a way to put coefficients on losses and scores
    // StepScores handles this :)
    test2();  // (already does this)
  }

  @Test
  public void test5() {
    // Make a full update (two beams, beam search)
    Info inf = getOracleInfo(0);
    State2<Info> root = mod.genRootState(inf);
    TV t = root.getRoot().eggs.car();
    assert t.getType() == TFKS.T;
//    addALabel(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, t.getValue());

    assert inf.getLabelParse().numFrameInstances() > 0;
    Pair<State2<Info>, DoubleBeam<State2<Info>>> oracle = mod.runInference(root);
    System.out.println("oracle state (beam): " + oracle.get1());
    System.out.println("max violation: " + oracle.get2().pop());
  }

  @Test
  public void test6() {
    // See that you can get global features working, e.g. roleCooc
  }
}
