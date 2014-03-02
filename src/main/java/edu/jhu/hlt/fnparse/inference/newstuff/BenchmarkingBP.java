package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FactorGraph.FgEdge;
import edu.jhu.gm.model.FactorGraph.FgNode;

public class BenchmarkingBP extends BeliefPropagation {

	static class Stats {
		
		public String id;
		public long time;
		public int count;
		
		public Stats(String id) { this.id = id; }
		
		public void took(long ms) {
			count++;
			time += ms;
		}
		
		@Override
		public String toString() {
			return String.format("<Stats %s %d times over %.2f sec, rate = %.1f/sec>", id, count, time/1000d, count/((double)time));
		}
	}
	
	private Map<String, Stats> createMessageStats;
	private Stats allCreateMessage, allRun, allGetProductOfMessagesNormalized;
	private Stats allGetBetheFreeEnergy, allGetPartition;
	private Stats v2f, f2v;

	public BenchmarkingBP(FactorGraph fg, BeliefPropagationPrm prm) {
		super(fg, prm);
		createMessageStats = new HashMap<String, Stats>();
		allCreateMessage = new Stats("all-create-msg");
		allRun = new Stats("all-run");
		allGetProductOfMessagesNormalized = new Stats("all-prod-messages");
		allGetPartition = new Stats("all-partition");
		allGetBetheFreeEnergy = new Stats("all-bethe");
		
		// both for create message
		v2f = new Stats("Variable->Factor");
		f2v = new Stats("Factor->Variable");
	}
	
	protected void createMessage(FgEdge edge, int iter) {
		
		String id = edge.getFactor().getClass().toString().replace("class edu.jhu.hlt.fnparse.inference.", "");
		long start = System.currentTimeMillis();
		super.createMessage(edge, iter);
		long t = System.currentTimeMillis() - start;
		
		Stats s = createMessageStats.get(id);
		if(s == null) s = new Stats(id);
		s.took(t);
		createMessageStats.put(id, s);
		allCreateMessage.took(t);
		
		(edge.isVarToFactor() ? v2f : f2v).took(t);
		
		if(allCreateMessage.count % 250000 == 0) {
			List<Stats> byRuntime = new ArrayList<Stats>();
			byRuntime.addAll(createMessageStats.values());
			Collections.sort(byRuntime, new Comparator<Stats>() {
				@Override
				public int compare(Stats o1, Stats o2) {
					if(o1.time > o2.time) return -1;
					if(o2.time > o1.time) return 1;
					return 0;
				}
			});
			for(Stats st : byRuntime)
				System.out.printf("[BP benchmarking createMessage] %.2f%% \t %s\n", (100d*st.time)/allCreateMessage.time, st);
			System.out.println();
		}
	}
	
	@Override
	protected void getProductOfMessagesNormalized(FgNode node, DenseFactor prod, FgNode exclNode) {
		long start = System.currentTimeMillis();
		super.getProductOfMessagesNormalized(node, prod, exclNode);
		long t = System.currentTimeMillis() - start;
		allGetProductOfMessagesNormalized.took(t);
//		if(allGetProductOfMessagesNormalized.count % 2000 == 0)
//			printTopLevelProportions();
	}
	
	@Override
    public double getPartition() {
		long start = System.currentTimeMillis();
		double Z = super.getPartition();
		long t = System.currentTimeMillis() - start;
		allGetPartition.took(t);
		return Z;
	}
	
	@Override
	protected double getBetheFreeEnergy() {
		long start = System.currentTimeMillis();
		double Z = super.getBetheFreeEnergy();
		long t = System.currentTimeMillis() - start;
		allGetBetheFreeEnergy.took(t);
		return Z;
	}
	
	@Override
	public void run() {
		System.out.println("[BP benchmarking] RUN STARTING...");
		long start = System.currentTimeMillis();
		super.run();
		long t = System.currentTimeMillis() - start;
		allRun.took(t);
		System.out.println("[BP benchmarking] RUN IS DONE.");
		printTopLevelProportions();
	}
	
	public void printTopLevelProportions() {
		System.out.println("[BP benchmarking] top level:");
		System.out.println(allCreateMessage);
		System.out.println(v2f);
		System.out.println(f2v);
		System.out.println(allGetProductOfMessagesNormalized);
		System.out.println(allGetPartition);
		System.out.println(allGetBetheFreeEnergy);
		System.out.println(allRun);
		System.out.println();
	}
}
