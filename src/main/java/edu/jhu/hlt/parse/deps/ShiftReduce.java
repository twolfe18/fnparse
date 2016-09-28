package edu.jhu.hlt.parse.deps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public class ShiftReduce {
  
  public static boolean SHOW_TRANSITION_SYSTEM = false;
  
  public static Set<IntPair> DEBUG_GOLD_EDGES = new HashSet<>();
  
  public static int shiftAction() { return 0; }
  public static int reduceAction() { return 1; }
  public static int arcAction(int head, int modifier, int label) {
    assert head != modifier : "loop? h=m=" + head;
    int a = (head << 16) | (modifier << 8) | label;
    return a + 2;
  }
  public static boolean isArcAction(int action) {
    return action >= 2;
  }
  public static int getHeadOfArcAction(int action) {
    assert action >= 0;
    int a = action - 2;
    return a >>> 16;
  }
  public static int getModifierOfArcAction(int action) {
    assert action >= 0;
    int a = action - 2;
    return (a >>> 8) & 0xff;
  }
  public static int getLabelOfArcAction(int action) {
    assert action >= 0;
    int a = action - 2;
    return a & 0xff;
  }
  /** returns an in in [0,1,2,3] for SH, RE, AL, AR */
  public static int getActionType(int action) {
    if (isArcAction(action)) {
      int h = getHeadOfArcAction(action);
      int m = getModifierOfArcAction(action);
      return m < h ? 2 : 3;
    } else {
      return action;
    }
  }
  
  public static String showAction(int action) {
    if (action < 0)
      return "null";
    if (action == shiftAction())
      return "SH";
    if (action == reduceAction())
      return "RE";
    assert isArcAction(action);
    int h = getHeadOfArcAction(action);
    int m = getModifierOfArcAction(action);
    if (h > m)
      return "(AL h=" + h + " m=" + m + ")";
    else
      return "(AR h=" + h + " m=" + m + ")";
  }
  public static String showActions(int[] arcs) {
    arcs = Arrays.copyOf(arcs, arcs.length);
    Arrays.sort(arcs);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < arcs.length; i++) {
      if (i == 0)
        sb.append('[');
      else
        sb.append(", ");
      sb.append(showAction(arcs[i]));
    }
    sb.append(']');
    return sb.toString();
  }
  
  public static int recallAbs(int[] goldArcs, int[] predArcs) {
    int r = 0;
    for (int i = 0; i < goldArcs.length; i++)
      if (contains(predArcs, goldArcs[i]))
        r++;
    return r;
  }
  public static double recallRel(int[] goldArcs, int[] predArcs) {
    return ((double) recallAbs(goldArcs, predArcs)) / goldArcs.length;
  }
  
  public static class Stack {
    int item;
    Stack prev;
    public Stack(int item, Stack prev) {
      this.item = item;
      this.prev = prev;
    }
    @Override
    public String toString() {
      Deque<Stack> d = new ArrayDeque<>();
      for (Stack s = this; s != null; s = s.prev)
        d.push(s);
      StringBuilder sb = new StringBuilder();
      while (!d.isEmpty()) {
        if (sb.length() == 0)
          sb.append('[');
        else
          sb.append(", ");
        sb.append(d.pop().item);
      }
      sb.append(']');
      return sb.toString();
    }
  }
  
  /** Goes onto a beam */
  public static class State {
    
    public static final Comparator<State> BY_ALPHA = new Comparator<State>() {
      @Override
      public int compare(State o1, State o2) {
        if (o1.alpha > o2.alpha)
          return -1;
        if (o1.alpha < o2.alpha)
          return +1;
        return 0;
      }
    };

    State prev;
    // TODO Can have LL of "prev state who's action is an arcLeft"

    Stack stack;
    int buf;
    int action;
    
    IntArrayList features;
    double psi;   // exp(theta * features)
    double alpha; // sum_{prev} psi(prev -> cur) * prev.alpha
    double beta;  // sum_{next} psi(cur -> next) * next.beta

    public State(Stack s, int b, int a, State p) {
      stack = s;
      buf = b;
      action = a;
      prev = p;
    }
    
    public State() {
      stack = new Stack(0, null);
      buf = 1;
      action = -1;
      prev = null;
    }
    
    @Override
    public String toString() {
      return "(State s=" + stack + " b=" + buf + " action=" + showAction(action) + ")";
    }
    
    public List<State> reverse() {
      List<State> r = new ArrayList<>();
      for (State cur = this; cur != null; cur = cur.prev)
        r.add(cur);
      Collections.reverse(r);
      return r;
    }
    
    public boolean isFinal(int sentLen) {
      return stack.item == 0 && buf > sentLen;
    }
    
    public State shift() {
      Stack s = new Stack(buf, stack);
      return new State(s, buf+1, shiftAction(), this);
    }
    public State reduce() {
      return new State(stack.prev, buf, reduceAction(), this);
    }
    public State arcLeft(int label) {
      int a = arcAction(buf, stack.item, label);
      return new State(stack.prev, buf, a, this);
    }
    public State arcRight(int label) {
      int a = arcAction(stack.item, buf, label);
      return new State(new Stack(buf, stack), buf+1, a, this);
    }

    public boolean canArcLeft() {
      if (stack.item == 0)  // ROOT
        return false;
      // Check that we haven't already assigned a head to S0
      // TODO This is slow!
      for (State cur = this; cur != null; cur = cur.prev) {
        if (isArcAction(cur.action)) {
          int m = getModifierOfArcAction(cur.action);
          if (m == stack.item)
            return false;
        }
      }
      return true;
    }
    
    public boolean canArcRight(int sentLen) {
      return buf <= sentLen;
    }
    
    public boolean canReduce() {
      if (stack.item == 0)  // ROOT
        return false;
      for (State cur = this; cur != null; cur = cur.prev) {
        if (isArcAction(cur.action)) {
          int m = getModifierOfArcAction(cur.action);
          if (m == stack.item)
            return true;
        }
      }
      return false;
    }
    
    public boolean canShift(int sentLen) {
      return buf <= sentLen;
    }
    
    public int s0Head() {
      if (stack.item == 0)
        return -1;  // ROOT
      for (State cur = this; cur != null; cur = cur.prev) {
        if (isArcAction(cur.action)) {
          int m = getModifierOfArcAction(cur.action);
          if (m == stack.item)
            return getHeadOfArcAction(cur.action);
        }
      }
      return -1;
//      // the head of the thing on the top of the stack was chosen by some arcX action
//      // (shift and reduce don't add arcs)
//      if (isArcAction(action)) {
//        // case arcLeft:
//        //   this.action must be an edge from S0 <- N0
//        // case argRight:
//        //   this.action must be an edge from S1 -> S0
//        if (isLeftArcAction(action)) {
//          return buf;
//        } else {
//          return stack.prev.item;
//        }
//      } else {
//        return -1;
//      }
    }
    
    /** Last element is this.action */
    public int[] getHistory(boolean onlyArcs) {
      Deque<State> hist = new ArrayDeque<>();
      State cur = this;
      while (cur.prev != null) {
        if (!onlyArcs || isArcAction(cur.action))
          hist.add(cur);
        cur = cur.prev;
      }
      int[] h = new int[hist.size()];
      for (int i = 0; !hist.isEmpty(); i++)
        h[i] = hist.pop().action;
      return h;
    }
  }
  
  public static int mix(int a, int b) {
    return Hash.mix(a, b);
  }
  public static int mix(int... a) {
    return Hash.mix(a);
  }

  // http://hnk.ffzg.hr/bibl/acl2011/Short/pdf/ACL-HLT2011033.pdf
  
  public static void zhangNivre2011_singleWords(FeatureHelper fh, IntArrayList features) {
    // S0wp; S0w; S0p; N0wp; N0w; N0p; N1wp; N1w; N1p; N2wp; N2w; N2p;
    State s = fh.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    int N1 = s.buf+1;
    int N2 = s.buf+2;

    features.add(mix(1, fh.w(S0), fh.p(S0)));
    features.add(mix(2, fh.w(S0)));
    features.add(mix(3, fh.p(S0)));

    features.add(mix(4, fh.w(N0), fh.p(N0)));
    features.add(mix(5, fh.w(N0)));
    features.add(mix(6, fh.p(N0)));

    features.add(mix(7, fh.w(N1), fh.p(N1)));
    features.add(mix(8, fh.w(N1)));
    features.add(mix(9, fh.p(N1)));

    features.add(mix(10, fh.w(N2), fh.p(N2)));
    features.add(mix(11, fh.w(N2)));
    features.add(mix(12, fh.p(N2)));
  }
  
  public static void zhangNivre2011_pairWords(FeatureHelper fh, IntArrayList features) {
    // S0wpN0wp; S0wpN0w; S0wN0wp; S0wpN0p; S0pN0wp; S0wN0w; S0pN0p N0pN1p
    State s = fh.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    int N1 = s.buf+1;
    
    features.add(mix(13, fh.w(S0), fh.p(S0), fh.w(N0), fh.p(N0)));
    features.add(mix(14, fh.w(S0), fh.p(S0), fh.w(N0)));
    features.add(mix(15, fh.w(S0), fh.w(N0), fh.p(N0)));
    features.add(mix(16, fh.w(S0), fh.p(S0), fh.p(N0)));
    features.add(mix(17, fh.p(S0), fh.w(N0), fh.p(N0)));
    features.add(mix(18, fh.w(S0), fh.w(N0)));
    features.add(mix(19, fh.p(S0), fh.p(N0)));
    features.add(mix(19, fh.p(N0), fh.p(N1)));
  }

  public static void zhangNivre2011_tripleWords(FeatureHelper fh, IntArrayList features) {
    // N0pN1pN2p; S0pN0pN1p; S0hpS0pN0p; S0pS0lpN0p; S0pS0rpN0p; S0pN0pN0lp
    State s = fh.getState();
    int S0 = s.stack.item;
    int S0h = fh.getS0h();
    int S0l = fh.sentenceLength();
    int S0r = -1;
    int N0 = s.buf;
    int N0l = fh.sentenceLength();
    int N1 = s.buf+1;
    int N2 = s.buf+2;
    
    // Bite the bullet and just compute the children the brute force way
    // (this may actually be the fastest way...)
    int[] hist = fh.getHistoryOnlyArcs();
    for (int i = 0; i < hist.length; i++) {
      int h = getHeadOfArcAction(hist[i]);
      int m = getModifierOfArcAction(hist[i]);
      if (h == S0 && m < S0l) S0l = m;
      if (h == S0 && m > S0r) S0r = m;
      if (h == N0 && m < N0l) N0l = m;
    }

    features.add(mix(20, fh.p(N0), fh.p(N1), fh.p(N2)));
    features.add(mix(21, fh.p(S0), fh.p(N0), fh.p(N1)));
    features.add(mix(22, fh.p(S0h), fh.p(S0), fh.p(N0)));
    features.add(mix(23, fh.p(S0), fh.p(S0l), fh.p(N0)));
    features.add(mix(24, fh.p(S0), fh.p(S0r), fh.p(N0)));
    features.add(mix(25, fh.p(S0), fh.p(N0), fh.p(N0l)));
  }

  public static void zhangNivre2011_distance(int[] words, int[] pos, FeatureHelper configuration, IntArrayList features) {
    // S0wd; S0pd; N0wd; N0pd; S0wN0wd; S0pN0pd;
    State s = configuration.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    
    int dist = N0 - S0;
    
    features.add(mix(26, words[S0], dist));
    features.add(mix(27, pos[S0], dist));
    features.add(mix(28, words[N0], dist));
    features.add(mix(29, pos[N0], dist));
    features.add(mix(30, words[S0], words[N0], dist));
    features.add(mix(31, pos[S0], pos[N0], dist));
  }

  public static void zhangNivre2011_valency(int[] words, int[] pos, FeatureHelper configuration, IntArrayList features) {
    // S0wvr; S0pvr; S0wvl; S0pvl; N0wvl; N0pvl;
    State s = configuration.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    int vl = 0;
    int vr = 0;

    int[] hist = configuration.getHistoryOnlyArcs();
    for (int i = 0; i < hist.length; i++) {
      int h = getHeadOfArcAction(hist[i]);
      if (h == S0) vl++;
      if (h == N0) vr++;
    }
    
    features.add(mix(32, words[S0], vr));
    features.add(mix(33, pos[S0], vr));
    features.add(mix(34, words[S0], vl));
    features.add(mix(35, pos[S0], vl));
    features.add(mix(36, words[N0], vl));
    features.add(mix(37, pos[N0], vl));
  }
  
  public static void zhangNivre2011_unigrams(int[] words, int[] pos, FeatureHelper configuration, IntArrayList features) {
    // S0_hw; S0_hp; S0_l; S0_lw; S0_lp; S0_ll; S0_rw; S0_rp; S0_rl;N0_lw; N0_lp; N0_ll;
    State s = configuration.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    int S0h = -1;
    int S0l = -1;
    int S0l_label = -1;
    int S0r = -1;
    int S0r_label = -1;
    int N0l = -1;
    int N0l_label = -1;
    int S0_label = -1;

    int[] hist = configuration.getHistoryOnlyArcs();
    for (int i = 0; i < hist.length; i++) {
      int h = getHeadOfArcAction(hist[i]);
      int m = getModifierOfArcAction(hist[i]);
      int l = getLabelOfArcAction(hist[i]);
      if (m == S0) S0_label = l;
      if (h == S0 && m < S0l) {
        S0l = m;
        S0l_label = l;
      }
      if (h == S0 && m > S0r) {
        S0r = m;
        S0r_label = l;
      }
      if (h == N0 && m < N0l) {
        N0l = m;
        N0l_label = l;
      }
    }
    
    features.add(mix(38, words[S0h]));
    features.add(mix(39, pos[S0h]));
    features.add(mix(40, S0_label));
    features.add(mix(41, words[S0l]));
    features.add(mix(42, pos[S0l]));
    features.add(mix(43, S0l_label));
    features.add(mix(44, words[S0r]));
    features.add(mix(45, pos[S0r]));
    features.add(mix(46, S0r_label));
    features.add(mix(44, words[N0l]));
    features.add(mix(45, pos[N0l]));
    features.add(mix(46, N0l_label));
  }

  public static void zhangNivre2011_thirdOrder(int[] words, int[] pos, FeatureHelper configuration, IntArrayList features) {
    // S0h2w; S0h2p; S0hl; S0l2w; S0l2p; S0l2l;
    // S0r2w; S0r2p; S0r2l; N0l2w; N0l2p; N0l2l;
    // S0pS0lpS0l2p; S0pS0rpS0r2p;
    // S0pS0hpS0h2p; N0pN0lpN0l2p;
    State s = configuration.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    int N0l = -1;   // leftmost modifier N0
    int N0l2 = -1;  // second-leftmost modifier of N0
    int N0l2_label = -1;
    int S0h = -1;   // head of S0
    int S0l = -1;   // leftmost modifier of S0
    int S0l2 = -1;  // second-leftmost modifier of S0
    int S0l_label = -1;
    int S0r = -1;   // rightmost modifier of S0
    int S0r2 = -1;  // second-rightmost modifier of S0
    int S0r_label = -1;
    int S0h_label = -1;
    int S0h2 = -1;  // head of (S0h = head of S0)
    
    int[] hist = configuration.getHistoryOnlyArcs();
    for (int i = 0; i < hist.length; i++) {
      int h = getHeadOfArcAction(hist[i]);
      int m = getModifierOfArcAction(hist[i]);
      int l = getLabelOfArcAction(hist[i]);
      if (h == N0) {
        N0l = m;
      }
      if (m == S0) {
        S0h = h;
      }
      if (h == S0) {
        if (m < S0l) {
          S0l = m;
          S0l_label = l;
        }
        if (m > S0r) {
          S0r = m;
          S0r_label = l;
        }
      }
    }
    for (int i = 0; i < hist.length; i++) {
      int h = getHeadOfArcAction(hist[i]);
      int m = getModifierOfArcAction(hist[i]);
      if (h == N0 && m > N0l && m < N0l) {
        N0l = m;
      }
      if (m == S0h) {
        S0h2 = h;
      }
      if (h == S0) {
        if (m > S0l && m < S0l2) S0l2 = m;
        if (m < S0r && m > S0r2) S0r2 = m;
      }
    }

    features.add(mix(47, words[S0h2]));
    features.add(mix(48, pos[S0h2]));
    features.add(mix(49, S0h_label));

    features.add(mix(50, words[S0l]));
    features.add(mix(51, pos[S0l]));
    features.add(mix(52, S0l_label));

    features.add(mix(53, words[S0r]));
    features.add(mix(54, pos[S0r]));
    features.add(mix(55, S0r_label));

    features.add(mix(56, words[N0l2]));
    features.add(mix(57, pos[N0l2]));
    features.add(mix(58, N0l2_label));

    features.add(mix(59, pos[S0], pos[S0l], pos[S0l2]));
    features.add(mix(60, pos[S0], pos[S0r], pos[S0r2]));
    features.add(mix(61, pos[S0], pos[S0h], pos[S0h2]));
    features.add(mix(62, pos[N0], pos[N0l], pos[N0l2]));
  }
  
  public static void zhangNivre2011_labelSet(int[] words, int[] pos, FeatureHelper configuration, IntArrayList features) {
    // S0wsr; S0psr; S0wsl; S0psl; N0wsl; N0psl;
    
    State s = configuration.getState();
    int S0 = s.stack.item;
    int N0 = s.buf;
    throw new RuntimeException("implement me");
  }
  
  /** Provide 1-indexed heads where 0 is the ROOT  */
  public static int[] convertToArcs(int[] heads, int[] deprels) {
    int nr = 0;
    int[] arcs = new int[heads.length];
    for (int i = 0; i < arcs.length; i++) {
      assert heads[i] >= 0;
      if (heads[i] == 0) nr++;
      arcs[i] = arcAction(heads[i], i+1, deprels[i]);
    }
    assert nr == 1 : "not exactly one root?";
    return arcs;
  }
  
  public static boolean contains(int[] set, int elem) {
    return contains(set, elem, false);
  }
  public static boolean contains(int[] set, int elem, boolean dropLastSetElement) {
    int n = set.length;
    if (dropLastSetElement)
      n--;
    for (int i = 0; i < n; i++)
      if (set[i] == elem)
        return true;
    return false;
  }
  
  public static State arcEagerDynamicOracleTrajectory(State init, int[] heads, int[] deprels, int[] words, int[] pos) {
//  public static State arcEagerDynamicOracleTrajectory(FeatureHelper initFH, int[] heads, int[] deprels) {
    int[] arcsGold = convertToArcs(heads, deprels);
    if (SHOW_TRANSITION_SYSTEM)
      Log.info("gold arcs: " + showActions(arcsGold));
    int n = heads.length;
    State cur = init;
    while (!cur.isFinal(n)) {
      
      if (SHOW_TRANSITION_SYSTEM) {
        System.out.println("\ncur=" + cur);
        
        // Check if this action added a gold edge
        if (isArcAction(cur.action)) {
          int h = getHeadOfArcAction(cur.action);
          int m = getModifierOfArcAction(cur.action);
          IntPair e = new IntPair(h, m);
          System.out.println("added " + e + ", in gold edge set? " + DEBUG_GOLD_EDGES.contains(e));
        }
      }

//      int[] costs = arcEagerDynamicOracleCosts(cur, arcsGold, n);
      FeatureHelper fh = new FeatureHelper(cur, words, pos);
      int[] costs = arcEagerDynamicOracleCosts(fh, arcsGold, n);
      if (costs[0] == 0)
        cur = cur.arcLeft(0);
      else if (costs[1] == 0)
        cur = cur.arcRight(0);
      else if (costs[2] == 0)
        cur = cur.reduce();
      else if (costs[3] == 0)
        cur = cur.shift();
      else
        throw new RuntimeException("no zero cost action?");
    }
    return cur;
  }
  
  /**
   * Goldberg's arc-eager dynamic oracle
   * http://www.aclweb.org/anthology/C12-1059
   * @return [costArcLeft, costArcRight, costReduce, costShift]
   */
  public static int[] arcEagerDynamicOracleCosts(FeatureHelper fh, int[] arcsGold, int sentLen) {
//    FeatureHelper fh = new FeatureHelper(cur);
    State cur = fh.getState();
    int S0 = cur.stack == null ? -1 : cur.stack.item;
    int N0 = cur.buf;

    // COST OF ARC_LEFT
    // "number of gold arcs s.t. modifier=S0 and head in buf"
    // + "number of gold arcs s.t. head=S0 and modifier in buf"
    int costArcLeft = 0;
    if (!cur.canArcLeft()) {
      if (SHOW_TRANSITION_SYSTEM)
        System.out.println("can't AL at " + cur);
      costArcLeft = -1;
    } else {
      for (int i = 0; i < arcsGold.length; i++) {
        int h = getHeadOfArcAction(arcsGold[i]);
        int m = getModifierOfArcAction(arcsGold[i]);
        if ((S0 == h && m > N0)
            || (S0 == m && h > N0)) {
          if (SHOW_TRANSITION_SYSTEM)
            System.out.println("unreachable edge: " + showAction(arcsGold[i]) + " because of AL at " + cur);
          costArcLeft++;
        }
      }
    }

    // COST OF ARC_RIGHT
    // "number of gold arcs s.t. modifier=N0 and (head in stack OR head in buf)"
    // + "number of gold arcs s.t. head=N0 and modifier in stack"
    int costArcRight = 0;
    if (!cur.canArcRight(sentLen)) {
      if (SHOW_TRANSITION_SYSTEM)
        System.out.println("can't AR at " + cur);
      costArcRight = -1;
    } else {
      for (int i = 0; i < arcsGold.length; i++) {
        assert isArcAction(arcsGold[i]);
        int h = getHeadOfArcAction(arcsGold[i]);
        int m = getModifierOfArcAction(arcsGold[i]);
        boolean ignoreTopOfStack = true;
        if ((m == N0 && (h > N0 || contains(fh.getStackContents(), h, ignoreTopOfStack)))
            || (h == N0 && contains(fh.getStackContents(), m, ignoreTopOfStack))) {
          if (SHOW_TRANSITION_SYSTEM)
            System.out.println("unreachable edge: " + showAction(arcsGold[i]) + " because of AR at " + cur);
          costArcRight++;
        }
      }
    }

    // COST OF REDUCE
    // "number of gold arcs where head=S0 and modifier in the buf"
    int costReduce = 0;
    if (!cur.canReduce()) {
      if (SHOW_TRANSITION_SYSTEM)
        System.out.println("can't RE at " + cur);
      costReduce = -1;
    } else {
      for (int i = 0; i < arcsGold.length; i++) {
        int h = getHeadOfArcAction(arcsGold[i]);
        int m = getModifierOfArcAction(arcsGold[i]);
        if (h == S0 && m >= N0) {
          if (SHOW_TRANSITION_SYSTEM)
            System.out.println("unreachable edge: " + showAction(arcsGold[i]) + " because of RE at " + cur);
          costReduce++;
        }
      }
    }

    // COST OF SHIFT
    // "number of gold arcs s.t. modifier=N0 and head in stack"
    // + "number of gold arcs s.t. head=N0 and modifier in stack"
    int costShift = 0;
    if (!cur.canShift(sentLen)) {
      if (SHOW_TRANSITION_SYSTEM)
        System.out.println("can't SH at " + cur);
      costShift = -1;
    } else {
      for (int i = 0; i < arcsGold.length; i++) {
        int h = getHeadOfArcAction(arcsGold[i]);
        int m = getModifierOfArcAction(arcsGold[i]);
        if ((m == N0 && contains(fh.getStackContents(), h))
            || (h == N0 && contains(fh.getStackContents(), m))) {
          if (SHOW_TRANSITION_SYSTEM)
            System.out.println("unreachable edge: " + showAction(arcsGold[i]) + " because of SH at " + cur);
          costShift++;
        }
      }
    }

    int[] costs = new int[] { costArcLeft, costArcRight, costReduce, costShift };
    if (SHOW_TRANSITION_SYSTEM) {
//      System.out.println("costs: " + Arrays.toString(costs));
      System.out.println("costs: {AL=" + costArcLeft
          + ", AR=" + costArcRight
          + ", RE=" + costReduce
          + ", SH=" + costShift
          + "}");
    }
    return costs;
  }
  
  /** This is how you memoize work across various feature functions */
  public static class FeatureHelper {
    private int[] words;  // one per word, so if you have 1-indexed word ids, subtract one before uisng as an index
    private int[] pos;
    
    private State state;
    private int[] histOnlyArcs;
    private int S0h = -2;  // S0 head

    public FeatureHelper(State s, int[] words, int[] pos) {
      if (words.length != pos.length)
        throw new IllegalArgumentException();
      this.state = s;
      this.words = words;
      this.pos = pos;
    }
    
    public int sentenceLength() {
      return words.length;
    }
    
    public int w(int i) {
      if (i < 1) return i;
      if (i > words.length)
        return -3;  // </s>
      return words[i-1];
    }
    public int p(int i) {
      if (i < 1) return i;
      if (i > words.length)
        return -3;  // </s>
      return pos[i-1];
    }
    public int wp(int i) {
      return Hash.mix(w(i), p(i));
    }
    
    public State getState() {
      return state;
    }
    
    public int getS0h() {
      if (S0h == -2) {
        S0h = state.s0Head();
      }
      return S0h;
    }
    
    /** returns the stack s.t. the last element in the array is the head of the stack. */
    public int[] getStackContents() {
      int s = 0;
      for (Stack st = state.stack; st != null; st = st.prev)
        s++;
      int[] c = new int[s];
      for (Stack st = state.stack; st != null; st = st.prev)
        c[--s] = st.item;
      return c;
    }
    
    public int[] getHistoryOnlyArcs() {
      if (histOnlyArcs == null) {
        histOnlyArcs = state.getHistory(true);
      }
      return histOnlyArcs;
    }
  }
  
  interface Semiring {
    double otimes(double a, double b);
    double oplus(double a, double b);
    double one();
    double zero();
    double multiplicativeInverse(double a);
    double toProbability(double a);
  }
  static class LogSemiring implements Semiring {
    public double otimes(double a, double b) {
      return a + b;
    }
    public double oplus(double a, double b) {
      // TODO exp(1+x) trick
      return Math.log(Math.exp(a) + Math.exp(b));
    }
    public double one() {
      return 0;
    }
    public double zero() {
      return Double.NEGATIVE_INFINITY;
    }
    public double multiplicativeInverse(double a) {
      return -a;
    }
    public double toProbability(double a) {
      return Math.exp(a);
    }
  }
  
  private double[] theta;
  private double[] gradient;
  private double[] gradientSS;  // g[i] * g[i] used for adagrad
  private double adaGradEps = 1e-8;
  private Alphabet<String> alph;
  private int beamSize = 8;
  private Semiring sr = new LogSemiring();
  
  public ShiftReduce(int featureDim) {
    this.theta = new double[featureDim];
    this.gradient = new double[featureDim];
    this.gradientSS = new double[featureDim];
    this.alph = new Alphabet<>();
  }
  
  public void clearGradient() {
    Arrays.fill(gradient, 0);
  }
  
  public void applyGradient(double learningRate) {
    for (int i = 0; i < gradient.length; i++) {
      theta[i] += learningRate * gradient[i] / Math.sqrt(gradientSS[i] + adaGradEps);
      gradientSS[i] += gradient[i] * gradient[i];
    }
  }
  
  public int[] intern(String[] tokens) {
    int[] t = new int[tokens.length];
    for (int i = 0; i < t.length; i++)
      t[i] = alph.lookupIndex(tokens[i]);
    return t;
  }
  public String[] outern(int[] tokens) {
    String[] t = new String[tokens.length];
    for (int i = 0; i < t.length; i++)
      t[i] = alph.lookupObject(tokens[i]);
    return t;
  }
  
  public double score(State s) {
    double d = 0;
    int a = getActionType(s.action);
    assert a < 4 : "action too big: " + s.action;
    int n = s.features.size();
    for (int i = 0; i < n; i++) {
      int fx = s.features.get(i);
      int f = fx * 4 + a;
      f = Math.floorMod(f, theta.length);
      d += theta[f];
    }
    return d;
  }
  
  public void accumGradient(State s, double scale) {
    int a = getActionType(s.action);
    assert a < 4 : "action too big: " + s.action;
    int n = s.features.size();
    for (int i = 0; i < n; i++) {
      int fx = s.features.get(i);
      int f = fx * 4 + a;
      f = Math.floorMod(f, theta.length);
      gradient[f] += scale;
    }
  }
  
  private IntArrayList computeFeatures(FeatureHelper fh) {
    IntArrayList features = new IntArrayList();
    zhangNivre2011_singleWords(fh, features);
    zhangNivre2011_pairWords(fh, features);
    zhangNivre2011_tripleWords(fh, features);
    return features;
  }

  public void generateActions(State cur, List<State> next, int[] words, int[] pos) {
    FeatureHelper fh = new FeatureHelper(cur, words, pos);
    IntArrayList features = computeFeatures(fh);

    if (cur.canArcLeft()) {
      State al = cur.arcLeft(0);
      al.features = features;
      al.psi = score(al);
      al.alpha = sr.otimes(cur.alpha, al.psi);
      next.add(al);
    }

    if (cur.canArcRight(words.length)) {
      State ar = cur.arcRight(0);
      ar.features = features;
      ar.psi = score(ar);
      ar.alpha = sr.otimes(cur.alpha, ar.psi);
      next.add(ar);
    }

    if (cur.canReduce()) {
      State re = cur.reduce();
      re.features = features;
      re.psi = score(re);
      re.alpha = sr.otimes(cur.alpha, re.psi);
      next.add(re);
    }

    if (cur.canShift(words.length)) {
      State sh = cur.shift();
      sh.features = features;
      sh.psi = score(sh);
      sh.alpha = sr.otimes(cur.alpha, sh.psi);
      next.add(sh);
    }
  }
  
  public void step(int[] words, int[] pos, int[] heads, int[] deprels) {
    
    int T = words.length;

    // forwards pass + beam search
    State[][] y = new State[T+2][];
    y[0] = new State[] { new State() };
    y[T-1] = new State[] { new State() };
    List<State> next = new ArrayList<>();
    for (int t = 1; t < y.length; t++) {
      
      next.clear();
      for (int i = 0; i < y[t-1].length; i++) {
        State prev = y[t-1][i];
        generateActions(prev, next, words, pos);
      }
      
      // TODO Merge by kernel features
      Collections.sort(next, State.BY_ALPHA);
      
      int b = Math.min(beamSize, next.size());
      y[t] = new State[(t == y.length - 1) ? 1 : b];
      for (int j = 0; j < y[t].length; j++)
        y[t][j] = next.get(j);
    }
    
    // Z
    double Z = sr.zero();
    int last = y.length - 1;
    for (int i = 0; i < y[last].length; i++)
      Z = sr.oplus(Z, y[last][i].alpha);
    double Zinv = sr.multiplicativeInverse(Z);

    // backwards pass + log(Z) part of gradient
    clearGradient();
    for (int t = y.length-1; t > 0; t--) {
      for (int j = 0; j < y[t].length; j++) {
        State cur = y[t][j];
        cur.prev.beta += sr.otimes(cur.psi, cur.beta);
        
        double marginalProb = sr.one();
        marginalProb = sr.otimes(marginalProb, cur.alpha);
        marginalProb = sr.otimes(marginalProb, cur.psi);
        marginalProb = sr.otimes(marginalProb, cur.beta);
        marginalProb = sr.otimes(marginalProb, Zinv);
        marginalProb = sr.toProbability(marginalProb);
        
        accumGradient(cur, -marginalProb);
//        int n = cur.features.size();
//        for (int i = 0; i < n; i++) {
//          int feat = cur.features.get(i);
//          gradient[feat] -= marginalProb;
//        }
      }
    }
    
    // Actual/oracle features part of gradient
    State oracle = arcEagerDynamicOracleTrajectory(y[0][0], heads, deprels, words, pos);
    for (State s = oracle; s != null; s = s.prev) {
      if (s.action >= 0) {
        assert s.prev != null;
        s.features = computeFeatures(new FeatureHelper(s.prev, words, pos));
        accumGradient(s, +1);
//      int n = s.features.size();
//      for (int i = 0; i < n; i++) {
//        int feat = s.features.get(i);
//        gradient[feat] += 1;
//      }
      }
    }
    
    applyGradient(0.1);
  }
  
  public boolean oracleHasPerfectRecall(int[] heads, int[] deprels, int[] words, int[] pos) {
    if (SHOW_TRANSITION_SYSTEM)
      Log.info("heads: " + Arrays.toString(heads));
    State t = arcEagerDynamicOracleTrajectory(new State(), heads, deprels, words, pos);
    int[] g = convertToArcs(heads, deprels);
    int[] p = t.getHistory(true);
    int r = recallAbs(g, p);
    assert r <= g.length;
    if (SHOW_TRANSITION_SYSTEM) {
      System.out.println(r + " / " + g.length);
      System.out.println("g: " + showActions(g));
      System.out.println("p: " + showActions(p));
    }
    return r == g.length;
  }

  public static List<File> trainFiles() {
    File p = new File("/home/travis/code/universal-dependencies/v1.3/ud-treebanks-v1.3/UD_English");
    return Arrays.asList(new File(p, "en-ud-train.conllu"));
  }
  
  public static class ConllUSentence {
    String[] form;
    String[] lemma;
    String[] upos;
    String[] xpos;
    String[][] feats;
    int[] heads;
    String[] deprels;
    String[] secondaryDeps;
    String[] misc;
    
    public ConllUSentence(List<String> lines) {
      int n = lines.size();
      form = new String[n];
      upos = new String[n];
      xpos = new String[n];
      heads = new int[n];
      deprels = new String[n];
      for (int i = 0; i < n; i++) {
        String[] t = lines.get(i).split("\t");
        if (t.length != 10)
          throw new RuntimeException("not Conll-U format: " + lines.get(i));
        form[i] = t[1];
        upos[i] = t[3];
        xpos[i] = t[4];
        heads[i] = Integer.parseInt(t[6]);
        deprels[i] = t[7];
      }
    }
    
    public List<String> getHumanReadableDeps() {
      List<String> d = new ArrayList<>();
      for (int i = 0; i < heads.length; i++) {
        String h = "ROOT";
        if (heads[i] > 0)
          h = form[heads[i]-1] + "-" + heads[i];
        String e = deprels[i] + "(" + h + ", " + form[i] + "-" + (i+1) + ")";
        d.add(e);
      }
      return d;
    }
    
    public List<Pair<IntPair, IntPair>> findCrossingEdges() {
      List<IntPair> es = new ArrayList<>();
      for (int i = 0; i < heads.length; i++) {
        es.add(new IntPair(heads[i], i+1));
        es.add(new IntPair(i+1, heads[i]));
      }
      
      List<Pair<IntPair, IntPair>> crossing = new ArrayList<>();
      int n = es.size();
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
          if (i == j) continue;
          IntPair a = es.get(i);
          IntPair b = es.get(j);
          boolean cr = false;
          cr |= a.first < b.first && b.first < a.second && a.second < b.second;
          cr |= b.first < a.second && a.second < b.second && b.second < a.second;
          IntPair t = a; a = b; b = t;
          cr |= a.first < b.first && b.first < a.second && a.second < b.second;
          cr |= b.first < a.second && a.second < b.second && b.second < a.second;
//          Log.info("a=" + a + " b=" + b);
          if (cr)
            crossing.add(new Pair<>(a, b));
        }
      }
      return crossing;
    }
  }
  
  public static class ConllUSentenceIterator implements Iterator<ConllUSentence> {
    private BufferedReader r;
    private List<String> curLines;
    
    public ConllUSentenceIterator(File f) throws IOException {
      r = FileUtil.getReader(f);
      curLines = new ArrayList<>();
      advance();
    }

    private void advance() throws IOException {
      curLines.clear();
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (line.isEmpty())
          break;
        curLines.add(line);
      }
    }

    @Override
    public boolean hasNext() {
      return !curLines.isEmpty();
    }

    @Override
    public ConllUSentence next() {
      ConllUSentence c = new ConllUSentence(curLines);
      try { advance(); }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      return c;
    }
  }

  public static void main(String[] args) throws Exception {
    Log.info("starting...");
    oldMain(args);
//    SHOW_TRANSITION_SYSTEM = true;
    
    int featureDim = 1<<20;
    ShiftReduce model = new ShiftReduce(featureDim);
    model.alph.lookupIndex("dsyn");
    
    // Read in the data
    List<ConllUSentence> sentences = new ArrayList<>();
    for (File f : trainFiles()) {
      Log.info("reading from " + f.getPath());
      ConllUSentenceIterator itr = new ConllUSentenceIterator(f);
      while (itr.hasNext())
        sentences.add(itr.next());
    }
    Log.info("done reading data");

    TimeMarker tm = new TimeMarker();
    int toks = 0;
    int g = 0;
    int nonprojective = 0;
    for (ConllUSentence s : sentences) {
      toks += s.form.length;

      if (SHOW_TRANSITION_SYSTEM) {
        System.out.println(Arrays.toString(s.form));
        for (String e : s.getHumanReadableDeps())
          System.out.println(e);
        System.out.println();
      }

      List<Pair<IntPair, IntPair>> crossing = s.findCrossingEdges();
      if (!crossing.isEmpty()) {
        nonprojective++;
        if (nonprojective % 10 == 0)
          System.out.println(nonprojective + " / " + (g+nonprojective) + " are nonprojective!");
        if (SHOW_TRANSITION_SYSTEM) {
          System.out.println("crossing: " + crossing);
          System.out.println();
        }
        continue;
      }

      Arrays.fill(s.deprels, "dsyn");

      if (DEBUG_GOLD_EDGES != null) {
        DEBUG_GOLD_EDGES.clear();
        for (int i = 0; i < s.heads.length; i++) {
          IntPair e = new IntPair(s.heads[i], i+1);
          DEBUG_GOLD_EDGES.add(e);
        }
      }

      int[] words = model.intern(s.form);
      int[] pos = model.intern(s.upos);
      int[] deprels = model.intern(s.deprels);
      boolean good = model.oracleHasPerfectRecall(s.heads, deprels, words, pos);
      if (good) {
        g++;
      } else {
        System.out.println(g + " good before this");
        System.out.println(Arrays.toString(s.heads));
        System.out.println(Arrays.toString(s.deprels));
        throw new RuntimeException("derp");
      }

      model.step(words, pos, s.heads, deprels);


      if (tm.enoughTimePassed(5)) {
        int tps = (int) (toks / tm.secondsSinceFirstMark());
        Log.info("parsed " + toks + " tokens, " + tps + " tokens per second");
      }
    }
    System.out.println(g + " good, " + nonprojective + " nonprojective");
    Log.info("done");
  }

  public static void oldMain(String[] args) {
    int featureDim = 1<<20;
    ShiftReduce model = new ShiftReduce(featureDim);

//    int[] words = model.intern(new String[]   { "ROOT", "John",  "loves", "Mary", "."    });
//    int[] pos = model.intern(new String[]     { "ROOT", "NNP",   "VBZ",   "NNP",  "."    });
//    int[] heads = new int[]                   { 0,       2,       0,       2,      2     };
////    int[] deprels = model.intern(new String[] { "ROOT", "nsubj", "root",  "dobj", "punc" });
//    int[] deprels = new int[]                 { 0,       0,       0,       0,      0     };
    
//    int[] words = model.intern(new String[]   { "ROOT", "He",  "wrote", "her", "a",  "letter", "."    });
//    int[] pos = model.intern(new String[]     { "ROOT", "PRP", "VBD",   "PRP", "DT", "NN",     "."    });
//    int[] heads = new int[]                   { -1,      2,     0,       2,     5,    2,        2     };
//    int[] deprels = new int[]                 {  0,      0,     0,       0,     0,    0,        0     };

    int[] words = model.intern(new String[]   { "He",  "wrote", "her", "a",  "letter", "."    });
    int[] pos = model.intern(new String[]     { "PRP", "VBD",   "PRP", "DT", "NN",     "."    });
    int[] heads = new int[]                   {  2,     0,       2,     5,    2,        2     };    // 1-indexed with ROOT=0
    int[] deprels = new int[]                 {  0,     0,       0,     0,    0,        0     };
    
    if (SHOW_TRANSITION_SYSTEM) {
      Log.info("words: " + Arrays.toString(model.outern(words)));
      Log.info("heads: " + Arrays.toString(heads));
    }
    
    int a0 = arcAction(12, 5, 3);
    assert 12 == getHeadOfArcAction(a0);
    assert 5 == getModifierOfArcAction(a0);
    assert 3 == getLabelOfArcAction(a0);
    

    State t = arcEagerDynamicOracleTrajectory(new State(), heads, deprels, words, pos);
    
    if (SHOW_TRANSITION_SYSTEM) {
      System.out.println();
      for (State s : t.reverse())
        System.out.println(s);

      System.out.println();
      for (State s : t.reverse())
        System.out.println(showAction(s.action));
    }
    
    int[] g = convertToArcs(heads, deprels);
    int[] p = t.getHistory(true);

    if (SHOW_TRANSITION_SYSTEM) {
      System.out.println();
      System.out.println("goldArcs: " + showActions(g));
      System.out.println("oracleArcs: " + showActions(p));
      System.out.println("recall: " + recallAbs(g, p) + " / " + g.length);
    }

    assert model.oracleHasPerfectRecall(heads, deprels, words, pos);
    
    /*
     * Beam search as Variational Message Passing, aka
     * SPARSE FORWARD-BACKWARD USING MINIMUM DIVERGENCE BEAMS FOR FAST TRAINING OF CONDITIONAL RANDOM FIELDS
     * https://people.cs.umass.edu/~mccallum/papers/sparse-fb.pdf
     * 
     * The variational family is a sum of point masses.
     * Key: they compute all items which might go on the beam and keep enough s.t. KL(pointwise||full) < epsilon
     *   If there are N items generated at every step, you pay O(N log(N)) for sorting rather than O(N log(B)), but this isn't the expensive part.
     *   The expensive part is computing the next factor (global features) on the top B rather than top N.
     *   
     * Variational family proposed by sparse-fb:
     * q(y) = \sum_{i in Beam} q_i delta_i(y)
     *
     * The point I was trying to make was that including a "backoff to a uniform dist" might be useful.
     * q_{alt}(y) = lambda * q(y) + (1-lambda) * Unif(y)
     * The reason to do this is that optimizing over {Beam, lambda} is not much harder than {Beam} and might yield lower KL.
     * More intuitively, lambda corresponds to global feature muting, which I think is essential if we are going to backoff to local learning.
     * 
     * Remember, I want to have a lambda per global feature, not a lambda per state.
     * One lambda per state is the naive case which can work, but I would like to mute simple global features less than complex ones.
     * 
     * Lets do the 1st order HMM.
     * local factor:            phi(y(t,i))
     * 1st order global factor: phi(y(t,i), y(t-1,j))
     * There are T tags and the beam is size B
     * belief(y(t,i))
     *   = lambda * I(y(t,i) is in beam(t))
     *   + (1-lambda) * 1/T
     *
     * message(y(t,i) => y(t+1,j))
     *   = belief(y(t,i)) * phi(y(t,i), y(t+1,j))
     *   = lambda * I(y(t,i) is in beam(t)) * phi(y(t,i), y(t+1,j))   # beam message
     *     + (1-lambda) * 1/T * phi(y(t,i), y(t+1,j))                 # off-beam message
     * message(y(t,*) => y(t+1,j))
     *   = sum_i message(y(t,i) => y(t+1,j))
     * message(local => y(t+1,j))
     *   = phi(y(t+1),j)
     * 
     * belief(y(t+1,*)) = argmin_{Beam,lambda} KL(q_{Beam,lambda} || (\sum_j message(y(t,*) => y(t+1,j)) * message(local => y(t+1,j))) )
     * 
     * Analysis:
     * Does this scheme lead to decreasing lambda?
     * Maybe, maybe not.
     * Ex. Yes: local factor is uniform distribution; phi is a stochastic permutation matrix; and B << T*T
     * Ex. No: local factor scale >> global factor scale; local factor message entropy is low ("fits in B").
     * 
     * This is actually sort of clear!
     * 
     * Difficulty: Computing off-beam messages!
     * message_{off-beam}(y(t,*) => y(t+1,j))
     *   = (1-lambda)/T \sum_i phi(y(t,i), y(t+1,j))
     *   = (1-lambda)/T * columnSums(j)
     * We could just store the columnSums(phi) as a separate vector, update it when phi is updated.
     *
     * 
     * What are the messages for shift-reduce parsing features?
     * Every y(t,i) is a configuration (State)
     *
     * message(f(y(t,i) => y(t+1,j)) = exp(theta * f(y(t,i), a, x))
     *   s.t. y(t+1,j) = apply(a, y(t,i))
     * 
     * The hard part is the off-beam messages:
     *   (1-lambda)/numActions \sum_i message(y(t,i), y(t+1,j))
     *   (1-lambda)/numActions \sum_i exp(theta * f(y(t,i), a(t+1,j), x))
     *   (1-lambda)/numActions Z(a(t+1,j), x)
     * This is a loop over "all states which could lead up to taking action a(t+1,j) in sentence x"
     * We know that this is too hard to do naively or with dynamic programming!
     * We must use tricks like we did in the HMM case, something like pre-computing column sums.
     * Perhaps we can estimate Z(a(t+1,j), x) with another function?
     *   Something like computing the distribution of observed Z values conditioned on some features of a(t+1,j) and x, store means
     *   Or use function approximator? Again, estimating the mean. Same idea as above but perhaps more compact than a table.
     *   Remember that we can decompose this over features: compute a Z table for something like "a.type * S[0].word * N[0].word" rather than all of a(t+1,j)*x
     *
     *
     * So, assuming I was willing to compute all this junk, this would give me a variational distribution over states (configurations).
     * This leaves off the issue of training!
     * ERMA is the natural option, but requires back-propping through message passing, including Z(a(t+1,j),x)!!!
     * I could just do variational EM (E-step uses q instead of p).
     */
    
    
    
  }
  

}
