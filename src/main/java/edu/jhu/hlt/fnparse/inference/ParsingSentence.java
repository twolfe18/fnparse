package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.data.UnlabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.dep.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;

/**
 * wraps the variables needed for training/predicting the factor graph
 * for FN parsing.
 * 
 * because FgExamples store pointers to Vars which are statefully used in this
 * class, if you want to create two FgExamples for training (e.g. for a pipeline
 * model), you need to create two ParsingSentences for the one underlying Sentence.
 * 
 * classes that extend this should not have a setGold method, but rather have two constructors:
 * one for when you know the label and one for when you don't (which should call the
 * respective constructor in this class). The only downside of this is that if you construct
 * a ParsingSentence for training (and you provide a gold label), you cannot reuse this
 * instance for prediction because the VarType's of some of the variables may not be correctly set.
 * 
 * @param Hypothesis is the type that serves as a variable wrapper. This class must
 * be capable of storing a label, and the {@code setGold} method should change the
 * state of the {@code Hypothesis}s' labels.
 * 
 * @param Label is the type for whatever information you need to set the labels in
 * the factor graph.
 * 
 * @see {@code FrameIdSentence}, {@code RoleIdSentence}, {@code JointFrameRoleIdSentence}.
 * 
 * @author travis
 */
public abstract class ParsingSentence<Hypothesis extends FgRelated, Label> {
	
	protected final Label gold;	// must be set at construction

	// hyp could be FrameVars, RoleVars, or FrameInstanceHypothesis
	// should be populated at construction
	public List<Hypothesis> hypotheses;

	public ProjDepTreeFactor depTree;	// holds variables too
	
	protected FactorFactory<Hypothesis> factorTemplate;
	protected FactorFactory<Object> depParseFactorTemplate;
	
	public Sentence sentence;
	protected ParserParams params;
	

	/** constructor for cases where you don't know the label */
	public ParsingSentence(Sentence s, ParserParams params, FactorFactory<Hypothesis> factorTemplate) {
		this(s, params, factorTemplate, null);
	}

	/** constructor for cases where you do know the label */
	public ParsingSentence(Sentence s, ParserParams params, FactorFactory<Hypothesis> factorTemplate, Label label) {
		this.params = params;
		this.depParseFactorTemplate = new DepParseFactorFactory(params);
		this.factorTemplate = factorTemplate;
		this.sentence = s;
		this.depTree = null;
		this.hypotheses = null;
		this.gold = label;
	}
	
	public Sentence getSentence() { return sentence; }
	
	public boolean hasGold() { return gold != null; }

	/**
	 * @param labeled will return a LabeledFgExample if true, otherwise a UnlabeledFgExample
	 * @param updateFromModel will call updateFgLatPred if true
	 */
	public FgExample getExample(boolean labeled, boolean updateFromModel) {
		
		FactorGraph fg = new FactorGraph();
		VarConfig gold = new VarConfig();
		
		// create factors
		List<Factor> factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies) {
			depTree = new ProjDepTreeFactor(sentence.size(), VarType.LATENT);
			factors.addAll(depParseFactorTemplate.initFactorsFor(sentence, Collections.emptyList(), depTree));
		}
		else depTree = null;
		factors.addAll(factorTemplate.initFactorsFor(sentence, hypotheses, depTree));

		// register all the variables and factors
		for(Hypothesis hyp : hypotheses)
			hyp.register(fg, gold);

		// add factors to the factor graph
		for(Factor f : factors)
			fg.addFactor(f);
		
		FgExample fge = labeled
			? new LabeledFgExample(fg, gold)
			: new UnlabeledFgExample(fg, new VarConfig());	// if you ever add observed variables, this needs to change

		if(updateFromModel) {
			assert params.weights != null;
			fge.updateFgLatPred(params.weights, params.logDomain);
		}
		return fge;
	}

	public List<Hypothesis> getHypotheses() { return hypotheses; }
	
	
	/**
	 * basically a Future<FNParse>
	 */
	public abstract static class ParsingSentenceDecodable {

		public final FactorGraph fg;
		public final FgInferencerFactory infFact;
		private FgInferencer inf;

		/**
		 * you should call fg.update*** before providing the factor graph to this constructor.
		 */
		public ParsingSentenceDecodable(FactorGraph fg, FgInferencerFactory infFact) {
			this.fg = fg;
			this.infFact = infFact;
		}
		
		/**
		 * forces inference to be run, but will only do so once
		 * (future calls are just returned from cache).
		 */
		public FgInferencer getMargins() {
			if(inf == null) {
				inf = infFact.getInferencer(fg);
				inf.run();
			}
			return inf;
		}
		
		/**
		 * re-decodes the sentence using the current state except for the margins,
		 * which are computed once and don't change.
		 */
		public abstract FNParse decode();
		
		public FactorGraph getFactorGraph() { return fg; }
	}


	/**
	 * may not actually run inference, but returns a Future<FNParse>
	 */
	public abstract ParsingSentenceDecodable runInference(FgModel model, FgInferencerFactory infFactory);

	
	public LabeledFgExample getTrainingExample() {
		if(gold == null)
			throw new RuntimeException("are you sure you used the constructor with the gold label?");
		LabeledFgExample fge = (LabeledFgExample) getExample(true, false);
		return fge;
	}

	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 * 
	 * TODO move this into FrameIdSentence? does JointIdSentence need this?
	 */
	protected FrameVars makeFrameVar(Sentence s, int headIdx, boolean logDomain) {
		assert false : "stop using this";
		return null;
	}
	
}

