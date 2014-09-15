package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a tab separated file which maps IDs (first column) to some set of
 * section names (second column).
 * 
 * NOTE: This class is not affiliated with DataSplitter. I made these splits
 * very cheaply with awk (see make-dipanjan-dev-splits.sh).
 * 
 * @author travis
 */
public class DataSplitReader {
	
	public static final String sep = "\t";

	private Map<String, String> id2section;
	private Map<String, Integer> sectionCounts;

	public DataSplitReader(File f) {
		if (!f.isFile())
			throw new IllegalArgumentException(f.getPath() + " is not a file");
		id2section = new HashMap<>();
		sectionCounts = new HashMap<>();
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
			while (r.ready()) {
				String line = r.readLine();
				String[] ar = line.split(sep);
				if (ar.length != 2)
					throw new RuntimeException("line=" + line);
				String id = ar[1];
				String sec = ar[0];
				String oldSection = id2section.put(id, sec);
				if (oldSection != null) {
					throw new RuntimeException(id + " appears in both "
							+ oldSection + " and " + sec);
				}
				Integer c = sectionCounts.get(sec);
				if (c == null) c = 0;
				sectionCounts.put(sec, c + 1);
			}
			r.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * @param checkThatEveryInstanceInSectionIsFound if true, will throw an
	 * exception if there is an id that appears in the section file (given at
	 * construction time) that is not in all.
	 */
	public <T extends HasId> List<T> getSection(
			List<T> all,
			String sectionName,
			boolean checkThatEveryInstanceInSectionIsFound) {
		List<T> section = new ArrayList<>();
		for (T t : all)
			if (sectionName.equals(id2section.get(t.getId())))
				section.add(t);
		if (checkThatEveryInstanceInSectionIsFound) {
			Integer c = sectionCounts.get(sectionName);
			if (c == null || section.size() < c) {
				throw new RuntimeException("only found " + section.size()
						+ " of " + c + " instances in " + sectionName);
			}
		}
		return section;
	}
}
