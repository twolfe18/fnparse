package edu.jhu.hlt.fnparse.experiment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.SemaforicTests;

public class DataSplitterTest {

	@Test
	public void basic() {
		// steal the FrameInstances from Semaforic tests
		SemaforicTests st = new SemaforicTests();
		List<FrameInstance> fis = st.getFrameInstances();
		DataSplitter ds = new DataSplitter();
		
		Set<FrameInstance> in, out;
		List<FrameInstance> train = new ArrayList<FrameInstance>();
		List<FrameInstance> test = new ArrayList<FrameInstance>();
		boolean saveSplit, newSplit;
		File splitFile;
		
		in = new HashSet<FrameInstance>();
		out = new HashSet<FrameInstance>();
		for(FrameInstance fi : fis) in.add(fi);
		
		// new split file
		splitFile = ds.getSplitFile(fis);
		assertTrue(!splitFile.isDirectory());
		if(splitFile.isFile()) splitFile.delete();
		saveSplit = true;
		newSplit = ds.split(fis, train, test, 0.5d, saveSplit);
		assertTrue(newSplit);
		out.addAll(train);
		out.addAll(test);
		assertEquals(in, out);
		
		// read split file
		List<FrameInstance> train2 = new ArrayList<FrameInstance>();
		List<FrameInstance> test2 = new ArrayList<FrameInstance>();
		saveSplit = true;
		newSplit = ds.split(fis, train2, test2, 0.5d, saveSplit);
		assertTrue(!newSplit);
		assertEquals(train, train2);
		assertEquals(test, test2);
		
		// no split file
		out.clear();
		train.clear();
		test.clear();
		saveSplit = false;
		newSplit = ds.split(fis, train, test, 0.5d, saveSplit);
		assertTrue(newSplit);
		out.addAll(train);
		out.addAll(test);
		assertEquals(in, out);
	}
}
