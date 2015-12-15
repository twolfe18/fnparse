package edu.jhu.hlt.fnparse.rl.full2;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.rl.full2.Node2.DebugTS;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;

public class Node2Tests {

  private DebugTS mod;

  @Before
  public void setupModule() {
    mod = new DebugTS();
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
    Node2 root = mod.genRootNode();
    List<Node2> next = mod.nextStates(root);
    for (int i = 0; i < next.size(); i++) {
      System.out.println("next after root " + i + ": " + next.get(i));
    }
  }

  @Test
  public void test2() {
    // Actions should have losses
  }

  @Test
  public void test3() {
    // Actions should have losses AND scores
  }

  @Test
  public void test4() {
    // Need a way to put coefficients on losses and scores
  }

  @Test
  public void test5() {
    // Make a full update (two beams, beam search)
  }
}
