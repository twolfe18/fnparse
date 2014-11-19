package edu.jhu.hlt.fnparse.experiment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
import edu.jhu.hlt.fnparse.util.ParserLoader;

/**
 * Takes a directory of models trained by ParserTrainer, chooses the best model
 * for each stage, and then puts them together into a full PipelinedFnParser,
 * and evaluates it on a test set.
 * 
 * @author travis
 */
public class ParserEvaluator {
  public static final Logger LOG = Logger.getLogger(ParserEvaluator.class);

  public static void main(String[] args) throws Exception {
    if (args.length % 2 == 1 || args.length == 0) {
      System.out.println("please provide key value pairs");
      return;
    }
    Map<String, String> options = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      String key = args[i].replaceFirst("--", "");
      String oldValue = options.put(key, args[i + 1]);
      assert oldValue == null;
    }

    // Get the parser
    LOG.info("loading parser");
    Parser parser = ParserLoader.loadParser(options);

    // Get the evaluation data
    LOG.info("loading evaluation data from "
        + Parser.SENTENCE_ID_SPLITS);
    List<FNParse> all = DataUtil.iter2list(
        new FNIterFilters.SkipSentences<FNParse>(
            FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
            Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
    DataSplitReader dsr = new DataSplitReader(Parser.SENTENCE_ID_SPLITS);
    List<FNParse> test = dsr.getSection(all, "test", false);	// TODO make this true!
    LOG.info("read in " + test.size() + " test instances");

    // Make sure everything is loaded for timing's sake
    TargetPruningData.getInstance().getPrototypesByFrame();
    TargetPruningData.getInstance().getWordnetDict();

    // Evaluate the parser
    List<Sentence> sentences = DataUtil.stripAnnotations(test);
    List<FNParse> predicted = parser.parse(sentences, test);
    Map<String, Double> results = BasicEvaluation.evaluate(test, predicted);
    BasicEvaluation.showResults("[test]", results);
    LOG.info("done");
  }
}
