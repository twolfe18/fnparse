package edu.jhu.hlt.fnparse.inference.dep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.data.simple.SimpleAnnoSentence;
import edu.jhu.gm.feat.FeatureExtractor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.srl.CorpusStatistics;
import edu.jhu.srl.CorpusStatistics.CorpusStatisticsPrm;
import edu.jhu.srl.DepParseFactorGraph.DepParseFactorTemplate;
import edu.jhu.srl.DepParseFeatureExtractor;
import edu.jhu.srl.DepParseFeatureExtractor.DepParseFeatureExtractorPrm;
import edu.jhu.srl.FeTypedFactor;
import edu.jhu.util.Alphabet;

/**
 * adds
 * 1) unary factors on link variables
 * 2) the global factor for the tree
 * 
 * implements FactorFactory<Object> because it doesn't keep any hypotheses about the sentence
 * other than the tree itself, which is passed in through the initFactorsFor() method.
 * @author travis
 */
public class DepParseFactorFactory implements FactorFactory<Object> {

	private static final long serialVersionUID = 1L;
	
	private DepParseFeatureExtractorPrm fePrm;
	private CorpusStatistics corpusStats;
	private ParserParams params;
	
	public DepParseFactorFactory(ParserParams params) {
		this.params = params;
		fePrm = new DepParseFeatureExtractorPrm();
		// defaults for fePrm are fine
		
		corpusStats = new CorpusStatistics(new CorpusStatisticsPrm());
		// TODO: corpusStats.init(simpleAnnoSentenceIterable);
	}
	
	private static SimpleAnnoSentence toPacayaSentence(Sentence s) {
		SimpleAnnoSentence sas = new SimpleAnnoSentence();
		sas.setWords(Arrays.asList(s.getWords()));
		sas.setPosTags(Arrays.asList(s.getPos()));
		sas.setLemmas(Arrays.asList(s.getLemmas()));
		
		// for some reason one of the feature templates is asking for morphological features
		// i'm going to create a bunch of empty feature lists because I have no way to generate these
		ArrayList<List<String>> feats = new ArrayList<List<String>>();
		for(int i=0; i<s.size(); i++)
			feats.add(Collections.<String>emptyList());
		sas.setFeats(feats);
		
		// shouldn't need dep parse info because we're making our own
		return sas;
	}

	@Override
	public List<Features> getFeatures() {
		return Collections.emptyList();
	}

	@Override
	public List<Factor> initFactorsFor(Sentence s, List<Object> inThisSentence, ProjDepTreeFactor l) {

		List<Factor> factors = new ArrayList<Factor>();
		
		if(s.size() < 4) {	// 3 words + 1 punc is about as short as you might ever see as a legit sentence
			System.err.println("[DepParseFactorFactory] really short sentence (skipping): " + s);
			return factors;
		}

		factors.add(l);	// global factor
		
		// unary factors on edge variables
		@SuppressWarnings("unchecked")
		Alphabet<Object> alph = (Alphabet<Object>) (Object) params.featIdx;
		FeatureExtractor fe = new DepParseFeatureExtractor(fePrm, toPacayaSentence(s), corpusStats, alph);
		final int n = s.size();
		for(int i=-1; i<n; i++) {
			for(int j=0; j<n; j++) {
				if(i == j) continue;
				Var ijVar = l.getLinkVar(i, j);
				if(ijVar == null) continue;

				factors.add(new FeTypedFactor(new VarSet(ijVar), DepParseFactorTemplate.LINK_UNARY, fe));
				// TODO grandparent and sibling links
			}
		}
		
		return factors;
	}

}