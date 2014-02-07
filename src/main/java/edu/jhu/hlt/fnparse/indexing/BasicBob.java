package edu.jhu.hlt.fnparse.indexing;

import java.io.*;
import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.util.Alphabet;

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

	private Map<String, Alphabet<String>> featureNames = new HashMap<String, Alphabet<String>>();
	
	public Alphabet<String> trackMyAlphabet(Joe<JoeInfo> owner) {
		if(firstPass) {
			Alphabet<String> alph = new Alphabet<String>();
			featureNames.put(owner.getJoeName(), alph);
			return alph;
		}
		else {
			Alphabet<String> alph = featureNames.get(owner.getJoeName());
			if(alph == null) throw new RuntimeException();
			return alph;
		}
	}
	
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
				
				// how many JoeInfos?
				String[] header = r.readLine().split("\\s+");
				int numJI = Integer.parseInt(header[0]);
				
				for(int i=0; i<numJI; i++) {
					String line = r.readLine().trim();
					JoeInfo j = JoeInfo.deserialize(line);
					info.put(j.name, j);
					featureNames.put(j.name, new Alphabet<String>());
				}
				
				// the rest of the file will be feature names
				while(r.ready()) {
					
					// i was going to compute the localIdx from the offset and globalIdx,
					// but Alphabet doesn't support set(int, object), so i'll have to rely
					// on the fact that they'll get the same ids if they're inserted in the same order.
					
					String[] ar = r.readLine().split("\t");
					//int globalIdx = Integer.parseInt(ar[0]);
					String joeName = ar[1];
					String localName = ar[2];
					Alphabet<String> alph = featureNames.get(joeName);
					//JoeInfo ji = info.get(joeName);
					//int localIdx = globalIdx - ji.offset;
					alph.lookupIndex(localName, true);
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
			
			// write out Joes' details (e.g. feature dimensionalities)
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cacheTo)));
			w.write(byIndex.size() + " features\n");
			for(JoeInfo ji : byIndex)
				w.write(ji.serialize());
			
			// save feature names if they were provided
			for(JoeInfo ji : byIndex) {
				Alphabet<String> alph = featureNames.get(ji.name);
				if(alph == null) continue;
				int n = alph.size();
				for(int i=0; i<n; i++) {
					int index = ji.offset + i;
					String fn = alph.lookupObject(i);
					w.write(String.format("%d\t%s\t%s\n", index, ji.name, fn));
				}
			}
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
