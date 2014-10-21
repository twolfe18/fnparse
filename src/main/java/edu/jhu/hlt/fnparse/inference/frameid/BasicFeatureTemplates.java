package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
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
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.apache.commons.math3.util.FastMath;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.MinimalRoleFeatures;
import edu.jhu.hlt.fnparse.features.Path;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.TemplateSS;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanLabelingStage;
import edu.jhu.hlt.fnparse.util.BrownClusters;
import edu.jhu.hlt.fnparse.util.SentencePosition;
import edu.mit.jwi.item.IPointer;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;

public class BasicFeatureTemplates {

  private static String discretizeWidth(String name, int divisor, int maxCardinality, int width) {
    int w = width / divisor;
    if (w >= maxCardinality-1)
      return String.format("%s>%d", name, maxCardinality-1);
    else
      return String.format("%s=%d", name, w);
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

	public static Template getLabel(String name) {
	  return labels.get(name);
	}

  public static Template getBasicTemplate(String name) {
    return basicTemplates.get(name);
  }

  public static List<String> getBasicTemplateNames() {
    List<String> l = new ArrayList<>();
    l.addAll(basicTemplates.keySet());
    return l;
  }

  private static void addTemplate(String name, Template t) {
    if (basicTemplates == null)
      basicTemplates = new HashMap<>();
    Template ot = basicTemplates.put(name, t);
    if (ot != null)
      throw new RuntimeException("abiguous template name: " + name);
  }

  private static Map<String, Function<SentencePosition, String>> tokenExtractors;
  private static Map<String, Template> basicTemplates;
  private static Map<String, Template> labels;
  static {
    /* TOKEN EXTRACTORS *******************************************************/
    tokenExtractors = new HashMap<>();
    tokenExtractors.put("Word", x -> {
      if (x.indexInSent())
        return x.sentence.getWord(x.index);
      else
        return null;
    });
    tokenExtractors.put("Word2", x -> {
      if (x.indexInSent()
          && MinimalRoleFeatures.canLexicalize(x.index, x.sentence)) {
        return x.sentence.getWord(x.index);
      } else {
        return null;
      }
    });
    tokenExtractors.put("Word3", x -> {
      if (x.indexInSent()) {
        String s = x.sentence.getWord(x.index);
        if (s.length() > 4)
          return s.substring(0, 4);
        return s;
      } else {
        return null;
      }
    });
    tokenExtractors.put("Pos", x -> {
      if (x.indexInSent())
        return x.sentence.getPos(x.index);
      else
        return null;
    });
    tokenExtractors.put("Pos2", x -> {
      if (x.indexInSent())
        return x.sentence.getPos(x.index).substring(0, 1);
      else
        return null;
    });
    tokenExtractors.put("CollapsedLabel", x -> {
      if (x.indexInSent()) {
        DependencyParse deps = x.sentence.getCollapsedDeps();
        return deps.getLabel(x.index);
      } else {
        return null;
      }
    });
    tokenExtractors.put("CollapsedParentDir", x -> {
      if (x.indexInSent()) {
        DependencyParse deps = x.sentence.getCollapsedDeps();
        int h = deps.getHead(x.index);
        if (h < 0)
          return "root";
        else if (h < x.index)
          return "left";
        else
          return "right";
      } else {
        return null;
      }
    });
    for (int maxLen : Arrays.asList(2, 4, 6, 99)) {
      tokenExtractors.put("Bc256/" + maxLen, x -> {
        if (x.indexInSent()) {
          String w = x.sentence.getWord(x.index);
          return BrownClusters.getBc256().getPath(w, maxLen);
        } else {
          return null;
        }
      });
      tokenExtractors.put("Bc1000/" + maxLen, x -> {
        if (x.indexInSent()) {
          String w = x.sentence.getWord(x.index);
          return BrownClusters.getBc1000().getPath(w, maxLen);
        } else {
          return null;
        }
      });
    }


    /* START OF TEMPLATES *****************************************************/
    addTemplate("1", new TemplateSS() {
      @Override
      public String extractSS(TemplateContext context) {
        return "intercept";
      }
    });


    /* BASIC TEMPLATES ********************************************************/
    // head1
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "head1" + x.getKey();
      addTemplate(name, new TemplateSS() {
        private SentencePosition pos = new SentencePosition();
        public String extractSS(TemplateContext context) {
          pos.index = context.getHead1();
          if (pos.index == TemplateContext.UNSET)
            return null;
          pos.sentence = context.getSentence();
          return name + "=" + x.getValue().apply(pos);
        }
      });
    }

    // head1 parent
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "head1Parent" + x.getKey();
      addTemplate(name, new TemplateSS() {
        private SentencePosition pos = new SentencePosition();
        public String extractSS(TemplateContext context) {
          int h = context.getHead1();
          if (h == TemplateContext.UNSET)
            return null;
          pos.index = context.getSentence().getCollapsedDeps().getHead(h);
          pos.sentence = context.getSentence();
          return name + "=" + x.getValue().apply(pos);
        }
      });
    }

    // head1 children
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "head1ChildCollaped" + x.getKey();
      addTemplate(name, new Template() {
        private SentencePosition pos = new SentencePosition();
        public Iterable<String> extract(TemplateContext context) {
          int h = context.getHead1();
          if (h == TemplateContext.UNSET)
            return null;
          DependencyParse d = context.getSentence().getCollapsedDeps();
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

    // left, first, last, right
    Map<String, ToIntFunction<Span>> spanLocs = new HashMap<>();
    spanLocs.put("Left", x -> x.start - 1);
    spanLocs.put("First", x -> x.start);
    spanLocs.put("Last", x -> x.end - 1);
    spanLocs.put("Right", x -> x.end);
    for (Entry<String, Function<SentencePosition, String>> ex1 : tokenExtractors.entrySet()) {
      for (Entry<String, ToIntFunction<Span>> loc1 : spanLocs.entrySet()) {
        String name1 = "span1" + loc1.getKey() + ex1.getKey();
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
        // two-index templates
        for (Entry<String, Function<SentencePosition, String>> ex2 : tokenExtractors.entrySet()) {
          for (Entry<String, ToIntFunction<Span>> loc2 : spanLocs.entrySet()) {
            String name2 = name1 + "Span2" + loc2.getKey() + ex2.getKey();
            addTemplate(name2, new TemplateSS() {
              private SentencePosition pos = new SentencePosition();
              public String extractSS(TemplateContext context) {
                String v1 = temp1.extractSS(context);
                if (v1 == null)
                  return null;
                Span s = context.getSpan2();
                if (s == null)
                  return null;
                pos.index = loc2.getValue().applyAsInt(s);
                pos.sentence = context.getSentence();
                String v2 = name2 + "=" + ex2.getValue().apply(pos);
                return v1 + "_" + v2;
              }
            });
          }
        }
      }
    }

    // span1 width
    for (int div = 1; div <= 4; div++) {
      final int divL = div;
      final String name = "span1Width/" + div;
      addTemplate(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getSpan1();
          if (t == null)
            return null;
          return discretizeWidth(name, divL, 5, t.width());
        }
      });
    }

    // span1 depth
    for (int coarse : Arrays.asList(1, 2, 3)) {
      String name = "span1DepthCol" + coarse;
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
          return discretizeWidth(name, coarse, 5, min);
        }
      });
    }

    // TODO max child depth
    // TODO count children left|right

    // TODO distance to first POS going left|right
    // TODO count of POS to the left|right

    addTemplate("span1GovDirRelations", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span s = context.getSpan1();
        if (s == null)
          return null;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        List<String> rels = new ArrayList<>();
        for (int i = s.start; i < s.end; i++)
          rels.add(parentRelTo(i, s.start, s.end - 1, deps));
        Collections.sort(rels);
        StringBuilder rs = new StringBuilder();
        rs.append("span1GovDirRelations");
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
    for (int coarse : Arrays.asList(1, 2, 3)) {
      String name = "span1LeftProjCol" + coarse;
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
            int d = deps.getProjLeft(i);
            if (d < min) min = d;
          }
          assert min != 99;
          int toksLeft = t.start - min;
          return discretizeWidth(name, coarse, 5, toksLeft);
        }
      });
    }
    // right projection
    for (int coarse : Arrays.asList(1, 2, 3)) {
      String name = "span1RightProjCol" + coarse;
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
          return discretizeWidth(name, coarse, 5, toksRight);
        }
      });
    }

    // path features
    for (Path.NodeType nt : Path.NodeType.values()) {
      for (Path.EdgeType et : Path.EdgeType.values()) {
        String name1 = "head1RootPath-" + nt + "-" + et + "-t";
        addTemplate(name1, new TemplateSS() {
          public String extractSS(TemplateContext context) {
            int h = context.getHead1();
            if (h == TemplateContext.UNSET)
              return null;
            Sentence s = context.getSentence();
            Path p = new Path(s, s.getCollapsedDeps(), h, nt, et);
            return name1 + "=" + p.getPath();
          }
        });
        String name2 = "head1head2Path-" + nt + "-" + et + "-t";
        addTemplate(name2, new TemplateSS() {
          public String extractSS(TemplateContext context) {
            int h1 = context.getHead1();
            if (h1 == TemplateContext.UNSET)
              return null;
            int h2 = context.getHead2();
            if (h2 == TemplateContext.UNSET)
              return null;
            Sentence s = context.getSentence();
            Path p = new Path(s, s.getCollapsedDeps(), h1, h2, nt, et);
            return name2 + "=" + p.getPath();
          }
        });
        for (int length : Arrays.asList(1, 2, 3, 4)) {
          String nameL = "head1RootPathNgram-" + nt + "-" + et + "-len" + length;
          addTemplate(nameL, new Template() {
            public Iterable<String> extract(TemplateContext context) {
              int h = context.getHead1();
              if (h == TemplateContext.UNSET)
                return null;
              Sentence s = context.getSentence();
              Path p = new Path(s, s.getCollapsedDeps(), h, nt, et);
              Set<String> pieces = new HashSet<>();
              p.pathNGrams(length, pieces, nameL + "=");
              return pieces;
            }
          });
          String nameL2 = "head1head2PathNgram-" + nt + "-" + et + "-len" + length;
          addTemplate(nameL2, new Template() {
            public Iterable<String> extract(TemplateContext context) {
              int h1 = context.getHead1();
              if (h1 == TemplateContext.UNSET)
                return null;
              int h2 = context.getHead2();
              if (h2 == TemplateContext.UNSET)
                return null;
              Sentence s = context.getSentence();
              Path p = new Path(s, s.getCollapsedDeps(), h1, h2, nt, et);
              Set<String> pieces = new HashSet<>();
              p.pathNGrams(length, pieces, nameL2 + "=");
              return pieces;
            }
          });
        }
      }
    }

    /* FRAME-TARGET FEATURES **************************************************/
    addTemplate("luMatch", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span t = context.getTarget();
        if (t == null)
          return null;
        if (t.width() != 1)
          return null;
        TargetPruningData tpd = TargetPruningData.getInstance();
        LexicalUnit lu = context.getSentence().getFNStyleLU(
            t.start, tpd.getWordnetDict(), true);
        Frame f = context.getFrame();
        if (tpd.getFramesFromLU(lu).contains(f))
          return "luMatch";
        else
          return null;
      }
    });
    addTemplate("luMatch-WNSynSet", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span t = context.getTarget();
        if (t == null)
          return null;
        if (t.width() != 1)
          return null;
        TargetPruningData tpd = TargetPruningData.getInstance();
        IWord word = context.getSentence().getWnWord(t.start);
        if (word == null)
          return null;
        Set<IWord> synset = new HashSet<>();
        synset.addAll(word.getSynset().getWords());
        boolean hadAChance = false;
        int c = 0;
        for (FrameInstance p : tpd.getPrototypesByFrame(context.getFrame())) {
          hadAChance = true;
          // see if syn-set match for (head1, prototype)
          Span pt = p.getTarget();
          if (pt.width() != 1)
            continue;
          IWord otherWord = p.getSentence().getWnWord(pt.start);
          if (synset.contains(otherWord))
            c++;
        }
        if (c == 0 && hadAChance)
          return "NO-luMatch-WNSynSet";
        c = (int) FastMath.pow(c, 0.6d);
        return "luMatch-WnSynSet=" + c;
      }
    });
    addTemplate("luMatch-WNRelated", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Span t = context.getTarget();
        if (t == null)
          return null;
        if (t.width() != 1)
          return null;
        List<String> ret = new ArrayList<>();
        TargetPruningData tpd = TargetPruningData.getInstance();
        IWord word = context.getSentence().getWnWord(t.start);
        if (word == null)
          return null;
        Map<IPointer, List<IWordID>> rel = word.getRelatedMap();
        boolean hadAChance = false;
        for (FrameInstance p : tpd.getPrototypesByFrame(context.getFrame())) {
          hadAChance = true;
          // see if syn-set match for (head1, prototype)
          Span pt = p.getTarget();
          if (pt.width() != 1)
            continue;
          IWord otherWord = p.getSentence().getWnWord(pt.start);
          if (otherWord == null)
            continue;
          for (Map.Entry<IPointer, List<IWordID>> x : rel.entrySet()) {
            if (x.getValue().contains(otherWord.getID()))
              ret.add("luMatch-WNRelated=" + x.getKey().getName());
          }
        }
        if (ret.size() == 0 && hadAChance)
          ret.add("NO-luMatch-WNRelated");
        return ret;
      }
    });
    addTemplate("luMatch-WNRelatedSynSet", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Span t = context.getTarget();
        if (t == null)
          return null;
        if (t.width() != 1)
          return null;
        List<String> ret = new ArrayList<>();
        TargetPruningData tpd = TargetPruningData.getInstance();
        IWord word = context.getSentence().getWnWord(t.start);
        if (word == null)
          return null;
        Map<IPointer, List<ISynsetID>> relSS = word.getSynset().getRelatedMap();
        boolean hadAChance = false;
        for (FrameInstance p : tpd.getPrototypesByFrame(context.getFrame())) {
          hadAChance = true;
          // see if syn-set match for (head1, prototype)
          Span pt = p.getTarget();
          if (pt.width() != 1)
            continue;
          IWord otherWord = p.getSentence().getWnWord(pt.start);
          if (otherWord == null)
            continue;
          for (Map.Entry<IPointer, List<ISynsetID>> x : relSS.entrySet()) {
            for (ISynsetID ssid : x.getValue()) {
              ISynset ss = tpd.getWordnetDict().getSynset(ssid);
              if (ss.getWords().contains(otherWord))
                ret.add("luMatch-WNRelatedSynSet=" + x.getKey().getName());
            }
          }
        }
        if (ret.size() == 0 && hadAChance)
          ret.add("NO-luMatch-WNRelatedSynSet");
        return ret;
      }
    });

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
        return "argHeadRelation2=" + parentRelTo(t, s.start, s.end - 1, deps);
      }
    });

    /* SENTENCE FEATURES ******************************************************/
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
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
      // between head1 and head2
      String nameBetween = "betweenHead1Head2" + x.getKey();
      addTemplate(nameBetween, new Template() {
        private SentencePosition pos = new SentencePosition();
        public Iterable<String> extract(TemplateContext context) {
          int h1 = context.getHead1();
          if (h1 == TemplateContext.UNSET)
            return null;
          int h2 = context.getHead2();
          if (h2 == TemplateContext.UNSET)
            return null;
          assert h1 >= 0 && h2 >= 0;
          if (h1 > h2) {
            int temp = h1;
            h1 = h2;
            h2 = temp;
          }
          pos.sentence = context.getSentence();
          Set<String> words = new HashSet<String>();
          for (int i = h1 + 1; i < h2; i++) {
            pos.index = i;
            words.add(nameBetween + "=" + x.getValue().apply(pos));
          }
          if (words.size() == 0)
            words.add(nameBetween + "=NONE");
          return words;
        }
      });
    }


    /* LABELS *****************************************************************/
    // These templates should ALWAYS come first in a product
    labels = new HashMap<>();
    labels.put("intercept", new TemplateSS() {
      /**
       * Always fires.
       */
      public String extractSS(TemplateContext context) {
        return "intercept";
      }
    });
    labels.put("frame", new TemplateSS() {
      /**
       * fires whenever f_it=1 for some frame t.
       */
      public String extractSS(TemplateContext context) {
        Frame f = context.getFrame();
        return f == null ? null : "frame=" + f.getName();
      }
    });
    labels.put("dep", new TemplateSS() {
      /**
       * fires whenever l_{root,i} exists where i is the head of some FrameInstance
       */
      public String extractSS(TemplateContext context) {
        if (context.getHead1_isRootSet())
          return "dep";
        else
          return null;
      }
    });
    labels.put("frameDep", new TemplateSS() {
      /**
       * fires whenever l_{root,i} exists AND f_it=1 for some frame t.
       */
      public String extractSS(TemplateContext context) {
        if (!context.getHead1_isRootSet())
          return null;
        else if (context.getFrame() == null)
          return null;
        else
          return "frameDep=" + context.getFrame().getName();
      }
    });
    labels.put("frameRole", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int role = context.getRole();
        if (role == TemplateContext.UNSET)
          return null;
        Frame f = context.getFrame();
        assert f != null;
        return "frameRole=" + f.getName() + "." + f.getRole(role);
      }
    });
    labels.put("role", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int role = context.getRole();
        if (role == TemplateContext.UNSET)
          return null;
        Frame f = context.getFrame();
        assert f != null;
        return "role=" + f.getRole(role);
      }
    });
  }

  private static int estimateFrameIdCardinality(
      Template template,
      List<FNParse> parses) {
    TemplateContext ctx = new TemplateContext();
    Set<String> uniq = new HashSet<>();
    for (FNParse p : parses) {
      ctx.setSentence(p.getSentence());
      for (FrameInstance fi : p.getFrameInstances()) {
        ctx.setFrame(fi.getFrame());
        ctx.setTarget(fi.getTarget());
        ctx.setSpan1(fi.getTarget());
        ctx.setHead1(fi.getTarget().end - 1);
        Iterable<String> t = template.extract(ctx);
        if (t != null)
          for (String s : t)
            uniq.add(s);
      }
    }
    return uniq.size() + 1;
  }

  private static int estimateRoleLabellingCardinality(
      String templateName,
      Template template,
      List<FNParse> parses) {
    Logger.getLogger(RoleSpanLabelingStage.class).setLevel(Level.ERROR);
    ParserParams params = new ParserParams();
    params.setFeatureTemplateDescription("frameRole * " + templateName);
    RoleSpanLabelingStage stage = new RoleSpanLabelingStage(params, params);
    params.getAlphabet().startGrowth();
    List<FNParseSpanPruning> input = FNParseSpanPruning.optimalPrune(parses);
    stage.scanFeatures(input, parses, 99, 99_999_999);
    //stage.train(input, parses);
    return params.getAlphabet().size() + 1;
  }

  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    File f = new File("experiments/forward-selection/basic-templates.txt");
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
    Collection<String> templatesToView;
    if (args.length == 0)
      templatesToView = basicTemplates.keySet();
    else
      templatesToView = Arrays.asList(args);
    for (String tmplName : templatesToView) {
      Template tmpl = basicTemplates.get(tmplName);
      System.out.println(tmplName);
      int card_frameId = estimateFrameIdCardinality(tmpl, parses);
      int card_roleLab = estimateRoleLabellingCardinality(tmplName, tmpl, parses);
      w.write(String.format("%s\t%d\t%d\n", tmplName, card_frameId, card_roleLab));
      w.flush();
    }
    System.out.println("there are " + basicTemplates.size() + " templates");
    w.close();
    System.out.println("took " + (System.currentTimeMillis() - start)/1000d + " seconds");
  }
}
