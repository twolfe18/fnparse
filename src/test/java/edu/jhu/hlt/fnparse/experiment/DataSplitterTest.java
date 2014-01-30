package edu.jhu.hlt.fnparse.experiment;

//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertTrue;
//
//import java.io.File;
//import java.util.ArrayList;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//import org.junit.Test;
//
//import edu.jhu.hlt.fnparse.datatypes.Sentence;
//import edu.jhu.hlt.fnparse.inference.SemaforicTest;

public class DataSplitterTest {

//	@Test
//	public void basic() {
//		// steal the FrameInstances from Semaforic tests
//		SemaforicTest st = new SemaforicTest();
//		List<Sentence> fis = st.getFrameInstances();
//		DataSplitter ds = new DataSplitter();
//		
//		double propTest = 0.5d;
//		Set<Sentence> in, out;
//		List<Sentence> train = new ArrayList<Sentence>();
//		List<Sentence> test = new ArrayList<Sentence>();
//		boolean saveSplit, newSplit;
//		File splitFile;
//		
//		in = new HashSet<Sentence>();
//		out = new HashSet<Sentence>();
//		for(Sentence fi : fis) in.add(fi);
//		
//		// new split file
//		splitFile = ds.getSplitFile(fis, propTest);
//		assertTrue(!splitFile.isDirectory());
//		if(splitFile.isFile()) splitFile.delete();
//		saveSplit = true;
//		newSplit = ds.split(fis, train, test, propTest, saveSplit);
//		assertTrue(newSplit);
//		out.addAll(train);
//		out.addAll(test);
//		assertEquals(in, out);
//		
//		// read split file
//		List<Sentence> train2 = new ArrayList<Sentence>();
//		List<Sentence> test2 = new ArrayList<Sentence>();
//		saveSplit = true;
//		newSplit = ds.split(fis, train2, test2, propTest, saveSplit);
//		assertTrue(!newSplit);
//		assertEquals(train, train2);
//		assertEquals(test, test2);
//		
//		// no split file
//		out.clear();
//		train.clear();
//		test.clear();
//		saveSplit = false;
//		newSplit = ds.split(fis, train, test, propTest, saveSplit);
//		assertTrue(newSplit);
//		out.addAll(train);
//		out.addAll(test);
//		assertEquals(in, out);
//	}
}
