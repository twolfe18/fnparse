package edu.jhu.hlt.fnparse.util;

import static org.junit.Assert.*;

import java.util.*;

import org.junit.Test;

import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;

/**
 * use the statefulVersion as example code, much cleaner
 * than the other.
 * 
 * @author travis
 */
public class ArrayJobHelperTest {

	@Test
	public void simple() {
		List<String> fooValues = Arrays.asList("1", "2", "3");
		List<String> barValues = Arrays.asList("a", "b", "c");
		ArrayJobHelper ajh = new ArrayJobHelper();
		ajh.addOption("foo", fooValues);
		ajh.addOption("bar", barValues);
		assertEquals(9, ajh.numJobs());
		int configIdx = 0;
		for(String fv : fooValues) {
			for(String bv : barValues) {
				Map<String, String> m = new HashMap<String, String>();
				m.put("foo", fv);
				m.put("bar", bv);
				assertEquals(m, ajh.getConfig(configIdx));
				configIdx++;
			}
		}
		System.out.println(ajh.helpString());
	}
	
	@Test
	public void statefulVersion() {
		int c = 4;
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> fooOpt = ajh.addOption("foo", Arrays.asList(1, 2, 3));
		Option<String> barOpt = ajh.addOption("bar", Arrays.asList("a", "b", "c"));
		ajh.setConfig(new String[] {""+c});
		assertEquals(c, ajh.getStoredConfigIndex());
		assertEquals(ajh.getConfig(c), ajh.getStoredConfig());
		assertEquals(new Integer(2), fooOpt.get());
		assertEquals("b", barOpt.get());
		//System.out.println(ajh.getStoredConfig());
	}
	
}
