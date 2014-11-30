package edu.jhu.hlt.fnparse.inference.stages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.BpScheduleType;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.gm.train.CrfTrainer.CrfTrainerPrm;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.FeatureCountFilter;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.HasSentence;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.ModelViewer;
import edu.jhu.hlt.fnparse.util.ModelViewer.FeatureWeight;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.optimize.AdaGrad;
import edu.jhu.hlt.optimize.AdaGrad.AdaGradPrm;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.hlt.optimize.SGD.SGDPrm;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;
import edu.jhu.prim.arrays.Multinomials;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

/**
 * Some helper code on top of Stage
 * 
 * @author travis
 *
 * @param <I> input to this stage
 * @param <O> output of this stage
 */
public abstract class AbstractStage<I, O extends FNTagging>
    implements Stage<I, O> {
	protected transient Logger log = Logger.getLogger(this.getClass());

	protected FgModel weights;
	protected String featureTemplatesString;
	private TemplatedFeatures featureTemplates;
	protected GlobalParameters globals;

	protected boolean useSyntaxFeatures = true;
	protected boolean useLatentDependencies = false;
	protected boolean useLatentConstituencies = false;

	protected boolean scanFeaturesHasBeenRun = false;

	protected int bpIters = 1;
  protected Double learningRate = null;
  protected int batchSize = 1;
  protected int passes = 10;
  protected Regularizer regularizer = new L2(1_000_000d);
  protected double propDev = 0.15d;
  protected int maxDev = 150;
  protected boolean tuneOnTrainingData = false;

  public AbstractStage(GlobalParameters globals, String featureTemplatesString) {
    this.globals = globals;
    this.featureTemplatesString = featureTemplatesString;
  }

  public GlobalParameters getGlobalParameters() {
    return globals;
  }

  public void setFeatures(String featureTemplatesString) {
    log.info("[setFeatures] " + featureTemplatesString);
    this.featureTemplates = null;
    this.featureTemplatesString = featureTemplatesString;
  }

  public TemplatedFeatures getFeatures() {
    if (featureTemplates == null) {
      featureTemplates = new TemplatedFeatures(
          getName(), featureTemplatesString, globals.getFeatureNames());
    }
    return featureTemplates;
  }

  public void showExtremeFeatures(int k) {
    if (globals == null || weights == null) {
      log.info("[showExtremeFeatures] can't, globals or weights are null");
      return;
    }
    List<FeatureWeight> weights =
        ModelViewer.getSortedWeights(this.weights, globals.getFeatureNames());
    int n = weights.size();
    log.info("[showExtremeFeatures] " + k + " smallest features:");
    for (int i = 0; i < k && i < n; i++)
      log.info("[showExtremeFeatures] " + weights.get(i));
    log.info("[showExtremeFeatures] " + k + " biggest features:");
    for (int i = 0; i < k && i < n; i++)
      log.info("[showExtremeFeatures] " + weights.get((n - k) + i));
  }

  @Override
  public void saveModel(DataOutputStream dos, GlobalParameters globals) {
    log.info("starting save model");
    try {
      dos.writeUTF(featureTemplatesString);
      dos.writeBoolean(useSyntaxFeatures);
      dos.writeBoolean(useLatentDependencies);
      dos.writeBoolean(useLatentConstituencies);
      dos.writeBoolean(scanFeaturesHasBeenRun);
      dos.writeInt(bpIters);
      dos.writeDouble(learningRate == null ? 0d : learningRate);
      dos.writeInt(batchSize);
      dos.writeInt(passes);
      // TODO Regularizer
      dos.writeDouble(propDev);
      dos.writeInt(maxDev);
      dos.writeBoolean(tuneOnTrainingData);
      ModelIO.writeBinaryWithStringFeatureNames(
          weights, globals.getFeatureNames(), dos);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    log.info("done save model");
  }

  @Override
  public void loadModel(DataInputStream dis, GlobalParameters globals) {
    log.info("starting load model");
    this.globals = globals;
    try {
      setFeatures(dis.readUTF());
      useSyntaxFeatures = dis.readBoolean();
      useLatentDependencies = dis.readBoolean();
      useLatentConstituencies = dis.readBoolean();
      scanFeaturesHasBeenRun = dis.readBoolean();
      bpIters = dis.readInt();
      learningRate = dis.readDouble();
      if (learningRate == 0) learningRate = null;
      batchSize = dis.readInt();
      passes = dis.readInt();
      // TODO Regularizer
      propDev = dis.readDouble();
      maxDev = dis.readInt();
      tuneOnTrainingData = dis.readBoolean();
      this.weights = ModelIO.readBinaryWithStringFeatureNames(
          globals.getFeatureNames(), dis);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    log.info("done load model");
  }

  public void configure(String key, String value) {
    Map<String, String> m = new HashMap<>();
    m.put(key, value);
    configure(m);
  }

  @Override
  public void configure(Map<String, String> configuration) {
    String key, value;

    key = "regularizer." + getName();
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      regularizer = new L2(Double.parseDouble(value));
    }

    key = "batchSize." + getName();
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      batchSize = Integer.parseInt(value);
    }

    key = "passes";
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      this.passes = Integer.parseInt(value);
    }

    key = "passes." + getName();
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      this.passes = Integer.parseInt(value);
    }

    key = "tuneOnTrainingData." + getName();
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      this.tuneOnTrainingData = Boolean.valueOf(value);
    }

    key = "regularizer." + getName();
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      this.regularizer = new L2(Double.parseDouble(value));
    }

    key = "useSyntaxFeatures";
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      useSyntaxFeatures = Boolean.valueOf(value);
    }

    key = "useLatentDependencies";
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      useLatentDependencies = Boolean.valueOf(value);
    }

    key = "useLatentConstituencies";
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      useLatentConstituencies = Boolean.valueOf(value);
    }

    // affects all stages that use bpIters
    key = "bpIters";
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      bpIters = Integer.parseInt(value);
    }

    // overrides global setting (by natur of comming second)
    key = "bpIters." + getName();
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      bpIters = Integer.parseInt(value);
    }

    key = "syntaxMode";
    value = configuration.get(key);
    if (value != null) {
      log.info("[configure] set " + key + " = " + value);
      setSyntaxMode(value);
    }
  }

  public void setSyntaxMode(String syntaxMode) {
    Map<String, String> config = new HashMap<>();
    if ("regular".equalsIgnoreCase(syntaxMode)) {
      config.put("useSyntaxFeatures", "true");
      config.put("useLatentDependencies", "false");
      config.put("useLatentConstituencies", "false");
    } else if ("latent".equalsIgnoreCase(syntaxMode)) {
      config.put("useSyntaxFeatures", "false");
      config.put("useLatentDependencies", "true");
      config.put("useLatentConstituencies", "true");
    } else if ("none".equalsIgnoreCase(syntaxMode)) {
      config.put("useSyntaxFeatures", "false");
      config.put("useLatentDependencies", "false");
      config.put("useLatentConstituencies", "false");
    } else {
      throw new RuntimeException("unknown mode: " + syntaxMode);
    }
    configure(config);
  }

	public void setGlobals(GlobalParameters globals) {
	  this.globals = globals;
	}

	private int getExampleCtr = 0, getExampleCtrInterval = 500;
	protected void observeGetExample(String msg) {
	  if (getExampleCtr % getExampleCtrInterval == 0) {
	    log.info("[getExample] (" + msg + ")"
	        + " ctr=" + getExampleCtr
	        + " useLatentConstituencies=" + useLatentConstituencies
	        + " useLatentDependencies=" + useLatentDependencies
	        + " useSyntaxFeatures=" + useSyntaxFeatures);
	  }
	  getExampleCtr++;
	}

	private int getDecodableCtr = 0, getDecodableCtrInterval = 500;
	protected void observeGetDecodable(String msg) {
	  if (getDecodableCtr % getDecodableCtrInterval == 0) {
	    log.info("[getDecodable] (" + msg + ")"
	        + " ctr=" + getDecodableCtr
	        + " useLatentConstituencies=" + useLatentConstituencies
	        + " useLatentDependencies=" + useLatentDependencies
	        + " useSyntaxFeatures=" + useSyntaxFeatures);
	  }
	  getDecodableCtr++;
	}

  /** checks if they're log proportions from this.logDomain */
  public void normalize(double[] proportions) {
    if (logDomain())
      Multinomials.normalizeLogProps(proportions);
    else
      Multinomials.normalizeProps(proportions);
  }

	public String getName() {
		String[] ar = this.getClass().getName().split("\\.");
		return ar[ar.length-1];
	}

	@Override
	public FgModel getWeights() {
		if (weights == null) {
			throw new IllegalStateException(
					"you never initialized the weights");
		}
		return weights;
	}

	@Override
	public void setWeights(FgModel weights) {
		if (weights == null)
			throw new IllegalArgumentException();
		if (this.weights == null)
			this.weights = weights;
		else
			this.weights.setParams(weights.getParams());
	}

	@Override
	public boolean logDomain() {
		return true;  //globalParams.logDomain;
	}

	public FgInferencerFactory infFactory() {
		final BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = false;
		bpParams.schedule = BpScheduleType.TREE_LIKE;
		bpParams.logDomain = logDomain();
		bpParams.cacheFactorBeliefs = false;
		bpParams.maxIterations = bpIters;
		return new FgInferencerFactory() {
			@Override
			public boolean isLogDomain() { return bpParams.isLogDomain(); }
			@Override
			public FgInferencer getInferencer(FactorGraph fg) {
				return new BeliefPropagation(fg, bpParams);
			}
		};
	}

	/**
	 * A convenience method for calling decode on the input, which runs inference
	 * if it hasn't been run yet and then takes the output of that inference and
	 * decodes an answer.
	 */
	public List<O> decode(StageDatumExampleList<I, O> decodables) {
		List<O> decoded = new ArrayList<>();
		int n = decodables.size();
		for(int i=0; i<n; i++) {
			decoded.add(decodables
					.getStageDatum(i)
					.getDecodable()
					.decode());
		}
		return decoded;
	}

	public List<O> predict(List<I> input) {
		return decode(setupInference(input, null));
	}

	public void initWeights() {
		int numParams = globals.getFeatureNames().size();
		if(numParams == 0) {
		  log.warn("[initWeights] no parameters!");
		  assert scanFeaturesHasBeenRun;
		}
		//assert globalParams.verifyConsistency();
		if (weights != null && weights.getNumParams() > 0)
			log.warn("re-initializing paramters!");
		weights = new FgModel(numParams);
	}

	/** initializes to a 0 mean Gaussian with diagnonal variance (provided) */
	public void randomlyInitWeights(final double variance, final Random r) {
		log.info("randomly initializing weights with a variance of " + variance);
		initWeights();
		weights.apply(new FnIntDoubleToDouble() {
			@Override
			public double call(int idx, double val) {
				return r.nextGaussian() * variance;
			}
		});
	}

	/** null means auto-select */
	public Double getLearningRate() {
		return this.learningRate;
	}

	public Regularizer getRegularizer() {
		return this.regularizer;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public int getNumTrainingPasses() {
		return passes;
	}

	/**
	 * You MUST call this before doing your setupInference work.
	 */
	protected void setupInferenceHook(
	    List<? extends I> input,
	    List<? extends O> output) {
	  assert input != null;
	  assert output == null || output.size() == input.size();
	  setupSyntax(input);
	}

	// every time i call setupInference, you have to call
	// super.setupInferenceHook
	// which will do the appropriate hiding of syntax, parsing, or making it visible
	private void setupSyntax(Collection<? extends I> input) {
		if (useSyntaxFeatures) {
		  log.info("[setupSyntax] useSyntaxFeatures=true checking if anything needs to be parsed");
		  ConcreteStanfordWrapper parser = ConcreteStanfordWrapper.getSingleton(true);
		  for (I in : input) {
		    Sentence s = getSentence(in);
		    if (s.getStanfordParse(false) == null || s.getBasicDeps(false) == null) {
		      ConstituencyParse cp = new ConstituencyParse(parser.parse(s, true));
		      s.setStanfordParse(cp);
		      assert s.getBasicDeps(false) != null;
		    }
		    s.hideSyntax(false);
		  }
		} else {
		  log.info("[setupSyntax] useSyntaxFeatures=false hiding syntax");
		  for (I in : input)
		    getSentence(in).hideSyntax();
		}
	}

	private static Sentence getSentence(Object in) {
	  if (in instanceof Sentence)
	    return (Sentence) in;
	  else if (in instanceof HasSentence)
	    return ((HasSentence) in).getSentence();
	  else
	    throw new RuntimeException("input type: " + in.getClass());
	}

	@Override
	public void train(List<I> x, List<O> y) {
		train(x, y, getLearningRate(),
				getRegularizer(), getBatchSize(), getNumTrainingPasses());
	}

	/**
	 * @param x
	 * @param y
	 * @param learningRate if null pacaya will try to auto-select a learning rate
	 * @param regularizer
	 * @param batchSize
	 * @param passes is how many passes to make over the entire dataset
	 */
	public void train(List<I> x, List<O> y, Double learningRate,
			Regularizer regularizer, int batchSize, int passes) {
		//assert globalParams.verifyConsistency();
		if (x.size() != y.size())
			throw new IllegalArgumentException("x.size=" + x.size() + ", y.size=" + y.size());
		log.info("[train] starting training");
		long start = System.currentTimeMillis();

		//initWeights();
		randomlyInitWeights(0.1d, new Random(9001));

		List<I> xTrain, xDev;
		List<O> yTrain, yDev;
		TuningData td = this.getTuningData();
		if (td == null) {
		  log.info("[train] performing no dev tuning");
			xTrain = x;
			yTrain = y;
			xDev = Collections.emptyList();
			yDev = Collections.emptyList();
		} else {
			if (td.tuneOnTrainingData()) {
			  log.info("[train] tuning on train data");
				xDev = xTrain = x;
				yDev = yTrain = y;
			} else {
			  log.info("[train] tuning on held-out data");
				xTrain = new ArrayList<>();
				yTrain = new ArrayList<>();
				xDev = new ArrayList<>();
				yDev = new ArrayList<>();
				devTuneSplit(x, y, xTrain, yTrain, xDev, yDev,
						0.15d, maxDev, globals.getRandom());
			}
		}
		log.info("[train] #train=" + xTrain.size() + " #tune=" + xDev.size());

		CrfTrainerPrm trainerParams = new CrfTrainerPrm();
		SGDPrm sgdParams = new SGDPrm();
		AdaGradPrm adagParams = new AdaGradPrm();
		if (learningRate == null) {
		  log.info("[train] automatically selecting learning rate");
			sgdParams.autoSelectLr = true;
		} else {
		  log.info("[train] learningRate=" + learningRate);
			sgdParams.autoSelectLr = false;
			adagParams.eta = learningRate;
		}
		sgdParams.batchSize = batchSize;
		sgdParams.numPasses = passes;
		sgdParams.sched = new AdaGrad(adagParams);
		log.info("[train] passes=" + passes + " batchSize=" + batchSize);
		log.info("[train] regularizer=" + regularizer);

		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new SGD(sgdParams);
		trainerParams.infFactory = infFactory();
		trainerParams.numThreads = 1; //globalParams.threads;
		trainerParams.regularizer = regularizer;
		log.info("[train] numThreads=" + trainerParams.numThreads);

		Alphabet<String> alph = globals.getFeatureNames();
		log.info("[train] Feature alphabet is frozen (size=" + alph.size() + "), "
				+ "going straight into training");
		alph.stopGrowth();

		// Get the data
		StageDatumExampleList<I, O> exs = this.setupInference(x, y);

		// Setup model and train
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		try {
			weights = trainer.train(weights, exs);
		} catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		long timeTrain = System.currentTimeMillis() - start;
		log.info(String.format(
				"[train] Done training on %d examples for %.1f minutes, using %d features",
				exs.size(), timeTrain/(1000d*60d), alph.size()));

		showExtremeFeatures(20);

		// Tune
		if(td != null)
			tuneRecallBias(xDev, yDev, td);

		log.info("[train] done training");
	}

	/**
	 * forces the factor graphs to be created and the features to be computed,
	 * which has the side effect of populating the feature alphabet in params.
	 * @param labels may be null
	 */
	@Override
	public void scanFeatures(
			List<? extends I> unlabeledExamples,
			List<? extends O> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		if (labels != null && unlabeledExamples.size() != labels.size())
			throw new IllegalArgumentException();
		if (!globals.getFeatureNames().isGrowing()) {
			throw new IllegalStateException("There is no reason to run this "
					+ "unless you've set the alphabet to be growing");
		}

		Timer t = new Timer(this.getName() + "@scan-features", 500, false);
		log.info("[scanFeatures] Counting the number of parameters needed over "
				+ unlabeledExamples.size() + " examples");

		// This stores counts in an array.
		// It gets the indices from the feature vectors, w/o knowing which
		// alphabet they came from.
		FeatureCountFilter fcount = new FeatureCountFilter();

		// Keep track of what parses we added so we can get a sense of our
		// frame/role coverage.
		List<FNTagging> seen = new ArrayList<>();

		final int alphSizeStart = globals.getFeatureNames().size();
		int examplesSeen = 0;
		int examplesWithNoFactorGraph = 0;
		StageDatumExampleList<I, O> data = this.setupInference(
				unlabeledExamples, null);
		int n = data.size();
		for (int i = 0; i < n; i++) {
			t.start();
			StageDatum<I, O> d = data.getStageDatum(i);
			IDecodable<O> dec = d.getDecodable();
			if (dec instanceof Decodable)
				fcount.observe(((Decodable<O>) dec).getFactorGraph());
			else
				examplesWithNoFactorGraph++;
			examplesSeen++;
			t.stop();

			if (labels != null)
				seen.add(labels.get(i));

			if (t.totalTimeInSeconds() / 60d > maxTimeInMinutes) {
				log.info("[scanFeatures] Stopping because we used the max time "
						+ "(in minutes): " + maxTimeInMinutes);
				break;
			}
			int featuresAdded = globals.getFeatureNames().size()
					- alphSizeStart;
			if (featuresAdded > maxFeaturesAdded) {
				log.info("[scanFeatures] Stopping because we added the max "
						+ "allowed features: " + featuresAdded);
				break;
			}
		}

		if (examplesWithNoFactorGraph > 0) {
			log.warn("[scanFeatures] Some examples didn't have any FactorGraph "
					+ "associated with them: " + examplesWithNoFactorGraph);
		}

		if (seen.size() == 0) {
			log.info("[scanFeatures] Labels were provided, so we can't compute "
					+ "frame/role recall");
		} else {
			Set<Frame> fSeen = new HashSet<>();
			Set<String> rSeen = new HashSet<>();
			Set<String> frSeen = new HashSet<>();
			for(FNTagging tag : seen) {
				for(FrameInstance fi : tag.getFrameInstances()) {
					Frame f = fi.getFrame();
					fSeen.add(f);
					int K = f.numRoles();
					for(int k=0; k<K; k++) {
						Span a = fi.getArgument(k);
						if(a == Span.nullSpan) continue;
						String r = f.getRole(k);
						String fr = f.getName() + "." + r;
						rSeen.add(r);
						frSeen.add(fr);
					}
				}
			}
			log.info(String.format("[scanFeatures] Saw %d frames, "
					+ "%d frame-roles, and %d roles (ignoring frame)",
					fSeen.size(), frSeen.size(), rSeen.size()));
		}

		log.info(String.format("[scanFeatures] Done, scanned %d examples in "
				+ "%.1f minutes, alphabet size is %d, added %d",
				examplesSeen, t.totalTimeInSeconds() / 60d,
				globals.getFeatureNames().size(),
				globals.getFeatureNames().size() - alphSizeStart));
		scanFeaturesHasBeenRun = true;
	}

	public static interface TuningData {

		public ApproxF1MbrDecoder getDecoder();

		/** Function to be maximized */
		public EvalFunc getObjective();

		public List<Double> getRecallBiasesToSweep();

		/** Return true if it is not necessary to split train and dev data */
		public boolean tuneOnTrainingData();
	}

	/**
	 * this is for specifying *how* to tune an {@link ApproxF1MbrDecoder}.
	 * if you don't have one to tune, then return null (the default implementation).
	 */
	public TuningData getTuningData() {
		return null;
	}

	public void tuneRecallBias(List<I> x, List<O> y, TuningData td) {
		if (x == null || y == null || x.size() != y.size())
			throw new IllegalArgumentException();
		if (td == null)
			throw new IllegalArgumentException();

		if (x.size() == 0) {
			log.warn("[tuneRecallBias] 0 examples were provided for tuning, skipping this");
			return;
		}

		log.info(String.format("[tuneRecallBias] Tuning to maximize %s on "
				+ "%d examples over biases in %s",
				td.getObjective().getName(), x.size(),
				td.getRecallBiasesToSweep()));

		// Run inference and store the margins
		long t = System.currentTimeMillis();

		List<Decodable<O>> decodables = new ArrayList<>();
		for (StageDatum<I, O> sd : this.setupInference(x, null).getStageData()) {
			Decodable<O> d = (Decodable<O>) sd.getDecodable();
			d.force();
			decodables.add(d);
		}
		long tInf = System.currentTimeMillis() - t;

		// Decode many times and store performance
		t = System.currentTimeMillis();
		double originalBias = td.getDecoder().getRecallBias();
		double bestScore = Double.NEGATIVE_INFINITY;
		List<Double> scores = new ArrayList<Double>();
		for (double b : td.getRecallBiasesToSweep()) {
			td.getDecoder().setRecallBias(b);
			List<O> predicted = new ArrayList<>();
			for (Decodable<O> m : decodables)
				predicted.add(m.decode());
			List<SentenceEval> instances = BasicEvaluation.zip(y, predicted);
			double score = td.getObjective().evaluate(instances);
			log.info(String.format("[tuneRecallBias] recallBias=%.2f %s=%.3f",
					b, td.getObjective().getName(), score));
			scores.add(score);
			if (score > bestScore) bestScore = score;
		}
		long tDec = System.currentTimeMillis() - t;

		List<Double> regrets = new ArrayList<Double>();
		for(double s : scores)
			regrets.add(bestScore - s);

		List<Double> weights = new ArrayList<Double>();
		for(double r : regrets) weights.add(Math.exp(-r * 200d));

		double n = 0d, z = 0d;
		for (int i=0; i<td.getRecallBiasesToSweep().size(); i++) {
			double b = td.getRecallBiasesToSweep().get(i);
			double w = weights.get(i);
			n += w * b;
			z += w;
		}
		double bestBias = n / z;
		log.info(String.format("[tuneRecallBias] Took %.1f sec for inference and"
				+ " %.1f sec for decoding, done. recallBias %.2f => %.2f @ %.3f",
				tInf/1000d, tDec/1000d, originalBias, bestBias, bestScore));
		td.getDecoder().setRecallBias(bestBias);
	}

	public static <A, B> void devTuneSplit(
			List<? extends A> x, List<? extends B> y,
			List<A> xTrain,      List<B> yTrain,
			List<A> xDev,        List<B> yDev,
			double propDev, int maxDev, Random r) {

		if (x.size() != y.size()) {
			throw new IllegalArgumentException(
					"x.size=" + x.size() + ", y.size=" + y.size());
		}
		if (xTrain.size() + yTrain.size() + xDev.size() + yDev.size() > 0)
			throw new IllegalArgumentException();
		if (x.size() == 0)
			return;

		final int n = x.size();
		for (int i=0; i<n; i++) {
			boolean train = r.nextDouble() > propDev;
			if (train) {
				xTrain.add(x.get(i));
				yTrain.add(y.get(i));
			} else {
				xDev.add(x.get(i));
				yDev.add(y.get(i));
			}
		}

		while (xDev.size() > maxDev) {
			xTrain.add(xDev.remove(xDev.size()-1));
			yTrain.add(yDev.remove(yDev.size()-1));
		}
	}
}
