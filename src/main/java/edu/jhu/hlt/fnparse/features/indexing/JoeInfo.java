package edu.jhu.hlt.fnparse.features.indexing;

import edu.jhu.gm.feat.FeatureVector;

public class JoeInfo {
	
	public String name;
	public int index;
	public int offset;
	public int width;
	
	public JoeInfo(String name, int index, int offset, int width) {
		if(name.contains("\t"))
			throw new IllegalArgumentException();
		this.name = name;
		this.index = index;
		this.offset = offset;
		this.width = width;
	}
	
	public void update(FeatureVector fv) {
		int[] indices = fv.getIndices();
		int n = indices.length;
		int max = 0;
		for(int i=0; i<n; i++) {
			int ii = indices[i];
			if(ii > max) max = ii;
		}
		if(max+1 > width)
			width = max+1;
	}
	
	public String serialize() {
		return String.format("%s\t%d\t%d\t%d\n", name, index, offset, width);
	}
	
	public static JoeInfo deserialize(String line) {
		String[] ar = line.split("\t");
		return new JoeInfo(ar[0], Integer.parseInt(ar[1]), Integer.parseInt(ar[2]), Integer.parseInt(ar[3]));
	}
}