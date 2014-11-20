package edu.jhu.hlt.fnparse.inference;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.Option;

/**
 * Parent class for classes that do parsing. Training is not captured here and
 * is assumed to be deriving-class-specific.
 * 
 * @author travis
 */
public interface Parser extends HasFeatureAlphabet {

  // Command line options
  public static final Option PARSER_MODE =
      new Option("parserMode", true, "span", "head");
  public static final Option SYNTAX_MODE =
      new Option("syntaxMode", true, "regular", "latent", "none");
  public static final String FEATURES = "features";

  public static final File SENTENCE_ID_SPLITS =
      new File("toydata/development-split.dipanjan-train.txt");

  public static DataOutputStream getDOStreamFor(File directory, String filename) {
    if (!directory.isDirectory())
      throw new RuntimeException();
    try {
      File f = new File(directory, filename);
      OutputStream os = new FileOutputStream(f);
      if (filename.toLowerCase().endsWith(".gz"))
        os = new GZIPOutputStream(os);
      return new DataOutputStream(os);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public GlobalParameters getGlobalParameters();

  public void setFeatures(String featureTemplateDescription);

	public void saveModel(File directory);

  /**
   * NOTE: You could check for pre-computed stages here
   * 
   * TODO: in redis, store the models with:
   * key = (stageId, configString)
   * value = some byte serialized string (not java ser, manual)
   * 
   * TODO each stage can declare a set of options it depends on
   * these options, and their values, go into the redis key for serialization
   */
  public void configure(Map<String, String> configuration);

  /**
   * Fully train each stage/component of the parser.
   * NOTE: If certain stages have been cached and can be proven to be consistent
   * with the configuration give, then you don't have to train those parts.
   */
  public void train(List<FNParse> data);

  /**
   * Parse some sentences. gold may be null, otherwise the elements in gold
   * should match up with the input sentences (same length lists).
   */
  public List<FNParse> parse(List<Sentence> sentences, List<FNParse> gold);
}
