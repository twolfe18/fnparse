package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.DataSplitter;

/**
 * Contains methods that are meant to be run offline and compute the alphabet of feature names.
 * 
 * @author travis
 */
public class AlphabetComputer {

	public static int maxFeaturesAdded = 12_000_000;	// by any one stage
	public static double scanFeaturesTimeInMinutes = 15;
	public static boolean checkForPreExistingModelFile = false;

	public static void main(String[] args) throws IOException {
		if(args.length != 2 && args.length != 3) {
			System.out.println("please provide:");
			System.out.println("1) an file to save the model to");
			System.out.println("2) a syntax mode (\"none\", \"latent\", or \"regular\")");
			System.out.println("3) [optional] how many minutes to run this for (default is 15 minutes)");
			return;
		}

		if(args.length == 3)
			scanFeaturesTimeInMinutes = Double.parseDouble(args[args.length-1]);

		File saveModelTo = new File(args[0]);
		if(checkForPreExistingModelFile && saveModelTo.isFile())
			throw new RuntimeException("this file already exists: " + saveModelTo.getPath());

		String syntaxMode = args[1].toLowerCase();
		if(!Arrays.asList("none", "latent", "regular").contains(syntaxMode))
			throw new RuntimeException("unknown syntax mode: " + syntaxMode);
		boolean latentSyntax = syntaxMode.equals("latent");
		boolean noSyntaxFeatures = syntaxMode.equals("none");

		ParserParams parserParams = new ParserParams();
		PipelinedFnParser parser = new PipelinedFnParser(parserParams);
		parser.getParams().useSyntaxFeatures = !noSyntaxFeatures;
		parser.getParams().useLatentDepenencies = latentSyntax;
		parser.getParams().useLatentConstituencies = latentSyntax;

		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train-test");
		test = null;	// don't need this

		// compute features and populate the alphabet
		parser.computeAlphabet(train, scanFeaturesTimeInMinutes, maxFeaturesAdded);

		// write model to disk
		long time = System.currentTimeMillis();
		/*
		OutputStream os = new FileOutputStream(saveModelTo);
		if(saveModelTo.getName().toLowerCase().endsWith(".gz"))
			os = new GZIPOutputStream(os);
		ObjectOutputStream oos = new ObjectOutputStream(os);
		oos.writeObject(parser);
		oos.close();
		*/
		parser.getParams().writeFeatAlphTo(saveModelTo);
		System.out.printf("saved model in %.1f seconds\n", (System.currentTimeMillis()-time)/(1000d*60d));
	}

}
