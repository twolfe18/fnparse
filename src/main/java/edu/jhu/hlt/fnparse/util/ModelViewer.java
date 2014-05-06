package edu.jhu.hlt.fnparse.util;

import java.io.File;
import java.util.Arrays;

import edu.jhu.hlt.fnparse.inference.Parser;

public class ModelViewer {
	
	public static void main(String[] args) {

		for(String fn : Arrays.asList(args)) {
			
			System.out.println(fn);
			Parser p = new Parser(new File(fn));
			int n = p.params.featIdx.size();
			for(int i=0; i<n; i++) {
				String f = p.params.featIdx.lookupObject(i);
				if(p.params.weights != null) {
					double w = p.params.weights.getParams().get(i);
					System.out.printf("%-130s %.5f\n", f, w);
				}
				else System.out.println(f);
			}

		}

	}

}
