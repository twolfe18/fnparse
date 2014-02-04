package edu.jhu.hlt.fnparse.inference.newstuff;

import static edu.jhu.hlt.fnparse.util.ScalaLike.*;

import java.util.*;

public class BinaryVarUtil {

	@SuppressWarnings("serial")
	public static final List<String> stateNames = new ArrayList<String>() {{ add("false"); add("true"); }};
	
	public static boolean configToBool(int config) {
		require(config == 0 || config == 1);
		return config == 1;
	}
	
	public static int boolToConfig(boolean b) {
		return b ? 1 : 0;
	}
}
