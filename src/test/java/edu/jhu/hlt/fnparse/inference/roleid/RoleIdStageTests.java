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
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.pruning.NoArgPruner;
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
				Map<String, Double> regularProbs = new HashMap<String, Double>();	// key=name
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
						System.out.println("skipping " + v.getName());
						continue;
					}
					assertEquals(2, v.getNumStates());
					System.out.printf("%s %.5f %.5f\n", v.getName(), pR, pL);
					if(Math.abs(pR - pL) < 1e-2) {
						if(decR.getWeights().l2Norm() + decL.getWeights().l2Norm() < 1e-3) {
							// nbd, just trivially the same because all the weights are 0, i.e. there are no factors
						}
						else {		// why would these probabilities be the same?
							List<Factor> touchingL = touching(v, fgL);
							List<Factor> touchingR = touching(v, fgR);
							if(touchingL.size() != 1 || touchingR.size() != 1) {
								System.out.println("touching in L : " + touchingL);
								System.out.println("touching in R : " + touchingR);
								
								System.out.println("touching L:");
								for(Factor f : touchingL) {
									System.out.println();
									System.out.println(f);
									int i = 0;
									for(Var vl : f.getVars()) {
										if(!v.getName().equals(vl.getName()))
											System.out.println((++i) + ": " + decL.getMargins().getMarginals(vl));
									}
								}
								
								// gah....
								// this works, but to write the correct test code, i basically have to rewrite BP
								// what is happening is that there is a very small prob on l_ij=1
								// so even if the factor value for (r_itjk=1,l_ij=1) is not that small,
								// there will be very little different in the r_itjk beliefs because the l_ij
								// beliefs are so skewed towards l_ij=0
								
								assertTrue(false);
							}
						}
					}
				}
			}
			/** uses var names, so will work even if you give a var in another factor graph (but has same name/naming convention */
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
		
		// latent
		ParserParams paramsL = new ParserParams();
		paramsL.useLatentDepenencies = true;
		RoleIdStage ridL = new RoleIdStage(paramsL);
		ridL.globalParams.readFeatAlphFrom(alphFile);
		if(prune)
			configureRid(ridL);
		StageDatumExampleList<FNTagging, FNParse> dataL = ridL.setupInference(input, output);
		assertEquals(output.size(), dataL.size());
		
		// regular
		ParserParams paramsR = new ParserParams();
		paramsR.useLatentDepenencies = false;
		RoleIdStage ridR = new RoleIdStage(paramsR);
		//ridR.globalParams.readFeatAlphFrom(alphFile);
		paramsR.setFeatureAlphabet(paramsL.getFeatureAlphabet());
		if(prune)
			configureRid(ridR);
		StageDatumExampleList<FNTagging, FNParse> dataR = ridR.setupInference(input, output);
		assertEquals(output.size(), dataR.size());
			
		assertEquals(paramsL.logDomain, paramsR.logDomain);
		assertFalse(paramsL.getFeatureAlphabet().isGrowing());
		assertTrue(paramsL.getFeatureAlphabet() == paramsR.getFeatureAlphabet());

		ridL.initWeights();
		ridR.initWeights();
		
		Timer tL = new Timer("getDecodable-latent");
		Timer tR = new Timer("getDecodable-regular");
		tL.printIterval = 1;
		tR.printIterval = 1;
		
		int origAlphSize = paramsL.getFeatureAlphabet().size();
		assertEquals(origAlphSize, paramsR.getFeatureAlphabet().size());
		assertTrue(ridL.globalParams.getFeatureAlphabet() == paramsL.getFeatureAlphabet());
		assertTrue(ridL.globalParams.getFeatureAlphabet() == paramsR.getFeatureAlphabet());
		assertTrue(ridL.globalParams.getFeatureAlphabet() == ridR.globalParams.getFeatureAlphabet());
		assertFalse(ridL.globalParams.getFeatureAlphabet().isGrowing());
		
		for(int i=0; i<output.size(); i++) {
			
			StageDatum<FNTagging, FNParse> dL = dataL.getStageDatum(i);
			StageDatum<FNTagging, FNParse> dR = dataR.getStageDatum(i);

			tL.start();
			Decodable<FNParse> decL = dL.getDecodable(ridL.infFactory());
			tL.stop();
			tR.start();
			Decodable<FNParse> decR = dR.getDecodable(ridR.infFactory());
			tR.stop();
			
			for(Map.Entry<String, ChecksSomething> x : tests.entrySet()) {
				System.out.printf("running %s on example %d...\n", x.getKey(), i+1);
				x.getValue().check(input.get(i), output.get(i), decR, decL);
			}
			
			// check that the alphabet hasn't grown or changed
			assertEquals(origAlphSize, paramsL.getFeatureAlphabet().size());
			assertEquals(origAlphSize, paramsR.getFeatureAlphabet().size());
			assertTrue(ridL.globalParams.getFeatureAlphabet() == paramsL.getFeatureAlphabet());
			assertTrue(ridL.globalParams.getFeatureAlphabet() == paramsR.getFeatureAlphabet());
			assertTrue(ridL.globalParams.getFeatureAlphabet() == ridR.globalParams.getFeatureAlphabet());
			assertFalse(ridL.globalParams.getFeatureAlphabet().isGrowing());
			
			// try some random weights and try again
			int seed = 9001;
			double sigma = 0.02d;
			ridL.randomlyInitWeights(sigma, new Random(seed));
			ridR.randomlyInitWeights(sigma, new Random(seed));
		}


	}

}
