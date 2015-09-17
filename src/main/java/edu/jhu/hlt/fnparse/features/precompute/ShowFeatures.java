package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.io.IOException;

import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Templates;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Tmpl;
import edu.jhu.hlt.fnparse.util.FindReplace;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;

/**
 * Given a feature file with in (template,feature)s and alphabet, produce a
 * feature file with String (template,feature)s. Only really useful for
 * debugging, as the goal is to get rid of strings not introduce them.
 *
 * @author travis
 */
public class ShowFeatures {

  // int -> string
  private Templates alph;

  // If true, only print the feature instead of "template:feature".
  // This is useful when you wrote you feature strings like "templateName=value"
  public boolean onlyFeature = true;
  public String sep = ":";

  public ShowFeatures(File alphFile) {
    this.alph = new Templates(alphFile);
  }

  /** Accepts strings like "22:42" and maps them to "fooTemplate:barFeature" */
  public String intTemplateFeatureToStrings(String input) {
    IntPair tf = AlphabetMerger.parseTemplateFeature(input);
    Tmpl t = alph.get(tf.first);
    String f = t.alph.lookupObject(tf.second);
    if (onlyFeature)
      return f;
    else
      return t + sep + f;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    ShowFeatures sf = new ShowFeatures(
        config.getExistingFile("alph"));
    FindReplace fr = new FindReplace(
        AlphabetMerger::findTemplateFeatureMentions,
        sf::intTemplateFeatureToStrings);
    fr.findReplace(
        config.getExistingFile("inputFeatures"),
        config.getFile("outputFeatures"));
  }
}
