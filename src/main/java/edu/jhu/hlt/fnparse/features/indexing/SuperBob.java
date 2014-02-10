package edu.jhu.hlt.fnparse.features.indexing;

public class SuperBob {

	private static final Bob<JoeInfo> basicBob = new BasicBob();
	public static final String WHICH_BOB = "WHICH_BOB";
	
	public static Bob<?> getBob(Joe<?> whosAskin) {
		return getBob(whosAskin, System.getProperty(WHICH_BOB));
	}
	
	@SuppressWarnings("unchecked")
	public static Bob<?> getBob(Joe<?> whosAskin, String whichBob) {
		if(BasicBob.NAME.equalsIgnoreCase(whichBob)) {
			if(whosAskin != null)
				basicBob.register((Joe<JoeInfo>) whosAskin);
			return basicBob;
		}
		else throw new RuntimeException("I don't know who " + whichBob + " is.");
	}
	
}
