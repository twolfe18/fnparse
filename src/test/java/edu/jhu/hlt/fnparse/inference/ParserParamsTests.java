package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.*;

import java.io.*;

import org.junit.Test;

import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

public class ParserParamsTests {

	@Test
	public void canSerialize() throws IOException, ClassNotFoundException {
		int d = 100;
		int k = 53;
		double v = 1.45d;
		
		Parser p = new Parser();
		p.params.model = new FgModel(d);
		double[] weights = new double[d];
		weights[k] = v;
		p.params.model.updateModelFromDoubles(weights);
		
		File f = File.createTempFile("serialization-test", ".ser");
		
		ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f));
		oos.writeObject(p.params);
		oos.close();
		
		ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f));
		ParserParams pp = (ParserParams) ois.readObject();
		ois.close();
		double[] newWeights = new double[d];
		pp.model.updateDoublesFromModel(newWeights);
		for(int i=0; i<d; i++)
			assertEquals(weights[i], newWeights[i], 1e-10);
	}
}
