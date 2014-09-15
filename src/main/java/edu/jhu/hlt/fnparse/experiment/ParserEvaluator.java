package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
import edu.jhu.hlt.fnparse.util.ModelMerger;

/**
 * Takes a directory of models trained by ParserTrainer, chooses the best model
 * for each stage, and then puts them together into a full PipelinedFnParser,
 * and evaluates it on a test set.
 * 
 * @author travis
 */
public class ParserEvaluator {
	
	public static final Logger LOG = Logger.getLogger(ParserEvaluator.class);

	public static void main(String[] args) {
		if (args.length != 4) {
			System.out.println("please provide:");
			System.out.println("1) A directory containing a frameId model and alphabet");
			System.out.println("2) A directory containing a argId model and alphabet");
			System.out.println("3) A directory containing a argSpans model and alphabet");
			System.out.println("4) An output working direcotry");
			return;
		}
		String frameIdDir = args[0];
		String argIdDir = args[1];
		String argSpansDir = args[2];
		//String workingDir = args[3];

		// Merge the models into one model.
		ModelMerger.Model<String> model = ModelMerger.merge(
			getModel(frameIdDir), getModel(argIdDir), getModel(argSpansDir));
		LOG.info("merge model has " + model.weights.length + " features");

		// Create the parser
		ParserParams params = new ParserParams();
		PipelinedFnParser parser = new PipelinedFnParser(params);
		parser.setAlphabet(model.alphabet);
		parser.getFrameIdStage().setWeights(model.getFgModel());
		parser.getArgIdStage().setWeights(model.getFgModel());
		parser.getArgSpanStage().setWeights(model.getFgModel());
		parser.getAlphabet().stopGrowth();

		// Get the evaluation data
		LOG.info("getting the evaluation data from "
				+ ParserTrainer.SENTENCE_ID_SPLITS);
		List<FNParse> all = DataUtil.iter2list(
				new FNIterFilters.SkipSentences<FNParse>(
				FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
				Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
		DataSplitReader dsr = new DataSplitReader(ParserTrainer.SENTENCE_ID_SPLITS);
		List<FNParse> test = dsr.getSection(all, "test", false);	// TODO make this true!
		LOG.info("read in " + test.size() + " test instances");

		// Evaluate the parser
		List<FNParse> predicted = parser.predictWithoutPeaking(test);
		Map<String, Double> results = BasicEvaluation.evaluate(test, predicted);
		BasicEvaluation.showResults("[test]", results);
		LOG.info("done");
	}
	
	private static ModelMerger.Model<String> getModel(String dir) {
		LOG.info("reading model from " + dir);
		/*ModelMerger.Model<String> model = new ModelMerger.Model<>(
				ModelIO.readAlphabet(new File(dir,
						ParserTrainer.ALPHABET_NAME)),
				ModelIO.readBinary(new File(dir,
						ParserTrainer.MODEL_NAME)));*/
		ModelMerger.Model<String> model = ParserTrainer.readMergedModel(
				new File(dir, ParserTrainer.ALPHABET_NAME),
				new File(dir, ParserTrainer.MODEL_NAME));
		LOG.info("done, model has " + model.weights.length + " features");
		return model;
	}
}
