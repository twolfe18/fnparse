package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.State;
import edu.jhu.hlt.fnparse.rl.full2.Node2.DebugTS;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.fnparse.rl.full2.Node2.State2;
import edu.jhu.prim.tuple.Pair;

public class Node2Tests {

  private DebugTS mod;
  private List<FNParse> examples;

  @Before
  public void setupModule() {
    mod = new DebugTS();
  }

  @Before
  public void getParses() {
    examples = State.getParse();
  }

  @Test
  public void test0() {
    System.out.println("eggs for empty prefix:");
    System.out.println(mod.genEggs(null));

    NodeWithSignature root = mod.newNode(null, mod.genEggs(null), null, null);
    System.out.println("root:");
    System.out.println(root);

    Node2 c1 = mod.hatch(root);
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
    addSomeLabels();
    State2 prev = null;
    Node2 root = mod.genRootNode();
    List<State2> next = mod.nextStatesL(prev, root);
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

  private void addSomeLabels() {
    List<LL<TV>> y = new ArrayList<>();
    y.add(lltvSugar(TFKS.S, 0, TFKS.K, 0, TFKS.F, 0, TFKS.T, 0));
    y.add(lltvSugar(TFKS.S, 2, TFKS.K, 1, TFKS.F, 1, TFKS.T, 1));
    y.add(lltvSugar(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, 2));
    mod.provideLabel(y);
  }
  private void addALabel(int... tvPairs) {
    List<LL<TV>> y = new ArrayList<>();
    y.add(lltvSugar(tvPairs));
    mod.provideLabel(y);
  }

  @Test
  public void test2() {
    // Actions should have losses
    for (boolean hatchIsLossy : Arrays.asList(true, false)) {
      System.out.println("hatchIsLossy=" + hatchIsLossy);
      State2 prev = null;
      State2 root = mod.genRootState();

      // Get the first egg from root and then ensure this is either in y or not
      // based on hatchIsLossy
      TV t = root.getNode().eggs.car();
      assert t.getType() == TFKS.T;
      if (hatchIsLossy)
        addALabel(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, t.getValue() + 1);
      else
        addALabel(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, t.getValue());

      List<State2> next = mod.nextStatesL(prev, root.getNode());
      for (int i = 0; i < next.size(); i++) {
        State2 n = next.get(i);
        double l = n.getStepScores().constraintObjectivePlusConstant();
        System.out.println("loss of next state after root " + i + ": " + l);
      }
    }
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
    State2 root = mod.genRootState();
    TV t = root.getNode().eggs.car();
    assert t.getType() == TFKS.T;
    addALabel(TFKS.S, 4, TFKS.K, 2, TFKS.F, 2, TFKS.T, t.getValue());

//    DoubleBeam<State2> next = new DoubleBeam<>(4, Mode.BEAM_SEARCH_OBJ);
//    DoubleBeam<State2> all = new DoubleBeam<>(128, Mode.CONSTRAINT_OBJ);
//    mod.nextStatesB(root, next, all);
//
//    for (int i = 0; next.size() > 0; i++)
//      System.out.println("next[" + i + "] " + next.pop());
//    for (int i = 0; all.size() > 0; i++)
//      System.out.println("all[" + i + "] " + all.pop());
    FNParse y = examples.get(0);
    assert y.numFrameInstances() > 0;
    Pair<State2, DoubleBeam<State2>> oracle = mod.runInference(y);
    System.out.println("oracle state (beam): " + oracle.get1());
    System.out.println("max violation: " + oracle.get2().pop());
  }

  @Test
  public void test6() {
    // See that you can get global features working, e.g. roleCooc
  }
}
