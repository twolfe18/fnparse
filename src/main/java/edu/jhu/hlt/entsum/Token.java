package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.util.MultiMap;

public class Token implements Serializable {
  private static final long serialVersionUID = -4813744691315373711L;

  public int word;
  public byte pos;
  public byte ner;
  public byte dParentLabel;
  public short dParentNode;
  public short dLeftChildNode;
  public short dRightSibNode;

  /*
   * TODO Have a new method which uses 1-indexing with n+1 Token's per sentence
   * This allows you to have sentence[0] as the root, get children of root easily
   */
  
  public static Token[] readConll_0IndexedNoRoot(List<String> lines, MultiAlphabet alph) {
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
      sent[i].ner = -1;
      sent[i].dParentNode = i2s(Integer.parseInt(c[6])-1);
      sent[i].dParentLabel = i2b(alph.dep(c[7]));
      sent[i].dLeftChildNode = -1;
      sent[i].dRightSibNode = -1;
      
      int p = sent[i].dParentNode;
      p2c.add(p, i);
      if (leftChild[p] < 0)
        leftChild[p] = i;   // i is increasing, so set once/first, aka left-most
    }
    
    // Set left child
    for (int i = 0; i < n; i++)
      if (leftChild[i] >= 0)
        sent[i].dLeftChildNode = i2s(leftChild[i]);
    
    // Set right sib
    for (int p : p2c.keySet()) {
      List<Integer> cs = p2c.get(p);
      for (int i = 1; i < cs.size(); i++) {
        int c1 = cs.get(i-1);
        int c2 = cs.get(i);
        sent[c1].dRightSibNode = i2s(c2);
      }
    }

    return sent;
  }
  
  public static class ConllFileReader implements Iterator<Token[]>, AutoCloseable {
    
    private MultiAlphabet a;
    private BufferedReader r;
    private List<String> curLines;
    
    public ConllFileReader(File f, MultiAlphabet a) throws IOException {
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
      boolean done = false;
      for (String line = r.readLine(); line != null && !done; line = r.readLine()) {
        if (line.isEmpty()) {
          done = true;
        } else {
          curLines.add(line);
        }
      }
      assert done : "malformed";
    }

    @Override
    public boolean hasNext() {
      return !curLines.isEmpty();
    }

    @Override
    public Token[] next() {
      Token[] t = readConll_0IndexedNoRoot(curLines, a);
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
}