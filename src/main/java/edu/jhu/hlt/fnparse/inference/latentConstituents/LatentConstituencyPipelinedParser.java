package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.OracleStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.util.Describe;
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
		for (int i = 0; i < parses.size(); i++) {
			LOG.info(i + " frames: " + Describe.fnTagging(frames.get(i)));
			LOG.info(i + " prunes: " + prunes.get(i).describe());
		}
		frameId.train(sentences, frames);
		rolePruning.train(frames, prunes);
		for (AlmostFNParse pr : prunes)
			LOG.info("[train] pruning gold AlmostFNParse: " + pr.describe());
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

	// Instantiate the variables to see if it is at all possible.
	public static void main(String[] args) {
		Logger.getLogger(SGD.class).setLevel(Level.ERROR);
		Logger.getLogger(Threads.class).setLevel(Level.ERROR);
		Logger.getLogger(CrfObjective.class).setLevel(Level.ERROR);
		Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.ERROR);
		//Logger.getLogger(RoleSpanPruningStage.class).setLevel(Level.INFO);
		BasicRoleSpanFeatures.OVERFITTING_DEBUG = true;
		LatentConstituencyPipelinedParser p = new LatentConstituencyPipelinedParser();
		List<FNParse> parses = DataUtil.iter2list(
				FileFrameInstanceProvider.debugFIP.getParsedSentences())
				;//.subList(0, 10);
		p.scanFeatures(parses);
		p.train(parses);
		List<Sentence> sentences = DataUtil.stripAnnotations(parses);
		List<FNParse> predicted = p.predict(sentences, parses);
		Map<String, Double> results = BasicEvaluation.evaluate(parses, predicted);
		BasicEvaluation.showResults("[test]", results);
	}
}
