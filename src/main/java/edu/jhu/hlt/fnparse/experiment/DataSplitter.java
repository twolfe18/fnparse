package edu.jhu.hlt.fnparse.experiment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public class DataSplitter {
	
	private Random rand;
	private Pattern delim = Pattern.compile("\\s+");
	private String delimStr = "\t";
	
	public DataSplitter() {
		rand = new Random(9001);
	}
	
	public DataSplitter(Random rand) {
		this.rand = rand;
	}

	public boolean split(List<FrameInstance> all, List<FrameInstance> train, List<FrameInstance> test, double propTest) throws FileNotFoundException {
		return split(all, train, test, propTest, true);
	}
	
	/**
	 * @param all is a list of examples to split up
	 * @param train should be an empty list to add to
	 * @param test should be an empty list to add to
	 * @param propTest says what proportion should go to the test set (between 0 and 1)
	 * @param saveSplit says whether to respect a split of this data contained in a file on disk
	 * @return true if this split was created for the first time (false if loaded from a file).
	 * 	if saveSplit=false, then it will always return true
	 */
	public boolean split(List<FrameInstance> all, List<FrameInstance> train, List<FrameInstance> test, double propTest, boolean saveSplit) {
		assert train.size() == 0;
		assert test.size() == 0;
		assert all.size() > 0;
		if(propTest <= 0d || propTest >= 1d)
			throw new IllegalArgumentException("propTest must be between 0 and 1: " + propTest);
		
		if(saveSplit) {
			File f = getSplitFile(all);
			boolean newSplit;
			Map<String, Boolean> isTrain;
			if(f.isFile()) {
				isTrain = readSplit(f);
				newSplit = false;
			} else {
				isTrain = writeSplit(f, all, propTest);
				newSplit = true;
			}
			for(FrameInstance fi : all) {
				String key = frameInstanceId(fi);
				if(isTrain.get(key)) train.add(fi);
				else test.add(fi);
			}
			return newSplit;
		}
		else {
			for(FrameInstance fi : all) {
				if(rand.nextDouble() < propTest)
					test.add(fi);
				else
					train.add(fi);
			}
			return true;
		}
	}
	
	/**
	 * used to write out where FrameInstances should go (train or test sets)
	 */
	public String frameInstanceId(FrameInstance fi) {
		if(delim.matcher(fi.getSentence().getId()).find())
			throw new RuntimeException("choose a new delimiter for the split file");
		return fi.getFrame().getId() + "@" + fi.getSentence().getId() + "." + fi.getTargetIdx();
	}
	
	public File getSplitFile(List<FrameInstance> all) {
		if(!allInstancesFromSameDataset(all))
			throw new RuntimeException();
		File p = new File("src/main/resources");	// TODO fix this
		return new File(p, all.get(0).getSentence().getDataset() + "_" + all.size() + ".split");
	}
	
	public boolean allInstancesFromSameDataset(List<FrameInstance> all) {
		String ds = null;
		for(int i=0; i<all.size(); i++) {
			String d = all.get(i).getSentence().getDataset();
			if(i == 0) ds = d;
			else if(!ds.equals(d)) return false;
		}
		return true;
	}
	
	public Map<String, Boolean> readSplit(File f) {
		Map<String, Boolean> m = new HashMap<String, Boolean>();
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
			while(r.ready()) {
				String line = r.readLine();
				String[] toks = delim.split(line.trim());
				if(toks.length != 2) throw new IllegalStateException("line = " + line.trim());
				if(toks[1].equals("train"))
					m.put(toks[0], true);
				else if(toks[1].equals("test"))
					m.put(toks[0], false);
				else throw new IllegalStateException("line = " + line.trim());
			}
			r.close();
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
		return m;
	}
	
	public Map<String, Boolean> writeSplit(File f, List<FrameInstance> all, double propTest) {
		if(!delim.matcher(delimStr).matches())
			throw new RuntimeException("choose a new delimiter for the split file");
		Map<String, Boolean> m = new HashMap<String, Boolean>();
		try {
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			for(FrameInstance fi : all) {
				String key = frameInstanceId(fi);
				String val;
				if(rand.nextDouble() < propTest) {
					m.put(key, false);
					val = "test";
				}
				else {
					m.put(key, true);
					val = "train";
				}
				w.write(String.format("%s%s%s\n", key, delimStr, val));
			}
			w.close();
		} catch(Exception e) {
			throw new RuntimeException(e);
		}
		return m;
	}
}
