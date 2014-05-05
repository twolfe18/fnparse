package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.util.DataSplitter;

/**
 * Contains methods that are meant to be run offline and compute the alphabet of feature names.
 * 
 * @author travis
 */
public class AlphabetComputer {
	
	public static double scanFeaturesTimeInMinutes = 15;
	public static boolean checkForPreExistingModelFile = false;
	
	public static void main(String[] args) {
		
		if(args.length < 3 || args.length > 5) {
			System.out.println("please provide:");
			System.out.println("1) an file to save the model to");
			System.out.println("2) a parser mode (\"frameId\", \"argId\", or \"jointId\")");
			System.out.println("3) a syntax mode (\"none\", \"latent\", or \"regular\")");
			System.out.println("4) [optional] an existing model for pipeline training");
			System.out.println("5) [optional] how many minutes to run this for (default is 15 minutes)");
			return;
		}
		
		if(args.length == 5)
			scanFeaturesTimeInMinutes = Double.parseDouble(args[4]);
		
		File saveModelTo = new File(args[0]);
		if(checkForPreExistingModelFile && saveModelTo.isFile())
			throw new RuntimeException("this file already exists: " + saveModelTo.getPath());

		Mode mode = null;
		String modeName = args[1].toLowerCase();
		if(modeName.equals("frameid"))
			mode = Mode.FRAME_ID;
		else if(modeName.equals("roleid") || modeName.equals("argid"))
			mode = Mode.PIPELINE_FRAME_ARG;
		else throw new RuntimeException("not supported");
		
		String syntaxMode = args[2].toLowerCase();
		boolean latentSyntax = syntaxMode.equals("latent");
		boolean noSyntaxFeatures = syntaxMode.equals("none");
		
		Parser p;	// either make new one or read from file if provided
		File existingModel = args.length == 3 ? null : new File(args[3]);
		if(existingModel != null) {
			p = new Parser(existingModel);
			p.setMode(mode, latentSyntax);
		}
		else {
			p = new Parser(mode, latentSyntax, false);
		}
		p.params.useSyntaxFeatures = !noSyntaxFeatures;
		
		assert !(mode == Mode.PIPELINE_FRAME_ARG && existingModel == null);

		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train-test");
		test = null;	// don't need this
		Collections.shuffle(train);	// make sure they aren't sorted by frame for instance

		p.scanFeatures(train, scanFeaturesTimeInMinutes);
		p.train(Collections.<FNParse>emptyList(), 1, 1, 1d, 1d, true);
		p.writeModel(saveModelTo);
	}

}
