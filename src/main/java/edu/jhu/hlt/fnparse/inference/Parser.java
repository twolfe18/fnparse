package edu.jhu.hlt.fnparse.inference;

import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.Option;

/**
 * Parent class for classes that do parsing. Training is not captured here and
 * is assumed to be deriving-class-specific.
 * 
 * @author travis
 */
public interface Parser extends HasFeatureAlphabet {

  public static final Option PARSER_MODE =
      new Option("parserMode", true, "classifySpans", "classifyHeads");

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
