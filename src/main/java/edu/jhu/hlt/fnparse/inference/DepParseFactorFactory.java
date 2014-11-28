package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.data.simple.SimpleAnnoSentence;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.srl.CorpusStatistics;
import edu.jhu.srl.CorpusStatistics.CorpusStatisticsPrm;
import edu.jhu.srl.DepParseFactorGraphBuilder.DepParseFactorTemplate;
import edu.jhu.srl.DepParseFeatureExtractor;
import edu.jhu.srl.DepParseFeatureExtractor.DepParseFeatureExtractorPrm;
import edu.jhu.srl.FeTypedFactor;
import edu.jhu.util.Alphabet;

/**
 * This factory adds:
 * 1) Unary factors on link variables
 * 2) The global factor for the tree
 * 
 * This factory does NOT add:
 * 1) Binary factors between f_it and l_ij (see FrameFactorFactory)
 * 
 * Implements FactorFactory<Object> because it doesn't keep any hypotheses about
 * the sentence other than the tree itself, which is passed in through the
 * initFactorsFor() method.
 * 
 * @author travis
 */
public class DepParseFactorFactory implements FactorFactory<Object> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG =
      Logger.getLogger(DepParseFactorFactory.class);

  private DepParseFeatureExtractorPrm fePrm;
  private GlobalParameters globals;

  public DepParseFactorFactory(GlobalParameters globals) {
    this.globals = globals;
    fePrm = new DepParseFeatureExtractorPrm();
    // defaults for fePrm are fine
  }

  public static SimpleAnnoSentence toPacayaSentence(Sentence s) {
    SimpleAnnoSentence sas = new SimpleAnnoSentence();
    sas.setWords(Arrays.asList(s.getWords()));
    sas.setPosTags(Arrays.asList(s.getPos()));
    sas.setLemmas(Arrays.asList(s.getLemmas()));

    // For some reason one of the feature templates is asking for
    // morphological features I'm going to create a bunch of empty feature
    // lists because I have no way to generate these
    ArrayList<List<String>> feats = new ArrayList<>();
    for(int i=0; i<s.size(); i++)
      feats.add(Collections.<String>emptyList());
    sas.setFeats(feats);

    // Shouldn't need dep parse info because we're making our own
    return sas;
  }

  private static CorpusStatistics corpusStats;
  public static CorpusStatistics getCorpusStats() {
    if (corpusStats == null) {
      corpusStats = new CorpusStatistics(new CorpusStatisticsPrm());
      List<SimpleAnnoSentence> corpus = new ArrayList<>();
      Iterator<? extends FNTagging> iter = null;
      iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
      while (iter.hasNext())
        corpus.add(toPacayaSentence(iter.next().getSentence()));
      iter = FileFrameInstanceProvider.fn15trainFIP.getParsedOrTaggedSentences();
      while (iter.hasNext())
        corpus.add(toPacayaSentence(iter.next().getSentence()));
      corpusStats.init(corpus);
    }
    return corpusStats;
  }

  @Override
  public List<Features> getFeatures() {
    return Collections.emptyList();
  }

  @Override
  public List<Factor> initFactorsFor(
      Sentence s,
      List<Object> inThisSentence,
      ProjDepTreeFactor d,
      ConstituencyTreeFactor c) {
    List<Factor> factors = new ArrayList<Factor>();

    // Global/tree factor
    factors.add(d);

    // Unary factors on edge variables
    @SuppressWarnings("unchecked")
    Alphabet<Object> alph = (Alphabet<Object>) (Object) globals.getFeatureNames();
    DepParseFeatureExtractor fe = new DepParseFeatureExtractor(fePrm,
        toPacayaSentence(s), getCorpusStats(), alph);
    final int n = s.size();
    for (int i = -1; i < n; i++) {  // Head
      for (int j = 0; j < n; j++) { // Dependent
        if (i == j)
          continue;
        Var ijVar = d.getLinkVar(i, j);
        if (ijVar == null)
          continue;
        FeTypedFactor phi = new FeTypedFactor(new VarSet(ijVar),
            DepParseFactorTemplate.LINK_UNARY, fe);
        factors.add(phi);
      }
    }
    return factors;
  }

  public static void main(String[] args) {
    CorpusStatistics cs = getCorpusStats();
    LOG.info("lang: " + cs.getLanguage());
    LOG.info("done");
  }
}
