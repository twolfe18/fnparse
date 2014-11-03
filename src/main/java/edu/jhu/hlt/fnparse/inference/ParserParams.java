package edu.jhu.hlt.fnparse.inference;

import java.io.File;
import java.io.Serializable;
import java.util.Random;

import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.util.stats.Multinomials;
import edu.jhu.util.Alphabet;

public class ParserParams
    implements Serializable, HasFeatureAlphabet, HasParserParams {
  private static final long serialVersionUID = 1L;

  public boolean logDomain = true;
  public boolean useLatentDepenencies = false;
  public boolean useLatentConstituencies = false;
  public boolean useSyntaxFeatures = true;
  public boolean usePredictedFramesToTrainArgId = false;	// otherwise use gold frames

  // If true, use the largest feature set you have available. Useful for
  // overfitting experiments.
  public boolean useOverfittingFeatures = false;

  // store weights at the stage level
  private Alphabet<String> featAlph = new Alphabet<>();

  public int threads = 1;
  public Random rand = new Random(9001);
  public HeadFinder headFinder = new SemaforicHeadFinder();

  /**
   * This encodes all of the features for the model.
   * See {@link TemplatedFeatures}.
   */
  private String featureTemplateDescription = null;
  /*
      "frame * head1Word"
      + "+ frame * span1LeftPos"
      + "+ frame * span1Width/1"
      + "+ frameRoleArg * head1Bc1000/99"
      + "+ frameRoleArg * span1Width/1"
      + "+ frameRoleArg * head1CollapsedLabel"
      + "+ roleArg * span1GovDirRelations"
      + "+ roleArg * head1CollapsedParentDir * head1Pos"
      + "+ frameRoleArg * Word-2-grams-between-Head1-and-Head2 * Dist(Direction,Head1,Head2)";
   */

  public String getFeatureTemplateDescription() {
    return featureTemplateDescription;
  }

  public void setFeatureTemplateDescription(String templates) {
    /*
    try {
      TemplatedFeatures.parseTemplates(templates);
    } catch (TemplateDescriptionParsingException e) {
      throw new RuntimeException(e);
    }
    */
    featureTemplateDescription = templates;
  }

  @Override
  public Alphabet<String> getAlphabet() {
    return featAlph;
  }

  @Override
  public ParserParams getParserParams() {
    return this;
  }

  /** checks if they're log proportions from this.logDomain */
  public void normalize(double[] proportions) {
    if (this.logDomain)
      Multinomials.normalizeLogProps(proportions);
    else
      Multinomials.normalizeProps(proportions);
  }

  public boolean verifyConsistency() {
    return true;
  }

  public void setFeatureAlphabet(Alphabet<String> featIdx) {
    this.featAlph = featIdx;
  }

  public void readFeatAlphFrom(File f) {
    System.out.printf("[ParserParams readFeatAlphFrom] %s\n", f.getPath());
    featAlph = ModelIO.readAlphabet(f);
  }

  public void writeFeatAlphTo(File f) {
    System.out.printf("[ParserParams writeFeatAlphTo] %s\n", f.getPath());
    ModelIO.writeAlphabet(featAlph, f);
  }

  private transient MultiTimer timer = new MultiTimer();
  public Timer getTimer(String name) {
    if (timer == null)
      timer = new MultiTimer();
    return timer.get(name, true);
  }
}