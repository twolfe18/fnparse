package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.FPR;
import edu.jhu.hlt.fnparse.experiment.ParserTrainer;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.OracleStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.util.Threads;

public class LatentConstituencyPipelinedParser {
	public static final Logger LOG =
			Logger.getLogger(LatentConstituencyPipelinedParser.class);

	private ParserParams params;
	private Stage<Sentence, FNTagging> frameId;
	private RoleSpanPruningStage rolePruning;
	private RoleSpanLabelingStage roleLabeling;

	public LatentConstituencyPipelinedParser() {
		params = new ParserParams();
		frameId = new OracleStage<>();
		rolePruning = new RoleSpanPruningStage(params);
		roleLabeling = new RoleSpanLabelingStage(params);
	}

	public void scanFeatures(List<FNParse> parses) {
		LOG.info("setting up inference for " + parses.size() + " parses");
		params.getFeatureAlphabet().startGrowth();
		List<Sentence> sentences = DataUtil.stripAnnotations(parses);
		List<FNTagging> frames = DataUtil.convertParsesToTaggings(parses);
		List<AlmostFNParse> prunes = AlmostFNParse.optimalPrune(parses);
		frameId.scanFeatures(sentences, frames, 45, 10_000_000);
		rolePruning.scanFeatures(frames, prunes, 45, 10_000_000);
		roleLabeling.scanFeatures(prunes, parses, 45, 10_000_000);
		params.getFeatureAlphabet().stopGrowth();
	}

	public void train(List<FNParse> parses) {
		LOG.info("training");
		List<Sentence> sentences = DataUtil.stripAnnotations(parses);
		List<FNTagging> frames = DataUtil.convertParsesToTaggings(parses);
		List<AlmostFNParse> prunes = AlmostFNParse.optimalPrune(parses);
		frameId.train(sentences, frames);
		rolePruning.train(frames, prunes);
		roleLabeling.train(prunes, parses);
	}

	public List<FNParse> predict(List<Sentence> sentences, List<FNParse> gold) {
		List<FNTagging> frames = frameId.setupInference(sentences, gold).decodeAll();
		List<AlmostFNParse> goldPrune = null;
		if (gold != null)
			goldPrune = AlmostFNParse.optimalPrune(gold);
		List<AlmostFNParse> prunes = rolePruning.setupInference(frames, goldPrune).decodeAll();
		for (AlmostFNParse pr : prunes)
			LOG.info("[predict] pruning predicted AlmostFNParse: " + pr.describe());
		List<FNParse> parses = roleLabeling.setupInference(prunes, gold).decodeAll();
		return parses;
	}

	public static void main(String[] args) {
		int nTrainLimit = 50;

		Logger.getLogger(SGD.class).setLevel(Level.ERROR);
		Logger.getLogger(Threads.class).setLevel(Level.ERROR);
		Logger.getLogger(CrfObjective.class).setLevel(Level.ERROR);
		Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.ERROR);
		//Logger.getLogger(RoleSpanPruningStage.class).setLevel(Level.INFO);
		//BasicRoleSpanFeatures.OVERFITTING_DEBUG = true;

		LatentConstituencyPipelinedParser p = new LatentConstituencyPipelinedParser();

		// Get the data
		List<FNParse> all = DataUtil.iter2list(
				new FNIterFilters.SkipSentences<FNParse>(
				FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
				Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
		DataSplitReader dsr = new DataSplitReader(ParserTrainer.SENTENCE_ID_SPLITS);
		List<FNParse> train = dsr.getSection(all, "train", false);
		List<FNParse> tune = dsr.getSection(all, "tune", false);
		List<FNParse> test = dsr.getSection(all, "test", false);
		if(nTrainLimit < train.size()) {
			train = DataUtil.reservoirSample(
					train, nTrainLimit, new Random(9001));
		}
		if (test.size() > 200) {
			test = DataUtil.reservoirSample(test, 200, new Random(9002));
		}
		LOG.info("#train=" + train.size()
				+ " #tune=" + tune.size()
				+ " #test=" + test.size());

		// Train a model
		p.scanFeatures(train);
		p.train(train);

		// TODO tune decoder thresholds

		ModelIO.writeHumanReadable(
				p.rolePruning.getWeights(),
				p.params.getFeatureAlphabet(),
				new File("saved-models/constit-pruning.txt"),
				true);
		checkPruning(p, train);
		checkPruning(p, test);
		System.exit(-1);

		// Eval on test
		List<Sentence> sentences = DataUtil.stripAnnotations(test);
		List<FNParse> predicted = p.predict(sentences, test);
		Map<String, Double> results = BasicEvaluation.evaluate(test, predicted);
		BasicEvaluation.showResults("[test]", results);

		// Eval on train
		sentences = DataUtil.stripAnnotations(train);
		predicted = p.predict(sentences, train);
		results = BasicEvaluation.evaluate(train, predicted);
		BasicEvaluation.showResults("[train]", results);
	}

	private static void checkPruning(
			LatentConstituencyPipelinedParser parser,
			List<FNParse> examples) {
		int kept = 0, total = 0;
		FPR micro = new FPR(false);
		for (FNParse p : examples) {
			FNTagging in = DataUtil.convertParseToTagging(p);
			AlmostFNParse out = parser.rolePruning
					.setupInference(Arrays.asList(in), null)
					.decodeAll().get(0);
			kept += out.numPossibleArgs();
			total += out.numPossibleArgsNaive();
			FPR m = out.recall(p);
			LOG.info(String.format("%s recall=%.1f #var=%d %%kept=%.1f",
					p.getSentence().getId(),
					m.recall() * 100d,
					out.numPossibleArgs(),
					(100d * out.numPossibleArgs()) / out.numPossibleArgsNaive()));
			micro.accum(m);
		}
		LOG.info(String.format("[checkPruning] on %d examples, microRecall=%.1f"
				+ " #var/parse=%.1f %%kept=%.1f",
				examples.size(),
				micro.recall() * 100d,
				((double) kept) / examples.size(),
				(100d * kept) / total));
	}
}
