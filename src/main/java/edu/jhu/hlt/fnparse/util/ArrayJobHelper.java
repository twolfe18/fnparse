package edu.jhu.hlt.fnparse.util;

import java.util.*;

public class ArrayJobHelper {

	// specifies loop order, first element is outermost loop, last is innermost loop
	private List<String> optionNames = new ArrayList<String>();
	private Map<String, List<Object>> optionValues = new HashMap<String, List<Object>>();
	

	// only for Option
	private int configIdx = -1;
	public void setConfig(int i) { configIdx = i; }
	public void setConfig(String[] argsFromMain) {
		try {
			setConfig(Integer.parseInt(argsFromMain[0]));
		}
		catch(Exception e) {
			System.err.println(helpString());
			throw new RuntimeException(e);
		}
	}
	public int getStoredConfigIndex() {
		if(configIdx < 0)
			throw new IllegalStateException("you need to set the config");
		return configIdx;
	}
	public Map<String, Object> getStoredConfig() {
		return getConfig(getStoredConfigIndex());
	}
	
	public static class Option<T> {
		private ArrayJobHelper ajh;
		private String name;
		public Option(ArrayJobHelper ajh, String name) {
			this.ajh = ajh;
			this.name = name;
		}
		@SuppressWarnings("unchecked")
		public T get() {
			int i = ajh.getStoredConfigIndex();
			Map<String, Object> m = ajh.getConfig(i);
			return (T) m.get(name);
		}
	}
	
	public <T> Option<T> addOption(String name, List<T> values) {
		optionNames.add(name);
		@SuppressWarnings("unchecked")
		List<Object> old = optionValues.put(name, (List<Object>) values);
		assert old == null;
		
		return new Option<T>(this, name);
	}
	
	// TODO constraints are a little hard in java (as opposed to scala)
	// because there are no first class functions...
	
	public int numJobs() {
		int n = 1;
		for(List<Object> v : optionValues.values())
			n *= v.size();
		return n;
	}
	
	/**
	 * may return null.
	 */
	public Map<String, Object> getConfig(String[] argsFromMain) {
		try {
			int i = Integer.parseInt(argsFromMain[0]);
			return getConfig(i);
		}
		catch(Exception e) {
			System.err.println(helpString());
			return null;
		}
	}
	
	public Map<String, Object> getConfig(int configIdx) {
		Map<String, Object> c = new HashMap<String, Object>();
		getConfigHelper(configIdx, optionNames.size()-1, c);
		return c;
	}
	
	private void getConfigHelper(int configIdx, int index, Map<String, Object> buf) {
		if(index < 0) return;
		String opt = optionNames.get(index);
		List<Object> values = optionValues.get(opt);
		Object v = values.get(configIdx % values.size());
		buf.put(opt, v);
		getConfigHelper(configIdx / values.size(), index-1, buf);
	}
	
	public String helpString() { return helpString(20); }
	public String helpString(int show) {
		StringBuilder sb = new StringBuilder();
		sb.append("jobs setup:\n");
		int nj = numJobs();
		int n = Math.min(show, nj);
		for(int i=0; i<n; i++)
			sb.append(String.format("\t%d: %s\n", i+1, getConfig(i)));
		if(nj > show)
			sb.append("\t... and " + (nj-show) + " more\n");
		sb.append("please request " + nj + " jobs\n");
		sb.append("if you wanted to start an array job, you must provide the index of the job\n");
		sb.append("remember that SGE's jobs must be 1-indexed\n");
		sb.append(String.format("e.g. qsub -q text.q -t 1-%d acl14-ablation.qsub\n", nj));
		return sb.toString();
	}

}
