package edu.jhu.hlt.fnparse.experiment;

import java.util.Random;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.util.Alphabet;

/**
 * the best i could get is 19% with StringBuilder.setLength(0)
 * probably not worth it in light of other possible optimizations.
 * @deprecated
 * @author travis
 */
public class AbstractFeaturesBenchmark extends AbstractFeatures<AbstractFeaturesBenchmark> {

	public static void main(String[] args) {
		
		AbstractFeaturesBenchmark n = new AbstractFeaturesBenchmark(true);
		AbstractFeaturesBenchmark o = new AbstractFeaturesBenchmark(false);
		
		double ttn = 0d, tto = 0d;
		int numAdds = 50000;
		int numFeatsPerAdd = 50;
		for(int i=0; i<10; i++) {
			double tn = n.benchmark(numAdds, numFeatsPerAdd);
			double to = o.benchmark(numAdds, numFeatsPerAdd);
			System.out.printf("[bench] new took %.2f seconds, old took %.2f\n", tn, to);
			if(i > 0) {
				ttn += tn;
				tto += to;
			}
		}
		System.out.printf("[bench] new took %.2f seconds total, old took %.2f total, %.1f %% gain\n",
				ttn, tto, 100d * (1d - ttn / tto));
	}
	
	public boolean newWay;
	public AbstractFeaturesBenchmark(boolean newWay) {
		super(null);	// FIXME
		this.newWay = newWay;
	}
	
	public double benchmark(int numAdds, int numFeatsPerAdd) {
	
		// i removed this code from AbstractFeatures
//		super.newWay = this.newWay;
		Random r = new Random();
		Timer t = new Timer("fv");
		for(int i=0; i<numAdds; i++) {
		
			t.start();
			FeatureVector fv = new FeatureVector();
			for(int j=0; j<numFeatsPerAdd; j++) {
				b(fv, Refinements.noRefinements, "foo", "bar", "baz");
				//b(fv, "foo", String.valueOf(r.nextInt(100)));
				b(fv, Refinements.noRefinements, "foo", "bar", "baz", "zing", String.valueOf(r.nextBoolean()));
				//b(fv, "foo", "bar", String.valueOf(r.nextInt(1000)));
				//b(fv, String.valueOf(r.nextInt(10000)));
			}
			double test = fv.get(43);
			t.stop();
		}
		return t.totalTimeInSec();
	}
	
}
