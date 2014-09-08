package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.data.simple.SimpleAnnoSentence;
import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.gm.train.CrfTrainer.CrfTrainerPrm;
import edu.jhu.hlt.optimize.AdaGrad;
import edu.jhu.hlt.optimize.AdaGrad.AdaGradPrm;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.hlt.optimize.SGD.SGDPrm;
import edu.jhu.srl.CorpusStatistics;
import edu.jhu.srl.CorpusStatistics.CorpusStatisticsPrm;
import edu.jhu.srl.DepParseFactorGraph.DepParseFactorTemplate;
import edu.jhu.srl.DepParseFeatureExtractor;
import edu.jhu.srl.DepParseFeatureExtractor.DepParseFeatureExtractorPrm;
import edu.jhu.srl.FeTypedFactor;
import edu.jhu.util.Alphabet;

/**
 * The goal of this test is to make sure that I can get Matt's latent dependency
 * code to do something correct. I have observed that the way that I have my
 * inference setup, it does not make a difference if I add latent dependencies,
 * and I cannot figure out why because my code has too many moving pieces. I
 * have observed that the features are computed for the unary factors on the
 * link variables, but this doesn't seem to propagate any effect to the
 * prediction variables.
 * 
 * NOTE: This works now, but I haven't added the check into the code. You just
 * have to run twice and ensure that the beliefs for the prediction variables
 * is different for useLatentDeps=true vs false.
 * 
 * @author travis
 */
public class BasicLatentDepTest {
	
	SimpleAnnoSentence sentence;
	static List<String> tagNames = Arrays.asList("N", "V", "O");
	static boolean useLatentDeps = false;
	
	@Before
	public void setupSentence() {
		sentence = new SimpleAnnoSentence();
		sentence.setWords(Arrays.asList("the", "fox", "ate", "many", "hens"));
		sentence.setPosTags(Arrays.asList("DT", "NN", "VBP", "JJ", "NNS"));
		sentence.setParents(new int[] {1, 2, -1, 4, 2});

		// Morphology features, not sure if needed
		ArrayList<List<String>> feats = new ArrayList<>();
		for(int i=0; i<sentence.size(); i++)
			feats.add(Collections.<String>emptyList());
		sentence.setFeats(feats);
	}

	/**
	 * For this test we will create a short sentence's-worth of variables.
	 * The prediction variables will be POS tags (N, V, or O for other).
	 * The unary factors on these prediction variables will be relatively
	 * un-informative, e.g. whether the word ends in "s" or not.
	 * In addition, we'll include latent dependencies. Assuming the default dep
	 * features can capture the correct syntax, the syntax should be very
	 * helpful (children of root are V, children of of V are N, and children of
	 * N are O). 
	 * NOTE: we may need to resort to giving supervision for the dep variables
	 * to get this to work, but hopefully not.
	 */
	@Test
	public void test() {
		Alphabet<Object> featureAlphabet = new Alphabet<>();
		featureAlphabet.startGrowth();
		FactorGraph fg = new FactorGraph();

		// Create tag variables
		List<Var> tags = new ArrayList<>();
		for (String t : sentence.getWords()) {
			tags.add(new Var(
					VarType.PREDICTED, tagNames.size(), "tag_" + t, tagNames));
		}

		// Create unary factors on tag variables
		for (Var t : tags)
			fg.addFactor(getUnaryFactorForTagVar(t, featureAlphabet));

		int n = sentence.size();
		if (useLatentDeps) {
			// Create dep tree variables and global factor
			ProjDepTreeFactor depTree = new ProjDepTreeFactor(n, VarType.LATENT);
			fg.addFactor(depTree);

			// Create unary factors on link variables
			DepParseFeatureExtractorPrm fePrm = new DepParseFeatureExtractorPrm();
			CorpusStatistics corpusStats = new CorpusStatistics(
					new CorpusStatisticsPrm());
			DepParseFeatureExtractor fe = new DepParseFeatureExtractor(
					fePrm, sentence, corpusStats, featureAlphabet);
			for (int parent = -1; parent < n; parent++) {
				for (int child = 0; child < n; child++) {
					if (parent == child) continue;
					LinkVar l_ij = depTree.getLinkVar(parent, child);
					FeTypedFactor phi = new FeTypedFactor(new VarSet(l_ij),
							DepParseFactorTemplate.LINK_UNARY, fe);
					fg.addFactor(phi);

					// Create binary factors between link variables and
					// prediction variables.
					Var t = tags.get(child);
					ExplicitExpFamFactor binaryPhi = new ExplicitExpFamFactor(
							new VarSet(l_ij, t));
					int m = binaryPhi.getVars().calcNumConfigs();
					for (int i = 0; i < m; i++) {
						VarConfig c = binaryPhi.getVars().getVarConfig(i);
						FeatureVector fv = new FeatureVector();
						if (c.getState(l_ij) != LinkVar.TRUE) {
							String fn =
									"head=" + (parent < 0
										? "root"
										: sentence.getWord(parent))
									+ "_dep=" + c.getStateName(t);
							fv.add(featureAlphabet.lookupIndex(fn, true), 1d);
						}
						binaryPhi.setFeatures(i, fv);
					}
					fg.addFactor(binaryPhi);
				}
			}
		}

		// Train some weights via maximum likelihood
		VarConfig goldConfig = new VarConfig();
		for (int i = 0; i < n; i++) {
			String t = sentence.getPosTag(i);
			if (t.startsWith("N"))
				goldConfig.put(tags.get(i), "N");
			else if (t.startsWith("V"))
				goldConfig.put(tags.get(i), "V");
			else
				goldConfig.put(tags.get(i), "O");
		}
		FgExample fge = new LabeledFgExample(fg, goldConfig);
		FgModel model = trainModel(fge, featureAlphabet);
		for (Factor f : fg.getFactors())
			f.updateFromModel(model, true);

		// Do inference and see what the marginals are on the prediction vars
		BeliefPropagationPrm bpPrm = new BeliefPropagationPrm();
		BeliefPropagation bp = new BeliefPropagation(fg, bpPrm);
		bp.run();
		for (Var v : tags) {
			DenseFactor marg = bp.getMarginals(v);
			System.out.println(marg);
		}

		// Print out the weights for the link factors
		int d = featureAlphabet.size();
		double[] weights = new double[model.getNumParams()];
		model.updateDoublesFromModel(weights);
		for (int i = 0; i < d; i++) {
			Object feat = featureAlphabet.lookupObject(i);
			if (feat instanceof String && ((String) feat).indexOf("_contains_") == 1) {
			} else {
				System.out.println(
						feat.getClass().getName()
						+ "\t" + feat + "\t" + i + "\t" + weights[i]);
				//System.out.printf("%-120s %.3f\n", feat, weights[i]);
			}
		}
	}

	public static ExpFamFactor getUnaryFactorForTagVar(
			Var t,
			Alphabet<Object> featureNames) {
		// Features will be bag of characters (intentionally bad)
		String word = t.getName().substring(4);
		FeatureVector fvN = new FeatureVector();
		FeatureVector fvV = new FeatureVector();
		for (char c : word.toCharArray()) {
			fvN.add(featureNames.lookupIndex("N_contains_" + c, true), 1d);
			fvV.add(featureNames.lookupIndex("V_contains_" + c, true), 1d);
		}
		ExplicitExpFamFactor phi = new ExplicitExpFamFactor(new VarSet(t));
		assertEquals(phi.getValues().length, 3);
		phi.setFeatures(tagNames.indexOf("N"), fvN);
		phi.setFeatures(tagNames.indexOf("V"), fvV);
		phi.setFeatures(tagNames.indexOf("O"), new FeatureVector());
		return phi;
	}

	public static FgModel trainModel(
			FgExample fge,
			Alphabet<Object> featureAlphabet) {
		SGDPrm sgdParams = new SGDPrm();
		AdaGradPrm adagParams = new AdaGradPrm();
		sgdParams.sched = new AdaGrad(adagParams);
		CrfTrainerPrm trainerParams = new CrfTrainerPrm();
		trainerParams.batchMaximizer = new SGD(sgdParams);
		trainerParams.maximizer = null;
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		//FgModel model = new FgModel(featureAlphabet.size() + 1);
		FgModel model = new FgModel(999999);
		final FgExample fgeFinal = fge;
		return trainer.train(model, new FgExampleList() {
			@Override
			public Iterator<FgExample> iterator() {
				throw new RuntimeException();
			}
			@Override
			public FgExample get(int i) {
				return fgeFinal;
			}
			@Override
			public int size() { return 1; }
		});
	}
}
