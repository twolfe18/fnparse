package edu.jhu.hlt.fnparse.inference;

import java.util.Iterator;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;

public class HeadFinderTests {
	public static final Logger LOG = Logger.getLogger(HeadFinderTests.class);

	// pull some wide spans from WideArguments.estimateCardinalityOfTemplates and see if we can get them right by tweaking the head finder

	String t1 = "Lawton Davis , head of the South Central Health District";
	String h1 = "Davis";	// previously "head"

	String t2 = "' I think , overall , things went very well , '";
	String h2 = "think";	// previously "'"

	/**
	 * i'm just looking at the output for now
	 */
	@Test
	public void test() {
		HeadFinder hf = new SemaforicHeadFinder();
		Iterator<FNParse> it = FileFrameInstanceProvider.debugFIP.getParsedSentences();
		while (it.hasNext()) {
			FNParse p = it.next();
			for (FrameInstance fi : p.getFrameInstances()) {
				int K = fi.getFrame().numRoles();
				for (int k = 0; k < K; k++) {
					Span s = fi.getArgument(k);
					if (s == Span.nullSpan) continue;
					if (s.width() == 1) continue;
					LOG.info("head: " + p.getSentence().getWord(
							hf.head(s, p.getSentence())));
					LOG.info(fi.getFrame().getName() + "."
							+ fi.getFrame().getRole(k) + ":\n"
							+ Describe.spanWithDeps(s, p.getSentence()));
					LOG.info("sentence: " + p.getSentence());
				}
			}
		}
	}
}
