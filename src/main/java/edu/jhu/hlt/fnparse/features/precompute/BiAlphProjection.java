package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.io.IOException;

import edu.jhu.hlt.fnparse.util.FindReplace;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;

/**
 * Read in some features and a bialph and spit out some features (new domain)
 * and an alph.
 *
 * @author travis
 */
public class BiAlphProjection {

  /** Maps oldInt -> newInt using a {@link BiAlph} */
  static class BiAlphIntMapper {
    private BiAlph bialph;
    public BiAlphIntMapper(File bialphFile) {
      bialph = new BiAlph(bialphFile);
    }
    /** Takes a substring like "22:42" */
    public String replace(String input) {
      IntPair tf = AlphabetMerger.parseTemplateFeature(input);
      int newTemplate = bialph.mapTemplate(tf.first);
      int newFeature = bialph.mapFeature(tf.first, tf.second);
      return newTemplate + ":" + newFeature;
    }
  }

  public static void project(File inputFeatures, File bialphFile, File outputFeatures) throws IOException {
    FindReplace fr = new FindReplace(
        AlphabetMerger::findTemplateFeatureMentions,
        new BiAlphIntMapper(bialphFile)::replace);
    fr.findReplace(inputFeatures, outputFeatures);
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    project(
        config.getExistingFile("inputFeatures"),
        config.getExistingFile("inputBialph"),
        config.getFile("outputFeatures"));
  }
}
