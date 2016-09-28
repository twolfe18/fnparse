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
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LL;
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
  private int beamSize = 32;
  private Semiring sr = new LogSemiring();
  private Counts<String> events = new Counts<>();
  
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
    events.increment("applyGradient");
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
    events.increment("scoreState");
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
    events.increment("accumGradient");
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
    events.increment("featureComputation");
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
    
    events.update("actionGeneration", next.size());
  }
  
  public void step(int[] words, int[] pos, int[] heads, int[] deprels) {
    events.increment("step");
    
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
        int sps = (int) (g / tm.secondsSinceFirstMark());
        Log.info("parsed " + toks + " tokens, " + tps + " tokens per second, " + sps + " sentence per second");
        System.out.println(model.events);
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
  }

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


  /*
   * Before I write any more code, I need to have a clear idea of how to compute the off-beam messages for one feature.
   * 
   * First lets define variables:
   * 
   * f_i = a vector variable representing the features at state i
   *       there is one index per feature, e.g. f_i[0] = "stack[0].leftmostModifier.word"
   * a_i = a categorical variable over action i (domain is [AL,AR,RE,SH]
   *
   * We can exactly represent the distribution over a_i during EM.
   * For f_i, we use the variational family
   *  q(f_i) = 
   *    alpha * 1/B * \sum_{i in B} I(state i on the beam has feature f_i)
   *    + (1-alpha) * u_i
   * What is u_i?
   * It is a random variable of course. Same shape as f_i.
   * We optimize u_i to minimize KL divergence from the true message.
   * 
   * Wait, why even use a beam if we can use f_i?
   * Ah, I think the answer is in p(f_{i+1} | a_i, f_i)
   * We don't have to specify this in normal beam search (variational distribution using states)
   * p("stack[0].word"_{i+1} | SH, f_i) = ???
   * Simple cases will be covered, e.g.
   *   f_{i+1}="stack[0].word" | a_i=SH, f_i="buf[0].word"
   *   f_{i+1}="stack[0].word" | a_i=RE, f_i="stack[1].word"
   *   f_{i+1}="stack[0].word" | a_i=AL, f_i="stack[1].word"
   *   f_{i+1}="stack[0].word" | a_i=AR, f_i="buf[0].word"
   * Complex cases will use information which is not contained in f_i.
   * Using the beam variational family lets you answer these questions, always.
   * But, since this family is so size-constrained, q(f_i) is not perfect.
   *
   * What if we just learned a unary factor on f_i?
   * q(f_i) ~ beam + resid
   * p(a_i) = model(q(f_i))
   * q(f_{i+1}) = apply(a_i, q(f_i).beam) + apply(a_i, q(f_i).resid)
   * 
   * To clarify this, I think I need to extend the definition of f_i to include all features used to compute actually parameterized features.
   * e.g. "stack[0].leftmostModifier" | RE needs to know stack[1].leftmostModifier
   * As Huang and Sagae (2010) http://www.aclweb.org/anthology/P10-1110
   * point out: f_i only needs to be extended with extra facts for computing what happens on RE.
   * There is not "state" on the buf, only the stack.
   * Their DP alg is based on state merging based on kernel features. This is certainly a good thing to do.
   * I don't think that work is "complete", in the sense that all possible sources of evidence are used to update q(f_{i+1})
   * It is certainly more complete than regular beam search with no state merging.
   * Since it is non-prob it is also using early update (I think this was pre-VFP)
   * 
   * 
   * I am now a bit confused why we can't just do EP: compute expectations over the features.
   * Likelihood factorizes over features, so the messages factorize over features.
   * Need a table of (f_{i+1}, a_i, f_i) weights... or something like that.
   * Lets say we want to add the feature "stack[0].lc" to the model ("lc" == "left child", aka "leftmost modifier")
   * We need to specify how the feature is updated under all actions:
   *    buf[0]=X & AR => stack[0].lc = min(prev(stack[0].lc), X)
   * where prev(*) is a function of an index in f_i which returns that indices value at f_{i-1}
   * 
   * Once you have enumerated all rules, f_i^R are all of the RHSs of those rules
   * and f_i^I is (all vars mention in a rule) \setminus f_i^R
   * Ah!
   * but f_i^I also need update rules!
   * So when you add a rule, you really have to add its transitive dependencies.
   * What is one such rule where we couldn't do this?
   * idk, it doesn't seem that hard.
   * 
   * Going through Zhang and Nivre (2011) features, the hard ones seem to be:
   * a) distance between stack[0] and buf[0]
   * b) valency of stack[0] (number of left or right children)
   * c) parent, lc, and rc of stack[0] and buf[0]
   * 
   * ###                    # expressions on the RHS of = are evaluated on f_i, so (probably) no need for prev(*)
   * stack[0].distance | AR = 0   # the top of buf is pushed onto the stack
   * stack[0].distance | AL = stack[1].distance
   * stack[0].distance | RE = stack[1].distance
   * stack[0].distance | SH = 0
   * 
   * stack[0].lValence | AR = stack[0].lValence
   * stack[0].lValence | AL = stack[1].lValence   # head of stack is popped, f_{i+1}.buf[0].lValence = f_i.buf[0].lValence + 1
   * stack[0].lValence | RE = stack[1].lValence   # head of stack is popped
   * stack[0].lValence | SH = 0
   * 
   * stack[0].rValence | AR = 0   # head of buf is pushed onto stack, but f_{i+1}.stack[1].rValence = f_i.stack[0].rValence + 1
   * stack[0].rValence | AL = stack[1].rValence
   * stack[0].rValence | RE =
   * stack[0].rValence | SH =
   * 
   * I think I need to reframe this so that I state updates to a block of f_i condition on each action:
   * I'm going to use f_c = f_{i+1} and f_p = f_i which correspond to the "prev" and "cur" mnemonic
   * 
   * AR wrt valence {
   *   f_c.stack[0].valence = f_p.buf[0].valence
   *   f_c.stack[1].valence = f_p.stack[0].valence + 1
   *   f_c.stack[i+1].valence = f_p.stack[i].valence        FORALL i >= 1
   *   f_c.buf[i].valence = 0                               FORALL i
   * }
   * AL wrt valence {
   *   f_c.stack[i+1].valence = f_p.stack[i].valence        FORALL i
   *   f_c.buf[0].valence = f_p.buf[0].valence + 1
   * }
   * RE wrt valence {
   *   f_c.stack[i].valence = f_p.stack[i+1].valence        FORALL i
   * }
   * SH wrt valence {
   *   f_c.stack[0].valence = 0
   *   f_c.stack[i+1].valence = f_p.stack[i].valence        FORALL i
   * }
   * 
   * AR wrt distance {
   *   f_c.stack[0].distance = 1
   *   f_c.stack[i+1].distance = f_p.stack[i].distance      FORALL i
   * }
   * AL wrt distance {
   *   f_c.stack[i].distance = f_p.stack[i+1].distance      FORALL i
   * }
   * RE wrt distance {
   *   f_c.stack[i].distance = f_p.stack[i+1].distance      FORALL i
   * }
   * SH wrt distance {
   *   f_c.stack[0].distance = 1
   *   f_c.stack[i+1].distance = f_p.stack[i].distance + 1  FORALL i
   * }
   * 
   * 
   * So this makes it seem like we can really do exact dynamic programming.
   * What about conjoining, say valency, with the word.
   * Can we do the joint table?
   * Doesn't seem that hard...
   * AR wrt valenceAndWord {
   *   f_c.stack[0].valenceAndWord = f_p.buf[0].valenceAndWord
   *   ...
   * }
   * 
   * 
   * Ah, perhaps here is the problem:
   * The size of f_{i+1}^{valenceAndWord} grows throughout inference.
   * Above, the "i" in f_c.stack[i].valenceAndWord is a template.
   * The real variables are like f_c.stack[2].valenceAndWord
   * This is now a distribution over W x N.
   * Its true that this distribution only takes values from the sentence, but it can still grow quickly.
   * We are back to the case where we want a beam for f_{i+1}^{valenceAndWord}
   * And we are back to the problem of off-beam messages for mu(phi_i(f_i, a_i) => f_{i+1})
   * So q(f_{i+1}^{valenceAndWord}) =
   *    alpha * beamOver(W x N, size=k)
   *    (1-alpha) * Uniform(W x N)        # this is naive! we could have a variational parameter in place of this Uniform
   * So mu(phi_i(f_i, a_i) => f_{i+1}) =
   *    alpha * mu(phi(a_i, beamOver(f_i,k)) => f_{i+1})      # use "AR wrt valenceAndWord" rules to update this, after each step compress beliefs (beam pruning)
   *    + (1-alpha) * mu(phi(a_i, Uniform(WxN)) => f_{i+1})   # 
   * This "message" is an abstract thing, what we really care about is q(f_{i+1})
   * We use the "AR wrt valenceAndWord" rules where we bind f_p values to either beamOver(f_i,k) or Uniform(WxK)
   * We compute the full set of values for f_{i+1}, which will be bigger than k,
   * Then we compress this into beamOver(f_{i+1},k) which is one part of q(f_{i+1})
   * We could choose alpha to optimize KL[q(f_{i+1}) || f_{i+1}],
   *   where f_{i+1} is what we got from mu(phi(a_i, q(f_i)) => f_{i+1}) BEFORE compressing/pruning to the beam representation q(f_{i+1})
   *   ...but this requires more math
   * We could also use the Pal,Sutton,McCallum trick of setting k s.t. KL < epsilon
   *   ...but we need to stop somewhere!
   * We could jointly set k,alpha to optimize some penalty on magnitude of k and KL divergence
   *   ...but now we're way off the rails, too complex, just hand tune k!
   *
   *
   * Lets make sure we have an example of computing off-beam messages for valenceAndWord
   * mu(phi(a_i, Uniform(WxN)) => f_{i+1}) =
   *   "p(f_c.stack[0].valenceAndWord | a_i, f_p)"
   *   where f_p ~ Uniform(WxN)
   *   so any time we see an equation like:
   *   f_c.stack[0].valenceAndWord = f_p.buf[0].valenceAndWord
   *   we need to lift it to a pointwise expression over Uniform(WxN)
   * Remember, the only reason that we're computing this is to again compress it in beamOver(f_{i+1},k)
   * So we can start off by computing the message from beamOver(f_i,k) and then re-scoring.
   * Remember, the values in beamOver(f_i,k) are full assignments:
   *   {stack[0].valenceAndWord=(0,"John"), stack[1].valenceAndWord=(1,"loves"), ...}
   * So unifying (1-alpha) * mu(phi(a_i,Uniform(WxN))    => f_{i+1})  # off-beam
   *        with    alpha  * mu(phi(a_i,beamOver(f_i,k)) => f_{i+1})  # beam
   * can be done by
   * 1) computing a list of full assignments derived from beamOver(f_i,k) and a_i
   * 2) adding in the score induced by phi(a_i,Uniform(WxN))
   *    score(
   *      {stack[0].valenceAndWord=(0,"John"), stack[1].valenceAndWord=(1,"loves"), ...}
   *    ) += log p(f_c.stack[0].valenceAndWord=(0,"John") | f_p.buf[0].valenceAndWord ~ Uniform(WxN))
   *      += log p(f_c.stack[1].......
   *
   * Revisiting the earlier idea, if we say that mu_i = Uniform(WxN)
   * and that we could choose other values for mu_i, then we could compute mu_i as:
   * forall config C in apply(a_{i-1}, q(f_{i-1})):
   *   mu_i[stack[0].valenceAndWord] += q(C.stack[0].valenceAndWord)
   *
   *
   *
   * AHH, going back to MUCH earlier,
   * I was having trouble working out what the off-beam messages would be while thinking about
   * the p(a|s) as a SINGLE factor, and it came out to a need to estimate a partition function
   * which summed over all CONFIGURATIONS (not variable configs like valenceAndWord), which was
   * going to be hard/impossible.
   * This was when I though that the BEAM would be over configurations, and the off-beam messages
   * would be a function of Uniform(ConfigurationSpace), which won't work well.
   * By breaking the p(a|s) factor down by module, I realized that I can have a beam-variation-approximation
   * per module, which makes things much easier.
   * 
   * 
   * To ensure that I'm in touch with my earlier goals, how does this get back to the goal of
   * being able to "trust" your global features?
   * 1) WITHOUT learning alpha: the smaller alpha is, the less "context" you have to look at...
   * 2) OMG, an obvious way to set alpha!
   * You are pruning mu(=>f_{i+1}) down to beamOver(f_{i+1},k)
   * That beam is sorted by the alphas (as in DP, cur.alpha = cur.psi + prev.alpha),
   * which represent probability mass.
   * You know what proportion of that mass is being pruned!
   * You can set alpha = (mass preserved by the k items on the beam) / (mass generated by mu(=>f_{i+1}))
   * 
   * THIS answers the question: the better the beam approximation, the more q(f_i) deviates from
   * what it would have been without considering the history!
   * If the beam approx is terrible, then your model has to learn to deal with
   * the fact that the "values" (aka f_p) fed to the features will be from Uniform(f_i)!
   */
  
  
  
  /**
   * Represents q(f_i) which is a distribution over s0.
   * 
   * When we compute q(f_{i+1}) from q(a_i) and q(f_i),
   * 1) b(f_{i+1}) += (q(AL) + q(RE)) * [Backoff[q(f_i)], q(f_i)]
   * 2) b(f_{i+1}) += (q(AR) + q(SH)) * [        q(f_i),  buf0  ]
   * 3) m(f_{i+1}) = marginalize(b(f_{i+1}))  # s0 values, but can be large
   * 4) t(f_{i+1}) = TopK(m(f_{i+1}))
   * 5) alpha_{i+1} = Weight(t(f_{i+1})) / Weight(m(f_{i+1}))
   * 6) q(f_{i+1}) = alpha_{i+1} * t(f_{i+1}) + (1-alpha_{i+1}) * Uniform(s0)
   * 
   * The tricky part is computing (1) and (2) efficiently.
   * 
   * Also, this assumes that buf0 is given.
   * Put another way, that i indexes |Beta|.
   * This transition system requires that the action space be (AL|RE)*(AR|SH)
   * If this is the case, a_prev is infinite.
   * Lines (1) and (2) don't really make sense anymore.
   */
  static class Stack0AssumingBuf0IsGivenB {
    
    static class StackBigram {
      double weight;
      String s1, s0;  // stack representation
      StackBigram(String s1, String s0, double w) {
        this.s1 = s1;
        this.s0 = s0;
        this.weight = w;
      }
    }
    
    static class BackoffDist {
      List<StackBigram> dist;
      // TODO: s0 -> [s1, weight] where the lists are sorted (decreasing) by weight
    }
    
    private Stack0AssumingBuf0IsGivenB f_prev;
    private double[] a_prev;
    
    private List<StackBigram> beliefs_beam;
    private BackoffDist beliefs_offbeam;      // This is given at the outset! Static resource!
    private double alpha;

    // TODO Have entries sorted by weight, decreasing
    // THIS is what we limit to size k.
    private Map<String, Double> s0_marginal_beliefs;
    
    
    
    
    public Stack0AssumingBuf0IsGivenB(Stack0AssumingBuf0IsGivenB f_prev, double[] a_prev, String buf0) {
      
      // SH {{{
      // beam message
      for (String prev_s0 : f_prev.s0_marginal_beliefs.keySet()) {
        double prev_s0_alpha = f_prev.s0_marginal_beliefs.get(prev_s0);
        StackBigram s2 = new StackBigram(prev_s0, buf0, prev_s0_alpha * a_prev[SH]);
        beliefs_beam.add(s2);
        // s0_marginal_beliefs[stackBigram.s0] += alpha * stackBigram.w
      }
      
      // offbeam message

      // }}}

    }
  }
  
  /*
   * Was I mistaken in assuming that you can decompose the variational distribution by feature aspect?
   * The problem appears to be that we can't just maintain a distribution over stack[0]
   * since the equations used to update it reference arbitrary information about the state.
   * 
   * Lets zoom in on stack[0] as an example.
   * How do we compute what q(f_{i+1}) after a SH if our beam doesn't have a dist over buf[0]?
   * Is this any easier if we don't take any information from buf, just stack?
   * (I'm thinking of whether the switch to the |B|-indexed transition system is worthwhile)
   * If this were the case, we could take buf[0] from x for SH, but for RE we would need to take stack[1] from an item in the beam.
   * This is perhaps do-able: beam stores a distribution over stacks.
   * Stacks are strings, can estimate this with a regular weighted FSA, roughly p(head, modifier)
   * 
   * So for computing messages, we would 
   */
  public static class Stack0AssumingBuf0IsGiven {
    static class Dist {
      LL<String> stack;
      double weight;
    }
    
    // f_i
    Stack0AssumingBuf0IsGiven f_prev;
    // a_i: problem is that this is now a distribution over the language (AL|RE)*(AR|SH)
    double[] a_prev;
    // x_i: assumes that i indexes |B|
    String buf0;

    // f_{i+1}
    List<Dist> beliefs_beam;
    double beliefs_offbeam;     // mass not captured by first k elements in beliefs_beam, evenly distributed over V tokens
    // 1 == beliefs_offbeam + sum_{i=1 to k} beliefs_beam[i].weight
    int k, V;
    
    
    
    public Stack0AssumingBuf0IsGiven(Stack0AssumingBuf0IsGiven f_prev, double[] a_prev, String buf0) {
      
      beliefs_beam = new ArrayList<>();
      for (int i = 0; i < f_prev.k; i++) {
        Dist stack_prev = f_prev.beliefs_beam.get(i);

        Dist stack_cur_AR_or_SH = new Dist();
        stack_cur_AR_or_SH.weight = stack_prev.weight * (a_prev[AR] + a_prev[SH]);
        stack_cur_AR_or_SH.stack = new LL<>(buf0, stack_prev.stack);
        beliefs_beam.add(stack_cur_AR_or_SH);
        
        Dist stack_cur_AL_or_RE = new Dist();
        stack_cur_AL_or_RE.weight = stack_prev.weight * (a_prev[AL] + a_prev[RE]);
        stack_cur_AL_or_RE.stack = stack_prev.stack.next;
        beliefs_beam.add(stack_cur_AL_or_RE);
      }
      
      // TODO Merge
      
      // TODO Add in offbeam message
      // What would the model do under the "Uniform" distribution?
      double Z = 0;
      for (Dist d : beliefs_beam) {
        
        // AR and SH push buf0
        // 
      }
    }
  }
  
  
  
  

  public static boolean areNormalizedProbs(double... ds) {
    double s = 0;
    for (int i = 0; i < ds.length; i++) {
      if (ds[i] < 0)
        return false;
      if (ds[i] > 1)
        return false;
      s += ds[i];
    }
    return Math.abs(1 - s) < 1e-8;
  }


  // Represents the variables this module cares about
  static class Dist {
    double weight;
    LL<String> stack;
    LL<String> buf;
  }
  
  static final int AL = 0;
  static final int AR = 1;
  static final int RE = 2;
  static final int SH = 3;

  public static class Stack1ModuleC {
    // distribution over fp.buf[0]? then we would need to take fp.buf[1] dist upon construction...
    // 
  }
  
  public static class Stack1ModuleB {
    // f_i
    Stack1ModuleB f_prev;
    // a_i
    double[] a_prev;        // beliefs about [AL, AR, RE, SH]
    // q(f_{i+1})
    List<Dist> f_cur_beam;  // contains all derived dists
    double f_cur_offbeam;   // prob mass captured by the first k items in f_cur_beam
    int k, V;                  // vocab size
    
    public Stack1ModuleB(Stack1ModuleB f_prev, double[] a_prev) {
      this.f_prev = f_prev;
      this.a_prev = a_prev;
      
      // Where do we get fp.buf[0] from?
      // This seems like this could be given at construction since it is known based on i...
      // not really, buf[0] is not known (with arg-eager definition)
      
      
    }
  }
    
  /**
   * Maintains a distribution over stack[0] and any features which can be derived from it.
   */
  public static class Stack1Module {

    List<Dist> fc_unpruned;
    List<Dist> fp_beam_beliefs;   // p(f_c.stack[0] == x) where x is a word
    double fp_offbeam_beliefs;              // probability mass not on the beam
    int V;                                  // vocabulary size, fp_offbeam_beliefs are distributed uniformly over this
    
    /*
     * LA and RE {
     *   fc.stack[i+1] = fp.stack[i]    FORALL i
     * }
     * RA and SH {
     *   fc.stack[0] = fp.buf[0]
     *   fc.stack[i+1] = fp.stack[i]    FORALL i
     * }
     */
    public void sendMessage(double pAL, double pAR, double pRE, double pSH) {
      assert areNormalizedProbs(pAL, pAR, pRE, pSH);

      /*
       * So we can start off by computing the message from beamOver(f_i,k) and then re-scoring.
       * Remember, the values in beamOver(f_i,k) are full assignments:
       *   {stack[0].valenceAndWord=(0,"John"), stack[1].valenceAndWord=(1,"loves"), ...}
       * So unifying (1-alpha) * mu(phi(a_i,Uniform(WxN))    => f_{i+1})  # off-beam
       *        with    alpha  * mu(phi(a_i,beamOver(f_i,k)) => f_{i+1})  # beam
       * can be done by
       * 1) computing a list of full assignments derived from beamOver(f_i,k) and a_i
       * 2) adding in the score induced by phi(a_i,Uniform(WxN))
       *    score(
       *      {stack[0].valenceAndWord=(0,"John"), stack[1].valenceAndWord=(1,"loves"), ...}
       *    ) += log p(f_c.stack[0].valenceAndWord=(0,"John") | f_p.buf[0].valenceAndWord ~ Uniform(WxN))
       *      += log p(f_c.stack[1].......
       */
      
      fc_unpruned = new ArrayList<>();
      
      // 1) compute a list of full assignments derived from beamOver(f_i,k) and a_i
      for (Dist fp : fp_beam_beliefs) {
        // AL and RE
        Dist fc = new Dist();
        fc.stack = fp.stack.next;
        fc.buf = fp.buf;
        fc.weight = fp.weight * (pAL + pRE);
        fc_unpruned.add(fc);

        // AR and SH
        fc = new Dist();
        fc.stack = null;
        fc.buf = null;
        fc.weight = fp.weight * (pAR + pSH);
        fc_unpruned.add(fc);
      }
      
      // 1b) merge equivalent fc Dists
      // TODO
      
      // 2) add in mu(phi(a_i, Uniform) => f_{i+1})
      for (Dist fc : fc_unpruned) {
        // For LA, the score of this dist assuming fp ~ Uniform is ???
        
      }
    }
  }
  

}
