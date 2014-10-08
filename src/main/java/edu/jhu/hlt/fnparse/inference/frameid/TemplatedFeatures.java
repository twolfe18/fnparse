package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

/**
 * Issue: how to handle availability of information...
 * I would like this stage to work regardless of if we're doing latent syntax or
 * not...
 * 
 * e.g. "targetWord + targetLabel * targetPos"
 * 
 * @author travis
 */
public class TemplatedFeatures implements Serializable {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(TemplatedFeatures.class);

  /**
   * All of the information needed for a template to make an extraction.
   */
  public static class Context {
    public static final int UNSET = -3;
    private Frame frame = null;
    private Span target = null;
    private int head = UNSET;
    private int child = UNSET;
    private Sentence sentence;
    public Sentence getSentence() {
      return sentence;
    }
    public void setSentence(Sentence sentence) {
      this.sentence = sentence;
    }
    public Frame getFrame() {
      return frame;
    }
    public void setFrame(Frame frame) {
      this.frame = frame;
    }
    public Span getTarget() {
      return target;
    }
    public void setTarget(Span target) {
      this.target = target;
    }
    public int getHead() {
      return head;
    }
    public void setHead(int head) {
      this.head = head;
    }
    public int getChild() {
      return child;
    }
    public void setChild(int child) {
      this.child = child;
    }
  }

  /**
   * Templates have no arguments, just represent a piece of information like
   * "lemma of the first word in the target"
   */
  public static interface Template {
    /**
     * This should incorporate the name of this template, be globally unique.
     * NOTE: If this method returns NULL, then the semantics are that any
     * template built off of conjunctions with this feature will NOT fire.
     */
    String extract(Context context);
  }

  /** The basic templates */
  private static Map<String, Template> basicTemplates;
  static {
    basicTemplates = new HashMap<>();
    basicTemplates.put("1", new Template() {
      @Override
      public String extract(Context context) {
        return "intercept";
      }
    });
    basicTemplates.put("headWord", new Template() {
      @Override
      public String extract(Context context) {
        int h = context.getTarget().end - 1;
        String w = context.getSentence().getWord(h);
        return "headWord=" + w;
      }
    });
    basicTemplates.put("headPos", new Template() {
      @Override
      public String extract(Context context) {
        int h = context.getTarget().end - 1;
        String p = context.getSentence().getPos(h);
        return "headPos=" + p;
      }
    });
    basicTemplates.put("headCollLabel", new Template() {
      @Override
      public String extract(Context context) {
        int h = context.getTarget().end - 1;
        DependencyParse deps = context.getSentence().getCollapsedDeps();
        return "headCollLabel=" + deps.getLabel(h);
      }
    });

    // TODO write some more templates
    
    
    
    // These templates should ALWAYS come first in a product
    basicTemplates.put("frame", new Template() {
      /**
       * fires whenever f_it=1 for some frame t.
       */
      @Override
      public String extract(Context context) {
        Frame f = context.getFrame();
        return f == null ? null : "frame=" + f.getName();
      }
    });
    basicTemplates.put("dep", new Template() {
      /**
       * fires whenever l_{root,i}=1 where i is the head of some FrameInstance
       */
      @Override
      public String extract(Context context) {
        if (context.getHead() == Context.UNSET)
          return null;
        else
          return "dep";
      }
    });
    basicTemplates.put("frameDep", new Template() {
      /**
       * fires whenever l_{root,i}=1 AND f_it=1 for some frame t.
       */
      @Override
      public String extract(Context context) {
        if (context.getHead() == Context.UNSET)
          return null;
        else if (context.getFrame() == null)
          return null;
        else
          return "frameDep=" + context.getFrame().getName();
      }
    });
  }

  /** Conjoins two basic templates */
  public static class TemplateJoin implements Template {
    private static final String JOIN_STR = "_";
    private Template left, right;

    public TemplateJoin(Template left, Template right) {
      if (left == null || right == null)
        throw new IllegalArgumentException();
      this.left = left;
      this.right = right;
    }

    @Override
    public String extract(Context context) {
      String l = left.extract(context);
      if (l == null)
        return null;
      String r = right.extract(context);
      if (r == null)
        return null;
      return l + JOIN_STR + r;
    }
  }

  /** Take a full template string and break it into independent templates */
  private static List<String> tokenizeTemplates(String templateString) {
    List<String> toks = new ArrayList<>();
    for (String s : templateString.split("\\+"))
      toks.add(s.trim());
    return toks;
  }

  /** Take an independent template and build it up from basic templates */
  private static Template parseTemplateToken(String templateToken) {
    Template template = null;
    for (String basicTemplateName : templateToken.split("\\*")) {
      Template basicTemplate = basicTemplates.get(basicTemplateName.trim());
      if (basicTemplate == null) {
        throw new RuntimeException("could not parse basic template: "
            + basicTemplateName);
      }
      if (template == null) {
        template = basicTemplate;
      } else {
        template = new TemplateJoin(template, basicTemplate);
      }
    }
    if (template == null) {
      template = basicTemplates.get(templateToken);
      if (template == null) {
        throw new RuntimeException("could not parse basic template: "
            + templateToken);
      }
    }
    return template;
  }

  private String globalPrefix;
  private String templateString;
  private transient List<Template> templates;
  private transient Alphabet<String> featureAlphabet;
  private transient Context context;

  public TemplatedFeatures(String globalPrefix, String description, Alphabet<String> featureAlphabet) {
    this.globalPrefix = globalPrefix;
    this.templateString = description;
    this.featureAlphabet = featureAlphabet;
    this.context = new Context();
    this.templates = new ArrayList<>();
    for (String templateToken : tokenizeTemplates(description))
      this.templates.add(parseTemplateToken(templateToken));
  }

  public String getTemplateString() {
    return templateString;
  }

  public void setContext(Sentence s, Frame f, Span t) {
    context.setFrame(f);
    context.setTarget(t);
    context.setSentence(s);
  }

  public Context getContext() {
    return context;
  }

  public void featurize(FeatureVector v) {
    boolean grow = featureAlphabet.isGrowing();
    for (Template t : templates) {
      String te = t.extract(context);
      if (te != null) {
        String featName = te + "::" + globalPrefix;
        int featIdx = featureAlphabet.lookupIndex(featName, grow);
        if (featIdx >= 0)
          v.add(featIdx, 1d);
      }
    }
  }
}
