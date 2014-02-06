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
public class BasicBob implements Bob<JoeInfo> {
	
	public static final String NAME = "BasicBob";
	public static final String BASIC_BOBS_FILE = "BASIC_BOBS_FILE";
	
	private Map<String, JoeInfo> info;	// keys are Joe names
	private boolean firstPass;
	private File cacheTo;

	public boolean isFirstPass() { return firstPass; }

	public int totalFeatures() {
		if(firstPass)
			throw new RuntimeException("I can't tell you this until you've made a first pass.");
		int t = 0;
		for(JoeInfo ji : info.values())
			t += ji.width;
		return t;
	}

	@Override
	public void startup() {
		cacheTo = new File(System.getProperty(BASIC_BOBS_FILE));
		info = new HashMap<String, JoeInfo>();
		if(cacheTo.isFile()) {
			firstPass = false;
			try {
				BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(cacheTo)));
				while(r.ready()) {
					String line = r.readLine().trim();
					JoeInfo j = JoeInfo.deserialize(line);
					info.put(j.name, j);
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
			System.out.println("[BasicBob] writing out " + info.size() + " widths to " + cacheTo.getPath());
			
			// compute offsets
			List<JoeInfo> byIndex = new ArrayList<JoeInfo>();
			byIndex.addAll(info.values());
			Collections.sort(byIndex, new Comparator<JoeInfo>() {
				@Override
				public int compare(JoeInfo o1, JoeInfo o2) {
					return o2.index - o1.index;
				}
			});
			int running = 0;
			for(JoeInfo ji : byIndex) {
				ji.offset = running;
				running += ji.width;
			}
			
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cacheTo)));
			for(JoeInfo ji : byIndex)
				w.write(ji.serialize());
			w.close();
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void register(Joe<JoeInfo> featureComputer) {
		JoeInfo prevInfo = info.get(featureComputer.getJoeName());
		if(prevInfo != null)
			featureComputer.storeJoeInfo(prevInfo);
		else {
			int index = info.size();
			JoeInfo newInfo = new JoeInfo(featureComputer.getJoeName(), index, 0, 0);
			featureComputer.storeJoeInfo(newInfo);
			info.put(featureComputer.getJoeName(), newInfo);	
		}
	}

	@Override
	public FeatureVector doYourThing(FeatureVector fv, Joe<JoeInfo> sender) {
		if(firstPass) {

			JoeInfo ji = sender.getJoeInfo();
			if(ji == null)
				throw new RuntimeException(sender + " didn't register with me");
			ji.update(fv);
			
			// the features are going to be useless here because we can't come up with a globally-consistent indexing
			// so for now just return a dummy and wait until the second pass
			fv = new FeatureVector();
			fv.add(ji.index, 1d);
			return fv;
		}
		else {
			// just slide everything over a bit
			// TODO: optimize this (mutate in place)
			int offset = sender.getJoeInfo().offset;
			FeatureVector fvNew = new FeatureVector();
			for(IntDoubleEntry ide : fv)
				fvNew.add(ide.index() + offset, ide.get());
			return fvNew;
		}
	}

}
