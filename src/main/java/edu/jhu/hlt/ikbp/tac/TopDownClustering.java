package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.directory.server.kerberos.shared.crypto.encryption.DesCbcCrcEncryption;
import org.junit.runner.Describable;

import com.google.common.collect.Iterators;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.FileBasedCommIter;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.set.IntHashSet;
import edu.jhu.util.Alphabet;
import edu.jhu.util.TokenizationIter;

public class TopDownClustering {
  
  static class StringAlph {
    public static final Charset UTF8 = Charset.forName("UTF8");
    
    static class Node {
      byte lastChar;
      int stringIndex;
      Node parent;
      Node[] char2child;    // I think this is the problem, needs to be sparse
      
      public Node(Node parent, byte lastChar, int stringIndex) {
        this.parent = parent;
        this.lastChar = lastChar;
        this.stringIndex = stringIndex;
        this.char2child = null; // lazily allocated
      }
      
      public Node getOrMakeChild(byte child) {
        if (this.char2child == null)
          this.char2child = new Node[Byte.MAX_VALUE];
        int i = Byte.toUnsignedInt(child);
        if (char2child[i] == null)
          char2child[i] = new Node(this, child, -1);
        return char2child[i];
      }
    }

    private ArrayList<Node> i2s;
    private Node s2i;
    
    public StringAlph() {
      i2s = new ArrayList<>();
      s2i = new Node(null, (byte) 0, 0);
      i2s.add(s2i);
    }
    
    /**
     * The empty string is always present and gets id 0, so this is 1 higher
     * than the number of non-empty adds. This also counts prefixes!
     */
    public int size() {
      return i2s.size();
    }
    
    public int lookupIndex(String s) {
      Node n = s2i;
      byte[] b = s.getBytes(UTF8);
      for (int i = 0; i < b.length; i++) {
        n = n.getOrMakeChild(b[i]);
        if (n.stringIndex < 0) {
          // new node
          n.stringIndex = i2s.size();
          i2s.add(n);
        }
      }
      return n.stringIndex;
    }
    
    public String lookupObject(int i) {
      Deque<Byte> stack = new ArrayDeque<>();
      for (Node n = i2s.get(i); n.parent != null; n = n.parent)
        stack.push(n.lastChar);
      byte[] b = new byte[stack.size()];
      for (int j = 0; j < b.length; j++)
        b[j] = stack.pop();
      return new String(b, UTF8);
    }
    
    public static void test() {
      StringAlph a = new StringAlph();
      System.out.println(a.lookupObject(0));
      System.out.println(a.lookupIndex("foo"));
      System.out.println(a.lookupIndex("foo"));
      System.out.println(a.lookupIndex("bar"));
      System.out.println(a.lookupIndex("foo"));
      System.out.println(a.lookupIndex("bar"));
      System.out.println(a.lookupObject(3));
      System.out.println(a.lookupObject(6));
    }
  }
  
  static class VarianceView {
    double[] x, xx;
    int n;
    
    public VarianceView(int dimension) {
      x = new double[dimension];
      xx = new double[dimension];
      n = 0;
    }
    
    public int dimension() {
      return x.length;
    }
    
    public void add(int feature, double value) {
      x[feature] += value;
      xx[feature] += value * value;
      n++;
    }

    public void remove(int feature, double value) {
      x[feature] -= value;
      xx[feature] -= value * value;
      n--;
    }
    
    public double variance() {
      return variance(null);
    }
    public double variance(VarianceView subtractOut) {
      double v = 0;
      double ex, exx;
      for (int i = 0; i < x.length; i++) {
        if (subtractOut == null) {
          ex = x[i]/n;
          exx = xx[i]/n;
        } else {
          int n = this.n - subtractOut.n;
          ex = (x[i] - subtractOut.x[i]) / n;
          exx = (xx[i] - subtractOut.xx[i]) / n;
        }
        v += exx - ex*ex;
      }
      return v;
    }
  }
  
  class SplitScore {
    int feature;
    double score;
    Map<Object, Object> meta;
    
    public SplitScore(int split, double score) {
      this.feature = split;
      this.score = score;
      this.meta = null;
    }
    
    public SplitScore meta(Object key, Object value) {
      if (meta == null)
        meta = new HashMap<>();
      meta.put(key, value);
      return this;
    }
    
    @Override
    public String toString() {
      return String.format("(SplitScore %s %.4g %s)", alph.lookupObject(feature), score, meta);
    }
  }
  
  class Node {
    Boolean include;  // null for root
    int feature;      // TODO Change to split score
    Node parent, left, right;   // left contains feat, right doesn't
    
    BitSet _featuresOnPath;
    
    @Override
    public String toString() {
      return String.format("(Node pos=%s neg=%d size=%d path=%s)",
          numPosFeatures(), numNegFeatures(), efficientMembers().size(), path());
    }
    
    public int numPosFeatures() {
      int c = 0;
      for (Node n = this; n != null; n = n.parent)
        if (n.include != null && n.include)
          c++;
      return c;
    }
    
    public int numNegFeatures() {
      int c = 0;
      for (Node n = this; n != null; n = n.parent)
        if (n.include != null && !n.include)
          c++;
      return c;
    }
    
    void createChildren(int splitFeature) {
      left = new Node();
      right = new Node();
      left.parent = this;
      right.parent = this;
      left.feature = right.feature = splitFeature;
      left.include = true;
      right.include = false;
    }
    
    public boolean precludedFeature(int f) {
      if (_featuresOnPath == null) {
        _featuresOnPath = new BitSet();
        for (Node c = this; c != null; c = c.parent) {
          if (c.feature >= 0) {
            _featuresOnPath.set(c.feature);
          } else {
            assert c.parent == null;  // root
          }
        }
      }
      return _featuresOnPath.get(f);
    }
    
    /** member == row */
    Iterator<Integer> members() {
      
      // TODO, take all the pos nodes on the path to root,
      // intersect their inverted indices,
      // then get all the neg nodes on the path to root,
      // and remove these rows from the intersection.
      // NOTE: This may not be worth it in time...
      
      // filter parent's members according to include/exclude feature
      Iterator<Integer> pm = parent == null ? allMembers() : parent.members();
      return Iterators.filter(pm, this::exclude);
    }

    // TODO just store the indices at each node!
    // eagerly compute and memoize on each call.
    // compared to efficientMembers: simpler, slightly more efficient, more memory usage.
    // I think the memory issue could actually matter when there are billions of rows.
    IntArrayList memoEfficientMembers() {
      throw new RuntimeException("implement me");
    }
    
    IntArrayList efficientMembers() {
      
      // Start by gathering all of the neg nodes, put those rows into one set
      // Then do repeated sorted list intersection, checking the exclude/neg set on each hit
      IntHashSet neg_rows = new IntHashSet();
      IntArrayList pos_feats = new IntArrayList();
      for (Node c = this; c != null; c = c.parent) {
        if (c.include == null) {
          assert c.parent == null;  // root
          break;
        }
        if (c.include) {
          pos_feats.add(c.feature);
        } else {
          IntArrayList neg = invertedIndex.get(c.feature);
          if (neg != null)
            neg_rows.add(neg);
        }
      }
      
      if (pos_feats.isEmpty()) {
        // range over every row, excluding things
        IntArrayList keep = new IntArrayList();
        int n = rows.size();
        for (int i = 0; i < n; i++)
          if (!neg_rows.contains(i))
            keep.add(i);
        return keep;
      } else {
        // recursively intersect pos rows, checking neg_rows each time
        // TODO this might be more efficient if i go in order of smallest inverted list first
        IntArrayList cur = new IntArrayList();
        IntArrayList next = new IntArrayList();
        cur.add(invertedIndex.get(pos_feats.get(0)));
        for (int i = 1; i < pos_feats.size(); i++) {
          next.clear();
          intersectSortedLists(cur, invertedIndex.get(pos_feats.get(i)), next);
          IntArrayList t = cur; cur = next; next = t;
        }
        // TODO for now i'm just going to check neg at the end
        next.clear();
        for (int i = 0; i < cur.size(); i++)
          if (!neg_rows.contains(cur.get(i)))
            next.add(cur.get(i));
        return next;
      }
    }
    
    /** member == row */
    public boolean exclude(int member) {
      if (include == null)
        return true;
      boolean contains = false;
      if (USE_ENTRY_DICT) {
        contains = values.contains(new IntPair(member, feature));
      } else {
        int[] r = rows.get(member);
        for (int i = 0; i < r.length && !contains; i++)
          contains |= r[i] == feature;
      }
      return !(include ^ contains);
    }
    
    /** loops over all rows in this node's members */
    public VarianceView buildVarianceView() {
      return buildVarianceView(efficientMembers());
    }
    /** precludes features between this node and root */
    public VarianceView buildVarianceView(IntArrayList rs) {
      VarianceView vv = new VarianceView(dimension());
      for (int idx = 0; idx < rs.size(); idx++) {
        int[] row = rows.get(rs.get(idx));

        // Filter out the features on the path to root
        int[] rowF = new int[row.length];
        int rowFtop = 0;
        for (int i = 0; i < row.length; i++)
          if (!precludedFeature(row[i]))
            rowF[rowFtop++] = row[i];
        row = null;

        // assumes uniq features!
        double tf = 1d / rowFtop;
        for (int i = 0; i < rowFtop; i++) {
          double v = tf * idf(rowF[i]);
          vv.add(rowF[i], v);
        }
      }
      return vv;
    }
    
    // TODO Efficient scoreSplit: compute sum:double[], sum_sq:double[], rows:int[]
    // for the node. when you consider splitting rows:int[] based on a feature,
    // you only need to compute a cavity based on intersection(invIdx(feature), node.rows)
    public SplitScore scoreSplitCavity(int feature, IntArrayList nodeRows, VarianceView all) {
      IntArrayList containsFeat = invertedIndex.get(feature);
      IntArrayList left = new IntArrayList();
      intersectSortedLists(containsFeat, nodeRows, left);
      int n_left = left.size();
      if (n_left < minRowsPerNode) {
        ec.increment("scoreSplit/minObs");
        return new SplitScore(feature, Double.NEGATIVE_INFINITY);
      }

      VarianceView lv = buildVarianceView(left);

      int n_right = nodeRows.size() - n_left;
      if (n_left < minRowsPerNode || n_right < minRowsPerNode) {
        ec.increment("scoreSplit/minObs2");
        return new SplitScore(feature, Double.NEGATIVE_INFINITY);
      }
//      int rows_skipped = rows.size() - (n_left + n_right);
      double n = n_left + n_right;
      double prec_left = 1/lv.variance(); //1/variance(sum_left, sum_left_sq, n);
      double prec_right = 1/(all.variance(lv)); //1/variance(sum_right, sum_right_sq, n);
      double p_left = n_left / n;
      double p_right = n_right / n;
      double entropy = p_left * -Math.log(p_left)
          + p_right * -Math.log(p_right);
      double score = prec_left * prec_right * entropy;// * entropy;
      return new SplitScore(feature, score);
//        .meta("entropy", entropy)
//        .meta("n_left", n_left)
//        .meta("n_right", n_right)
//        .meta("prec_left", prec_left)
//        .meta("prec_right", prec_right);
//        .meta("rows_skipped", rows_skipped);
    }

    // TODO This chooses splits which lead to small variance of tf-idf vecs on either side
    // Another version would be to split on ???
    public SplitScore scoreSplit(int feature) {
      ec.increment("scoreSplit");
      // See if it is a parent feature
      if (precludedFeature(feature)) {
        ec.increment("scoreSplit/dupFeat");
        return new SplitScore(feature, Double.NEGATIVE_INFINITY);
      }
      
      IntArrayList left_list = invertedIndex.get(feature);
      int n_left_aot = left_list == null ? 0 : left_list.size();
      if (n_left_aot < minRowsPerNode) {
        ec.increment("scoreSplit/minObs");
        return new SplitScore(feature, Double.NEGATIVE_INFINITY);
      }
      
      int d = dimension();
      double[] sum_left = new double[d];
      double[] sum_left_sq = new double[d];
      double[] sum_right = new double[d];
      double[] sum_right_sq = new double[d];
      
      int n_left = 0;
      int n_right = 0;
      IntArrayList memb = efficientMembers();
      int n_memb = memb.size();
      for (int mi = 0; mi < n_memb; mi++) {
//      Iterator<Integer> memb = members();
//      while (memb.hasNext()) {
//        int rowIdx = memb.next();
        int rowIdx = memb.get(mi);
        int[] row = rows.get(rowIdx);
        
        // Filter out the features on the path to root
        int[] rowF = new int[row.length];
        int rowFtop = 0;
        for (int i = 0; i < row.length; i++)
          if (!precludedFeature(row[i]))
            rowF[rowFtop++] = row[i];
        row = null;
        
        boolean left = false;
        for (int i = 0; i < rowFtop && !left; i++)
          left |= rowF[i] == feature;

        double[] sum, sum_sq;
        if (left) {
          sum = sum_left;
          sum_sq = sum_left_sq;
          n_left++;
        } else {
          sum = sum_right;
          sum_sq = sum_right_sq;
          n_right++;
        }
        
        // assumes uniq features!
        double tf = 1d / rowFtop;
        for (int i = 0; i < rowFtop; i++) {
          double v = tf * idf(rowF[i]);
          sum[rowF[i]] += v;
          sum_sq[rowF[i]] += v * v;
        }
      }
      if (n_left < minRowsPerNode || n_right < minRowsPerNode) {
        ec.increment("scoreSplit/minObs2");
        return new SplitScore(feature, Double.NEGATIVE_INFINITY);
      }
      int rows_skipped = rows.size() - (n_left + n_right);
      double n = n_left + n_right;
      double prec_left = 1/variance(sum_left, sum_left_sq, n);
      double prec_right = 1/variance(sum_right, sum_right_sq, n);
      double p_left = n_left / n;
      double p_right = n_right / n;
      double entropy = p_left * -Math.log(p_left)
          + p_right * -Math.log(p_right);
      double score = prec_left * prec_right * entropy * entropy;
      return new SplitScore(feature, score)
        .meta("entropy", entropy)
        .meta("n_left", n_left)
        .meta("n_right", n_right)
        .meta("prec_left", prec_left)
        .meta("prec_right", prec_right)
        .meta("rows_skipped", rows_skipped);
    }
  
    public String path() {
      Deque<String> stack = new ArrayDeque<>();
      for (Node c = this; c != null; c = c.parent) {
        if (c.include == null) {
          assert c.parent == null;
          break;
        }
        String p = c.include ? "+" : "-";
        stack.push(p + alph.lookupObject(c.feature));
      }
      return "[" + StringUtils.join(" & ", stack) + "]";
    }
    
    public List<String[]> randomSampleOfMembers(int k, Random rand) {
      ReservoirSample<Integer> res = new ReservoirSample<>(k, rand);
      Iterator<Integer> iter = members();
      while (iter.hasNext()) {
        int i = iter.next();
        res.add(i);
      }
      List<String[]> s = new ArrayList<>(k);
      for (int i : res) {
        int[] row = rows.get(i);
        String[] rs = new String[row.length];
        for (int j = 0; j < rs.length; j++)
          rs[j] = alph.lookupObject(row[j]);
        s.add(rs);
      }
      return s;
    }
  }

  private static void intersectSortedLists(IntArrayList sourceA, IntArrayList sourceB, IntArrayList dest) {
    int aptr = 0;
    int bptr = 0;
    int na = sourceA.size();
    int nb = sourceB.size();
    int va_prev = -1;
    int vb_prev = -1;
    while (aptr < na && bptr < nb) {
      int va = sourceA.get(aptr);
      int vb = sourceB.get(bptr);
      if (va == vb) {
        dest.add(va);
        aptr++;
        bptr++;
        assert va >= va_prev;
        assert vb >= vb_prev;
        va_prev = va;
        vb_prev = vb;
      } else if (va < vb) {
        aptr++;
        assert va >= va_prev;
        va_prev = va;
      } else {
        bptr++;
        assert vb >= vb_prev;
        vb_prev = vb;
      }
    }
  }

  public static double variance(double[] sum, double[] sum_sq, double n) {
    assert sum.length == sum_sq.length;
    double var = 0;
    for (int i = 0; i < sum.length; i++) {
      double v = (sum_sq[i]/n) - (sum[i]/n)*(sum[i]/n);
      assert v >= 0;
      var += v;
    }
    return var;
  }
  
  static boolean USE_ENTRY_DICT = false;
  
  private Alphabet<String> alph = new Alphabet<>();
//  private StringAlph alph = new StringAlph();
  private List<int[]> rows;
  private IntObjectHashMap<IntArrayList> invertedIndex;
  private Set<IntPair> values;
  private int nval = 0;
  private Node root;
  private Counts<String> ec;
  
  // TODO instead of pruning cooc features by frequency,
  // I should prune by PMI with the predicate word
  private IntPair coocFeatWordCountRange;
  private TokenObservationCounts tc;
  
  private int minRowsPerNode;
  
  public TopDownClustering(int minRowsPerNode, IntPair coocFeatWordCountRange) {
    Log.info("coocFeatWordCountRange=" + coocFeatWordCountRange);
    Log.info("minRowsPerNode=" + minRowsPerNode);
    this.coocFeatWordCountRange = coocFeatWordCountRange;
    this.minRowsPerNode = minRowsPerNode;
    rows = new ArrayList<>();
    invertedIndex = new IntObjectHashMap<>();
    root = new Node();
    root.feature = -1;
    ec = new Counts<>();
    if (USE_ENTRY_DICT)
      values = new HashSet<>();
  }
  
  public int dimension() {
    return alph.size();
  }
  
  public Iterator<Integer> allFeatures() {
    return new Iterator<Integer>() {
      int[] fs = invertedIndex.keys();
      int i = 0;
      @Override
      public boolean hasNext() {
        return i < fs.length;
      }
      @Override
      public Integer next() {
        return fs[i++];
      }
    };
  }
  public Iterator<Integer> allMembers() {
    return new Iterator<Integer>() {
      int i = 0, d = rows.size();
      @Override
      public boolean hasNext() {
        return i < d;
      }
      @Override
      public Integer next() {
        return i++;
      }
    };
  }
  
  public double idf(int feature) {
//    ec.increment("idf");    // this is like 96% of the time spent in this method, and this method is hot (57% of runtime)
    IntArrayList vals = invertedIndex.get(feature);
    int n = vals == null ? 1 : vals.size() + 1;
    double d = rows.size() + 1;
    return Math.log(d / n);
  }
  
  public void add(List<String> row) {
    ec.increment("addRow");
    int[] r = new int[row.size()];
    for (int i = 0; i < r.length; i++)
      r[i] = alph.lookupIndex(row.get(i));
    int x = rows.size();
    for (int i = 0; i < r.length; i++)
      invIdxAdd(r[i], x);
    rows.add(r);
  }
  
  private void invIdxAdd(int feature, int row) {
    ec.increment("addVal");
    IntArrayList l = invertedIndex.get(feature);
    if (l == null) {
      l = new IntArrayList(1);
      invertedIndex.put(feature, l);
    }
    l.add(row);
    nval++;
    if (USE_ENTRY_DICT)
      values.add(new IntPair(row, feature));
  }
  
  private static int[] pruneRow(int[] row, int remove) {
    int occ = 0;
    for (int i = 0; i < row.length; i++)
      if (row[i] == remove)
        occ++;
    if (occ == 0)
      return row;
    int[] n = new int[row.length - occ];
    for (int i = 0, t = 0; i < row.length; i++)
      if (row[i] != remove)
        n[t++] = row[i];
    return n;
  }
  
  public void prune(int minObs) {
    Log.info("starting nval=" + nval + " minObs=" + minObs);
    int[] features = invertedIndex.keys();
    for (int f : features) {
      IntArrayList rs = invertedIndex.get(f);
      if (rs.size() < minObs) {
        for (int i = 0; i < rs.size(); i++) {
          int[] r = rows.get(rs.get(i));
          int[] rp = pruneRow(r, f);
          nval -= r.length;
          assert nval >= 0;
          nval += rp.length;
          rows.set(rs.get(i), rp);
        }
        invertedIndex.remove(f);
      }
    }
    Log.info("done, nval=" + nval);
  }
  
  public SplitScore split(Node n) {
    Log.info("starting n=" + rows.size() + " d=" + invertedIndex.size() + " D=" + dimension());
    TimeMarker tm = new TimeMarker();
    SplitScore best = new SplitScore(-1, Double.NEGATIVE_INFINITY);
    int i = 0;

    // For cavity scoring
    IntArrayList nodeRows = n.efficientMembers();
    VarianceView vv = n.buildVarianceView();

    Iterator<Integer> splits = allFeatures();
    while (splits.hasNext()) {
      i++;
      int feature = splits.next();

//      SplitScore s = n.scoreSplit(feature);
      SplitScore s = n.scoreSplitCavity(feature, nodeRows, vv);

      if (best.feature < 0 || best.score < s.score)
        best = s;
      if (tm.enoughTimePassed(5)) {
//        Log.info("feature=" + new IntPair(i, dimension()));
        Log.info("feature=" + new IntPair(i, invertedIndex.size()));
        Log.info("path=" + n.path());
        Log.info("best: " + best);
        Log.info("cur: " + s);
        Log.info("counts: " + ec);
        System.out.println();
      }
    }
    return best;
  }
  
  /**
   * @param k How many items to sample from a node when it is popped.
   */
  public void bfsSplit(int k) {
    Random r = new Random(9001);
    Deque<Node> q = new ArrayDeque<>();
    q.push(root);
    while (!q.isEmpty()) {
      Node expand = q.pollFirst();

      Log.info("popped=" + expand + " remaining=" + q.size());
      for (String[] row : expand.randomSampleOfMembers(k, r))
        System.out.println(Arrays.toString(row));
      System.out.println();

      SplitScore split = split(expand);
      if (split.score > 1e-8) {
        expand.createChildren(split.feature);
        Log.info("pushing pos=" + expand.left);
        Log.info("pushing pos=" + expand.right);
        q.addLast(expand.left);
        q.addLast(expand.right);
      }
    }
  }
  
  private String norm(String input) {
    String s = input.toLowerCase().replaceAll("\\d", "0");
    if (tc == null)
      return s;
    int minCount = 10;
    return tc.getPrefixOccuringAtLeast(s, minCount);
  }
  
  private static String ner(int i, TokenTagging ner) {
    TaggedToken tt = ner.getTaggedTokenList().get(i);
    String tag = tt.getTag();
    if (tag.equalsIgnoreCase("O"))
      return null;
    if (tag.length() >= 3 && tag.charAt(1) == '-')
      return tag.substring(2);
    return tag;
  }

  private List<String> featurize(int predicate, List<Dependency>[] parents, List<Dependency>[] children, Tokenization t) {
    List<TaggedToken> pos = t.getTokenTaggingList().get(1).getTaggedTokenList(); // TODO
    List<Token> words = t.getTokenList().getTokenList();
    
    boolean finepath = true;

    List<String> f = new ArrayList<>();

    BitSet observed = new BitSet();
    observed.set(predicate);

    String w = norm(words.get(predicate).getText());
    String e = ner(predicate, getNerTags(t));
    String p = pos.get(predicate).getTag();
    f.add("word(" + w + ")");
    f.add("pos(" + p + ")");
    f.add("wordpos(" + w + "." + p + ")");
    if (e != null) {
      f.add("ner(" + e + ")");
      String s = PosPatternGenerator.shapeNormalize(words.get(predicate).getText());
      f.add("shape(" + s + ")");
    }

    for (Dependency d : children[predicate]) {
      // children
      int c = d.getDep();
      observed.set(c);
      String df = d.getEdgeType() + "(X," + norm(words.get(c).getText()) + ")";
      String dfc = d.getEdgeType() + "(X,?)";
      f.add(df);
      // grand children
      for (Dependency dd : children[c]) {
        int cc = dd.getDep();
        observed.set(cc);
        if (finepath)
          f.add(dd.getEdgeType() + "(" + df + "," + norm(words.get(cc).getText()) + ")");
        else
          f.add(dd.getEdgeType() + "(" + dfc + "," + norm(words.get(cc).getText()) + ")");
      }
    }
    for (Dependency d : parents[predicate]) {
      int c = d.getGov();
      if (c < 0)
        continue;
      observed.set(c);
      String df = d.getEdgeType() + "(" + norm(words.get(c).getText()) + ",X)";
      String dfc = d.getEdgeType() + "(?,X)";
      f.add(df);
      // siblings
      for (Dependency dd : children[c]) {
        int cc = dd.getDep();
        observed.set(cc);
        if (finepath)
          f.add(dd.getEdgeType() + "(" + norm(words.get(cc).getText()) + "," + df + ")");
        else
          f.add(dd.getEdgeType() + "(" + norm(words.get(cc).getText()) + "," + dfc + ")");
      }
      // grandparents
      for (Dependency dd : parents[c]) {
        int cc = dd.getGov();
        if (cc >= 0) {
          observed.set(cc);
          if (finepath)
            f.add(dd.getEdgeType() + "(" + df + "," + norm(words.get(cc).getText()) + ")");
          else
            f.add(dd.getEdgeType() + "(" + dfc + "," + norm(words.get(cc).getText()) + ")");
        }
      }
    }
    
    Set<String> uniq = new HashSet<>();
    for (Token tok : words) {
      if (!observed.get(tok.getTokenIndex())) {
        String tt = norm(tok.getText());
        if (!uniq.add(tt))
          continue;
        int c = tc.getCount(tt);
        if (coocFeatWordCountRange.first <= c && c <= coocFeatWordCountRange.second)
          f.add("cooc(" + tt + ")");
      }
    }

    return f;
  }
  
  private static void add(List<Dependency>[] a, int i, Dependency d) {
    if (i < 0)
      return;
    if (a[i] == null)
      a[i] = new ArrayList<>();
    a[i].add(d);
  }
  
  /** TODO find by tool name */
  private static DependencyParse getDeps(Tokenization t) {
    return t.getDependencyParseList().get(2);
  }
  
  private static TokenTagging getNerTags(Tokenization t) {
    TokenTagging ner = null;
    for (TokenTagging tt : t.getTokenTaggingList()) {
      if (tt.getTaggingType().equalsIgnoreCase("NER")) {
        assert ner == null;
        ner = tt;
      }
    }
    return ner;
  }
  
  public static boolean isHead(List<Dependency> parents) {
    if (parents.isEmpty())
      return false;
    for (Dependency d : parents) {
      String e = d.getEdgeType();
      if (e.equals("nn"))
        return false;
      if (e.equals("det"))
        return false;
    }
    return true;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    boolean situations = config.getBoolean("situations", true);
    Log.info("extracting=" + (situations ? "situations" : "entities"));
    
    int coocFeatWordCountMin = config.getInt("coocFeatWordCountMin", 10);
    int coocFeatWordCountMax = config.getInt("coocFeatWordCountMax", 1000);

    int minRowsPerNode = config.getInt("minRowsPerNode", 60);
    TopDownClustering s = new TopDownClustering(
        minRowsPerNode, new IntPair(coocFeatWordCountMin, coocFeatWordCountMax));

    Set<String> predEdges = new HashSet<>();
    predEdges.addAll(Arrays.asList("nsubj", "nsubjpass", "agent", "ccomp", "csubj", "csubjpass"));
//    predEdges.addAll(Arrays.asList("nsubj", "nsubjpass", "agent"));
    
    Set<String> nerTypes = new HashSet<>();
    nerTypes.addAll(Arrays.asList("PERSON", "ORGANIZATION", "LOCATION", "MISC"));
    
    int maxSentenceLength = config.getInt("maxSentenceLength", 50);
    @SuppressWarnings("unchecked")
    List<Dependency>[] children = new List[maxSentenceLength];
    @SuppressWarnings("unchecked")
    List<Dependency>[] parents = new List[maxSentenceLength];
    for (int i = 0; i < maxSentenceLength; i++) {
      children[i] = new ArrayList<>();
      parents[i] = new ArrayList<>();
    }
    
    File cc = config.getExistingFile("tokObsLc", new File("data/character-sequence-counts/charCounts.nyt_eng_2007.lower-true.reverse-false.minCount5.jser.gz"));
//    File cc = new File("data/character-sequence-counts/charCounts.nyt_eng_2007.lower-true.reverse-false.minCount5.jser.gz");
//    File cc = new File("data/character-sequence-counts/charCounts.apw_eng_2000on.lower-true.reverse-false.minCount5.jser.gz");
    s.tc = (TokenObservationCounts) FileUtil.deserialize(cc);
    
    TimeMarker tm = new TimeMarker();
//    List<File> f = Arrays.asList(new File("data/concretely-annotated-gigaword/sample-frank-oct16/nyt_eng_200909.tar.gz"));
//    List<File> f = FileUtil.find(new File("data/concretely-annotated-gigaword/sample-med"), "glob:**/*.tar.gz");
    List<File> f = config.getFileGlob("communications");
    
    // How many predicates to extract features on
    int maxRows = config.getInt("maxRows", 1_000_000);
    
    try (FileBasedCommIter iter = new FileBasedCommIter(f)) {
      reading:
      while (iter.hasNext()) {
        Communication comm = iter.next();
        for (Tokenization t : new TokenizationIter(comm)) {
          int n = t.getTokenList().getTokenListSize();
          if (n > maxSentenceLength)
            continue;

          DependencyParse d = getDeps(t);
          for (int i = 0; i < n; i++) {
            parents[i].clear();
            children[i].clear();
          }
          for (Dependency dep : d.getDependencyList()) {
            add(children, dep.getGov(), dep);
            add(parents, dep.getDep(), dep);
          }

          BitSet instances = new BitSet();
          if (situations) {
            // Find the predicates in this sentence
            for (Dependency dep : d.getDependencyList()) {
              if (predEdges.contains(dep.getEdgeType()))
                instances.set(dep.getGov());
              if ("root".equals(dep.getEdgeType()))
                instances.set(dep.getDep());
            }
          } else {
            // Alternatively, do this for all named entities
            TokenTagging ner = getNerTags(t);
            for (int i = 0; i < n; i++) {
              String nerTag = ner.getTaggedTokenList().get(i).getTag();
              if (nerTypes.contains(nerTag)) {
                if (isHead(parents[i])) {
                  instances.set(i);
                }
              }
            }
          }

          for (int p = instances.nextSetBit(0); p >= 0; p = instances.nextSetBit(p+1)) {
            List<String> feats = s.featurize(p, parents, children, t);
            s.add(feats);
          }
          
          if (tm.enoughTimePassed(5)) {
            Log.info("working on " + comm.getId() + ", populated " + s.rows.size()
              + " rows, alph.size=" + s.alph.size() + "\t" + Describe.memoryUsage());
          }
          if (s.rows.size() >= maxRows)
            break reading;
        }
      }
    }
    
    int minObs = config.getInt("minFeatureObs", 5);
    s.prune(minObs);
    
    int numRowPerNodeToShow = config.getInt("numRowPerNodeToShow", 30);
    s.bfsSplit(numRowPerNodeToShow);

    Log.info("done");
  }
}
