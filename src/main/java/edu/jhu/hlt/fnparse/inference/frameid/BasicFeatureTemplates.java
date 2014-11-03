package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.apache.commons.math3.util.FastMath;
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
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanLabelingStage;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanPruningStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.util.BrownClusters;
import edu.jhu.hlt.fnparse.util.SentencePosition;
import edu.mit.jwi.item.IPointer;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;

public class BasicFeatureTemplates {
  public static final Logger LOG = Logger.getLogger(BasicFeatureTemplates.class);

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
    if (name.contains("+"))
      throw new IllegalArgumentException("ambiguous name with +");
    if (name.contains("*"))
      throw new IllegalArgumentException("ambiguous name with *");
    Template ot = basicTemplates.put(name, t);
    if (ot != null)
      throw new RuntimeException("abiguous template name: " + name);
  }

  private static Map<String, Function<SentencePosition, String>> tokenExtractors;
  private static Map<String, Template> basicTemplates;
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
        if (deps == null)
          return null;
        return deps.getLabel(x.index);
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
        return "1";
      }
    });
    /*
    addTemplate("possibleArgs", new TemplateSS() {
      @Override
      public String extractSS(TemplateContext context) {
        if (context.getStage() == RoleSpanPruningStage.class)
          return "possibleArgs";
        else
          return null;
      }
    });
    */
    addTemplate("framePrune", new TemplateSS() {
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
        return "pruneFor" + f.getName();
      }
    });
    addTemplate("prune", new TemplateSS() {
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

    /* BASIC TEMPLATES ********************************************************/
    // head1
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name1 = "head1" + x.getKey();
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
      String name2 = "head2" + x.getKey();
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

    // head1 parent
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "head1Parent" + x.getKey();
      addTemplate(name, new TemplateSS() {
        private SentencePosition pos = new SentencePosition();
        public String extractSS(TemplateContext context) {
          int h = context.getHead1();
          if (h == TemplateContext.UNSET)
            return null;
          DependencyParse deps = context.getSentence().getCollapsedDeps();
          if (deps == null)
            return null;
          pos.index = deps.getHead(h);
          pos.sentence = context.getSentence();
          return name + "=" + x.getValue().apply(pos);
        }
      });
    }

    // head1 children
    for (Map.Entry<String, Function<SentencePosition, String>> x : tokenExtractors.entrySet()) {
      String name = "head1ChildCollapsed" + x.getKey();
      addTemplate(name, new Template() {
        private SentencePosition pos = new SentencePosition();
        public Iterable<String> extract(TemplateContext context) {
          int h = context.getHead1();
          if (h == TemplateContext.UNSET)
            return null;
          DependencyParse d = context.getSentence().getCollapsedDeps();
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

    // left, first, last, right
    Map<String, ToIntFunction<Span>> spanLocs = new HashMap<>();
    spanLocs.put("Left", x -> x.start - 1);
    spanLocs.put("First", x -> x.start);
    spanLocs.put("Last", x -> x.end - 1);
    spanLocs.put("Right", x -> x.end);
    // TODO put head in here
    for (Entry<String, Function<SentencePosition, String>> ex1 :
        tokenExtractors.entrySet()) {
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
        if (deps == null)
          return null;
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
            DependencyParse deps = s.getCollapsedDeps();
            if (deps == null)
              return null;
            Path p = new Path(s, deps, h, nt, et);
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
            DependencyParse deps = s.getCollapsedDeps();
            if (deps == null)
              return null;
            Path p = new Path(s, deps, h1, h2, nt, et);
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
              DependencyParse deps = s.getCollapsedDeps();
              if (deps == null)
                return null;
              Path p = new Path(s, deps, h, nt, et);
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
              DependencyParse deps = s.getCollapsedDeps();
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
    distancePoints.put("Head2", ctx -> ctx.getHead1());
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
        len -> MinimalRoleFeatures.semaforPathLengthBuckets(len));
    distanceBucketings.put("Direction", len -> len == 0
        ? "0" : (len < 0 ? "-" : "+"));
    distanceBucketings.put("Len3", len -> Math.abs(len) <= 3
        ? String.valueOf(len) : (len < 0 ? "-" : "+"));
    distanceBucketings.put("Len5", len -> Math.abs(len) <= 5
        ? String.valueOf(len) : (len < 0 ? "-" : "+"));
    for (Entry<String, ToIntFunction<TemplateContext>> p1 :
      distancePoints.entrySet()) {
      for (Entry<String, ToIntFunction<TemplateContext>> p2 :
        distancePoints.entrySet()) {
        if (p1.getKey().equals(p2.getKey()))
          continue;
        // Distance between two points
        for (Entry<String, IntFunction<String>> d : distanceBucketings.entrySet()) {
          String name = String.format("Dist(%s,%s,%s)", d.getKey(), p1.getKey(), p2.getKey());
          addTemplate(name, new TemplateSS() {
            @Override
            String extractSS(TemplateContext context) {
              int c1 = p1.getValue().applyAsInt(context);
              if (c1 == TemplateContext.UNSET)
                return null;
              int c2 = p2.getValue().applyAsInt(context);
              if (c2 == TemplateContext.UNSET)
                return null;
              return d.getValue().apply(c1 - c2);
            }
          });
        }
        // N-grams between the two points
        for (int n = 1; n <= 2; n++) {
          for (Map.Entry<String, Function<SentencePosition, String>> ext : tokenExtractors.entrySet()) {
            if ("Word2".equals(ext.getKey()))
              continue;
            if (ext.getKey().toLowerCase().startsWith("bc"))
              continue;
            final String name = String.format("%s-%d-grams-between-%s-and-%s",
                ext.getKey(), n, p1.getKey(), p2.getKey());
            final int ngram = n;
            addTemplate(name, new Template() {
              private Function<SentencePosition, String> extractor = ext.getValue();
              private SentencePosition pos = new SentencePosition();
              private ToIntFunction<TemplateContext> p1f = p1.getValue();
              private ToIntFunction<TemplateContext> p2f = p2.getValue();
              @Override
              public Iterable<String> extract(TemplateContext context) {
                int c1 = p1f.applyAsInt(context);
                if (c1 == TemplateContext.UNSET)
                  return null;
                int c2 = p2f.applyAsInt(context);
                if (c2 == TemplateContext.UNSET)
                  return null;
                if (c1 > c2) {
                  int temp = c1;
                  c1 = c2;
                  c2 = temp;
                }
                assert c1 < context.getSentence().size();
                assert c2 < context.getSentence().size();
                pos.sentence = context.getSentence();
                List<String> output = new ArrayList<>();
                for (int start = c1; start <= (c2 - ngram)+1; start++) {
                  StringBuilder feat = new StringBuilder(name);
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
                    if (feat.length() > 0)
                      feat.append("&");
                    feat.append(si);
                  }
                  if (!once)
                    feat.append("<NONE>");
                  output.add(feat.toString());
                }
                return output;
              }
            });
          }
        }
      }
    }

    /* LABELS *****************************************************************/
    // These templates should come first in a product, as they are the most selective
    addTemplate("frame", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Frame f = context.getFrame();
        return f == null ? null : "frame=" + f.getName();
      }
    });
    addTemplate("frameRole", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int role = context.getRole();
        if (role == TemplateContext.UNSET)
          return null;
        Frame f = context.getFrame();
        assert f != null;
        return "frameRole=" + f.getName() + "." + f.getRole(role);
      }
    });
    addTemplate("frameRoleArg", new TemplateSS() {
      // Like frameRole, but requires that arg not be null,
      // which can lead to much sparser feature sets (observed feature trick)
      public String extractSS(TemplateContext context) {
        if (context.getArg() == null)
          return null;
        int role = context.getRole();
        if (role == TemplateContext.UNSET)
          return null;
        Frame f = context.getFrame();
        assert f != null;
        return "frameRoleArg=" + f.getName() + "." + f.getRole(role);
      }
    });
    addTemplate("span1IsConstituent", new TemplateSS() {
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
    addTemplate("head1IsRoot", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        if (context.getHead1_parent() == TemplateContext.UNSET)
          return null;
        if (context.getHead1_parent() < 0)
          return "head1IsRoot";
        return null;
      }
    });
    addTemplate("head1GovHead2", new TemplateSS() {
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
    addTemplate("head2GovHead1", new TemplateSS() {
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
  }

  private static List<Function<ParserParams, Stage<?, ?>>> stages = new ArrayList<>();
  private static Map<String, Supplier<ParserParams>> syntaxModes = new HashMap<>();
  private static Map<String, Template> stageTemplates = new HashMap<>();
  static {
    stages.add(pp -> new FrameIdStage(pp, pp));
    stages.add(pp -> new RoleHeadStage(pp, pp));
    stages.add(pp -> new RoleHeadToSpanStage(pp, pp));
    stages.add(pp -> new RoleSpanPruningStage(pp, pp));
    stages.add(pp -> new RoleSpanLabelingStage(pp, pp));
    syntaxModes.put("regular", () -> {
      ParserParams p = new ParserParams();
      p.useLatentConstituencies = false;
      p.useLatentDepenencies = false;
      p.useSyntaxFeatures = true;
      return p;
    });
    syntaxModes.put("latent", () -> {
      ParserParams p = new ParserParams();
      p.useLatentConstituencies = true;
      p.useLatentDepenencies = true;
      p.useSyntaxFeatures = false;
      return p;
    });
    syntaxModes.put("none", () -> {
      ParserParams p = new ParserParams();
      p.useLatentConstituencies = false;
      p.useLatentDepenencies = false;
      p.useSyntaxFeatures = false;
      return p;
    });
    for (Function<ParserParams, Stage<?, ?>> y : stages) {
      Stage<?, ?> s = y.apply(new ParserParams());
      String name = s.getName();// + "-" + x.getKey();
      LOG.info("registering stage: " + name);
      Class<? extends Stage> cls = s.getClass();
      Object old = stageTemplates.put(name, new TemplateSS() {
        @Override
        String extractSS(TemplateContext context) {
          if (context.getStage() == cls)
            return cls.getName();
          return null;
        }
      });
      assert old == null : "name conflict for " + name;
    }
  }

  public static Template getStageTemplate(String name) {
    return stageTemplates.get(name);
  }

  private static int estimateCard(
      String templateName,
      ParserParams params,
      Function<ParserParams, Stage<?, ?>> stageFuture,
      List<FNParse> parses) {
    Stage<?, ?> stage = stageFuture.apply(params);
    templateName = stage.getName() + " * " + templateName;
    params.setFeatureTemplateDescription(templateName);
    params.getAlphabet().startGrowth();
    stage.scanFeatures(parses);
    return params.getAlphabet().size();
  }

  public static void main(String[] args) throws Exception {
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    if (args.length != 4) {
      System.err.println("please provide:");
      System.err.println("1) how many threads to use");
      System.err.println("2) a file to dump to");
      System.err.println("3) a partition of the data to take");
      System.err.println("4) how many partitions for the data");
      System.err.println("NOTE: if you don't want to use partitions, provide "
          + "\"0 1\" as the last two arguments");
	    return;
    }
    int parallel = Integer.parseInt(args[0]);
    File f = new File(args[1]);
    //File f = new File("experiments/forward-selection/basic-templates.txt");
    int part = Integer.parseInt(args[2]);
    int numParts = Integer.parseInt(args[3]);
    final boolean fakeIt = true;
    if (fakeIt) f.delete();
    LOG.info("estimating cardinality for " + basicTemplates.size()
        + " templates and " + stages.size() + " stages");
    LOG.info("stages:");
    for (String k : stageTemplates.keySet())
      LOG.info(k);

    // Load data ahead of time to ensure fair timing
    if (!fakeIt) {
      TargetPruningData.getInstance().getWordnetDict();
      TargetPruningData.getInstance().getPrototypesByFrame();
    }

    // parallelize with FileWriter that uses append
    ExecutorService es = parallel == 1
        ? Executors.newSingleThreadScheduledExecutor()
            : Executors.newFixedThreadPool(parallel);
    LOG.info("actually starting work on " + parallel + " threads");
    if (parallel > 1)
      LOG.warn("verify this is thread safe, last time it wasn't");

    // TODO read in existing results from the given file, skip those jobs

    for (String syntaxModeName : Arrays.asList("regular", "latent", "none")) {
      Supplier<ParserParams> syntaxModeSupp = syntaxModes.get(syntaxModeName);
      for (String tmplName : basicTemplates.keySet()) {
        for (Function<ParserParams, Stage<?, ?>> stage : stages) {
          Runnable r = new Runnable() {
            @Override
            public void run() {
              long tmplStart = System.currentTimeMillis();
              ParserParams params = syntaxModeSupp.get();
              String stageName = stage.apply(params).getName();
              LOG.info(tmplName + "\t" + stageName);

              // Only care about your part of the data
              int h = (tmplName + " " + stageName).hashCode();
              if (h % numParts != part)
                return;

              int card = -1;
              if (fakeIt)
                card = 250;
              else
                card = estimateCard(tmplName, params, stage, parses);
              double time = (System.currentTimeMillis() - tmplStart) / 1000d;
              String msg = String.format("%s\t%s\t%s\t%d\t%.2f\n",
                  tmplName, stageName, syntaxModeName, card, time);
              try (FileWriter fw = new FileWriter(f, true)) {
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
    es.shutdown();
    es.awaitTermination(999, TimeUnit.DAYS);
    LOG.info("done, results are in " + f.getPath());
  }
}
