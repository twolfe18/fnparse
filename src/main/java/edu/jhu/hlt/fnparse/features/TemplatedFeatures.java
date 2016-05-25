package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
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
 * NOTE: I believe this is still largely tied to {@link BasicFeatureTemplates},
 * so this is "pre" the pre-compute stage.
 *
 * @see FeatureSet for IO and convenience methods related to this.
 *
 * @author travis
 */
public abstract class TemplatedFeatures implements Serializable {
  private static final long serialVersionUID = 1L;

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
  public interface Template {
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
  public static List<String> tokenizeTemplates(String templateString) {
    List<String> toks = new ArrayList<>();
    for (String s : templateString.split("\\+"))
      toks.add(s.trim());
    return toks;
  }

  public static List<String> tokenizeProducts(String productOfTemplatesString) {
    List<String> toks = new ArrayList<>();
    for (String s : productOfTemplatesString.split("\\*"))
      toks.add(s.trim());
    return toks;
  }

  /** Take an independent template and build it up from basic templates */
  private static Template parseTemplateToken(String templateToken)
      throws TemplateDescriptionParsingException {
    BasicFeatureTemplates bft = new BasicFeatureTemplates();
    return parseTemplateToken(templateToken, bft, null);
  }

  private static Template parseTemplateToken(
      String templateToken,
      BasicFeatureTemplates bft,
      Map<String, Template> overrides)
      throws TemplateDescriptionParsingException {
    // Normalize
    List<String> tokens = tokenizeProducts(templateToken);

    // Lookup Templates
    int n = tokens.size();
    Template[] templates = new Template[n];
    for (int i = 0; i < n; i++) {
//      if (i == 0) {
//        templates[i] = bft.getStageTemplate(tokens.get(i));
//        if (templates[i] == null) {
//          // you must have meant "<template>-<syntax_mode>"
//          String[] tt = tokens.get(i).split("-");
//          if (tt.length == 2
//              && Arrays.asList("regular", "latent", "none").contains(tt[1])) {
//            templates[i] = bft.getStageTemplate(tt[0]);
//          }
//        }
//      }
//      if (templates[i] == null)
//        templates[i] = bft.getBasicTemplate(tokens.get(i));
      if (overrides != null)
        templates[i] = overrides.get(tokens.get(i));
      if (templates[i] == null)
        templates[i] = bft.getBasicTemplate(tokens.get(i));
    }

    // Verify all the templates
    for (int i = 0; i < n; i++) {
      if (templates[i] == null) {
        throw new IllegalArgumentException(
            "couldn't parse [" + i + "]: " + tokens.get(i));
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

  public static List<Template> parseTemplates(String templateDescription)
      throws TemplateDescriptionParsingException {
    throw new RuntimeException("lift BasicFeatureTemplates");
  }

  /**
   * Will throw an exception if it can't parse it (which many contain a message)
   */
  public static List<Template> parseTemplates(
      String templateDescription,
      BasicFeatureTemplates bft,
      Map<String, Template> overrides)
      throws TemplateDescriptionParsingException {
    assert templateDescription != null;
    assert templateDescription.length() > 0 : "you need to provide some templates";
    List<Template> templates = new ArrayList<>();
    for (String tok : tokenizeTemplates(templateDescription))
      templates.add(parseTemplateToken(tok, bft, overrides));
    return templates;
  }

  private String globalPrefix;
  private String templateString;
  private transient List<Template> templates;

  public abstract int indexOf(String featureName);
  public abstract int dimension();

  public TemplatedFeatures(
      String globalPrefix,
      String description) {
    this.globalPrefix = globalPrefix;
    this.templateString = description;
  }

  public static class AlphabetBased extends TemplatedFeatures {
    private static final long serialVersionUID = 1L;
    private Alphabet<String> featureAlphabet;
    public AlphabetBased(
        String globalPrefix,
        String description,
        Alphabet<String> featureAlphabet) {
      super(globalPrefix, description);
      this.featureAlphabet = featureAlphabet;
    }
    public Alphabet<String> getAlphabet() {
      return featureAlphabet;
    }
    @Override
    public int indexOf(String featureName) {
      boolean grow = featureAlphabet.isGrowing();
      int idx;
      if (grow) {
        int initAlphSize = featureAlphabet.size();
        idx = featureAlphabet.lookupIndex(featureName, true);
        if (idx > initAlphSize && idx % 1000000 == 0)
          Log.info("[featurize] alphabet just grew to " + idx);
      } else {
        idx = featureAlphabet.lookupIndex(featureName, false);
      }
      return idx;
    }
    @Override
    public int dimension() {
      return featureAlphabet.size();
    }
  }

  public static class HashBased extends TemplatedFeatures {
    private static final long serialVersionUID = 1L;
    private int numBuckets;
    public HashBased(
        String globalPrefix,
        String description,
        int numBuckets) {
      super(globalPrefix, description);
      this.numBuckets = numBuckets;
    }
    @Override
    public int indexOf(String featureName) {
      int h = featureName.hashCode();
      if (h < 0) h = ~h;
      return h % numBuckets;
    }
    @Override
    public int dimension() {
      return numBuckets;
    }
  }

  public String getTemplateString() {
    return templateString;
  }

  public static void showContext(TemplateContext ctx) {
    TemplateContext.showContext(ctx);
  }

  public static void showFeatures(IntDoubleUnsortedVector fv, Alphabet<String> params) {
    fv.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        String featName = params.lookupObject(arg0);
        Log.info(String.format("[features] %.1f\t%s", arg1, featName));
        return arg1;
      }
    });
    if (fv.l0Norm() == 0)
      Log.info("[features] ZERO VECTOR");
  }

  /**
   * Same as featurize, but prints the given message, context of the extraction,
   * and the features extracted.
   */
  public void featurizeDebug(IntDoubleUnsortedVector v, TemplateContext context, String message) {
    featurize(v, context);
    Log.info("");
    Log.info(message);
    showContext(context);
    if (this instanceof AlphabetBased) {
      showFeatures(v, ((AlphabetBased) this).featureAlphabet);
    } else {
      Log.warn("[featurizeDebug] can't show you features because you're not using AlphabetBased features: " + getClass().getName());
    }
    Log.info("");
  }

  public void featurize(IntDoubleUnsortedVector v, TemplateContext context) {
    if (templates == null) {
      try {
        templates = parseTemplates(templateString);
      } catch (Exception e) {
        System.err.println("problem parsing template string: " + templateString);
        throw new RuntimeException(e);
      }
    }
    //boolean grow = featureAlphabet.isGrowing();
    for (Template t : templates) {
      Iterable<String> te = t.extract(context);
      if (te == null)
        continue;
      for (String e : te) {
        String featName = e + "::" + globalPrefix;
        int featIdx = indexOf(featName);
        if (featIdx >= 0)
          v.add(featIdx, 1d);
      }
    }
  }

}
