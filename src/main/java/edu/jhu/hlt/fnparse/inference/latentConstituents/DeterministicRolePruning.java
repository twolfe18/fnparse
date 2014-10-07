package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;

public class StanfordParserRolePruning
		implements Stage<FNTagging, FNParseSpanPruning> {
	private static final long serialVersionUID = 1L;
	public static final Logger LOG =
			Logger.getLogger(StanfordParserRolePruning.class);
	private final FgModel weights = new FgModel(0);
	private ConcreteStanfordWrapper parser = new ConcreteStanfordWrapper();

	public static enum Mode {
		// Take all constituents in Stanford's constituency parse
		STANFORD_CONSTITUENTS,

		// Constituency tree version is described in the section
		// "Pruning Algorithm" in
		// http://www.cs.brandeis.edu/~xuen/publications/emnlp04.pdf
		XUE_PALRMER,

		// Based on XUE_PALMER, but first converting dep tree to constituent tree
		// TODO def dep2Cons:
		// For a dep node rooted at i, project to its left and rightmost
		// children l and r.
		// Make a constituent (l,r).
		// For each child of i, ask for their constituents from this same
		// projection step.
		// Attach each of these children to the (l,r) constituent
		XUE_PALMER_DEP,

		// Described in the section "Argument Candidates" in
		// http://www.dipanjandas.com/files/acl2014frames.pdf
		XUE_PALMER_DEP_HERMANN,
	}
	private static final Mode MODE = Mode.STANFORD_CONSTITUENTS;

	@Override
	public FgModel getWeights() {
		return weights;
	}

	@Override
	public void setWeights(FgModel weights) {
		LOG.info("[setWeights] not actually doing anything");
	}

	@Override
	public boolean logDomain() {
		return false;
	}

	@Override
	public String getName() {
		return this.getClass().getName();
	}

	@Override
	public void scanFeatures(
			List<? extends FNTagging> unlabeledExamples,
			List<? extends FNParseSpanPruning> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		LOG.info("[scanFeatures] not actually doing anything");
	}

	@Override
	public void train(List<FNTagging> x, List<FNParseSpanPruning> y) {
		LOG.info("[train] not actually doing anything");
	}

	@Override
	public StageDatumExampleList<FNTagging, FNParseSpanPruning> setupInference(
			List<? extends FNTagging> input,
			List<? extends FNParseSpanPruning> output) {
		List<StageDatum<FNTagging, FNParseSpanPruning>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++)
			data.add(new SD(input.get(i), parser));
		return new StageDatumExampleList<>(data);
	}

	static class SD implements StageDatum<FNTagging, FNParseSpanPruning> {
		private FNTagging input;
		private ConcreteStanfordWrapper parser;
		public SD(FNTagging input, ConcreteStanfordWrapper parser) {
			this.input = input;
			this.parser = parser;
		}
		@Override
		public FNTagging getInput() {
			return input;
		}
		@Override
		public boolean hasGold() {
			return false;
		}
		@Override
		public FNParseSpanPruning getGold() {
			throw new RuntimeException();
		}
		@Override
		public LabeledFgExample getExample() {
			FactorGraph fg = new FactorGraph();
			VarConfig gold = new VarConfig();
			return new LabeledFgExample(fg, gold);
		}
		@Override
		public IDecodable<FNParseSpanPruning> getDecodable() {
			return new Decodable(input, parser);
		}
	}

	static class Decodable implements IDecodable<FNParseSpanPruning> {
		private ConcreteStanfordWrapper parser;
		private FNTagging input;
		private FNParseSpanPruning output;
		public Decodable(FNTagging input, ConcreteStanfordWrapper parser) {
			this.input = input;
			this.parser = parser;
		}
		@Override
		public FNParseSpanPruning decode() {
			if (output == null) {
				if (StanfordParserRolePruning.MODE
						== Mode.STANFORD_CONSTITUENTS) {
					Map<Span, String> cons = parser.parse(input.getSentence());
					List<Span> consSpans = new ArrayList<>();
					consSpans.addAll(cons.keySet());
					consSpans.add(Span.nullSpan);
					Map<FrameInstance, List<Span>> possibleSpans = new HashMap<>();
					for (FrameInstance fi : input.getFrameInstances()) {
						FrameInstance key = FrameInstance.frameMention(
								fi.getFrame(), fi.getTarget(), fi.getSentence());
						List<Span> old = possibleSpans.put(key, consSpans);
						assert old == null;
					}
					output = new FNParseSpanPruning(
							input.getSentence(),
							input.getFrameInstances(),
							possibleSpans);
				} else {
					throw new RuntimeException("unknown mode: "
							+ StanfordParserRolePruning.MODE);
				}
			}
			return output;
		}
	}

	@Override
	public void saveModel(File file) {
		LOG.info("not actually saving anything");
	}

	@Override
	public void loadModel(File file) {
		LOG.info("not actually loading anything");
	}
}
