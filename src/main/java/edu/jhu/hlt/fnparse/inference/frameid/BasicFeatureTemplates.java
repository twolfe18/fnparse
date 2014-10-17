package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.util.FastMath;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.MinimalRoleFeatures;
import edu.jhu.hlt.fnparse.features.Path;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.TemplateSS;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
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

  private static Map<String, Template> basicTemplates;
  private static Map<String, Template> labels;
  static {
    basicTemplates = new HashMap<>();
    basicTemplates.put("1", new TemplateSS() {
      @Override
      public String extractSS(TemplateContext context) {
        return "intercept";
      }
    });

    /* TARGET FEATURES ********************************************************/
    // head
    basicTemplates.put("targetHeadWord", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        String w = context.getSentence().getWord(h);
        return "targetHeadWord=" + w;
      }
    });
    basicTemplates.put("targetHeadWord2", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        if (!MinimalRoleFeatures.canLexicalize(h, context.getSentence()))
          return null;
        String w = context.getSentence().getWord(h);
        return "targetHeadWord2=" + w;
      }
    });
    basicTemplates.put("targetHeadWord3", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        String w = context.getSentence().getWord(h);
        if (w.length() > 4)
          w = w.substring(0, 4);
        return "targetHeadWord3=" + w;
      }
    });
    basicTemplates.put("targetHeadPos", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        String p = context.getSentence().getPos(h);
        return "targetHeadPos=" + p;
      }
    });
    basicTemplates.put("targetHeadLabelCol", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        return "targetHeadLabelCol=" + deps.getLabel(h);
      }
    });

    // parent
    basicTemplates.put("targetParentWord", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        int p = context.getSentence().getCollapsedDeps().getHead(h);
        return "targetParentWord=" + AbstractFeatures.getLUSafe(
            p, context.getSentence()).word;
      }
    });
    basicTemplates.put("targetParentWord2", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        int p = context.getSentence().getCollapsedDeps().getHead(h);
        if (!MinimalRoleFeatures.canLexicalize(p, context.getSentence()))
            return null;
        return "targetParentWord2=" + AbstractFeatures.getLUSafe(
            p, context.getSentence()).word;
      }
    });
    basicTemplates.put("targetParentPos", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int h = context.getTarget().end - 1;
        int p = context.getSentence().getCollapsedDeps().getHead(h);
        return "targetParent=" + AbstractFeatures.getLUSafe(
            p, context.getSentence()).pos;
      }
    });
    basicTemplates.put("targetParentLabelCol", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Sentence s = context.getSentence();
        DependencyParse deps = s.getCollapsedDeps();
        int h = context.getTarget().end - 1;
        int p = deps.getHead(h);
        if (p < 0)
          return "targetParentCol=ROOT";
        return "targetParentCol=" + deps.getLabel(p);
      }
    });
    basicTemplates.put("targetParentColDir", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Sentence s = context.getSentence();
        DependencyParse deps = s.getCollapsedDeps();
        int h = context.getTarget().end - 1;
        int p = deps.getHead(h);
        if (p < 0)
          return "targetParentDir=root";
        else if (p == h)
          return "targetParentDir=loop";
        else if (p < h)
          return "targetParentDir=left";
        else
          return "targetParentDir=right";
      }
    });
    // TODO depth

    // children
    basicTemplates.put("targetChildColWord", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        DependencyParse d = context.getSentence().getCollapsedDeps();
        int[] c = d.getChildren(context.getTarget().end - 1);
        if (c.length == 0)
          return Arrays.asList("targetChildCol=NONE");
        else {
          List<String> cs = new ArrayList<>();
          for (int cd : c)
            cs.add("targetChildCol=" + context.getSentence().getWord(cd));
          return cs;
        }
      }
    });
    basicTemplates.put("targetChildColWord2", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Sentence s = context.getSentence();
        DependencyParse d = s.getCollapsedDeps();
        int[] c = d.getChildren(context.getTarget().end - 1);
        if (c.length == 0)
          return Arrays.asList("targetChildCol2=NONE");
        else {
          List<String> cs = new ArrayList<>();
          for (int cd : c)
            if (MinimalRoleFeatures.canLexicalize(cd, s))
              cs.add("targetChildCol2=" + s.getWord(cd));
          return cs;
        }
      }
    });
    basicTemplates.put("targetChildColPos", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        DependencyParse d = context.getSentence().getCollapsedDeps();
        int[] c = d.getChildren(context.getTarget().end - 1);
        if (c.length == 0)
          return Arrays.asList("targetChildCol=NONE");
        else {
          List<String> cs = new ArrayList<>();
          for (int cd : c)
            cs.add("targetChildCol=" + context.getSentence().getPos(cd));
          return cs;
        }
      }
    });
    basicTemplates.put("targetChildLabelCol", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        DependencyParse d = context.getSentence().getCollapsedDeps();
        int[] c = d.getChildren(context.getTarget().end - 1);
        if (c.length == 0)
          return Arrays.asList("targetChildCol=NONE");
        else {
          List<String> cs = new ArrayList<>();
          for (int cd : c)
            cs.add("targetChildCol=" + d.getLabel(cd));
          return cs;
        }
      }
    });
    // TODO max child depth
    // TODO count children left|right

    // TODO distance to first POS going left|right
    // TODO count of POS to the left|right
    
    // depth
    for (int coarse : Arrays.asList(1, 2, 3)) {
      String name = "targetHeadDepthCol" + coarse;
      basicTemplates.put(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getTarget();
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

    // left projection
    for (int coarse : Arrays.asList(1, 2, 3)) {
      String name = "targetLeftProjCol" + coarse;
      basicTemplates.put(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getTarget();
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
      String name = "targetRightProjCol" + coarse;
      basicTemplates.put(name, new TemplateSS() {
        public String extractSS(TemplateContext context) {
          Span t = context.getTarget();
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
        String name1 = "targetRootPath-" + nt + "-" + et + "-t";
        basicTemplates.put(name1, new TemplateSS() {
          public String extractSS(TemplateContext context) {
            Span t = context.getTarget();
            Sentence s = context.getSentence();
            Path p = new Path(s, s.getCollapsedDeps(), t.start, nt, et);
            return p.getPath();
          }
        });
        for (int length : Arrays.asList(1, 2, 3, 4)) {
          String name2 = "targetRootPathNgram-" + nt + "-" + et + "-len" + length;
          basicTemplates.put(name2, new Template() {
            public Iterable<String> extract(TemplateContext context) {
              Span t = context.getTarget();
              Sentence s = context.getSentence();
              Path p = new Path(s, s.getCollapsedDeps(), t.start, nt, et);
              Set<String> pieces = new HashSet<>();
              p.pathNGrams(length, pieces, name2 + "=");
              return pieces;
            }
          });
        }
      }
    }

    // left and right words
    basicTemplates.put("targetLeftWord", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        return "targetLeft=" + AbstractFeatures.getLUSafe(
            context.getTarget().start - 1, context.getSentence()).word;
      }
    });
    basicTemplates.put("targetLeftWord2", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int i = context.getTarget().start - 1;
        Sentence s = context.getSentence();
        if (!MinimalRoleFeatures.canLexicalize(i, s))
          return null;
        return "targetLeft2=" + AbstractFeatures.getLUSafe(i, s).word;
      }
    });
    basicTemplates.put("targetLeftPos", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        return "targetLeft=" + AbstractFeatures.getLUSafe(
            context.getTarget().start - 1, context.getSentence()).pos;
      }
    });
    basicTemplates.put("targetRightWord", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        return "targetRight=" + AbstractFeatures.getLUSafe(
            context.getTarget().end, context.getSentence()).word;
      }
    });
    basicTemplates.put("targetRightWord2", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        int i = context.getTarget().end;
        Sentence s = context.getSentence();
        if (!MinimalRoleFeatures.canLexicalize(i, s))
          return null;
        return "targetRight2=" + AbstractFeatures.getLUSafe(i, s).word;
      }
    });
    basicTemplates.put("targetRightPos", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        return "targetRight=" + AbstractFeatures.getLUSafe(
            context.getTarget().end, context.getSentence()).pos;
      }
    });

    /* FRAME-TARGET FEATURES **************************************************/
    basicTemplates.put("luMatch", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span t = context.getTarget();
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
    basicTemplates.put("luMatch-WNSynSet", new TemplateSS() {
      public String extractSS(TemplateContext context) {
        Span t = context.getTarget();
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
          // see if syn-set match for (targetHead, prototype)
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
    basicTemplates.put("luMatch-WNRelated", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Span t = context.getTarget();
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
          // see if syn-set match for (targetHead, prototype)
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
    basicTemplates.put("luMatch-WNRelatedSynSet", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Span t = context.getTarget();
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
          // see if syn-set match for (targetHead, prototype)
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


    /* SENTENCE FEATURES ******************************************************/
    basicTemplates.put("sentenceWords", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Sentence s = context.getSentence();
        Set<String> words = new HashSet<String>();
        for (int i = 0; i < s.size(); i++)
          words.add("sentContains=" + s.getWord(i));
        if (words.size() == 0)
          words.add("sentContains=NONE");
        return words;
      }
    });
    basicTemplates.put("sentenceWords2", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Sentence s = context.getSentence();
        Set<String> words = new HashSet<String>();
        for (int i = 0; i < s.size(); i++)
          if (MinimalRoleFeatures.canLexicalize(i, s))
            words.add("sentContains2=" + s.getWord(i));
        if (words.size() == 0)
          words.add("sentContains2=NONE");
        return words;
      }
    });
    basicTemplates.put("sentenceWords3", new Template() {
      public Iterable<String> extract(TemplateContext context) {
        Sentence s = context.getSentence();
        Set<String> words = new HashSet<String>();
        for (int i = 0; i < s.size(); i++) {
          String w = s.getWord(i);
          if (w.length() > 4)
            w = w.substring(0, 4);
          words.add("sentContains3=" + w);
        }
        if (words.size() == 0)
          words.add("sentContains3=NONE");
        return words;
      }
    });


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
       * fires whenever l_{root,i}=1 where i is the head of some FrameInstance
       */
      public String extractSS(TemplateContext context) {
        if (context.getHead() == TemplateContext.UNSET)
          return null;
        else
          return "dep";
      }
    });
    labels.put("frameDep", new TemplateSS() {
      /**
       * fires whenever l_{root,i}=1 AND f_it=1 for some frame t.
       */
      public String extractSS(TemplateContext context) {
        if (context.getHead() == TemplateContext.UNSET)
          return null;
        else if (context.getFrame() == null)
          return null;
        else
          return "frameDep=" + context.getFrame().getName();
      }
    });
    // TODO frameRole
  }

  private static int estimateCardinality(
      Template template,
      List<FNParse> parses) {
    TemplateContext ctx = new TemplateContext();
    Set<String> uniq = new HashSet<>();
    for (FNParse p : parses) {
      ctx.setSentence(p.getSentence());
      for (FrameInstance fi : p.getFrameInstances()) {
        ctx.setTarget(fi.getTarget());
        ctx.setFrame(fi.getFrame());
        Iterable<String> t = template.extract(ctx);
        if (t != null)
          for (String s : t)
            uniq.add(s);
      }
    }
    return uniq.size() + 1;
  }

  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    File f = new File("experiments/forward-selection/basic-templates.txt");
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
    for (Map.Entry<String, Template> tmpl : basicTemplates.entrySet()) {
      System.out.println(tmpl.getKey());
      int cardinality = estimateCardinality(tmpl.getValue(), parses);
      w.write(String.format("%s\t%d\n", tmpl.getKey(), cardinality));
    }
    System.out.println("there are " + basicTemplates.size() + " templates");
    w.close();
    System.out.println("took " + (System.currentTimeMillis() - start)/1000d + " seconds");
  }
}
