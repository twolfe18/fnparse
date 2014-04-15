package edu.jhu.hlt.fnparse.features.caching;

import java.io.File;
import java.util.Collections;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleDiskStore;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.Parser;

/**
 * @deprecated this can't work
 * files are too big, some stuff is a pain to serialize,
 * and its just as fast to compute the features the first time.
 * @author travis
 */
public class WriteExamplesToDisk {

	// FgExampleDiskStore settings
	public static final File cacheDir = new File("featureCache");
	public static final int maxEntriesInMemory = 10;
	public static final boolean gzipped = false;
	
	public static void main(String[] args) {
		writeToDisk();
		readFromDisk();
	}
	
	public static void readFromDisk() {
		
		Parser parser = new Parser();
		FgExampleDiskStore store = new FgExampleDiskStore(cacheDir, gzipped, maxEntriesInMemory);
		
		System.out.println("training on data from disk :)");
		
		// this should initialize a lot of junk
		parser.train(Collections.<FNParse>emptyList());
		
		throw new RuntimeException("fix the lines below");
//		try { parser.params.model = parser.params.trainer.train(parser.params.model, store); }
//		catch(cc.mallet.optimize.OptimizationException oe) {
//			oe.printStackTrace();
//		}
	}
	
	public static void writeToDisk() {
		System.out.println("[WriteExamplesToDisk] reading parses...");
		List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences());
		
		Parser parser = new Parser();
		RawExampleFactory makesExamples = new RawExampleFactory(parses, parser);
		FgExampleDiskStore store = new FgExampleDiskStore(cacheDir, gzipped, 1);
		
		long start = System.currentTimeMillis();
		int added = 0;
		for(FgExample fge : makesExamples) {
			System.out.println("adding the " + (added+1) + "th example of " +  makesExamples.size());
			store.add(fge);
			long used = System.currentTimeMillis() - start;
			System.out.printf("\t%.1f minutes taken, %.1f seconds per instance\n", used/(1000d*60d), (used/1000d)/added);
			added++;
			if(added > 25) break;
		}
	}
}
