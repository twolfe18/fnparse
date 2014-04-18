package edu.jhu.hlt.fnparse.inference;

import static edu.jhu.hlt.fnparse.util.ScalaLike.require;

import java.util.ArrayList;
import java.util.List;

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
