package edu.jhu.hlt.fnparse.indexing;

public class SuperBob {

	private static final Bob basicBob = new BasicBob();
	
	public static Bob getBob() {
		return getBob(System.getProperty("WHICH_BOB"));
	}
	
	public static Bob getBob(String whichBob) {
		if("BasicBob".equalsIgnoreCase(whichBob))
			return basicBob;
		else
			throw new RuntimeException("I don't know who " + whichBob + " is.");
	}
	
}
