package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.util.MultiMap;

/**
 * Compact dependency tree with POS tags (12 bytes + sizeof(pointer) per node/word).
 * For example, 300k length-33 sentences would take up 200 MB.
 * 
 * Includes parent, child, and sibling pointers.
 * 
 * Can read from CoNLL-X (e.g. parsey).
 *
 * @author travis
 */
public class Token implements Serializable {
  private static final long serialVersionUID = -4813744691315373711L;

  // 12 bytes = 4 + 1 + 1 + 2 + 2 + 2
  public int word;
  public byte pos;
  public byte depParentLabel;
  public short depParentNode;
  public short depLeftChildNode;
  public short depRightSibNode;

  /*
   * TODO Have a new method which uses 1-indexing with n+1 Token's per sentence
   * This allows you to have sentence[0] as the root, get children of root easily
   */
  
  public static int[] depths(Token[] sentence) {
    // Just do O(n*depth) algorithm of walking up from every token
    int[] d = new int[sentence.length];
    for (int i = 0; i < d.length; i++) {
      for (int p = i; p >= 0; p = sentence[p].depParentNode)
        d[i]++;
    }
    return d;
  }
  
  public static int[] distances(int source, Token[] sentence) {
    int n = sentence.length;
    int[] minDist = new int[n];
    Arrays.fill(minDist, n);
    
    // BFS from source
    // Entries are (node, distFromSource)
    BitSet seen = new BitSet(n);
    Deque<IntPair> q = new ArrayDeque<>();
    q.addLast(new IntPair(source, 0));
    seen.set(source);
    while (q.isEmpty()) {
      IntPair nd = q.pollFirst();
      int token = nd.first;
      int dist = nd.second;
      Token t = sentence[token];
      
      // Process
      minDist[token] = Math.min(minDist[token], dist);
      
      // Neighbors
      if (!seen.get(t.depParentNode)) {
        seen.set(t.depParentNode);
        q.addLast(new IntPair(t.depParentNode, dist+1));
      }
      for (int c = t.depLeftChildNode; c >= 0; c = sentence[c].depRightSibNode) {
        if (!seen.get(c)) {
          seen.set(c);
          q.addLast(new IntPair(c, dist+1));
        }
      }
    }
    
    return minDist;
  }
  
//  public static List<Integer> children(int node, Token[] sentence) {
//    List<Integer> children = new ArrayList<>();
//    for (int c = sentence[node].depLeftChildNode; c >= 0; c = sentence[c].depRightSibNode)
//      children.add(c);
//    return children;
//  }
  
  public static Token[] readConllx_0IndexedNoRoot(List<String> lines, MultiAlphabet alph) {
    int n = lines.size();
    Token[] sent = new Token[n];
    MultiMap<Integer, Integer> p2c = new MultiMap<>();
    int[] leftChild = new int[n];
    Arrays.fill(leftChild, -1);
    for (int i = 0; i < n; i++) {
      String[] c = lines.get(i).split("\t");

      int ii = Integer.parseInt(c[0]);
      assert i+1 == ii;
      assert ii < Short.MAX_VALUE;
      assert ii >= 0;

      sent[i] = new Token();
      sent[i].word = alph.word(c[1]);
      sent[i].pos = i2b(alph.pos(c[4]));
      sent[i].depParentNode = i2s(Integer.parseInt(c[6])-1);
      sent[i].depParentLabel = i2b(alph.dep(c[7]));
      sent[i].depLeftChildNode = -1;
      sent[i].depRightSibNode = -1;
      
      int p = sent[i].depParentNode;
      if (p >= 0) {
        p2c.add(p, i);
        if (leftChild[p] < 0)
          leftChild[p] = i;   // i is increasing, so set once/first, aka left-most
      }
    }
    
    // Set left child
    for (int i = 0; i < n; i++)
      if (leftChild[i] >= 0)
        sent[i].depLeftChildNode = i2s(leftChild[i]);
    
    // Set right sib
    for (int p : p2c.keySet()) {
      List<Integer> cs = p2c.get(p);
      for (int i = 1; i < cs.size(); i++) {
        int c1 = cs.get(i-1);
        int c2 = cs.get(i);
        sent[c1].depRightSibNode = i2s(c2);
      }
    }

    return sent;
  }
  
  public static class ConllxFileReader implements Iterator<Token[]>, AutoCloseable {
    
    private MultiAlphabet a;
    private BufferedReader r;
    private List<String> curLines;
    
    static List<String> allLines = new ArrayList<>();
    
    public ConllxFileReader(File f, MultiAlphabet a) throws IOException {
      this.a = a;
      r = FileUtil.getReader(f);
      curLines = new ArrayList<>();
      advance();
    }
    
    public MultiAlphabet getAlphabet() {
      return a;
    }
    
    private void advance() throws IOException {
      curLines.clear();
      for (String line = r.readLine(); true; line = r.readLine()) {
        if (line == null) {
          curLines = null;    // EOF
          return;
        } else if (line.isEmpty()) {
          return;
        } else {
          curLines.add(line);
        }
      }
//      assert false : "malformed";
    }

    @Override
    public boolean hasNext() {
//      return !curLines.isEmpty();
      return curLines != null;
    }

    @Override
    public Token[] next() {
      Token[] t = readConllx_0IndexedNoRoot(curLines, a);
      try {
        advance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return t;
    }

    @Override
    public void close() throws Exception {
      r.close();
    }
  }
  
  static byte i2b(int i) {
    if (i > Byte.MAX_VALUE || i < Byte.MIN_VALUE)
      throw new IllegalArgumentException();
    return (byte) i;
  }

  static short i2s(int i) {
    if (i > Short.MAX_VALUE || i < Short.MIN_VALUE)
      throw new IllegalArgumentException();
    return (short) i;
  }
  
  public static void test(ExperimentProperties config) throws Exception {
    File p = new File("/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum");
    File f = new File(p, "parsed-sentences-rare4/parsed.conll");
    MultiAlphabet a = new MultiAlphabet();
    List<Token[]> ts = new ArrayList<>();
    TimeMarker tm = new TimeMarker();
    long toks = 0;
    boolean show = config.getBoolean("show", false);
    try (ConllxFileReader iter = new ConllxFileReader(f, a)) {
      while (iter.hasNext()) {
        Token[] parse = iter.next();
        toks += parse.length;
        ts.add(parse);
        if (show) {
          for (int i = 0; i < parse.length; i++) {
            Token t = parse[i];
            String w = a.word(t.word);
            String pos = a.pos(t.pos);
            String parent = "NA";
            String edge = "NA";
            if (t.depParentNode >= 0) {
              parent = a.word(parse[t.depParentNode].word);
              edge = a.dep(t.depParentLabel);
            }
            List<String> children = new ArrayList<>();
            for (int c = t.depLeftChildNode; c >= 0; c = parse[c].depRightSibNode)
              children.add(a.word(parse[c].word));
            System.out.printf("% 3d\t%-24s %-12s %-24s %-12s %s\n", i, w, pos, parent, edge, children);
          }
          System.out.println();
        }
        if (tm.enoughTimePassed(2))
          Log.info("nSent=" + ts.size() + "\tnTok=" + toks + "\t" + Describe.memoryUsage());
      }
    }
    Log.info("done\tnSent=" + ts.size() + "\tnTok=" + toks + "\t" + Describe.memoryUsage());
    System.out.println(a);
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    test(config);
  }
}