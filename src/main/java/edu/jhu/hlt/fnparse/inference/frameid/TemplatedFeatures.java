package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

/**
 * Issue: how to handle availability of information...
 * I would like this stage to work regardless of if we're doing latent syntax or
 * not...
 * 
 * e.g. "1 + targetWord + targetLabel * targetPos"
 * 
 * labels = <none>, frame, role, frameRole
 * templates = ... (a lot, see below)
 * 
 * @author travis
 */
public class TemplatedFeatures implements Serializable {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(TemplatedFeatures.class);

  /**
   * Templates have no arguments, just represent a piece of information like
   * "lemma of the first word in the target".
   * 
   * NOTE: This interface is supposed to work such that you do not need to know
   * its requirements (e.g. a template only fires for contexts of (frame,span)).
   * You should implement this template so that it returns NULL if any of the
   * requirements are not filled. TemplateJoin is implemented with
   * short circuiting, so this should be particularly efficient in the case
   * where the label Template doesn't fire (the label Template should be first).
   */
  public static interface Template {
    /**
     * This should incorporate the name of this template, be globally unique.
     * NOTE: If this method returns NULL, then the semantics are that any
     * template built off of conjunctions with this feature will NOT fire.
     */
    Iterable<String> extract(TemplateContext context);
  }

  /**
   * SS = "single string"
   * Most templates only return one string and should extend this class
   */
  public static abstract class TemplateSS implements Template {
    abstract String extractSS(TemplateContext context);
    @Override
    public Iterable<String> extract(TemplateContext context) {
      String ss = extractSS(context);
      if (ss == null)
        return null;
      return Arrays.asList(ss);
    }
  }

  /** Conjoins two basic templates */
  public static class TemplateJoin implements Template {
    private Template left, right;
    public TemplateJoin(Template left, Template right) {
      if (left == null || right == null)
        throw new IllegalArgumentException();
      this.left = left;
      this.right = right;
    }
    @Override
    public Iterable<String> extract(TemplateContext context) {
      final Iterable<String> l = left.extract(context);
      if (l == null)
        return null;
      final Iterable<String> r = right.extract(context);
      if (r == null)
        return null;
      if (!l.iterator().hasNext() || !r.iterator().hasNext())
        return null;
      return new Iterable<String>() {
        @Override
        public Iterator<String> iterator() {
          return new IterableProduct(l, r);
        }
      };
    }
  }
  public static class IterableProduct implements Iterator<String> {
    private Iterable<String> b;
    private Iterator<String> aIter, bIter;
    private String aCur;
    public IterableProduct(Iterable<String> a, Iterable<String> b) {
      this.b = b;
      this.aIter = a.iterator();
      this.bIter = b.iterator();
      assert aIter.hasNext();
      assert bIter.hasNext();
    }
    public void remove() { throw new UnsupportedOperationException(); }
    public boolean hasNext() {
      return bIter.hasNext() || aIter.hasNext();
    }
    public String next() {
      if (aCur == null)
        aCur = aIter.next();
      String bCur;
      if (bIter.hasNext()) {
        bCur = bIter.next();
      } else {
        aCur = aIter.next();
        bIter = b.iterator();
        bCur = bIter.next();
      }
      return aCur + "_" + bCur;
    }
    public static void main(String[] args) {
      int a = Integer.parseInt(args[0]);
      int b = Integer.parseInt(args[1]);
      List<String> al = new ArrayList<>();
      List<String> bl = new ArrayList<>();
      for (int i = 0; i < a; i++)
        al.add("a" + i);
      for (int i = 0; i < b; i++)
        bl.add("b" + i);
      Iterator<String> c = new IterableProduct(al, bl);
      while (c.hasNext())
        System.out.println(c.next());
    }
  }

  /** Take a full template string and break it into independent templates */
  private static List<String> tokenizeTemplates(String templateString)
      throws TemplateDescriptionParsingException {
    List<String> toks = new ArrayList<>();
    for (String s : templateString.split("\\+"))
      toks.add(s.trim());
    return toks;
  }

  /** Take an independent template and build it up from basic templates */
  private static Template parseTemplateToken(String templateToken) 
      throws TemplateDescriptionParsingException {
    // Normalize
    String[] tokens = templateToken.split("\\*");
    int n = tokens.length;
    for (int i = 0; i < n; i++)
      tokens[i] = tokens[i].trim();

    // Lookup Templates
    Template[] templates = new Template[n];
    for (int i = 0; i < n; i++) {
      if (i == 0) {
        templates[i] = BasicFeatureTemplates.getStageTemplate(tokens[i]);
        if (templates[i] == null) {
          // you must have meant "<template>-<syntax_mode>"
          String[] tt = tokens[i].split("-");
          if (tt.length == 2
              && Arrays.asList("regular", "latent", "none").contains(tt[1])) {
            templates[i] = BasicFeatureTemplates.getStageTemplate(tt[0]);
          }
        }
      }
      if (templates[i] == null)
        templates[i] = BasicFeatureTemplates.getBasicTemplate(tokens[i]);
    }

    // Verify all the templates
    for (int i = 0; i < n; i++) {
      if (templates[i] == null) {
        throw new IllegalArgumentException(
            "couldn't parse [" + i + "]: " + tokens[i]);
      }
    }

    // Zip up with TemplateJoins
    if (templates.length == 1)
      return templates[0];
    Template joined = templates[n-1];
    for (int left = n - 2; left >= 0; left--)
      joined = new TemplateJoin(templates[left], joined);
    return joined;
  }

  public static class TemplateDescriptionParsingException extends Exception {
    private static final long serialVersionUID = 1L;
    public TemplateDescriptionParsingException(String message) {
      super(message);
    }
  }

  /**
   * Will throw an exception if it can't parse it (which many contain a message)
   */
  public static List<Template> parseTemplates(String templateDescription)
      throws TemplateDescriptionParsingException {
    assert templateDescription != null;
    assert templateDescription.length() > 0 : "you need to provide some templates";
    List<Template> templates = new ArrayList<>();
    for (String tok : tokenizeTemplates(templateDescription))
      templates.add(parseTemplateToken(tok));
    return templates;
  }

  private String globalPrefix;
  private String templateString;
  private transient List<Template> templates;
  private transient Alphabet<String> featureAlphabet;

  public TemplatedFeatures(
      String globalPrefix,
      String description,
      Alphabet<String> featureAlphabet) {
    this.globalPrefix = globalPrefix;
    this.templateString = description;
    this.featureAlphabet = featureAlphabet;
    /*
    // NOTE this has been pushed back into featurize because of some
    // initialization terribleness related to BasicFeatureTemplates
    try {
      this.templates = parseTemplates(description);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    */
  }

  public String getTemplateString() {
    return templateString;
  }

  public static void showContext(TemplateContext ctx) {
    Sentence s = ctx.getSentence();
    Frame f = ctx.getFrame();
    assert ctx.getStage() != null;
    LOG.debug("[context] stage=" + ctx.getStage());
    LOG.debug("[context] sentence=" + s);
    LOG.debug("[context] frame=" + (f == null ? "UNSET" : f.getName()));
    LOG.debug("[context] role=" + ctx.getRoleStrDebug());
    LOG.debug("[context] role2=" + ctx.getRole2StrDebug());
    LOG.debug("[context] target=" + desc(ctx.getTarget(), ctx));
    LOG.debug("[context] targetHead=" + desc(ctx.getTargetHead(), ctx));
    LOG.debug("[context] arg = " + desc(ctx.getArg(), ctx));
    LOG.debug("[context] argHead=" + desc(ctx.getArgHead(), ctx));
    LOG.debug("[context] span1=" + desc(ctx.getSpan1(), ctx));
    LOG.debug("[context] span2=" + desc(ctx.getSpan2(), ctx));
    LOG.debug("[context] head1=" + desc(ctx.getHead1(), ctx));
    LOG.debug("[context] head2=" + desc(ctx.getHead2(), ctx));
  }

  public static String desc(int i, TemplateContext ctx) {
    if (i == TemplateContext.UNSET)
      return "UNSET";
    return ctx.getSentence().getWord(i) + " @ " + i;
  }

  public static String desc(Span s, TemplateContext ctx) {
    if (s == null)
      return "UNSET";
    return Describe.span(s, ctx.getSentence()) + " @ " + s.toString();
  }

  public static void showFeatures(FeatureVector fv, Alphabet<String> params) {
    fv.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        String featName = params.lookupObject(arg0);
        LOG.debug(String.format("[features] %.1f\t%s", arg1, featName));
        return arg1;
      }
    });
    if (fv.l0Norm() == 0)
      LOG.debug("[features] ZERO VECTOR");
  }

  /**
   * Same as featurize, but prints the given message, context of the extraction,
   * and the features extracted.
   */
  public void featurizeDebug(FeatureVector v, TemplateContext context, String message) {
    featurize(v, context);
    LOG.debug("");
    LOG.info(message);
    showContext(context);
    showFeatures(v, featureAlphabet);
    LOG.debug("");
  }

  public void featurize(FeatureVector v, TemplateContext context) {
    if (templates == null) {
      try {
        templates = parseTemplates(templateString);
      } catch (Exception e) {
        System.err.println("problem parsing template string: " + templateString);
        throw new RuntimeException(e);
      }
    }
    boolean grow = featureAlphabet.isGrowing();
    int initAlphSize = featureAlphabet.size();
    for (Template t : templates) {
      Iterable<String> te = t.extract(context);
      if (te == null)
        continue;
      for (String e : te) {
        String featName = e + "::" + globalPrefix;
        int featIdx = featureAlphabet.lookupIndex(featName, grow);
        if (featIdx >= 0) {
          v.add(featIdx, 1d);
          if (grow && featIdx > initAlphSize && featIdx % 1000000 == 0)
            LOG.info("[featurize] alphabet just grew to " + featIdx);
        }
      }
    }
  }
}
