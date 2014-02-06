package edu.jhu.hlt.fnparse.indexing;

import java.io.*;
import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.prim.map.IntDoubleEntry;

/**
 * His only job is to keep track of how many features each Joe uses.
 * Other Bob's may do things like normalize features, cache features,
 * or binarize features. 
 * @author travis
 */
public class BasicBob implements Bob {
	
	private List<Integer> joeWidth;
	private List<Integer> joeOffsets;	// cumulative sums of joeWidth, only used on !firstPass
	private boolean firstPass;
	private File cacheTo;

	public int totalFeatures() {
		if(firstPass)
			throw new RuntimeException("I can't tell you this until you've made a first pass.");
		int i = joeWidth.size() - 1;
		return joeOffsets.get(i) + joeWidth.get(i);
	}
	
	@Override
	public void register(Joe featureComputer) {
		int joeId = joeWidth.size();
		featureComputer.setJoeId(joeId);
		joeWidth.add(0);
	}

	@Override
	public FeatureVector doYourThing(FeatureVector fv, Joe sender) {
		int[] indices = fv.getIndices();
		int n = indices.length;
		int max = 0;
		for(int i=0; i<n; i++) {
			int ii = indices[i];
			if(ii > max) max = ii;
		}
		
		if(max > joeWidth.get(sender.getJoeId()))
			joeWidth.set(sender.getJoeId(), max);
		
		if(firstPass) return fv;
		else {
			// just slide everything over a bit
			// TODO: optimize this (mutate in place)
			int offset = joeOffsets.get(sender.getJoeId());
			FeatureVector fvNew = new FeatureVector();
			for(IntDoubleEntry ide : fv)
				fvNew.add(ide.index() + offset, ide.get());
			return fvNew;
		}
	}

	@Override
	public void startup() {
		cacheTo = new File(System.getProperty("BASIC_BOBS_FILE"));
		joeWidth = new ArrayList<Integer>();
		if(cacheTo.isFile()) {
			firstPass = false;
			joeOffsets = new ArrayList<Integer>();
			try {
				BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(cacheTo)));
				int running = 0;
				while(r.ready()) {
					int w = Integer.parseInt(r.readLine());
					joeWidth.add(w);
					joeOffsets.add(running);
					running += w;
				}
				r.close();
			}
			catch(Exception e) {
				throw new RuntimeException(e);
			}
			
		}
		else firstPass = true;
	}

	@Override
	public void shutdown() {
		try {
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cacheTo)));
			for(Integer i : joeWidth)
				w.write(i + "\n");
			w.close();
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
}
