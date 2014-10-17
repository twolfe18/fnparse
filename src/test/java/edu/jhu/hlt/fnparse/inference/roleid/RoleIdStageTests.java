package edu.jhu.hlt.fnparse.inference.roleid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.pruning.NoArgPruner;
import edu.jhu.hlt.fnparse.inference.role.head.RoleIdStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage.Decodable;
import edu.jhu.hlt.fnparse.inference.stages.Stage.StageDatum;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.Timer;

public class RoleIdStageTests {

	List<FNTagging> input;
	List<FNParse> output;

	private static List<FNParse> getExamples() {
		List<FNParse> l = new ArrayList<>();
		int i = 0;
		Iterator<FNParse> iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
		while(iter.hasNext() && i < 10) {
			FNParse t = iter.next();
			if(t.numFrameInstances() > 0) {
				l.add(t);
				i++;
			}
		}
		return l;
	}

	@Before
	public void setupData() {
		output = getExamples();
		input = DataUtil.convertParsesToTaggings(output);
		assertEquals(output.size(), input.size());
	}

	static interface ChecksSomething {
		public void check(FNTagging input, FNParse output, Decodable<FNParse> decodableRegular, Decodable<FNParse> decodableLatent);
	}

	private Map<String, ChecksSomething> tests;

	@Before
	public void addTests() {

		tests = new HashMap<>();

		tests.put("count-num-vars-and-factors", new ChecksSomething() {
			@Override
			public void check(FNTagging input, FNParse output,
					Decodable<FNParse> decodableRegular,
					Decodable<FNParse> decodableLatent) {

				FactorGraph fgL = decodableLatent.getFactorGraph();
				FactorGraph fgR = decodableRegular.getFactorGraph();
				System.out.printf("#f %d %d\n", fgR.getNumFactors(), fgL.getNumFactors());
				System.out.printf("#v %d %d\n", fgR.getNumVars(), fgL.getNumVars());

				assertTrue(fgL.getNumFactors() > fgR.getNumFactors());
				assertTrue(fgR.getNumFactors() > 0);

				assertTrue(fgL.getNumVars() > fgR.getNumVars());
				assertTrue(fgR.getNumVars() > 0);

				assertTrue(fgL.getNumFactors() >= fgL.getNumVars());
				assertTrue(fgR.getNumFactors() >= fgR.getNumVars());

				int numRoles = 0;
				int n = input.getSentence().size();
				for(FrameInstance fi : input.getFrameInstances())
					numRoles += (n+1) * fi.getFrame().numRoles();
				//assertEquals(numRoles, fgR.getNumVars());	// unpruned
				assertTrue(fgR.getNumVars() <= numRoles);	// pruned
			}
		});

		tests.put("probs-are-actually-computed", new ChecksSomething() {
			@Override
			public void check(FNTagging input, FNParse output,
					Decodable<FNParse> decR, Decodable<FNParse> decL) {
				FactorGraph fgR = decR.getFactorGraph();
				FactorGraph fgL = decL.getFactorGraph();
				double eps = 1e-5;
				for(Var v : fgR.getVars()) {
					DenseFactor vm = decR.getMargins().getMarginals(v);
					assertNonzero(vm, eps);
				}
				for(Var v : fgL.getVars()) {
					DenseFactor vm = decL.getMargins().getMarginals(v);
					assertNonzero(vm, eps);
				}
			}
			void assertNonzero(DenseFactor df, double eps) {
				double[] values = df.getValues();
				for(double v : values)
					if(Math.abs(v) > eps)
						return;
				assertTrue("values = " + Arrays.toString(values), false);
			}
		});

		tests.put("latent-has-diff-probs", new ChecksSomething() {
			@Override
			public void check(FNTagging input, FNParse output,
					Decodable<FNParse> decR, Decodable<FNParse> decL) {
				FactorGraph fgR = decR.getFactorGraph();
				FactorGraph fgL = decL.getFactorGraph();
				Map<String, Double> regularProbs =
						new HashMap<String, Double>();	// key=name
				for(Var v : fgR.getVars()) {
					DenseFactor vm = decR.getMargins().getMarginals(v);
					double p = vm.getValue(BinaryVarUtil.boolToConfig(true));
					regularProbs.put(v.getName(), p);
				}
				for(Var v : fgL.getVars()) {
					DenseFactor vm = decL.getMargins().getMarginals(v);
					double pL = vm.getValue(BinaryVarUtil.boolToConfig(true));
					Double pR = regularProbs.get(v.getName());
					if(pR == null) {
						assertEquals(v.getType(), VarType.LATENT);
						continue;
					}
					assertEquals(2, v.getNumStates());
					System.out.printf("%s %.5f %.5f\n", v.getName(), pR, pL);
					double eps = 1e-4;
					if(Math.abs(pR - pL) < eps) {
						if(decR.getWeights().l2Norm()
								+ decL.getWeights().l2Norm() < eps) {
							// NBD, just trivially the same because all the
							// weights are 0, i.e. there are no factors
						} else {
							// Why would these probabilities be the same?
							List<Factor> touchingL = touching(v, fgL);
							List<Factor> touchingR = touching(v, fgR);
							if(touchingL.size() != 1 || touchingR.size() != 1) {
								System.out.println("touching in R : " + touchingR);
								System.out.println("touching L:");
								for(Factor f : touchingL) {
									System.out.println("#####################");
									System.out.println(f);
									int i = 0;
									for(Var vl : f.getVars()) {
										if(!v.getName().equals(vl.getName()))
											System.out.println((++i) + ": " + decL.getMargins().getMarginals(vl));
									}
								}

								// Lets try to see if the effect of this factor
								// *should* be significant.
								assertEquals(touchingL.size(), 2);
								Factor binaryPhi = touchingL.get(0).getVars().size() == 1
										? touchingL.get(1) : touchingL.get(0);
								assertEquals(binaryPhi.getVars().size(), 2);
								// l_ij
								Var l_ij, r_itjk;
								if (binaryPhi.getVars().get(0) instanceof LinkVar) {
									l_ij = binaryPhi.getVars().get(0);
									r_itjk = binaryPhi.getVars().get(1);
								} else {
									l_ij = binaryPhi.getVars().get(1);
									r_itjk = binaryPhi.getVars().get(0);
								}
								// the unary factor will be present in R
								// just compute the message from the binary phi
								double[] mu = new double[2];
								DenseFactor b_l_ij = decL.getMargins().getMarginals(l_ij);
								b_l_ij.convertLogToReal();
								b_l_ij.normalize();
								for (int c = 0; c < binaryPhi.getVars().calcNumConfigs(); c++) {
									VarConfig conf = binaryPhi.getVars().getVarConfig(c);
									mu[conf.getState(r_itjk)] +=
											binaryPhi.getUnormalizedScore(c)
											* b_l_ij.getValue(conf.getState(l_ij));
								}
								double delta = 0d;
								for (double m : mu) delta += Math.abs(m);
								assertTrue(delta < 1e-2);
							}
						}
					}
				}
			}

			/**
			 * Uses var names, so will work even if you give a var in another
			 * factor graph (but has same name/naming convention
			 */
			public List<Factor> touching(Var v, FactorGraph fg) {
				List<Factor> fs = new ArrayList<>();
				for(Factor f : fg.getFactors()) {
					for(Var vv : f.getVars()) {
						if(v.getName().equals(vv.getName())) {
							fs.add(f);
							break;
						}
					}
				}
				return fs;
			}
		});
	}
	
	public void configureRid(RoleIdStage rid) {
		rid.params.argPruner = new NoArgPruner();
	}
	
	@Test
	public void runTestsPruned() {
		runTests(true);
	}

	@Test
	public void runTestsUnPruned() {
		runTests(false);
	}

	public void runTests(boolean prune) {

		System.out.println("[RoleIdStageTest checkPartition] starting test.");
		File alphFile = new File("saved-models/testing/latent.alph.gz");
		assertTrue(alphFile.isFile());

		// Latent
		ParserParams paramsL = new ParserParams();
		paramsL.useLatentDepenencies = true;
		RoleIdStage ridL = new RoleIdStage(paramsL, paramsL);
		paramsL.readFeatAlphFrom(alphFile);
		if(prune) configureRid(ridL);
		StageDatumExampleList<FNTagging, FNParse> dataL =
				ridL.setupInference(input, output);
		assertEquals(output.size(), dataL.size());

		// Regular
		ParserParams paramsR = new ParserParams();
		paramsR.useLatentDepenencies = false;
		RoleIdStage ridR = new RoleIdStage(paramsR, paramsR);
		//ridR.globalParams.readFeatAlphFrom(alphFile);
		paramsR.setFeatureAlphabet(paramsL.getAlphabet());
		if(prune) configureRid(ridR);
		StageDatumExampleList<FNTagging, FNParse> dataR =
				ridR.setupInference(input, output);
		assertEquals(output.size(), dataR.size());

		assertEquals(paramsL.logDomain, paramsR.logDomain);
		assertFalse(paramsL.getAlphabet().isGrowing());
		assertTrue(paramsL.getAlphabet()
				== paramsR.getAlphabet());

		ridL.initWeights();
		ridR.initWeights();

		Timer tL = new Timer("getDecodable-latent");
		Timer tR = new Timer("getDecodable-regular");
		tL.printIterval = 1;
		tR.printIterval = 1;

		int origAlphSize = paramsL.getAlphabet().size();
		assertEquals(origAlphSize, paramsR.getAlphabet().size());
		assertTrue(ridL.getGlobalParams().getAlphabet() == paramsL.getAlphabet());
		assertTrue(ridL.getGlobalParams().getAlphabet() == paramsR.getAlphabet());
		assertTrue(ridL.getGlobalParams().getAlphabet() == ridR.getGlobalParams().getAlphabet());
		assertFalse(ridL.getGlobalParams().getAlphabet().isGrowing());

		for(int i=0; i<output.size(); i++) {
			StageDatum<FNTagging, FNParse> dL = dataL.getStageDatum(i);
			StageDatum<FNTagging, FNParse> dR = dataR.getStageDatum(i);

			tL.start();
			Decodable<FNParse> decL = (Decodable<FNParse>) dL.getDecodable();
			tL.stop();

			tR.start();
			Decodable<FNParse> decR = (Decodable<FNParse>) dR.getDecodable();
			tR.stop();

			for(Map.Entry<String, ChecksSomething> x : tests.entrySet()) {
				System.out.printf("running %s on example %d...\n", x.getKey(), i+1);
				x.getValue().check(input.get(i), output.get(i), decR, decL);
			}

			// Check that the alphabet hasn't grown or changed
			assertEquals(origAlphSize, paramsL.getAlphabet().size());
			assertEquals(origAlphSize, paramsR.getAlphabet().size());
			assertTrue(ridL.getGlobalParams().getAlphabet() == paramsL.getAlphabet());
			assertTrue(ridL.getGlobalParams().getAlphabet() == paramsR.getAlphabet());
			assertTrue(ridL.getGlobalParams().getAlphabet() == ridR.getGlobalParams().getAlphabet());
			assertFalse(ridL.getGlobalParams().getAlphabet().isGrowing());

			// Try some random weights and try again
			int seed = 9001;
			double sigma = 0.02d;
			ridL.randomlyInitWeights(sigma, new Random(seed));
			ridR.randomlyInitWeights(sigma, new Random(seed));
		}
	}
}
