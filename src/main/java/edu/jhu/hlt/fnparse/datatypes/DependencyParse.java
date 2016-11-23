package edu.jhu.hlt.fnparse.datatypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;

/**
 * Note: Punctuation is not parsed in the Stanford parser, so we mark its' head
 * as DependencyParse.PUNC.
 * 
 * @author travis
 */
public class DependencyParse implements Serializable {
  public static final long serialVersionUID = -5371460624361636628L;

  public static final int ROOT = -1;
  public static final int PUNC = -2;

  private int[] heads;
  private String[] labels;

  private transient int[] projLeft, projRight;
  private transient int[] depths;
  private transient int[][] children;
  private transient int hashCode = 0;

  // TODO Replace with custom Java serialization (e.g. see Span)
  public static final BiConsumer<DependencyParse, DataOutputStream> SERIALIZATION_FUNC = (deps, dos) -> {
    int n = deps.heads.length;
    try {
      dos.writeInt(n);
      for (int i = 0; i < n; i++)
        dos.writeInt(deps.heads[i]);
      for (int i = 0; i < n; i++) 
        dos.writeUTF(deps.labels[i]);
    } catch (Exception e) {
     throw new RuntimeException(e);
    }
  };
  public static final Function<DataInputStream, DependencyParse> DESERIALIZATION_FUNC = dis -> {
    try {
      int n = dis.readInt();
      int[] heads = new int[n];
      String[] labels = new String[n];
      for (int i = 0; i < n; i++)
        heads[i] = dis.readInt();
      for (int i = 0; i < n; i++)
        labels[i] = dis.readUTF();
      return new DependencyParse(heads, labels);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  };

  public DependencyParse(int[] heads, String[] labels) {
    if (heads.length != labels.length)
      throw new IllegalArgumentException();
    this.heads = heads;
    this.labels = labels;
  }
  
  public static DependencyParse fromConllx(List<String[]> conllx) {
    /* http://ilk.uvt.nl/conll/#dataformat
1  ID  Token counter, starting at 1 for each new sentence.
2 FORM  Word form or punctuation symbol.
3 LEMMA Lemma or stem (depending on particular data set) of word form, or an underscore if not available.
4 CPOSTAG Coarse-grained part-of-speech tag, where tagset depends on the language.
5 POSTAG  Fine-grained part-of-speech tag, where the tagset depends on the language, or identical to the coarse-grained part-of-speech tag if not available.
6 FEATS Unordered set of syntactic and/or morphological features (depending on the particular language), separated by a vertical bar (|), or an underscore if not available.
7 HEAD  Head of the current token, which is either a value of ID or zero ('0'). Note that depending on the original treebank annotation, there may be multiple tokens with an ID of zero.
8 DEPREL  Dependency relation to the HEAD. The set of dependency relations depends on the particular language. Note that depending on the original treebank annotation, the dependency relation may be meaningfull or simply 'ROOT'.
9 PHEAD Projective head of current token, which is either a value of ID or zero ('0'), or an underscore if not available. Note that depending on the original treebank annotation, there may be multiple tokens an with ID of zero. The dependency structure resulting from the PHEAD column is guaranteed to be projective (but is not available for all languages), whereas the structures resulting from the HEAD column will be non-projective for some sentences of some languages (but is always available).
10  PDEPREL Dependency relation to the PHEAD, or an underscore if not available. The set of dependency relations depends on the particular language. Note that depending on the original treebank annotation, the dependency relation may be meaningfull or simply 'ROOT'.
     */
    int n = conllx.size();
    int[] heads = new int[n];
    String[] labels = new String[n];
    for (int i = 0; i < n; i++) {
      String[] ar = conllx.get(i);
      heads[i] = Integer.parseInt(ar[6]) - 1;
      labels[i] = ar[7];
    }
    return new DependencyParse(heads, labels);
  }

  /**
   * tutils.LabeledDirectedGraph has globally indexed nodes, whereas this parse
   * is relatively indexed. You provide (start, end) to determine where to look
   * in the global indices.
   * @param tokenIndexFirst inclusive
   * @param tokenIndexLast inclusive
   */
  public DependencyParse(
      edu.jhu.hlt.tutils.LabeledDirectedGraph deps,
      MultiAlphabet forConvertingEdgeLabels,
      int tokenIndexFirst,
      int tokenIndexLast) {
    this(deps, forConvertingEdgeLabels, tokenIndexFirst, tokenIndexLast, false);
  }
  public DependencyParse(
      edu.jhu.hlt.tutils.LabeledDirectedGraph deps,
      MultiAlphabet forConvertingEdgeLabels,
      int tokenIndexFirst,
      int tokenIndexLast,
      boolean recoverFromMultipleHeads) {
    int n = (tokenIndexLast - tokenIndexFirst) + 1;
    if (n <= 0)
      throw new IllegalArgumentException();
    heads = new int[n];
    labels = new String[n];
    for (int j = 0, i = tokenIndexFirst; i <= tokenIndexLast; i++, j++) {
      edu.jhu.hlt.tutils.LabeledDirectedGraph.Node node = deps.getNode(i);
      if (node.numParents() == 0) {
        // Root
        labels[j] = "ROOT";
        heads[j] = -1;
      } else {
        assert node.numParents() > 0 : "numParents=" + node.numParents();
        if (!recoverFromMultipleHeads && node.numParents() > 1)
          throw new RuntimeException("multiple heads for " + node);
        int h = node.getParent(0);
        if (h > tokenIndexLast) {
          // ConcreteToDocument uses numTokensInDocument as root for all dep trees.
          heads[j] = -1;
        } else {
          heads[j] = h - tokenIndexFirst;
          assert heads[j] < n && heads[j] >= 0 : "h=" + h + " first=" + tokenIndexFirst + " last=" + tokenIndexLast + " n=" + n + " heads[" + j + "]=" + heads[j];
        }
        int l = node.getParentEdgeLabel(0);
        assert l >= 0;
        labels[j] = forConvertingEdgeLabels.dep(l);
      }
    }
  }

  /**
   * Strips label information and converts them to head direction (i.e. L and R)
   */
  public void stripEdgeLabels() {
    for (int i = 0; i < labels.length; i++) {
      if (heads[i] < 0)
        labels[i] = "X";
      else if (heads[i] < i)
        labels[i] = "L";
      else
        labels[i] = "R";
    }
  }

  public int hashCode() {
    if (hashCode == 0) {
      int n = size();
      for (int i = 0; i < n; i++)
        hashCode = hashCode * 83 + heads[i];
    }
    return hashCode;
  }
 
  public boolean equals(Object other) {
    if (other instanceof DependencyParse) {
      DependencyParse d = (DependencyParse) other;
      return Arrays.equals(heads, d.heads)
          && Arrays.equals(labels, d.labels);
    }
    return false;
  }

  public int[] getHeads() {
    return heads;
  }

  public String[] getLabels() {
    return labels;
  }

  public int size() {
    return heads.length;
  }

  public boolean isRoot(int i) {
    return heads[i] == ROOT;
  }

  public boolean isPunc(int i) {
    return heads[i] == PUNC;
  }

  public int getHead(int i) {
    return heads[i];
  }

  public String getLabel(int i) {
    return labels[i];
  }

  public int getDepth(int i) {
    if (depths == null) {
      depths = new int[size()];
      Arrays.fill(depths, -1);
    }
    if (depths[i] < 0) {
      int h = heads[i];
      if (h < 0)
        depths[i] = 0;
      else
        depths[i] = 1 + getDepth(h);
    }
    return depths[i];
  }

  public int getProjLeft(int i) {
    if (projLeft == null) {
      projLeft = new int[size()];
      Arrays.fill(projLeft, -1);
    }
    if (projLeft[i] < 0) {
      int l = i;
      for (int c : getChildren(i)) {
        int ll = getProjLeft(c);
        if (ll < l) l = ll;
      }
      projLeft[i] = l;
    }
    assert projLeft[i] < projLeft.length;
    return projLeft[i];
  }

  public int getProjRight(int i) {
    if (projRight == null) {
      projRight = new int[size()];
      Arrays.fill(projRight, -1);
    }
    if (projRight[i] < 0) {
      int r = i;
      for (int c : getChildren(i)) {
        int rr = getProjRight(c);
        if (rr > r) r = rr;
      }
      projRight[i] = r;
    }
    assert projRight[i] < projRight.length;
    return projRight[i];
  }

  public Span getProj(int i) {
    return Span.getSpan(getProjLeft(i), getProjRight(i)+1);
  }

  public Span getSpan(int i) {
    int l = getProjLeft(i);
    int r = getProjRight(i) + 1;
    return Span.getSpan(l, r);
  }

  private static class Dep {
    public final int gov, dep;
    public Dep(int gov, int dep) {
      this.gov = gov;
      this.dep = dep;
    }
  }

  public int[] getSiblings(int i) {
    int h = heads[i];
    if (h < 0)
      return new int[0];
    int[] sibAll = getChildren(h);
    int[] sib = new int[sibAll.length - 1];
    int k = 0;
    for (int j = 0; j < sibAll.length; j++) {
      if (sibAll[j] != i) {
        sib[k] = sibAll[j];
        k++;
      }
    }
    return sib;
  }

  public int[] getChildren(int i) {
    if (children == null) {
      // Get all dependency edges sorted by gov
      List<Dep> deps = new ArrayList<>();
      int n = size();
      for (int j = 0; j < n; j++)
        deps.add(new Dep(heads[j], j));
      Collections.sort(deps, new Comparator<Dep>() {
        @Override public int compare(Dep o1, Dep o2) { return o1.gov - o2.gov; }
      });
      // Group edges by gov and populate children for each
      children = new int[n][];
      int ptr = 0;
      while (ptr < n) {
        Dep s = deps.get(ptr);
        int offset = 1;
        while (ptr + offset < n && s.gov == deps.get(ptr + offset).gov)
          offset++;
        if (s.gov >= 0) {
          children[s.gov] = new int[offset];
          for (int j = 0; j < offset; j++)
            children[s.gov][j] = deps.get(ptr + j).dep;
        }
        ptr += offset;
      }
    }
    int[] c = children[i];
    if (c == null) {
      children[i] = new int[0];
      return children[i];
    } else {
      return c;
    }
  }
}
