package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse.Node;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse.NodePathPiece;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateSS;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.SentencePosition;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.data.BrownClusters;
import edu.jhu.prim.tuple.Pair;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IWord;

public class BasicFeatureTemplates {

  public boolean WIDE_SPAN_BAG_OF_WORDS_OPTIMIZATION = false;

  public static String spanPosRel(Span s1, Span s2) {
    return posRel(s1.start, s2.start)
        + "-" + posRel(s1.end, s2.end)
        + "-" + posRel(s1.start, s2.end)
        + "-" + posRel(s1.end, s2.start);
  }

  public static String posRel(int i, int j) {
    if (i+1 == j) return "B";
    if (j+1 == i) return "A";
    if (i < j) return "L";
    if (j > i) return "R";
    return "E";
  }

  private static ProductIndex[] sprMemo = new ProductIndex[5 * 5 * 5 * 5];
  static {
    for (int i = 0; i < sprMemo.length; i++)
      sprMemo[i] = new ProductIndex(i, sprMemo.length);
  }
  public static ProductIndex spanPosRel2(Span s1, Span s2) {
    long f = 0;
    f = f * 5 + posRel2(s1.start, s2.start).getFeature();
    f = f * 5 + posRel2(s1.end, s2.end).getFeature();
    f = f * 5 + posRel2(s1.start, s2.end).getFeature();
    f = f * 5 + posRel2(s1.end, s2.start).getFeature();
    return sprMemo[(int) f];
  }

  private static ProductIndex PI_B = new ProductIndex(0, 5);
  private static ProductIndex PI_A = new ProductIndex(1, 5);
  private static ProductIndex PI_L = new ProductIndex(2, 5);
  private static ProductIndex PI_R = new ProductIndex(3, 5);
  private static ProductIndex PI_E = new ProductIndex(4, 5);
  public static ProductIndex posRel2(int i, int j) {
    if (i+1 == j) return PI_B;
    if (j+1 == i) return PI_A;
    if (i < j) return PI_L;
    if (j > i) return PI_R;
    return PI_E;
  }

  private static int bound(int i, int width) {
    assert width >= 0;
    if (i >=  width) return  width;
    if (i <= -width) return -width;
    return i;
  }

  private static String discretizeWidth(String name, int divisor, int maxCardinality, int width) {
    int w = bound(width / divisor, maxCardinality/2);
    return name + "~" + w;
  }

  public static ProductIndex discretizeWidth2(int divisor, int maxCardinality, int width) {
    int w = bound(width / divisor, maxCardinality/2);
    return new ProductIndex(w, maxCardinality+1);
  }

  /**
  * Describes the position of the parent of p w.r.t. i and j.
  */
  public static String parentRelTo(int p, int i, int j, DependencyParse d) {
    if (p < 0 || p >= d.size())
      return "none";
    int lp = d.getHead(p);
    if (lp < 0 || lp >= d.size()) {
      return "root";
    } else if (lp < Math.min(i, j)) {
      return "left";
    } else if (lp == Math.min(i, j)) {
      return "min";
    } else if (lp < Math.max(i, j)) {
      return "middle";
    } else if (lp == Math.max(i, j)) {
      return "max";
    } else {
      return "right";
    }
  }

  public static String semaforPathLengthBuckets(int len) {
    if (len <= -20) return "(-inf,-20]";
    else if (len <= -10) return "(-20,-10]";
    else if (len <= -5) return "(-10,-5]";
    else if (len <= 4) return "[" + len + "]";
    else if (len < 10) return "[5,10)";
    else if (len < 20) return "[10,20)";
    else return "[20,inf)";
  }

  public static String sentenceLengthBuckets(int len) {
    if (len <= 5) return "[" + len + "]";
    else if (len <= 10) return "(5,10]";
    else if (len <= 15) return "(10,15]";
    else if (len <= 20) return "(15,20]";
    else if (len <= 25) return "(20,25]";
    else if (len <= 30) return "(25,30]";
    else if (len <= 40) return "(30,40]";
    else return "(40,inf)";
  }

  public static boolean canLexicalize(int i, Sentence s) {
    if (i < 0 || i >= s.size())
      return true;
    String pos = s.getPos(i);
    if (pos.startsWith("PRP")) return true;
    if (pos.equals("MD")) return true;
    if (pos.equals("CC")) return true;
    if (pos.equals("IN")) return true;
    if (pos.startsWith("W")) return true;
    if (pos.endsWith("DT")) return true;
    //if (pos.equals("SYM")) return true;
    if (pos.equals("RP")) return true;
    return false;
  }

//  private static BasicFeatureTemplates.Indexed SINGLETON;
//  public static BasicFeatureTemplates.Indexed getInstance() {
//    if (SINGLETON == null) {
//      SINGLETON = new BasicFeatureTemplates.Indexed();
//    }
//    return SINGLETON;
//  }

  protected BrownClusters bc256 = new BrownClusters(BrownClusters.bc256dirAuto());
  protected BrownClusters bc1000 = new BrownClusters(BrownClusters.bc1000dirAuto());

  protected Map<String, Function<SentencePosition, String>> tokenExtractors;
  protected Map<String, Template> basicTemplates;
  protected Map<String, Template> labelTemplates;

  public BasicFeatureTemplates() {
    init();
  }

  public Template getBasicTemplate(String name) {
    return basicTemplates.get(name);
  }
  public Template[] getBasicTemplates(String[] names) {
    Template[] t = new Template[names.length];
    for (int i = 0; i < t.length; i++)
      t[i] = getBasicTemplate(names[i]);
    return t;
  }

  public List<String> getBasicTemplateNames() {
    List<String> l = new ArrayList<>();
    l.addAll(basicTemplates.keySet());
    return l;
  }

  public List<String> getLabelTemplateNames() {
    List<String> l = new ArrayList<>();
    l.addAll(labelTemplates.keySet());
    return l;
  }

  private void addTemplate(String name, Template t) {
    if (basicTemplates == null)
      basicTemplates = new HashMap<>();
    if (name.contains("+"))
      throw new IllegalArgumentException("ambiguous name with +");
    if (name.contains("*"))
      throw new IllegalArgumentException("ambiguous name with *");
    Template ot = basicTemplates.put(name, t);
    if (ot != null)
      throw new RuntimeException("abiguous template name: " + name);
  }

  private void addLabel(String name, Template t) {
    if (labelTemplates == null)
      labelTemplates = new HashMap<>();
    if (name.contains("+"))
      throw new IllegalArgumentException("ambiguous name with +");
    if (name.contains("*"))
      throw new IllegalArgumentException("ambiguous name with *");
    Template ot = labelTemplates.put(name, t);
    if (ot != null)
      throw new RuntimeException("abiguous template name: " + name);
    addTemplate(name, t);
  }

  public void init() {
    /* TOKEN EXTRACTORS *******************************************************/
    tokenExtractors = new HashMap<>();
    tokenExtractors.put("Word", x -> {
      if (x.indexInSent())
        return "Word=" + x.sentence.getWord(x.index);
      else
        return null;
    });
    tokenExtractors.put("WordLex", x -> {
      if (x.indexInSent()
          && canLexicalize(x.index, x.sentence)) {
        return "WordLex=" + x.sentence.getWord(x.index);
      } else {
        return null;
      }
    });
    tokenExtractors.put("Word3", x -> {
      if (x.indexInSent()) {
        String s = x.sentence.getWord(x.index);
        if (s.length() > 4)
          return "Word3=" + s.substring(0, 4);
        return "Word3=" + s;
      } else {
        return null;
      }
    });
    tokenExtractors.put("Word4", x -> {
      if (x.indexInSent()) {
        String s = x.sentence.getWord(x.index);
        if (s.length() > 5)
          return "Word4=" + s.substring(0, 5);
        return "Word4=" + s;
      } else {
        return null;
      }
    });
    tokenExtractors.put("WordLC", x -> {
      if (x.indexInSent()) {
        String s = x.sentence.getWord(x.index).toLowerCase();
        return "WordLC=" + s;
      } else {
        return null;
      }
    });
    tokenExtractors.put("WordWnSynset", x -> {
      if (!x.indexInSent())
        return null;
      IWord w = x.sentence.getWnWord(x.index);
      if (w == null)
        return null;
//        return "WordWnSynset=FAILED_TO_GET_WN_WORD";
      ISynset ss = w.getSynset();
      if (ss == null)
        return null;
//        return "WordWnSynset=FAILED_TO_GET_SYNSET";
      ISynsetID id = ss.getID();
      if (id == null)
        return null;
//        return "WordWnSynset=NO_SYNSET_ID";
      return "WordWnSynset=" + id;
    });
    tokenExtractors.put("Lemma", x -> {
      if (!x.indexInSent())
        return null;
      return "Lemma=" + x.sentence.getLemma(x.index);
    });
    tokenExtractors.put("Shape", x -> {
      if (!x.indexInSent())
        return null;
      String shape = x.sentence.getShape(x.index);
      if (shape == null) {
        shape = PosPatternGenerator.shapeNormalize(x.sentence.getWord(x.index));
        x.sentence.setShape(x.index, shape);
      }
      if (shape == null)
        return null;
      return "Shape=" + shape;
    });
    tokenExtractors.put("Pos", x -> {
      if (x.indexInSent())
        return "Pos=" + x.sentence.getPos(x.index);
      else
        return null;
    });
    tokenExtractors.put("Pos2", x -> {
      if (x.indexInSent())
        return "Pos2=" + x.sentence.getPos(x.index).substring(0, 1);
      else
        return null;
    });
    tokenExtractors.put("BasicLabel", x -> {
      if (x.indexInSent()) {
        DependencyParse deps = x.sentence.getBasicDeps();
        if (deps == null)
          return null;
        return "BasicLabel=" + deps.getLabel(x.index);
      } else {
        return null;
      }
    });
    tokenExtractors.put("CollapsedLabel", x -> {
      if (x.indexInSent()) {
        DependencyParse deps = x.sentence.getCollapsedDeps();
        if (deps == null)
          return null;
        return "CollapsedLabel=" + deps.getLabel(x.index);
      } else {
        return null;
      }
    });
    tokenExtractors.put("BasicParentDir", x -> {
      if (x.indexInSent()) {
        DependencyParse deps = x.sentence.getBasicDeps();
        if (deps == null)
          return null;
        int h = deps.getHead(x.index);
        if (h < 0)
          return "BasicParentDir=root";
        else if (h < x.index)
          return "BasicParentDir=left";
        else
          return "BasicParentDir=right";
      } else {
        return null;
      }
    });
    tokenExtractors.put("CollapsedParentDir", x -> {
      if (x.indexInSent()) {
        DependencyParse deps = x.sentence.getCollapsedDeps();
        if (deps == null)
          return null;
        int h = deps.getHead(x.index);
        if (h < 0)
          return "CollapsedParentDir=root";
        else if (h < x.index)
          return "CollapsedParentDir=left";
        else
          return "CollapsedParentDir=right";
      } else {
        return null;
      }
    });
    for (int maxLen : Arrays.asList(8, 99)) {
      String name = "Bc256/" + maxLen;
      tokenExtractors.put(name, x -> {
        if (x.indexInSent()) {
          String w = x.sentence.getWord(x.index);
          return name + "=" + bc256.getPath(w, maxLen);
        } else {
          return null;
        }
      });
      String name2 = "Bc1000/" + maxLen;
      tokenExtractors.put(name2, x -> {
        if (x.indexInSent()) {
          String w = x.sentence.getWord(x.index);
          return name2 + "=" + bc1000.getPath(w, maxLen);
        } else {
          return null;
        }
      });
    }

    /* START OF TEMPLATES *****************************************************/
    
    
    /*
     * NEW
     */
  // lexpath: John <nsubj kill
  // bcpath: 011010 <nsubj 110100
  // lexarg: with <prep knife
  // bcarg: 000100 <prep 101011
    addTemplate("lexPredArg", new TemplateSS() {
      @Override
      String extractSS(TemplateContext context) {
        Sentence s = context.getSentence();
        DependencyParse d = s.getBasicDeps(false);
        int t = context.getTargetHead();
        int a = context.getArgHead();
        if (s == null || t < 0 || a < 0)
          return null;
        Path p = new Path(s, d, t, a, NodeType.LEMMA, EdgeType.DEP);
        return p.getPath();
      }
    });
    addTemplate("lexArgMod", new Template() {
      @Override
      public Iterable<String> extract(TemplateContext context) {
        Sentence s = context.getSentence();
        DependencyParse d = s.getBasicDeps(false);
        Span as = context.getSpan1();
        int a = context.getHead1();
        if (s == null || as == null || a < 0)
          return null;
        List<String> l = new ArrayList<>();
        for (int i = as.start; i < as.end; i++) {
          if (i == a)
            continue;
          Path p = new Path(s, d, a, i, NodeType.LEMMA, EdgeType.DEP);
          l.add(p.getPath());
        }
        return l;
      }
    });
    addTemplate("lexPredMod", new Template() {
      @Override
      public Iterable<String> extract(TemplateContext context) {
        Sentence s = context.getSentence();
        DependencyParse d = s.getBasicDeps(false);
        Span as = context.getSpan1();
        int t = context.getTargetHead();
        int a = context.getHead1();
        if (t < 0 || s == null || as == null || a < 0)
          return null;
        List<String> l = new ArrayList<>();
        int w = 10;
        int lo = Math.max(0, t - w);
        int hi = Math.min(s.size(), t + w);
        for (int i = lo; i < hi; i++) {
          if (i == t)
            continue;
          if (as.start <= i && i < as.end)
            continue;
          Path p = new Path(s, d, t, i, NodeType.LEMMA, EdgeType.DEP);
          if (p.size() <= 4)
            l.add(p.getPath());
        }
        return l;
      }
    });

    
    
    
    
    // intercept
    addTemplate("1", new TemplateSS() {
      @Override
      public String extractSS(TemplateContext context) {
        return "1";
      }
    });

    // head1
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name1 = "Head1-" + x.getKey();
      addTemplate(name1, new TemplateSS() {
        private SentencePosition pos = new SentencePosition();
        public String extractSS(TemplateContext context) {
          pos.index = context.getHead1();
          if (pos.index == TemplateContext.UNSET)
            return null;
          pos.sentence = context.getSentence();
          return name1 + "=" + x.getValue().apply(pos);
        }
      });
      String name2 = "Head2-" + x.getKey();
      addTemplate(name2, new TemplateSS() {
        private SentencePosition pos = new SentencePosition();
        public String extractSS(TemplateContext context) {
          pos.index = context.getHead2();
          if (pos.index == TemplateContext.UNSET)
            return null;
          pos.sentence = context.getSentence();
          return name2 + "=" + x.getValue().apply(pos);
        }
      });
    }

    Map<String, Function<Sentence, DependencyParse>> dps = new HashMap<>();
    dps.put("Basic", s -> s.getBasicDeps());
    dps.put("Collapsed", s -> s.getCollapsedDeps());
    for (Entry<String, Function<Sentence, DependencyParse>> dp : dps.entrySet()) {
      // head1 parent
      for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
        String name = "Head1-Parent-" + dp.getKey() + "-" + x.getKey();
        addTemplate(name, new TemplateSS() {
          private SentencePosition pos = new SentencePosition();
          private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
          public String extractSS(TemplateContext context) {
            int h = context.getHead1();
            if (h == TemplateContext.UNSET)
              return null;
            DependencyParse deps = extractDeps.apply(context.getSentence());
            if (deps == null)
              return null;
            pos.index = deps.getHead(h);
            pos.sentence = context.getSentence();
            return name + "=" + x.getValue().apply(pos);
          }
        });
      }
      // head2 parent
      for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
        String name = "Head2-Parent-" + dp.getKey() + "-" + x.getKey();
        addTemplate(name, new TemplateSS() {
          private SentencePosition pos = new SentencePosition();
          private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
          public String extractSS(TemplateContext context) {
            int h = context.getHead2();
            if (h == TemplateContext.UNSET)
              return null;
            DependencyParse deps = extractDeps.apply(context.getSentence());
            if (deps == null)
              return null;
            pos.index = deps.getHead(h);
            pos.sentence = context.getSentence();
            return name + "=" + x.getValue().apply(pos);
          }
        });
      }

      // head1 grandparent
      for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
        String name = "Head1-Grandparent-" + dp.getKey() + "-" + x.getKey();
        addTemplate(name, new TemplateSS() {
          private SentencePosition pos = new SentencePosition();
          private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
          public String extractSS(TemplateContext context) {
            int h = context.getHead1();
            if (h == TemplateContext.UNSET)
              return null;
            DependencyParse deps = extractDeps.apply(context.getSentence());
            if (deps == null)
              return null;
            pos.index = deps.getHead(h);
            if (pos.index < 0)
              return null;
            pos.index = deps.getHead(pos.index);
            pos.sentence = context.getSentence();
            return name + "=" + x.getValue().apply(pos);
          }
        });
      }
      // head2 grandparent
      for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
        String name = "Head2-Grandparent-" + dp.getKey() + "-" + x.getKey();
        addTemplate(name, new TemplateSS() {
          private SentencePosition pos = new SentencePosition();
          private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
          public String extractSS(TemplateContext context) {
            int h = context.getHead2();
            if (h == TemplateContext.UNSET)
              return null;
            DependencyParse deps = extractDeps.apply(context.getSentence());
            if (deps == null)
              return null;
            pos.index = deps.getHead(h);
            if (pos.index < 0)
              return null;
            pos.index = deps.getHead(pos.index);
            pos.sentence = context.getSentence();
            return name + "=" + x.getValue().apply(pos);
          }
        });
      }

      // head1 children
      for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
        String name = "Head1-Child-" + dp.getKey() +"-" + x.getKey();
        addTemplate(name, new Template() {
          private SentencePosition pos = new SentencePosition();
          private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
          public Iterable<String> extract(TemplateContext context) {
            int h = context.getHead1();
            if (h == TemplateContext.UNSET)
              return null;
            DependencyParse d = extractDeps.apply(context.getSentence());
            if (d == null)
              return null;
            int[] c = d.getChildren(h);
            if (c.length == 0)
              return Arrays.asList(name + "=NONE");
            else {
              pos.sentence = context.getSentence();
              List<String> cs = new ArrayList<>();
              for (int cd : c) {
                pos.index = cd;
                cs.add(name + "=" + x.getValue().apply(pos));
              }
              return cs;
            }
          }
        });
      }
      // head2 children
      for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
        String name = "Head2-Child-" + dp.getKey() +"-" + x.getKey();
        addTemplate(name, new Template() {
          private SentencePosition pos = new SentencePosition();
          private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
          public Iterable<String> extract(TemplateContext context) {
            int h = context.getHead2();
            if (h == TemplateContext.UNSET)
              return null;
            DependencyParse d = extractDeps.apply(context.getSentence());
            if (d == null)
              return null;
            int[] c = d.getChildren(h);
            if (c.length == 0)
              return Arrays.asList(name + "=NONE");
            else {
              pos.sentence = context.getSentence();
              List<String> cs = new ArrayList<>();
              for (int cd : c) {
                pos.index = cd;
                cs.add(name + "=" + x.getValue().apply(pos));
              }
              return cs;
            }
          }
        });
      }
    }

    // left, first, last, right
    Map<String, ToIntFunction<Span>> spanLocs = new HashMap<>();
    spanLocs.put("Left", x -> x.start - 1);
    spanLocs.put("First", x -> x.start);
    spanLocs.put("Last", x -> x.end - 1);
    spanLocs.put("Right", x -> x.end);
    for (Entry<String, Function<SentencePosition, String>> ex1 :
        tokenExtractors.entrySet()) {
      for (Entry<String, ToIntFunction<Span>> loc1 : spanLocs.entrySet()) {
        String name1 = "Span1-" + loc1.getKey() + "-" + ex1.getKey();
        TemplateSS temp1 = new TemplateSS() {
          private SentencePosition pos = new SentencePosition();
          public String extractSS(TemplateContext context) {
            Span s = context.getSpan1();
            if (s == null)
              return null;
            pos.index = loc1.getValue().applyAsInt(s);
            pos.sentence = context.getSentence();
            return name1 + "=" + ex1.getValue().apply(pos);
          }
        };
        addTemplate(name1, temp1);
        String name2 = "Span2-" + loc1.getKey() + "-" + ex1.getKey();
        TemplateSS temp2 = new TemplateSS() {
          private SentencePosition pos = new SentencePosition();
          public String extractSS(TemplateContext context) {
            Span s = context.getSpan2();
            if (s == null)
              return null;
            pos.index = loc1.getValue().applyAsInt(s);
            pos.sentence = context.getSentence();
            return name2 + "=" + ex1.getValue().apply(pos);
          }
        };
        addTemplate(name2, temp2);
      }
    }

    // head1 to span features
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "Head1-ToLeft-" + x.getKey();
      addTemplate(name, new Template() {
        private Function<SentencePosition, String> ext = x.getValue();
        private String namePref = name + "=";
        private SentencePosition pos = new SentencePosition();
        @Override
        public Iterable<String> extract(TemplateContext context) {
          int h = context.getHead1();
          if (h == TemplateContext.UNSET)
            return null;
          Span s = context.getSpan1();
          if (s == null)
            return null;
          if (s.start >= h)
            return null;
          Collection<String> elems = new HashSet<>();
          pos.sentence = context.getSentence();
          for (pos.index = h - 1; pos.index >= s.start; pos.index--)
            elems.add(namePref + ext.apply(pos));
          return elems;
        }
      });
    }
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "Head1-ToRight-" + x.getKey();
      addTemplate(name, new Template() {
        private Function<SentencePosition, String> ext = x.getValue();
        private String namePref = name + "=";
        private SentencePosition pos = new SentencePosition();
        @Override
        public Iterable<String> extract(TemplateContext context) {
          int h = context.getHead1();
          if (h == TemplateContext.UNSET)
            return null;
          Span s = context.getSpan1();
          if (s == null)
            return null;
          if (h >= s.end)
            return null;
          Collection<String> elems = new HashSet<>();
          pos.sentence = context.getSentence();
          for (pos.index = h + 1; pos.index < s.end; pos.index++)
            elems.add(namePref + ext.apply(pos));
          return elems;
        }
      });
    }
    // head2 to span features
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "Head2-ToLeft-" + x.getKey();
      addTemplate(name, new Template() {
        private Function<SentencePosition, String> ext = x.getValue();
        private String namePref = name + "=";
        private SentencePosition pos = new SentencePosition();
        @Override
        public Iterable<String> extract(TemplateContext context) {
          int h = context.getHead2();
          if (h == TemplateContext.UNSET)
            return null;
          Span s = context.getSpan1();
          if (s == null)
            return null;
          if (s.start >= h)
            return null;
          Collection<String> elems = new HashSet<>();
          pos.sentence = context.getSentence();
          for (pos.index = h - 1; pos.index >= s.start; pos.index--)
            elems.add(namePref + ext.apply(pos));
          return elems;
        }
      });
    }
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "Head2-ToRight-" + x.getKey();
      addTemplate(name, new Template() {
        private Function<SentencePosition, String> ext = x.getValue();
        private String namePref = name + "=";
        private SentencePosition pos = new SentencePosition();
        @Override
        public Iterable<String> extract(TemplateContext context) {
          int h = context.getHead2();
          if (h == TemplateContext.UNSET)
            return null;
          Span s = context.getSpan1();
          if (s == null)
            return null;
          if (h >= s.end)
            return null;
          Collection<String> elems = new HashSet<>();
          pos.sentence = context.getSentence();
          for (pos.index = h + 1; pos.index < s.end; pos.index++)
            elems.add(namePref + ext.apply(pos));
          return elems;
        }
      });
    }

    // span1 width
    for (int div : Arrays.asList(1, 2, 3)) {
      final int divL = div;
      final String name = "Span1-Width-Div" + div;
      addTemplate(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan1();
          if (t == null)
            return null;
          return discretizeWidth(name, divL, 6, t.width());
        }
      });
    }
    addTemplate("Span1-MultiWord", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span t = context.getSpan1();
        if (t == null)
          return null;
        if (t.width() > 1)
          return "Y";
        return null;
      }
    });
    addTemplate("Span2-MultiWord", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span t = context.getSpan2();
        if (t == null)
          return null;
        if (t.width() > 1)
          return "Y";
        return null;
      }
    });

    // span1 depth
    for (int coarse : Arrays.asList(1)) {
      String name = "Span1-DepthCol" + coarse;
      addTemplate(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan1();
          if (t == null)
            return null;
          Sentence s = context.getSentence();
          DependencyParse deps = s.getBasicDeps();
          if (deps == null)
            return null;
          int min = 99;
          for (int i = t.start; i < t.end; i++) {
            int d = deps.getDepth(i);
            if (d < min) min = d;
          }
          return discretizeWidth(name, coarse, 6, min);
        }
      });
    }

    // TODO max child depth
    // TODO count children left|right

    // TODO distance to first POS going left|right
    // TODO count of POS to the left|right

    addTemplate("Span1-GovDirRelations", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span s = context.getSpan1();
        if (s == null)
          return null;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        if (deps == null)
          return null;
        List<String> rels = new ArrayList<>();
        for (int i = s.start; i < s.end; i++)
          rels.add(parentRelTo(i, s.start, s.end - 1, deps));
//        Collections.sort(rels);
        StringBuilder rs = new StringBuilder();
        rs.append("Span1-GovDirRelations");
        if (rels.size() == 0) {
          rs.append("_NONE");
        } else {
          for (String rel : rels) {
            rs.append("_");
            rs.append(rel);
          }
        }
        return rs.toString();
      }
    });
    addTemplate("Span2-GovDirRelations", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span s = context.getSpan2();
        if (s == null)
          return null;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        if (deps == null)
          return null;
        List<String> rels = new ArrayList<>();
        for (int i = s.start; i < s.end; i++)
          rels.add(parentRelTo(i, s.start, s.end - 1, deps));
//        Collections.sort(rels);
        StringBuilder rs = new StringBuilder();
        rs.append("Span2-GovDirRelations");
        if (rels.size() == 0) {
          rs.append("_NONE");
        } else {
          for (String rel : rels) {
            rs.append("_");
            rs.append(rel);
          }
        }
        return rs.toString();
      }
    });

    // span1 left projection
    for (int coarse : Arrays.asList(2)) {
      String name = "Span1-LeftProjCol-" + coarse;
      addTemplate(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan1();
          if (t == null)
            return null;
          Sentence s = context.getSentence();
          DependencyParse deps = s.getBasicDeps();
          if (deps == null)
            return null;
          int min = s.size() + 1;
          for (int i = t.start; i < t.end; i++) {
            int d = deps.getProjLeft(i);
            if (d < min) min = d;
          }
          assert min <= s.size();
          int toksLeft = t.start - min;
          return discretizeWidth(name, coarse, 6, toksLeft);
        }
      });
      String name2 = "Span2-LeftProjCol-" + coarse;
      addTemplate(name2, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan2();
          if (t == null)
            return null;
          Sentence s = context.getSentence();
          DependencyParse deps = s.getBasicDeps();
          if (deps == null)
            return null;
          int min = s.size() + 1;
          for (int i = t.start; i < t.end; i++) {
            int d = deps.getProjLeft(i);
            if (d < min) min = d;
          }
          assert min <= s.size();
          int toksLeft = t.start - min;
          return discretizeWidth(name2, coarse, 6, toksLeft);
        }
      });
    }
    // right projection
    for (int coarse : Arrays.asList(2)) {
      String name = "Span1-RightProjCol-" + coarse;
      addTemplate(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan1();
          if (t == null)
            return null;
          Sentence s = context.getSentence();
          DependencyParse deps = s.getBasicDeps();
          if (deps == null)
            return null;
          int max = 0;
          for (int i = t.start; i < t.end; i++) {
            int d = deps.getProjRight(i);
            if (d > max) max = d;
          }
          int toksRight = (max - t.end) + 1;
          return discretizeWidth(name, coarse, 6, toksRight);
        }
      });
      String name2 = "Span2-RightProjCol-" + coarse;
      addTemplate(name2, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan2();
          if (t == null)
            return null;
          Sentence s = context.getSentence();
          DependencyParse deps = s.getBasicDeps();
          if (deps == null)
            return null;
          int max = 0;
          for (int i = t.start; i < t.end; i++) {
            int d = deps.getProjRight(i);
            if (d > max) max = d;
          }
          int toksRight = (max - t.end) + 1;
          return discretizeWidth(name2, coarse, 6, toksRight);
        }
      });
    }

    // path features
    for (Entry<String, Function<Sentence, DependencyParse>> dp : dps.entrySet()) {
      for (Path.NodeType nt : Path.NodeType.values()) {
        for (Path.EdgeType et : Path.EdgeType.values()) {
          String name1 = "Head1-RootPath-" + dp.getKey() + "-" + nt + "-" + et + "-t";
          addTemplate(name1, new TemplateSS() {
            private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
            public String extractSS(TemplateContext context) {
              int h = context.getHead1();
              if (h == TemplateContext.UNSET)
                return null;
              Sentence s = context.getSentence();
              DependencyParse deps = extractDeps.apply(s);
              if (deps == null)
                return null;
              Path p = new Path(s, deps, h, nt, et);
              return name1 + "=" + p.getPath();
            }
          });
          String name3 = "Head2-RootPath-" + dp.getKey() + "-" + nt + "-" + et + "-t";
          addTemplate(name3, new TemplateSS() {
            private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
            public String extractSS(TemplateContext context) {
              int h = context.getHead2();
              if (h == TemplateContext.UNSET)
                return null;
              Sentence s = context.getSentence();
              DependencyParse deps = extractDeps.apply(s);
              if (deps == null)
                return null;
              Path p = new Path(s, deps, h, nt, et);
              return name3 + "=" + p.getPath();
            }
          });
          String name2 = "Head1Head2-Path-" + dp.getKey() + "-" + nt + "-" + et + "-t";
          addTemplate(name2, new TemplateSS() {
            private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
            public String extractSS(TemplateContext context) {
              int h1 = context.getHead1();
              if (h1 == TemplateContext.UNSET)
                return null;
              int h2 = context.getHead2();
              if (h2 == TemplateContext.UNSET)
                return null;
              Sentence s = context.getSentence();
              DependencyParse deps = extractDeps.apply(s);
              if (deps == null)
                return null;
              Path p = new Path(s, deps, h1, h2, nt, et);
              return name2 + "=" + p.getPath();
            }
          });
          for (int length : Arrays.asList(1, 2, 3)) {
            String nameL = "Head1-RootPathNgram-" + dp.getKey() + "-" + nt + "-" + et + "-len" + length;
            addTemplate(nameL, new Template() {
              private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
              public Iterable<String> extract(TemplateContext context) {
                int h = context.getHead1();
                if (h == TemplateContext.UNSET)
                  return null;
                Sentence s = context.getSentence();
                DependencyParse deps = extractDeps.apply(s);
                if (deps == null)
                  return null;
                Path p = new Path(s, deps, h, nt, et);
                Set<String> pieces = new HashSet<>();
                p.pathNGrams(length, pieces, nameL + "=");
                return pieces;
              }
            });
            String name4 = "Head2-RootPathNgram-" + dp.getKey() + "-" + nt + "-" + et + "-len" + length;
            addTemplate(name4, new Template() {
              private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
              public Iterable<String> extract(TemplateContext context) {
                int h = context.getHead2();
                if (h == TemplateContext.UNSET)
                  return null;
                Sentence s = context.getSentence();
                DependencyParse deps = extractDeps.apply(s);
                if (deps == null)
                  return null;
                Path p = new Path(s, deps, h, nt, et);
                Set<String> pieces = new HashSet<>();
                p.pathNGrams(length, pieces, name4 + "=");
                return pieces;
              }
            });
            String nameL2 = "Head1Head2-PathNgram-" + dp.getKey() + "-" + nt + "-" + et + "-len" + length;
            addTemplate(nameL2, new Template() {
              private Function<Sentence, DependencyParse> extractDeps = dp.getValue();
              public Iterable<String> extract(TemplateContext context) {
                int h1 = context.getHead1();
                if (h1 == TemplateContext.UNSET)
                  return null;
                int h2 = context.getHead2();
                if (h2 == TemplateContext.UNSET)
                  return null;
                Sentence s = context.getSentence();
                DependencyParse deps = extractDeps.apply(s);
                if (deps == null)
                  return null;
                Path p = new Path(s, deps, h1, h2, nt, et);
                Set<String> pieces = new HashSet<>();
                p.pathNGrams(length, pieces, nameL2 + "=");
                return pieces;
              }
            });
          }
        }
      }
    }

    // ********** PosPatternGenerator ******************************************
    for (PosPatternGenerator.Mode mode : PosPatternGenerator.Mode.values()) {
      for (int tagsLeft : Arrays.asList(0, 1, 2, 3, 4)) {
        for (int tagsRight : Arrays.asList(0, 1, 2, 3, 4)) {
          if (tagsLeft + tagsRight > 4) continue;
          String name = String.format("Span1-PosPat-%s-%d-%d", mode, tagsLeft, tagsRight);
          addTemplate(name, new TemplateSS() {
            private PosPatternGenerator pat
              = new PosPatternGenerator(tagsLeft, tagsRight, mode);
            @Override
            String extractSS(TemplateContext context) {
              Span s = context.getSpan1();
              if (s == null)
                return null;
              if (s.width() > 10 + tagsLeft + tagsRight)
                return null;
//                return name + "=TOO_BIG";
              return name + "=" + pat.extract(s, context.getSentence());
            }
          });

          String name2 = String.format("Span2-PosPat-%s-%d-%d", mode, tagsLeft, tagsRight);
          addTemplate(name2, new TemplateSS() {
            private PosPatternGenerator pat
              = new PosPatternGenerator(tagsLeft, tagsRight, mode);
            @Override
            String extractSS(TemplateContext context) {
              Span s = context.getSpan2();
              if (s == null)
                return null;
              if (s.width() > 10 + tagsLeft + tagsRight)
                return null;
//                return name2 + "=TOO_BIG";
              return name2 + "=" + pat.extract(s, context.getSentence());
            }
          });
        }
      }
    }

    // ********** Constituency parse features **********************************
    // basic:
    List<Pair<String, Function<TemplateContext, Span>>> spanExtractors = new ArrayList<>();
    spanExtractors.add(new Pair<>("Span1", tc -> tc.getSpan1()));
    spanExtractors.add(new Pair<>("Span2", tc -> tc.getSpan2()));
    spanExtractors.add(new Pair<>("CommonParent", tc -> {
      ConstituencyParse cp = tc.getSentence().getStanfordParse();
      if (cp == null)
        return null;
      Span s1 = tc.getSpan1();
      Span s2 = tc.getSpan2();
      if (s1 == null || s2 == null)
        return null;
      Node n1 = cp.getConstituent(s1);
      Node n2 = cp.getConstituent(s2);
      if (n1 == null || n2 == null)
        return null;
      Node n3 = cp.getCommonParent(n1, n2);
      if (n3 == null)
        return null;
      return n3.getSpan();
    }));

    for (Pair<String, Function<TemplateContext, Span>> se : spanExtractors) {
      addTemplate(se.get1() + "-StanfordDepth", new TemplateSS() {
        String extractSS(TemplateContext context) {
          Span s = se.get2().apply(context);
          if (s == null)
            return null;
          ConstituencyParse cp = context.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n != null)
            return se.get1() + "-StanfordDepth=" + n.getDepth();
          return null;
        }
      });
//      addTemplate(se.get1() + "-StanfordCategory", new TemplateSS() {
//        String extractSS(TemplateContext context) {
//          Span s = se.get2().apply(context);
//          if (s == null)
//            return null;
//          ConstituencyParse cp = context.getSentence().getStanfordParse();
//          if (cp == null)
//            return null;
//          ConstituencyParse.Node n = cp.getConstituent(s);
//          String cat = "NONE";
//          if (n != null)
//            cat = n.getTag();
//          return se.get1() + "-StanfordCategory=" + cat;
//        }
//      });
      addTemplate(se.get1() + "-StanfordCategory2", new TemplateSS() {
        String extractSS(TemplateContext context) {
          Span s = se.get2().apply(context);
          if (s == null)
            return null;
          ConstituencyParse cp = context.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n != null)
            return se.get1() + "-StanfordCategory2=" + n.getTag();
          return null;
        }
      });
//      addTemplate(se.get1() + "-StanfordRule", new TemplateSS() {
//        String extractSS(TemplateContext context) {
//          Span s = se.get2().apply(context);
//          if (s == null)
//            return null;
//          ConstituencyParse cp = context.getSentence().getStanfordParse();
//          if (cp == null)
//            return null;
//          ConstituencyParse.Node n = cp.getConstituent(s);
//          String rule = "NONE";
//          if (n != null)
//            rule = n.getRule();
//          return se.get1() + "-IsStanfordRule=" + rule;
//        }
//      });
      addTemplate(se.get1() + "-StanfordRule2", new TemplateSS() {
        String extractSS(TemplateContext context) {
          Span s = se.get2().apply(context);
          if (s == null)
            return null;
          ConstituencyParse cp = context.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n != null)
            return se.get1() + "-IsStanfordRule2=" + n.getRule();
          return null;
        }
      });
    }

    // Values of this map have the following interpretation:
    // Entries in the outer list should fire as separate features (a bag of features)
    // Entries in the inner list should be conjoined into one feature string
    SortedMap<String,
      Function<TemplateContext,
        List<List<ConstituencyParse.NodePathPiece>>>> node2Path = new TreeMap<>();
    for (Pair<String, Function<TemplateContext, Span>> se : spanExtractors) {
      node2Path.put(se.get1() + "-DirectChildren", new Function<TemplateContext, List<List<ConstituencyParse.NodePathPiece>>>() {
        public List<List<NodePathPiece>> apply(TemplateContext t) {
          Span s = se.get2().apply(t);
          if (s == null)
            return null;
          ConstituencyParse cp = t.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n == null)
            return null;
          List<NodePathPiece> children = new ArrayList<>();
          for (Node c : n.getChildren())
            children.add(new NodePathPiece(c, null));
          return Arrays.asList(children);
        }
      });
      node2Path.put(se.get1() + "-AllChildrenBag", new Function<TemplateContext, List<List<ConstituencyParse.NodePathPiece>>>() {
        public List<List<NodePathPiece>> apply(TemplateContext t) {
          Span s = se.get2().apply(t);
          if (s == null)
            return null;
          ConstituencyParse cp = t.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n == null)
            return null;
          List<List<NodePathPiece>> children = new ArrayList<>();
          helper(n, children);
          if (children.size() == 0)
            return null;
          return children;
        }
        private void helper(ConstituencyParse.Node n, List<List<ConstituencyParse.NodePathPiece>> addTo) {
          addTo.add(Arrays.asList(new NodePathPiece(n, null)));
          for (ConstituencyParse.Node c : n.getChildren())
            helper(c, addTo);
        }
      });
      node2Path.put(se.get1() + "-ToRootPath", new Function<TemplateContext, List<List<ConstituencyParse.NodePathPiece>>>() {
        public List<List<NodePathPiece>> apply(TemplateContext t) {
          Span s = se.get2().apply(t);
          if (s == null)
            return null;
          ConstituencyParse cp = t.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n == null)
            return null;
          List<NodePathPiece> parents = new ArrayList<>();
          while (n != null) {
            parents.add(new NodePathPiece(n, null));
            n = n.getParent();
          }
          assert parents.size() > 0;
          if (parents.size() > 5)
            return Collections.emptyList();
          return Arrays.asList(parents);
        }
      });
      node2Path.put(se.get1() + "-ToRootBag", new Function<TemplateContext, List<List<ConstituencyParse.NodePathPiece>>>() {
        public List<List<NodePathPiece>> apply(TemplateContext t) {
          Span s = se.get2().apply(t);
          if (s == null)
            return null;
          ConstituencyParse cp = t.getSentence().getStanfordParse();
          if (cp == null)
            return null;
          ConstituencyParse.Node n = cp.getConstituent(s);
          if (n == null)
            return null;
          List<List<NodePathPiece>> parents = new ArrayList<>();
          while (n != null) {
            parents.add(Arrays.asList(new NodePathPiece(n, ">")));
            n = n.getParent();
          }
          assert parents.size() > 0;
          return parents;
        }
      });
    }
    node2Path.put("CommonParent", new Function<TemplateContext, List<List<ConstituencyParse.NodePathPiece>>>() {
      public List<List<NodePathPiece>> apply(TemplateContext t) {
        Span s1 = t.getSpan1();
        if (s1 == null)
          return null;
        Span s2 = t.getSpan2();
        if (s2 == null)
          return null;
        ConstituencyParse cp = t.getSentence().getStanfordParse();
        if (cp == null)
          return null;
        ConstituencyParse.Node n1 = cp.getConstituent(s1);
        if (n1 == null)
          return null;
        ConstituencyParse.Node n2 = cp.getConstituent(s2);
        if (n2 == null)
          return null;
        List<NodePathPiece> n1up = new ArrayList<>();
        Map<ConstituencyParse.Node, Integer> n1upseen = new HashMap<>();
        ConstituencyParse.Node n = n1;
        while (n != null) {
          n1up.add(new NodePathPiece(n, ">"));
          n1upseen.put(n, n1up.size() - 1);
          n = n.getParent();
        }
        int match = -1;
        List<NodePathPiece> n2up = new ArrayList<>();
        n = n2;
        while (n != null) {
          n2up.add(new NodePathPiece(n, "<"));
          Integer idx = n1upseen.get(n);
          if (idx != null) {
            match = idx;
            break;
          }
          n = n.getParent();
        }
        if (match < 0)
          return null;
        List<NodePathPiece> common = new ArrayList<>();
        common.addAll(n1up.subList(0, match + 1));
        common.addAll(n2up);
        return Arrays.asList(common);
      }
    });

    SortedMap<String, Function<List<ConstituencyParse.NodePathPiece>, String>>
      path2Feat = new TreeMap<>();
    path2Feat.put("Category", new Function<List<ConstituencyParse.NodePathPiece>, String>() {
      public String apply(List<NodePathPiece> t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Category:");
        boolean first = true;
        for (NodePathPiece npp : t) {
          if (!first) {
            String e = npp.getEdge();
            if (e == null) e = "_";
            sb.append(e);
          }
          first = false;
          sb.append(npp.getNode().getTag());
        }
        return sb.toString();
      }
    });
    path2Feat.put("Rule", new Function<List<ConstituencyParse.NodePathPiece>, String>() {
      public String apply(List<NodePathPiece> t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rule:");
        boolean first = true;
        for (NodePathPiece npp : t) {
          if (!first) {
            String e = npp.getEdge();
            if (e == null) e = "_";
            sb.append(e);
          }
          first = false;
          sb.append(npp.getNode().getRule());
        }
        return sb.toString();
      }
    });
    path2Feat.put("DeltaDepth", new Function<List<ConstituencyParse.NodePathPiece>, String>() {
      public String apply(List<NodePathPiece> t) {
        if (t.size() < 2)
          return "TOO-SHORT";
        int a = t.get(0).getNode().getDepth();
        int b = t.get(t.size() - 1).getNode().getDepth();
        return "DeltaDepth=" + (a - b);
      }
    });

    for (Entry<String, Function<TemplateContext, List<List<NodePathPiece>>>> a : node2Path.entrySet()) {
      for (Entry<String, Function<List<ConstituencyParse.NodePathPiece>, String>> b : path2Feat.entrySet()) {
        String name = String.format("CfgFeat-%s-%s", a.getKey(), b.getKey());
        addTemplate(name, new Template() {
          private Function<TemplateContext, List<List<NodePathPiece>>> extract = a.getValue();
          private Function<List<NodePathPiece>, String> collapse = b.getValue();
          @Override
          public Iterable<String> extract(TemplateContext context) {
            List<List<NodePathPiece>> paths = extract.apply(context);
            if (paths == null || paths.size() == 0)
              return null;
            //Collection<String> feats = new ArrayList<>();
            Collection<String> feats = new HashSet<>();
            for (List<NodePathPiece> p : paths)
              feats.add(name + "=" + collapse.apply(p));
            return feats;
          }
        });
      }
    }


    /* FRAME-TARGET FEATURES **************************************************/
//    addTemplate("luMatch", new TemplateSS() {
//      public String extractSS(TemplateContext context) {
//        Span t = context.getTarget();
//        if (t == null)
//          return null;
//        if (t.width() != 1)
//          return null;
//        TargetPruningData tpd = TargetPruningData.getInstance();
//        LexicalUnit lu = context.getSentence().getFNStyleLU(
//            t.start, tpd.getWordnetDict(), true);
//        Frame f = context.getFrame();
//        if (tpd.getFramesFromLU(lu).contains(f))
//          return "luMatch";
//        else
//          return null;
//      }
//    });
//    addTemplate("luMatch-WNSynSet", new TemplateSS() {
//      public String extractSS(TemplateContext context) {
//        Span t = context.getTarget();
//        if (t == null)
//          return null;
//        if (t.width() != 1)
//          return null;
//        TargetPruningData tpd = TargetPruningData.getInstance();
//        IWord word = context.getSentence().getWnWord(t.start);
//        if (word == null)
//          return null;
//        Set<IWord> synset = new HashSet<>();
//        synset.addAll(word.getSynset().getWords());
//        boolean hadAChance = false;
//        int c = 0;
//        for (FrameInstance p : tpd.getPrototypesByFrame(context.getFrame())) {
//          hadAChance = true;
//          // see if syn-set match for (head1, prototype)
//          Span pt = p.getTarget();
//          if (pt.width() != 1)
//            continue;
//          IWord otherWord = p.getSentence().getWnWord(pt.start);
//          if (synset.contains(otherWord))
//            c++;
//        }
//        if (c == 0 && hadAChance)
//          return "NO-luMatch-WNSynSet";
//        c = (int) Math.pow(c, 0.6d);
//        return "luMatch-WnSynSet=" + c;
//      }
//    });
//    addTemplate("luMatch-WNRelated", new Template() {
//      public Iterable<String> extract(TemplateContext context) {
//        Span t = context.getTarget();
//        if (t == null)
//          return null;
//        if (t.width() != 1)
//          return null;
//        List<String> ret = new ArrayList<>();
//        TargetPruningData tpd = TargetPruningData.getInstance();
//        IWord word = context.getSentence().getWnWord(t.start);
//        if (word == null)
//          return null;
//        Map<IPointer, List<IWordID>> rel = word.getRelatedMap();
//        boolean hadAChance = false;
//        for (FrameInstance p : tpd.getPrototypesByFrame(context.getFrame())) {
//          hadAChance = true;
//          // see if syn-set match for (head1, prototype)
//          Span pt = p.getTarget();
//          if (pt.width() != 1)
//            continue;
//          IWord otherWord = p.getSentence().getWnWord(pt.start);
//          if (otherWord == null)
//            continue;
//          for (Map.Entry<IPointer, List<IWordID>> x : rel.entrySet()) {
//            if (x.getValue().contains(otherWord.getID()))
//              ret.add("luMatch-WNRelated=" + x.getKey().getName());
//          }
//        }
//        if (ret.size() == 0) {
//          if (hadAChance)
//            ret.add("NO-luMatch-WNRelated");
//          else return null;
//        }
//        return ret;
//      }
//    });
//    addTemplate("luMatch-WNRelatedSynSet", new Template() {
//      public Iterable<String> extract(TemplateContext context) {
//        Span t = context.getTarget();
//        if (t == null)
//          return null;
//        if (t.width() != 1)
//          return null;
//        List<String> ret = new ArrayList<>();
//        TargetPruningData tpd = TargetPruningData.getInstance();
//        IWord word = context.getSentence().getWnWord(t.start);
//        if (word == null)
//          return null;
//        Map<IPointer, List<ISynsetID>> relSS = word.getSynset().getRelatedMap();
//        boolean hadAChance = false;
//        for (FrameInstance p : tpd.getPrototypesByFrame(context.getFrame())) {
//          hadAChance = true;
//          // see if syn-set match for (head1, prototype)
//          Span pt = p.getTarget();
//          if (pt.width() != 1)
//            continue;
//          IWord otherWord = p.getSentence().getWnWord(pt.start);
//          if (otherWord == null)
//            continue;
//          for (Map.Entry<IPointer, List<ISynsetID>> x : relSS.entrySet()) {
//            for (ISynsetID ssid : x.getValue()) {
//              ISynset ss = tpd.getWordnetDict().getSynset(ssid);
//              if (ss.getWords().contains(otherWord))
//                ret.add("luMatch-WNRelatedSynSet=" + x.getKey().getName());
//            }
//          }
//        }
//        if (ret.size() == 0) {
//          if (hadAChance)
//            ret.add("NO-luMatch-WNRelatedSynSet");
//          else return null;
//        }
//        return ret;
//      }
//    });

    /* FRAME-ROLE FEATURES ****************************************************/
    addTemplate("argHeadRelation1", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int t = context.getTargetHead();
        if (t == TemplateContext.UNSET)
          return null;
        int a = context.getArgHead();
        if (a == TemplateContext.UNSET)
          return null;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        if (deps == null)
          return null;
        return "argHeadRelation1=" + parentRelTo(t, t, a, deps);
      }
    });
    addTemplate("argHeadRelation2", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int t = context.getTargetHead();
        if (t == TemplateContext.UNSET)
          return null;
        int a = context.getArgHead();
        if (a == TemplateContext.UNSET)
          return null;
        Span s = context.getArg();
        assert s != null;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        if (deps == null)
          return null;
        return "argHeadRelation2=" + parentRelTo(t, s.start, s.end - 1, deps);
      }
    });

    // span overlap feature
    addTemplate("span1span2Overlap", new TemplateSS() {
      @Override
      String extractSS(TemplateContext context) {
        Span s1 = context.getSpan1();
        if (s1 == null || s1 == Span.nullSpan)
          return null;
        Span s2 = context.getSpan2();
        if (s2 == null || s2 == Span.nullSpan)
          return null;
        return "overlap=" + rel(s1.start, s2.start) + "-" + rel(s1.end, s2.end);
        //return String.format("overlap=%s-%s",
        //    rel(s1.start, s2.start), rel(s1.end, s2.end));
      }
      private String rel(int i, int j) {
        if (i == j) return "E";
        if (i < j) return "L";
        return "R";
      }
    });

    /* SENTENCE FEATURES ******************************************************/
    for (Map.Entry<String, Function<SentencePosition, String>> x :
      tokenExtractors.entrySet()) {
      // bag of words for entire sentence
      String name = "sentence" + x.getKey();
      addTemplate(name, new Template() {
        private SentencePosition pos = new SentencePosition();
        public Iterable<String> extract(TemplateContext context) {
          pos.sentence = context.getSentence();
          Set<String> words = new HashSet<String>();
          for (pos.index = 0;
              pos.index < pos.sentence.size();
              pos.index++) {
            words.add(name + "=" + x.getValue().apply(pos));
          }
          if (words.size() == 0)
            words.add(name + "=NONE");
          return words;
        }
      });
    }

    // distance between head1/head2 and span1/span2
    Map<String, ToIntFunction<TemplateContext>> distancePoints = new HashMap<>();
    distancePoints.put("<S>", ctx -> 0);
    distancePoints.put("</S>", ctx -> ctx.getSentence().size() - 1);
    distancePoints.put("Head1", ctx -> ctx.getHead1());
    distancePoints.put("Head2", ctx -> ctx.getHead2());
    for (String locName : Arrays.asList("First", "Last")) {
      ToIntFunction<Span> f = spanLocs.get(locName);
      distancePoints.put("Span1." + locName, ctx -> ctx.getSpan1() == null
          ? TemplateContext.UNSET : f.applyAsInt(ctx.getSpan1())
      );
      distancePoints.put("Span2." + locName, ctx -> ctx.getSpan2() == null
          ? TemplateContext.UNSET : f.applyAsInt(ctx.getSpan2())
      );
    }
    Map<String, IntFunction<String>> distanceBucketings = new HashMap<>();
    distanceBucketings.put("SemaforPathLengths",
        len -> semaforPathLengthBuckets(len));
    distanceBucketings.put("Direction", len -> len == 0
        ? "0" : (len < 0 ? "-" : "+"));
//    distanceBucketings.put("Len5", len -> Math.abs(len) <= 5
//        ? String.valueOf(len) : (len < 0 ? "-" : "+"));
    for (int div = 1; div <= 5; div++) {
      final int divL = div;
      final String name = "discLen" + div;
      distanceBucketings.put(name, stringLength -> discretizeWidth(name, divL, 8, stringLength));
    }
    List<String> dpKeys = new ArrayList<>();
    dpKeys.addAll(distancePoints.keySet());
    Collections.sort(dpKeys);
    for (int i = 0; i < dpKeys.size() - 1; i++) {
      for (int j = i + 1; j < dpKeys.size(); j++) {
        String p1k = dpKeys.get(i);
        String p2k = dpKeys.get(j);
        ToIntFunction<TemplateContext> p1v = distancePoints.get(p1k);
        ToIntFunction<TemplateContext> p2v = distancePoints.get(p2k);
        // Distance between two points
        for (Entry<String, IntFunction<String>> d : distanceBucketings.entrySet()) {
          String name = String.format("Dist-%s-%s-%s", d.getKey(), p1k, p2k);
          addTemplate(name, new TemplateSS() {
            @Override
            String extractSS(TemplateContext context) {
              int c1 = p1v.applyAsInt(context);
              if (c1 == TemplateContext.UNSET)
                return null;
              int c2 = p2v.applyAsInt(context);
              if (c2 == TemplateContext.UNSET)
                return null;
              return name + "=" + d.getValue().apply(c1 - c2);
            }
          });
        }
        // N-grams between the two points
        for (int n = 1; n <= 2; n++) {
          for (Map.Entry<String, Function<SentencePosition, String>> ext : tokenExtractors.entrySet()) {
            final String name = String.format("%s-%d-grams-between-%s-and-%s",
                ext.getKey(), n, p1k, p2k);
            final int ngram = n;
            addTemplate(name, new Template() {
              private Function<SentencePosition, String> extractor = ext.getValue();
              private SentencePosition pos = new SentencePosition();
              @Override
              public Iterable<String> extract(TemplateContext context) {
                int c1 = p1v.applyAsInt(context);
                if (c1 == TemplateContext.UNSET)
                  return null;
                int c2 = p2v.applyAsInt(context);
                if (c2 == TemplateContext.UNSET)
                  return null;
                boolean rev = false;
                if (c1 > c2) {
                  int temp = c1;
                  c1 = c2;
                  c2 = temp;
                  rev = true;
                }
                if (c1 >= context.getSentence().size()
                  || c2 >= context.getSentence().size()) {
                  Log.warn(String.format("c1=%d c2=%d sent.size=%d rev=%s ext=%s",
                    c1, c2, context.getSentence().size(), rev, ext.getKey()));
                  return null;
                }
                pos.sentence = context.getSentence();
                //Collection<String> output = new ArrayList<>();
                Collection<String> output = new HashSet<>();
                int end = (c2 - ngram)+1;
                if (WIDE_SPAN_BAG_OF_WORDS_OPTIMIZATION) {
                  int width = (end - c1) + 1;
                  if (width > 8)
                    return null;
                }
                for (int start = c1; start <= end; start++) {
                  StringBuilder feat = new StringBuilder();
                  feat.append(name);
                  feat.append("=");
                  boolean once = false;
                  for (pos.index = start;
                      pos.index < start + ngram && pos.index <= c2;
                      pos.index++) {
                    once = true;
                    String si = null;
                    //try {
                    si = extractor.apply(pos);
                    /*
                    } catch (java.lang.ArrayIndexOutOfBoundsException e) {
                      System.err.println("n=" + ngram);
                      System.err.println("ext=" + ext.getKey());
                      System.err.println("c1=" + c1);
                      System.err.println("c2=" + c2);
                      System.err.println("p1=" + p1.getKey());
                      System.err.println("p2=" + p2.getKey());
                      throw new RuntimeException(e);
                    }
                    */
                    if (si == null)
                      si = "NULL";
                    if (pos.index > start)
                      feat.append(rev ? "<" : ">");
                    feat.append(si);
                  }
                  if (!once)
                    feat.append("<NONE>");
                  output.add(feat.toString());
                }
                if (output.size() == 0)
                  return Arrays.asList(name + "=NONE");
                return output;
              }
            });
          }
        }
      }
    }

    /* LABELS *****************************************************************/
    // These templates should come first in a product, as they are the most selective
    addLabel("frame", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Frame f = context.getFrame();
        if (f == null)
          return null;
        return "frame=" + f.getName();
      }
    });
    addLabel("frameInst", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Frame f = context.getFrame();
        if (f == null)
          return null;
        if (f == Frame.nullFrame)
          return null;
        return "frameInst=" + f.getName();
      }
    });
    addLabel("frameMaybe", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Frame f = context.getFrame();
        if (f == null)
          return "f=?";
        return "f=" + f.getName();
      }
    });
    addLabel("frameRole", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        String f = context.getFrame() == null ? "null" : context.getFrame().getName();
        return "frameRoleArg=" + f + "/" + context.getRoleS();
      }
    });
    addLabel("role", new TemplateSS() {
      // Does not require an arg or span to be set, which is needed for
      // RoleHeadStage
      public String extractSS(TemplateContext context) {
        return "role=" + context.getRoleS();
      }
    });
    addLabel("frameRoleArg", new TemplateSS() {
      // Like frameRole, but requires that arg not be null,
      // which can lead to much sparser feature sets (observed feature trick)
      public String extractSS(TemplateContext context) {
        if (context.getArg() == null || context.getArg() == Span.nullSpan)
          return null;
        String f = context.getFrame() == null ? "null" : context.getFrame().getName();
        return "frameRoleArg=" + f + "/" + context.getRoleS();
      }
    });
    addLabel("roleArg", new TemplateSS() {
      // Like frameRole, but requires that arg not be null,
      // which can lead to much sparser feature sets (observed feature trick)
      public String extractSS(TemplateContext context) {
        if (context.getArg() == null || context.getArg() == Span.nullSpan)
          return null;
        return "roleArg=" + context.getRoleS();
      }
    });
    addLabel("arg", new TemplateSS() {
      // This is a backoff from roleArg. Requires the same data be present, but
      // doesn't give the name of the role in the feature
      public String extractSS(TemplateContext context) {
        if (context.getArg() == null || context.getArg() == Span.nullSpan)
          return null;
        return "arg=" + (context.getRoleS() == null ? "null" : "something");
      }
    });
    addLabel("argAndRoleArg", new Template() {
      // This is a backoff from roleArg. Requires the same data be present, but
      // doesn't give the name of the role in the feature
      public Iterable<String> extract(TemplateContext context) {
        if (context.getArg() == null || context.getArg() == Span.nullSpan)
          return null;
        return Arrays.asList(
            "arg=" + (context.getRoleS() == null ? "null" : "something"),
            "roleArg=" + context.getRoleS());
      }
    });
    addLabel("span1IsConstituent", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        if (!context.getSpan1IsConstituentIsSet())
          return null;
        if (!context.getSpan1IsConstituent())
          return null;
        // There are no span variables for width 1 spans, so do not allow the
        // features to fire for these cases (there is no decision to be made,
        // so features are useless).
        // NOTE: Only use span1IsConstituent as a label rather than a generic
        // template unless you are willing to deal with this behavior.
        Span span1 = context.getSpan1();
        if (span1 == null || span1.width() == 1)
          return null;
        return "span1IsConstituent";
      }
    });
    addLabel("framePrune", new TemplateSS() {
      @Override
      public String extractSS(TemplateContext context) {
        if (!context.isPruneSet())
          return null;
        // Fires when prune=false
        if (context.isPrune())
          return null;
        Frame f = context.getFrame();
        if (f == null)
          return null;
        return "framePrune=" + f.getName();
      }
    });
    addLabel("prune", new TemplateSS() {
      @Override
      public String extractSS(TemplateContext context) {
        if (!context.isPruneSet())
          return null;
        // Fires when prune=false
        if (context.isPrune())
          return null;
        return "prune";
      }
    });
    addLabel("head1IsRoot", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        if (context.getHead1_parent() == TemplateContext.UNSET)
          return null;
        if (context.getHead1_parent() < 0)
          return "head1IsRoot";
        return null;
      }
    });
    addLabel("head1GovHead2", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        if (context.getHead2_parent() == TemplateContext.UNSET)
          return null;
        if (context.getHead1() == TemplateContext.UNSET)
          return null;
        if (context.getHead2_parent() == context.getHead1())
          return "head1GovHead2";
        return null;
      }
    });
    addLabel("head2GovHead1", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        if (context.getHead1_parent() == TemplateContext.UNSET)
          return null;
        if (context.getHead2() == TemplateContext.UNSET)
          return null;
        if (context.getHead1_parent() == context.getHead2())
          return "head2GovHead1";
        return null;
      }
    });
//    addLabel("Role1", new TemplateSS() {
//      public String extractSS(TemplateContext context) {
////        int r = context.getRole();
////        if (r == TemplateContext.UNSET)
////          return null;
//        String r = context.getRoleS();
////        Frame f = context.getFrame();
////        assert f != null;
////        return "Role1=" + (r == f.numRoles() ? "NO_ROLE" : f.getRole(r));
//        return "Role1=" + r;
//      }
//    });
//    addLabel("Role2", new TemplateSS() {
//      public String extractSS(TemplateContext context) {
//        int r = context.getRole2();
//        if (r == TemplateContext.UNSET)
//          return null;
//        Frame f = context.getFrame();
//        assert f != null;
//        return "Role2=" + (r == f.numRoles() ? "NO_ROLE" : f.getRole(r));
//      }
//    });
  }


  /*
   * Methods and data structures on top of the enclosing class with some functionality to estimate
   * the cardinality of templates and products (features)
  public static class Indexed extends BasicFeatureTemplates {
    private List<Function<GlobalParameters, Function<String, Stage<?, ?>>>> stages;
    private Map<String, Consumer<Stage<?, ?>>> syntaxModes;
    private Map<String, Template> stageTemplates;

    public boolean debug = false;

    public Indexed() {
      super();
      stages = new ArrayList<>();
      syntaxModes = new HashMap<>();
      stageTemplates = new HashMap<>();

      stages.add(gp -> (fs -> new FrameIdStage(gp, fs)));
      stages.add(gp -> (fs -> new RoleHeadStage(gp, fs)));
      stages.add(gp -> (fs -> new RoleHeadToSpanStage(gp, fs)));
      stages.add(gp -> (fs -> new RoleSpanPruningStage(gp, fs)));
      stages.add(gp -> (fs -> new RoleSpanLabelingStage(gp, fs)));
      stages.add(gp -> (fs -> new RoleSequenceStage(gp, fs)));

      syntaxModes.put("regular", stage -> {
        Map<String, String> c = new HashMap<>();
        c.put("useSyntaxFeatures", "true");
        c.put("useLatentDependencies", "false");
        c.put("useLatentConstituencies", "false");
        stage.configure(c);
      });
      syntaxModes.put("latent", stage -> {
        Map<String, String> c = new HashMap<>();
        c.put("useSyntaxFeatures", "false");
        c.put("useLatentDependencies", "true");
        c.put("useLatentConstituencies", "true");
        stage.configure(c);
      });
      syntaxModes.put("none", stage -> {
        Map<String, String> c = new HashMap<>();
        c.put("useSyntaxFeatures", "false");
        c.put("useLatentDependencies", "false");
        c.put("useLatentConstituencies", "false");
        stage.configure(c);
      });

      // Add a template that checks for the class, for every stage.
      //for (Function<ParserParams, Stage<?, ?>> y : stages) {
      for (Function<GlobalParameters, Function<String, Stage<?, ?>>> y : stages) {
        Stage<?, ?> s = y.apply(new GlobalParameters()).apply("");
        String name = s.getName();// + "-" + x.getKey();
        Log.info("registering stage: " + name);
        @SuppressWarnings("rawtypes")
        Class<? extends Stage> cls = s.getClass();
        Object old = stageTemplates.put(name, new TemplateSS() {
          private String cn = null;

          @Override
          String extractSS(TemplateContext context) {
            if (context.getStage() == cls) {
              if (cn == null) {
                cn = cls.getName();
                int dot = cn.lastIndexOf('.');
                cn = cn.substring(dot + 1, cn.length());
              }
              return cn;
            }
            return null;
          }
        });
        assert old == null : "name conflict for " + name;
      }
    }

    public Template getStageTemplate(String name) {
      return stageTemplates.get(name);
    }

    private int estimateCard(
      String templateName,
      GlobalParameters gp,
      Stage<?, ?> stage,
      List<FNParse> parses) {

      // Try with the first K examples, if its 0, then assume it will remain 0
      int K = 50;
      gp.getFeatureNames().startGrowth();
      stage.scanFeatures(parses.subList(0, K));
      if (gp.getFeatureNames().size() == 0) {
//      System.out.println("[estimateCard] exitting early");
        return 0;
      }

      // Else finish the job
      System.out.println("[estimateCard] templateName=" + templateName);
      gp.getFeatureNames().startGrowth();
      stage.scanFeatures(parses.subList(K, parses.size()));
      return gp.getFeatureNames().size();
    }

    private boolean incompatible(String stageName, String syntaxMode, String labelName) {
      // TODO add more rules for speed!
      if ("FrameIdStage".equals(stageName) && !labelName.toLowerCase().contains("frame"))
        return true;
      if ("FrameIdStage".equals(stageName) && labelName.endsWith("Arg"))
        return true;
      if ("RoleSequenceStage".equals(stageName) && !"Role1".equals(labelName))
        return true;

      String k = "onlyDoStage";
      ExperimentProperties config = ExperimentProperties.getInstance();
      if (config.containsKey(k)) {
        String stage = config.getString(k);
        if (debug)
          System.out.println("[incompatible] stage=" + stage + " stageName=" + stageName);
        if (!stage.equals(stageName))
          return true;
      }

      return false;
    }

    private Set<String> alreadyComputedEntries(File f) {
      Set<String> s = new HashSet<>();
      try (BufferedReader r = FileUtil.getReader(f)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\\t");
          assert toks.length == 6;
          String key = String.format("%s\t%s\t%s\t%s",
            toks[0], toks[1], toks[2], toks[3]);
          boolean added = s.add(key);
          assert added;
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      Log.info("read " + s.size() + " pre-computed template cardinalities from "
        + f.getPath());
      return s;
    }

    public void estimateCardinalityOfTemplates() throws Exception {
      ExperimentProperties config = ExperimentProperties.getInstance();
      BasicFeatureTemplates.Indexed bft = BasicFeatureTemplates.getInstance();
      Random rand = new Random(config.getInt("seed", 9001));
      int parallel = config.getInt("parallel", 1);
      File outputFile = config.getFile("output");
      int part = config.getInt("part");
      int numParts = config.getInt("numParts");
//      RoleHeadStage.SHOW_FEATURES = config.getBoolean("roleHeadStage.showFeatures", false);

      // Load cardinalities that were estimated on another run (useful for partial failures)
      String precompFilenameKey = "precomputed";
      final Set<String> preComputed =
        config.containsKey(precompFilenameKey)
          ? alreadyComputedEntries(config.getExistingFile(precompFilenameKey))
          : null;
      if (preComputed == null)
        Log.info("not using any pre-computed entries");

      // TODO What is this for? debugging?
      final boolean fakeIt = false;
      if (fakeIt)
        outputFile.delete();

      Log.info("estimating cardinality for " + bft.basicTemplates.size()
        + " templates and " + stages.size() + " stages");
      Log.info("stages:");
      for (String k : stageTemplates.keySet())
        Log.info(k);

      // Get the data to compute features over
      int numParses = config.getInt("numParses", 1000);
      boolean usePropbank = config.getBoolean("usePropbank");
      Log.info("usePropbank=" + usePropbank);
      final List<FNParse> parses;
      if (usePropbank) {
        ParsePropbankData.Redis justParses = new ParsePropbankData.Redis(config);
        justParses.logParses = true;
        PropbankReader pbr = new PropbankReader(config, justParses);
        parses = ReservoirSample.sample(pbr.getDevData().iterator(), numParses, rand);

        // TODO remove, for debugging
        for (int i = 0; i < Math.min(10, parses.size()); i++) {
          FNParse y = parses.get(i);
          System.out.println("[propbankStartup] parse[" + i + "] " + Describe.fnParse(y));
          System.out.println("  basicDParse: " + y.getSentence().getBasicDeps(false));
          System.out.println("  stanfordParse: " + y.getSentence().getStanfordParse(false));
        }

      } else {
        parses = ReservoirSample.sample(
          FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
          numParses, rand);
      }

      // Load data ahead of time to ensure fair timing
      if (!fakeIt) {
//        TargetPruningData.getInstance().getWordnetDict();
//        TargetPruningData.getInstance().getPrototypesByFrame();
      }

      // parallelize with FileWriter that uses append
      ExecutorService es = parallel == 1
        ? Executors.newSingleThreadScheduledExecutor()
        : Executors.newFixedThreadPool(parallel);
      Log.info("actually starting work on " + parallel + " threads");

      Log.info(bft.basicTemplates.size() + " basic templates and " + stages.size() + " templates");
      for (Entry<String, Template> label : bft.labelTemplates.entrySet()) {
//      for (String syntaxModeName : Arrays.asList("regular", "latent", "none")) {
        for (String syntaxModeName : Arrays.asList("regular")) {
          Consumer<Stage<?, ?>> syntaxModeSupp = syntaxModes.get(syntaxModeName);
          for (String tmplName : bft.basicTemplates.keySet()) {
            for (Function<GlobalParameters, Function<String, Stage<?, ?>>> stageFut : stages) {
              Runnable r = new Runnable() {
                @Override
                public void run() {
                  long tmplStart = System.currentTimeMillis();
                  GlobalParameters gp = new GlobalParameters();
                  String labelName = label.getKey();
                  String fs = labelName + " * " + tmplName;
                  Stage<?, ?> stage = stageFut.apply(gp).apply(fs);
                  syntaxModeSupp.accept(stage);

                  String key = String.format("%s\t%s\t%s\t%s",
                    stage.getName(),
                    syntaxModeName,
                    labelName,
                    tmplName);
                  Log.info("key=" + key);

                  // Check if we've already computed this cardinality
                  if (preComputed != null && preComputed.contains(key)) {
                    if (debug) Log.info("already computed");
                    return;
                  }

                  // Only care about your part of the data
                  int h = key.hashCode();
                  if (h < 0) h = ~h;  // Java mod of negatives is negative!
                  if (h % numParts != part) {
//                  LOG.info("not a part of this piece, h=" + (h % numParts) + " numParts=" + numParts + " part=" + part);
                    return;
                  }

                  if (debug) Log.info("estimating cardinality for: " + key);
                  int card = -1;
                  if (incompatible(stage.getName(), syntaxModeName, labelName))
                    card = 0;
                  else if (fakeIt)
                    card = 2;
                  else
                    card = estimateCard(fs, gp, stage, parses);
                  double time = (System.currentTimeMillis() - tmplStart) / 1000d;
                  String msg = String.format("%s\t%d\t%.2f\n", key, card, time);
                  try (FileWriter fw = new FileWriter(outputFile, true)) {
                    fw.append(msg);
                  } catch (IOException e) {
                    System.out.flush();
                    e.printStackTrace();
                    System.err.println("failed to report: " + msg);
                    System.err.flush();
                    System.out.flush();
                  }
                }
              };
              if (fakeIt || parallel == 1)
                r.run();
              else
                es.execute(r);
            }
          }
        }
      }
      es.shutdown();
      es.awaitTermination(999, TimeUnit.DAYS);
      Log.info("done, results are in " + outputFile.getPath());
    }
  }
  */

//  public static void main(String[] args) throws Exception {
//    ExperimentProperties.init(args);
//    Indexed ce = new BasicFeatureTemplates.Indexed();
////    ce.estimateCardinalityOfTemplates();
//    int i = 0;
//    List<String> all = ce.getBasicTemplateNames();
//    Collections.sort(all);
//    for (String t : all) {
//      System.out.println((i++) + "\t" + t);
//    }
//  }

}
